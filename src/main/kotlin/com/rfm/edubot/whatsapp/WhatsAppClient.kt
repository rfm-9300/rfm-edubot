package com.rfm.edubot.whatsapp

import com.rfm.edubot.channel.ChannelCapabilities
import com.rfm.edubot.channel.OutboundClient
import com.rfm.edubot.channel.OutboundDeliveryException
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.forms.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class SendMessageRequest(
    val messaging_product: String = "whatsapp",
    val recipient_type: String = "individual",
    val to: String,
    val type: String = "text",
    val text: TextMessage? = null,
    val document: DocumentMessage? = null,
    val template: TemplateMessage? = null,
)

/** An accepted message: its WhatsApp id, and the WhatsApp user id Meta resolved the recipient number to. */
data class SentMessage(val id: String?, val waId: String?)

@Serializable
private data class SendMessageResponse(val contacts: List<SentContact> = emptyList(), val messages: List<SentMessageId> = emptyList())

@Serializable
private data class SentContact(@SerialName("wa_id") val waId: String? = null)

@Serializable
private data class SentMessageId(val id: String? = null)

/** Downloaded customer media: its bytes and Meta's content type. */
class MediaFile(val bytes: ByteArray, val mimeType: String)

class MediaTooLargeException(val size: Long) : RuntimeException("Media too large: $size bytes")

@Serializable
private data class MediaInfo(
    val url: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
)

@Serializable
data class TextMessage(
    val body: String,
)

@Serializable
data class DocumentMessage(
    val id: String,
    val filename: String,
)

@Serializable
data class MediaUploadResponse(
    val id: String,
)

