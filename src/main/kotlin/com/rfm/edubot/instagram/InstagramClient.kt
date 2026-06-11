package com.rfm.edubot.instagram

import com.rfm.edubot.channel.ChannelCapabilities
import com.rfm.edubot.channel.OutboundClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
private data class InstagramSendMessageRequest(
    val recipient: InstagramRecipient,
    val message: InstagramMessage,
)

@Serializable
private data class InstagramRecipient(val id: String)

@Serializable
private data class InstagramMessage(val text: String)

class InstagramClient(
    private val accessToken: String,
    private val instagramAccountId: String,
    private val apiVersion: String = "v21.0",
    private val httpClient: HttpClient? = null,
) : OutboundClient {
    // Instagram-Login tokens are valid against graph.instagram.com, NOT graph.facebook.com.
    // See docs/plan-instagram-oauth-onboarding.md §7.
    private val baseUrl = "https://graph.instagram.com/$apiVersion/$instagramAccountId"
    private val ownsClient = httpClient == null
    private val client = httpClient ?: HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15000 }
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val log = LoggerFactory.getLogger("InstagramClient")

    override val capabilities = ChannelCapabilities(supportsDocuments = false)

    override suspend fun sendText(to: String, text: String) {
        if (accessToken.isBlank()) {
            log.warn("Skipping Instagram send without access token: igId={}", instagramAccountId)
            return
        }
        val body = json.encodeToString(
            InstagramSendMessageRequest(
                recipient = InstagramRecipient(to),
                message = InstagramMessage(text),
            )
        )
        val response: HttpResponse = client.post("$baseUrl/messages") {
            header("Authorization", "Bearer $accessToken")
            header("Content-Type", "application/json")
            setBody(body)
        }
        if (response.status.value >= 400) {
            log.error("Instagram send error: status={} body={}", response.status.value, response.bodyAsText())
            return
        }
        log.info("Message sent to Instagram: igId={} to={}", instagramAccountId, to)
    }

    override suspend fun sendDocument(to: String, bytes: ByteArray, filename: String, mimeType: String) {
        log.info("Instagram does not support document delivery; skipped filename={} to={}", filename, to)
    }

    fun close() {
        if (ownsClient) client.close()
    }
}
