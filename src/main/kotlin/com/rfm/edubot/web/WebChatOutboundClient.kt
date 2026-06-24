package com.rfm.edubot.web

import com.rfm.edubot.channel.ChannelCapabilities
import com.rfm.edubot.channel.OutboundClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
internal data class BotMessageFrame(val type: String = "bot_message", val text: String)

/**
 * [OutboundClient] for the website channel. `to` is the browser session id; replies are routed to the
 * live WebSocket through [WebChannelRegistry]. Documents are not delivered inline for v1 — the pipeline
 * falls back to its "available in dashboard" text because [capabilities] reports no document support
 * (mirrors [com.rfm.edubot.instagram.InstagramClient]).
 */
class WebChatOutboundClient(
    private val registry: WebChannelRegistry,
) : OutboundClient {
    private val log = LoggerFactory.getLogger("WebChatOutboundClient")
    private val json = Json { encodeDefaults = true }

    override val capabilities = ChannelCapabilities(supportsDocuments = false)

    override suspend fun sendText(to: String, text: String) {
        registry.send(to, json.encodeToString(BotMessageFrame(text = text)))
    }

    override suspend fun sendDocument(to: String, bytes: ByteArray, filename: String, mimeType: String) {
        log.info("Web channel does not support inline document delivery; skipped filename={} session={}", filename, to)
    }
}
