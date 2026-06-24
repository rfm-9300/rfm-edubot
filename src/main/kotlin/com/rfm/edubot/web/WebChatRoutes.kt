package com.rfm.edubot.web

import com.rfm.edubot.messaging.InboundMessage
import com.rfm.edubot.messaging.MessageQueue
import com.rfm.edubot.tenant.TenantRegistry
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import io.ktor.server.routing.Routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

private val log = LoggerFactory.getLogger("WebChatRoutes")
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
private data class UserMessageFrame(val type: String? = null, val text: String? = null, val clientMsgId: String? = null)

@Serializable
private data class SessionFrame(val type: String = "session", val sessionId: String)

@Serializable
private data class ErrorFrame(val type: String = "error", val message: String)

/**
 * Website chat channel transport. The browser opens a WebSocket; each inbound user frame is turned
 * into an [InboundMessage] with platform=WEB and enqueued onto the shared [MessageQueue]. The existing
 * pipeline consumer then resolves a [WebChatOutboundClient] responder and streams replies back through
 * [WebChannelRegistry] to this socket — preserving mid-turn feedback messages.
 *
 * Identity mapping: tenant comes from the public widget `key` (a WEB ChannelBinding.externalId);
 * the end user is a `session` id minted in the browser and reused as the conversation key.
 */
fun Routing.webChatRoutes(
    messageQueue: MessageQueue,
    tenantRegistry: TenantRegistry,
    webChannelRegistry: WebChannelRegistry,
) {
    webSocket("/chat/ws") {
        val key = call.request.queryParameters["key"]
        if (key.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing key"))
            return@webSocket
        }
        val tenant = tenantRegistry.byExternalId(Platform.WEB, key)
        if (tenant == null || tenant.status != TenantStatus.ACTIVE) {
            log.warn("Rejecting web chat for unknown/inactive key={}", key)
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unknown widget key"))
            return@webSocket
        }
        if (!originAllowed(tenant, call.request.headers["Origin"])) {
            log.warn("Rejecting web chat from disallowed origin={} tenant={}", call.request.headers["Origin"], tenant.slug)
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Origin not allowed"))
            return@webSocket
        }

        val sessionId = call.request.queryParameters["session"]?.takeIf { it.isNotBlank() } ?: "web-${UUID.randomUUID()}"

        // Decouple pipeline replies (arbitrary coroutine) from this socket via an in-memory channel
        // the registry writes to and a single writer coroutine drains to the WebSocket.
        val outgoing = Channel<String>(Channel.UNLIMITED)
        webChannelRegistry.register(sessionId, outgoing)
        val writer = launch {
            for (frame in outgoing) {
                send(Frame.Text(frame))
            }
        }

        try {
            send(Frame.Text(json.encodeToString(SessionFrame(sessionId = sessionId))))
            log.info("Web chat connected: tenant={} session={}", tenant.slug, sessionId)

            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val parsed = runCatching { json.decodeFromString<UserMessageFrame>(frame.readText()) }.getOrNull()
                val text = parsed?.text?.trim()
                if (parsed?.type != "user_message" || text.isNullOrBlank()) {
                    send(Frame.Text(json.encodeToString(ErrorFrame(message = "Invalid message"))))
                    continue
                }
                val eventId = "web-${UUID.randomUUID()}"
                messageQueue.enqueue(
                    InboundMessage(
                        tenantId = tenant.id,
                        phoneNumberId = key,
                        platform = Platform.WEB,
                        channelExternalId = key,
                        waId = sessionId,
                        waMessageId = eventId,
                        profileName = null,
                        messageText = text,
                        timestamp = System.currentTimeMillis().toString(),
                        eventId = eventId,
                    )
                )
                log.info("Enqueued web message: tenant={} session={} id={}", tenant.slug, sessionId, eventId)
            }
        } catch (e: Exception) {
            log.warn("Web chat socket error: tenant={} session={} error={}", tenant.slug, sessionId, e.message)
        } finally {
            webChannelRegistry.unregister(sessionId)
            outgoing.close()
            writer.cancel()
            log.info("Web chat disconnected: tenant={} session={}", tenant.slug, sessionId)
        }
    }
}

private fun originAllowed(tenant: Tenant, origin: String?): Boolean {
    val allowed = tenant.binding(Platform.WEB)?.allowedOrigins.orEmpty()
    if (allowed.isEmpty()) return true // no allow-list configured = open (dev/early setup)
    if (origin.isNullOrBlank()) return false
    return allowed.any { it.equals(origin, ignoreCase = true) }
}