class WhatsAppClient(
    private val accessToken: String,
    private val phoneNumberId: String,
    private val apiVersion: String = "v21.0",
    private val maxRetries: Int = 3,
    private val httpClient: HttpClient? = null,
) : OutboundClient {
    private val graphRoot = "https://graph.facebook.com/$apiVersion"
    private val baseUrl = "$graphRoot/$phoneNumberId"
    private val ownsClient = httpClient == null
    private val client = httpClient ?: HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val log = LoggerFactory.getLogger("WhatsAppClient")

    override val capabilities = ChannelCapabilities(supportsDocuments = true)

    override suspend fun sendText(to: String, text: String) {
        sendTextMessage(to, text)
    }

    /** Sends a text message; later status webhooks refer to the returned id. */
    suspend fun sendTextMessage(to: String, text: String): SentMessage =
        postMessage(to, json.encodeToString(SendMessageRequest(to = to, text = TextMessage(body = text))))

    /** Sends an approved template; works outside the 24-hour customer service window. */
    suspend fun sendTemplate(to: String, name: String, language: String, bodyParameters: List<TemplateParameter>): SentMessage {
        val template = TemplateMessage(
            name = name,
            language = TemplateLanguage(language),
            components = bodyParameters.takeIf { it.isNotEmpty() }?.let { listOf(TemplateComponent("body", it)) },
        )
        return postMessage(to, json.encodeToString(SendMessageRequest(to = to, type = "template", template = template)))
    }

    /** Every template on [wabaId], whatever its review status, following Graph paging up to 500 templates. */
    suspend fun templates(wabaId: String): List<WhatsAppTemplate> {
        val result = mutableListOf<WhatsAppTemplate>()
        var url: String? = "$graphRoot/$wabaId/message_templates?limit=100&fields=id,name,language,status,category,components,parameter_format,rejected_reason"
        var pages = 0
        while (url != null && pages < 5) {
            val response: HttpResponse = client.get(url) { header("Authorization", "Bearer $accessToken") }
            val text = response.bodyAsText()
            if (response.status.value >= 400) throw WhatsAppApiException.fromResponse(response.status.value, text)
            val page = json.decodeFromString<GraphTemplatesPage>(text)
            page.data.mapNotNullTo(result) { WhatsAppTemplate.fromGraph(it) }
            url = page.paging?.next
            pages += 1
        }
        return result
    }

    /** Submits a template for Meta's review; it usually comes back PENDING and is decided within minutes. */
    suspend fun createTemplate(wabaId: String, draft: TemplateDraft): CreatedTemplate {
        val response: HttpResponse = client.post("$graphRoot/$wabaId/message_templates") {
            header("Authorization", "Bearer $accessToken")
            header("Content-Type", "application/json")
            setBody(json.encodeToString(draft.toRequest()))
        }
        val text = response.bodyAsText()
        if (response.status.value >= 400) throw WhatsAppApiException.fromResponse(response.status.value, text)
        return json.decodeFromString<CreatedTemplate>(text)
    }

    /** Deletes one language version of a template ([templateId]); without it Meta deletes every language of [name]. */
    suspend fun deleteTemplate(wabaId: String, name: String, templateId: String?) {
        val response: HttpResponse = client.delete("$graphRoot/$wabaId/message_templates") {
            header("Authorization", "Bearer $accessToken")
            url.parameters.append("name", name)
            templateId?.let { url.parameters.append("hsm_id", it) }
        }
        if (response.status.value >= 400) throw WhatsAppApiException.fromResponse(response.status.value, response.bodyAsText())
    }

    /**
     * A photo, voice note, video or document a customer sent. Meta hands out a download URL that expires
     * after 5 minutes and needs the same token; the media itself is kept for 30 days.
     */
    suspend fun downloadMedia(mediaId: String, maxBytes: Long): MediaFile {
        val metadata: HttpResponse = client.get("$graphRoot/$mediaId") { header("Authorization", "Bearer $accessToken") }
        val metadataText = metadata.bodyAsText()
        if (metadata.status.value >= 400) throw WhatsAppApiException.fromResponse(metadata.status.value, metadataText)
        val info = json.decodeFromString<MediaInfo>(metadataText)
        if ((info.fileSize ?: 0) > maxBytes) throw MediaTooLargeException(info.fileSize ?: 0)
        val url = info.url ?: throw WhatsAppApiException(metadata.status.value, null, null, "Media has no download URL", null, null)
        val file: HttpResponse = client.get(url) {
            header("Authorization", "Bearer $accessToken")
            timeout { requestTimeoutMillis = 60_000 }
        }
        if (file.status.value >= 400) throw WhatsAppApiException.fromResponse(file.status.value, file.bodyAsText())
        val bytes = file.bodyAsBytes()
        if (bytes.size > maxBytes) throw MediaTooLargeException(bytes.size.toLong())
        return MediaFile(bytes, info.mimeType ?: file.headers[HttpHeaders.ContentType] ?: "application/octet-stream")
    }

    private suspend fun postMessage(to: String, body: String): SentMessage {
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                log.info("Sending to {}/messages body={}", baseUrl, body)

                val response: HttpResponse = client.post("$baseUrl/messages") {
                    header("Authorization", "Bearer $accessToken")
                    header("Content-Type", "application/json")
                    setBody(body)
                }

                val responseBody = response.bodyAsText()
                if (response.status.value >= 400) {
                    if (response.status.value >= 500) {
                        throw RuntimeException("WhatsApp API error: ${response.status.value} - $responseBody")
                    }
                    log.error("Permanent WhatsApp error (attempt {}): {} - {}", attempt, response.status.value, responseBody)
                    throw WhatsAppApiException.fromResponse(response.status.value, responseBody)
                }

                val parsed = runCatching { json.decodeFromString<SendMessageResponse>(responseBody) }.getOrNull()
                val sent = SentMessage(id = parsed?.messages?.firstOrNull()?.id, waId = parsed?.contacts?.firstOrNull()?.waId)
                log.info("Message sent to WhatsApp: to={} id={}", to, sent.id)
                return sent
            } catch (e: Exception) {
                lastException = e
                log.warn("WhatsApp send failed (attempt {}/{}): {}", attempt, maxRetries, e.message)
                if (e is OutboundDeliveryException) throw e
                if (attempt < maxRetries) {
                    delay(500L * (1L shl (attempt - 1)))
                }
            }
        }

        throw lastException ?: RuntimeException("WhatsApp send failed after $maxRetries attempts")
    }

    suspend fun uploadMedia(bytes: ByteArray, mimeType: String): String {
        val response: HttpResponse = client.submitFormWithBinaryData(
            url = "$baseUrl/media",
            formData = formData {
                append("messaging_product", "whatsapp")
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, mimeType)
                    append(HttpHeaders.ContentDisposition, "filename=\"document.pdf\"")
                })
            },
        ) {
            header("Authorization", "Bearer $accessToken")
        }

        val body = response.bodyAsText()
        if (response.status.value >= 400) {
            throw RuntimeException("WhatsApp media upload error: ${response.status.value} - $body")
        }
        return json.decodeFromString<MediaUploadResponse>(body).id
    }

    suspend fun sendDocument(to: String, mediaId: String, filename: String) {
        val request = SendMessageRequest(
            to = to,
            type = "document",
            document = DocumentMessage(id = mediaId, filename = filename),
        )
        val body = json.encodeToString(request)
        val response: HttpResponse = client.post("$baseUrl/messages") {
            header("Authorization", "Bearer $accessToken")
            header("Content-Type", "application/json")
            setBody(body)
        }
        if (response.status.value >= 400) {
            throw RuntimeException("WhatsApp document send error: ${response.status.value} - ${response.bodyAsText()}")
        }
    }

    override suspend fun sendDocument(to: String, bytes: ByteArray, filename: String, mimeType: String) {
        val mediaId = uploadMedia(bytes, mimeType)
        sendDocument(to, mediaId, filename)
    }

    fun close() {
        if (ownsClient) client.close()
    }
}
