package com.rfm.edubot.whatsapp

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
) {
    private val baseUrl = "https://graph.facebook.com/$apiVersion/$phoneNumberId"
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val log = LoggerFactory.getLogger("WhatsAppClient")

    suspend fun sendText(to: String, text: String) {
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                val request = SendMessageRequest(to = to, text = TextMessage(body = text))
                val body = json.encodeToString(request)
                log.info("Sending to {}/messages body={}", baseUrl, body)

                val response: HttpResponse = client.post("$baseUrl/messages") {
                    header("Authorization", "Bearer $accessToken")
                    header("Content-Type", "application/json")
                    setBody(body)
                }

                if (response.status.value >= 400) {
                    val errorBody = response.bodyAsText()
                    if (response.status.value >= 500) {
                        throw RuntimeException("WhatsApp API error: ${response.status.value} - $errorBody")
                    }
                    log.error("Permanent WhatsApp error (attempt {}): {} - {}", attempt, response.status.value, errorBody)
                    return
                }

                log.info("Message sent to WhatsApp: to={}", to)
                return
            } catch (e: Exception) {
                lastException = e
                log.warn("WhatsApp send failed (attempt {}/{}): {}", attempt, maxRetries, e.message)
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

    fun close() {
        client.close()
    }
}
