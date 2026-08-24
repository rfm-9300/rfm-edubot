package com.rfm.edubot.webhook

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.instagram.InstagramSocialService
import com.rfm.edubot.messaging.DeduplicationService
import com.rfm.edubot.messaging.InboundMessage
import com.rfm.edubot.messaging.MessageQueue
import com.rfm.edubot.tenant.TenantRegistry
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import com.rfm.edubot.webhook.dto.InstagramPayload
import com.rfm.edubot.webhook.dto.WebhookPayload
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("WebhookRoutes")

private val json = Json { ignoreUnknownKeys = true }

fun Routing.webhookRoutes(
    configProvider: () -> AppConfig.WhatsAppConfig,
    instagramAppSecretProvider: () -> String,
    messageQueue: MessageQueue,
    deduplicationService: DeduplicationService,
    tenantRegistry: TenantRegistry,
    instagramSocial: InstagramSocialService,
) {
    route("/webhook") {
        get {
            val config = configProvider()
            val mode = call.request.queryParameters["hub.mode"]
            val token = call.request.queryParameters["hub.verify_token"]
            val challenge = call.request.queryParameters["hub.challenge"]

            if (mode == "subscribe" && token == config.verifyToken) {
                log.info("Webhook verification successful")
                call.respondText(challenge.orEmpty())
            } else {
                log.warn("Webhook verification failed: mode={}, token={}", mode, token)
                call.respond(HttpStatusCode.Forbidden, "Verification failed")
            }
        }

        post {
            val config = configProvider()
            val signature = call.request.headers["X-Hub-Signature-256"]?.removePrefix("sha256=")
            if (signature.isNullOrBlank()) {
                log.warn("Missing X-Hub-Signature-256 header")
                call.respond(HttpStatusCode.Unauthorized, "Missing signature")
                return@post
            }

            val body = call.receiveText()

            val objectType = try {
                json.parseToJsonElement(body).jsonObject["object"]?.jsonPrimitive?.content
            } catch (e: Exception) {
                log.error("Failed to parse webhook object", e)
                call.respond(HttpStatusCode.BadRequest, "Invalid payload")
                return@post
            }

            // Instagram-Login webhooks are signed with the Instagram app secret, which differs from
            // the WhatsApp/Facebook app secret. Pick the secret by object before verifying. Falls back
            // to the WhatsApp secret when no Instagram secret is configured (e.g. Facebook-Login setup).
            val appSecret = when (objectType) {
                "instagram" -> instagramAppSecretProvider().ifBlank { config.appSecret }
                else -> config.appSecret
            }
            if (!WebhookVerifier.verifySignature(body, signature, appSecret)) {
                log.warn("Invalid webhook signature (object={})", objectType)
                call.respond(HttpStatusCode.Unauthorized, "Invalid signature")
                return@post
            }

            when (objectType) {
                "whatsapp_business_account" -> handleWhatsAppWebhook(body, messageQueue, deduplicationService, tenantRegistry)
                "instagram" -> handleInstagramWebhook(body, messageQueue, deduplicationService, tenantRegistry, instagramSocial)
                else -> log.info("Ignoring unsupported webhook object={}", objectType)
            }

            call.respond(HttpStatusCode.OK, "ok")
        }
    }
}

private suspend fun handleWhatsAppWebhook(
    body: String,
    messageQueue: MessageQueue,
    deduplicationService: DeduplicationService,
    tenantRegistry: TenantRegistry,
) {
    val payload = try {
        json.decodeFromString(WebhookPayload.serializer(), body)
    } catch (e: Exception) {
        log.error("Failed to parse WhatsApp webhook payload", e)
        return
    }

    log.info("WhatsApp webhook received: entries={}", payload.entry.size)
    for (entry in payload.entry) {
        for (change in entry.changes) {
            if (change.field != "messages") continue
            val value = change.value
            val phoneNumberId = value.metadata?.phoneNumberId
            if (phoneNumberId.isNullOrBlank()) {
                log.warn("Skipping webhook change without phone_number_id metadata")
                continue
            }
            val tenant = activeTenant(tenantRegistry.byPhoneNumberId(phoneNumberId), Platform.WHATSAPP, phoneNumberId) ?: continue

            value.messages?.let { messages ->
                for (message in messages) {
                    if (deduplicationService.isDuplicate(message.id, body, tenant.id)) {
                        log.debug("Skipping duplicate message: id={}", message.id)
                        continue
                    }
                    val messageText = message.text?.body
                    if (messageText.isNullOrBlank()) {
                        log.debug("Skipping non-text message: type={}", message.type)
                        continue
                    }

                    messageQueue.enqueue(
                        InboundMessage(
                            tenantId = tenant.id,
                            phoneNumberId = phoneNumberId,
                            platform = Platform.WHATSAPP,
                            channelExternalId = phoneNumberId,
                            waId = message.from,
                            waMessageId = message.id,
                            profileName = value.contacts?.firstOrNull()?.profile?.name,
                            messageText = messageText,
                            timestamp = message.timestamp,
                            eventId = message.id,
                        )
                    )
                    log.info("Enqueued WhatsApp message: tenant={} from={} id={}", tenant.slug, message.from, message.id)
                }
            }

            value.statuses?.forEach { status -> log.info("Status update: id={} status={}", status.id, status.status) }
        }
    }
}

