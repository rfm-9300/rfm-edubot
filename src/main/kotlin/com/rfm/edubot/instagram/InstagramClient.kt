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

@Serializable
private data class GraphMediaListResponse(val data: List<GraphMediaItem> = emptyList())

@Serializable
private data class GraphCommentListResponse(val data: List<GraphCommentItem> = emptyList())

@Serializable
private data class GraphMediaItem(
    val id: String,
    val caption: String? = null,
    @kotlinx.serialization.SerialName("media_type") val mediaType: String? = null,
    @kotlinx.serialization.SerialName("media_url") val mediaUrl: String? = null,
    @kotlinx.serialization.SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val permalink: String? = null,
    val timestamp: String? = null,
    @kotlinx.serialization.SerialName("comments_count") val commentsCount: Int? = null,
)

@Serializable
private data class GraphCommentItem(
    val id: String,
    val text: String? = null,
    val timestamp: String? = null,
    val username: String? = null,
    val from: GraphCommentFrom? = null,
)

@Serializable
private data class GraphCommentFrom(val id: String? = null, val username: String? = null)

@Serializable
private data class GraphReplyRequest(val message: String)

@Serializable
private data class GraphIdResponse(val id: String? = null)

data class InstagramGraphMedia(
    val id: String,
    val caption: String? = null,
    val mediaType: String? = null,
    val mediaUrl: String? = null,
    val thumbnailUrl: String? = null,
    val permalink: String? = null,
    val timestamp: String? = null,
    val commentsCount: Int? = null,
)

data class InstagramGraphComment(
    val id: String,
    val text: String,
    val timestamp: String? = null,
    val fromId: String? = null,
    val fromUsername: String? = null,
)

sealed class InstagramGraphResult<out T> {
    data class Ok<T>(val value: T) : InstagramGraphResult<T>()
    data object PermissionDenied : InstagramGraphResult<Nothing>()
    data class Failed(val message: String) : InstagramGraphResult<Nothing>()
}

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

    suspend fun listMedia(): InstagramGraphResult<List<InstagramGraphMedia>> {
        if (accessToken.isBlank()) return InstagramGraphResult.Failed("missing access token")
        val response = client.get("https://graph.instagram.com/$apiVersion/me/media") {
            header("Authorization", "Bearer $accessToken")
            url.parameters.append("fields", "id,caption,media_type,media_url,thumbnail_url,permalink,timestamp,comments_count")
            url.parameters.append("limit", "40")
        }
        val body = response.bodyAsText()
        if (isPermissionDenied(response.status.value, body)) return InstagramGraphResult.PermissionDenied
        if (response.status.value >= 400) {
            log.warn("Instagram media list failed: status={} body={}", response.status.value, body)
            return InstagramGraphResult.Failed("Instagram API error: ${response.status.value}")
        }
        val items = runCatching { json.decodeFromString(GraphMediaListResponse.serializer(), body) }
            .getOrElse { return InstagramGraphResult.Failed("Could not parse media list") }
        return InstagramGraphResult.Ok(
            items.data.map {
                InstagramGraphMedia(
                    id = it.id,
                    caption = it.caption,
                    mediaType = it.mediaType,
                    mediaUrl = it.mediaUrl,
                    thumbnailUrl = it.thumbnailUrl,
                    permalink = it.permalink,
                    timestamp = it.timestamp,
                    commentsCount = it.commentsCount,
                )
            },
        )
    }

    suspend fun listComments(mediaId: String): InstagramGraphResult<List<InstagramGraphComment>> {
        if (accessToken.isBlank()) return InstagramGraphResult.Failed("missing access token")
        val response = client.get("https://graph.instagram.com/$apiVersion/$mediaId/comments") {
            header("Authorization", "Bearer $accessToken")
            url.parameters.append("fields", "id,text,username,timestamp,from")
        }
        val body = response.bodyAsText()
        if (isPermissionDenied(response.status.value, body)) return InstagramGraphResult.PermissionDenied
        if (response.status.value >= 400) {
            log.warn("Instagram comments list failed: status={} mediaId={}", response.status.value, mediaId)
            return InstagramGraphResult.Failed("Instagram API error: ${response.status.value}")
        }
        val items = runCatching { json.decodeFromString(GraphCommentListResponse.serializer(), body) }
            .getOrElse { return InstagramGraphResult.Failed("Could not parse comments") }
        return InstagramGraphResult.Ok(
            items.data.mapNotNull { item ->
                val text = item.text ?: return@mapNotNull null
                InstagramGraphComment(
                    id = item.id,
                    text = text,
                    timestamp = item.timestamp,
                    fromId = item.from?.id,
                    fromUsername = item.from?.username ?: item.username,
                )
            },
        )
    }

    suspend fun replyToComment(commentId: String, message: String): InstagramGraphResult<String> {
        if (accessToken.isBlank()) return InstagramGraphResult.Failed("missing access token")
        val payload = json.encodeToString(GraphReplyRequest.serializer(), GraphReplyRequest(message))
        val response = client.post("https://graph.instagram.com/$apiVersion/$commentId/replies") {
            header("Authorization", "Bearer $accessToken")
            header("Content-Type", "application/json")
            setBody(payload)
        }
        val body = response.bodyAsText()
        if (isPermissionDenied(response.status.value, body)) return InstagramGraphResult.PermissionDenied
        if (response.status.value >= 400) {
            log.error("Instagram comment reply failed: status={} body={}", response.status.value, body)
            return InstagramGraphResult.Failed("Instagram API error: ${response.status.value}")
        }
        val id = runCatching { json.decodeFromString(GraphIdResponse.serializer(), body).id }.getOrNull()
        return InstagramGraphResult.Ok(id ?: commentId)
    }

    private fun isPermissionDenied(status: Int, body: String): Boolean {
        if (status == 401 || status == 403) return true
        return body.contains("\"code\":10") || body.contains("\"code\":190") || body.contains("(#10)") || body.contains("(#190)")
    }

    fun close() {
        if (ownsClient) client.close()
    }
}
