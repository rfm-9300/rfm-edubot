package com.rfm.edubot.instagram

import com.rfm.edubot.channel.ChannelCapabilities
import com.rfm.edubot.channel.OutboundClient
import com.rfm.edubot.channel.OutboundDeliveryException
import com.rfm.edubot.channel.ProfileLookupClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
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

@Serializable
private data class InstagramProfile(val name: String? = null, val username: String? = null)

class InstagramClient(
    private val accessToken: String,
    private val instagramAccountId: String,
    private val apiVersion: String = "v21.0",
    private val httpClient: HttpClient? = null,
) : OutboundClient, ProfileLookupClient {
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
            throw OutboundDeliveryException("Instagram account $instagramAccountId has no access token")
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
            val errorBody = response.bodyAsText()
            log.error("Instagram send error: status={} body={}", response.status.value, errorBody)
            throw OutboundDeliveryException("Instagram API error: ${response.status.value} - $errorBody")
        }
        log.info("Message sent to Instagram: igId={} to={}", instagramAccountId, to)
    }

    override suspend fun profileName(id: String): String? {
        if (accessToken.isBlank()) return null
        val response = client.get("https://graph.instagram.com/$apiVersion/$id?fields=name,username") {
            header("Authorization", "Bearer $accessToken")
        }
        val body = response.bodyAsText()
        if (response.status.value >= 400) {
            log.warn("Instagram profile lookup failed: status={} id={}", response.status.value, id)
            return null
        }
        return try {
            val profile = json.decodeFromString<InstagramProfile>(body)
            profile.name ?: profile.username
        } catch (e: Exception) {
            log.warn("Could not parse Instagram profile response for id={}", id, e)
            null
        }
    }

    override suspend fun sendDocument(to: String, bytes: ByteArray, filename: String, mimeType: String) {
        log.info("Instagram does not support document delivery; skipped filename={} to={}", filename, to)
    }

    fun close() {
        if (ownsClient) client.close()
    }
}