private suspend fun handleInstagramWebhook(
    body: String,
    messageQueue: MessageQueue,
    deduplicationService: DeduplicationService,
    tenantRegistry: TenantRegistry,
    instagramSocial: InstagramSocialService,
) {
    val payload = try {
        json.decodeFromString(InstagramPayload.serializer(), body)
    } catch (e: Exception) {
        log.error("Failed to parse Instagram webhook payload", e)
        return
    }

    log.info("Instagram webhook received: entries={}", payload.entry.size)
    for (entry in payload.entry) {
        val instagramAccountId = entry.id
        val tenant = activeTenant(tenantRegistry.byExternalId(Platform.INSTAGRAM, instagramAccountId), Platform.INSTAGRAM, instagramAccountId) ?: continue
        for (comment in entry.commentEvents) {
            val commentId = comment.id.takeIf { it.isNotBlank() } ?: continue
            if (deduplicationService.isDuplicate("igc:$commentId", body, tenant.id)) {
                log.debug("Skipping duplicate Instagram comment: id={}", commentId)
                continue
            }
            val receivedAt = entry.time?.let { Instant.fromEpochMilliseconds(it * 1000) } ?: com.rfm.edubot.shared.SystemClock.now()
            try {
                instagramSocial.ingestComment(tenant, instagramAccountId, comment, receivedAt)
            } catch (e: Exception) {
                log.error("Failed to store Instagram comment id={}", commentId, e)
            }
        }
        for (messaging in entry.events) {
            val message = messaging.message ?: continue
            if (message.isEcho && !message.isSelf) {
                log.debug("Skipping Instagram echo message: mid={}", message.mid)
                continue
            }
            // Self Messaging lets a professional account message itself for previews. Meta sends
            // the self IGSID as the recipient; register it without invoking the bot to avoid an
            // echo loop when operators subsequently send from the dashboard.
            val sender = (if (message.isSelf) messaging.recipient else messaging.sender)?.id?.takeIf { it.isNotBlank() } ?: continue
            val mid = message.mid?.takeIf { it.isNotBlank() } ?: continue
            val text = message.text?.takeIf { it.isNotBlank() } ?: continue
            if (deduplicationService.isDuplicate(mid, body, tenant.id)) {
                log.debug("Skipping duplicate Instagram message: mid={}", mid)
                continue
            }

            messageQueue.enqueue(
                InboundMessage(
                    tenantId = tenant.id,
                    phoneNumberId = instagramAccountId,
                    platform = Platform.INSTAGRAM,
                    channelExternalId = instagramAccountId,
                    waId = sender,
                    waMessageId = mid,
                    messageText = text,
                    timestamp = entry.time?.toString().orEmpty(),
                    eventId = mid,
                    registerOnly = message.isSelf,
                )
            )
            log.info("Enqueued Instagram message: tenant={} from={} mid={} self={}", tenant.slug, sender, mid, message.isSelf)
        }
    }
}

private fun activeTenant(tenant: Tenant?, platform: Platform, externalId: String): Tenant? {
    if (tenant == null) {
        log.warn("Skipping webhook for unknown binding platform={} externalId={}", platform, externalId)
        return null
    }
    if (tenant.status != TenantStatus.ACTIVE) {
        log.info("Skipping webhook for inactive tenant slug={} status={}", tenant.slug, tenant.status)
        return null
    }
    return tenant
}
