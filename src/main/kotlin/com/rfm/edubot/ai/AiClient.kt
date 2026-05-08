package com.rfm.edubot.ai

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens")
    val maxTokens: Int = 1024,
)

@Serializable
data class UsageInfo(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0,
)

@Serializable
data class ChoiceInfo(
    val message: ChatMessage,
    val finish_reason: String? = null,
)

@Serializable
data class ChatResponse(
    val id: String,
    val choices: List<ChoiceInfo>,
    val usage: UsageInfo? = null,
)

class AiClient(
    private val apiKey: String,
    private val primaryModel: String,
    private val fallbackModel: String,
    private val maxTokens: Int = 1024,
    private val maxRetries: Int = 3,
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 30000
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val log = LoggerFactory.getLogger("AiClient")

    suspend fun complete(messages: List<ChatMessage>): ChatResponse {
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                val model = if (attempt > 1) fallbackModel else primaryModel
                return executeRequest(model, messages)
            } catch (e: Exception) {
                lastException = e
                log.warn("AI request failed (attempt {}/{}): {}", attempt, maxRetries, e.message)
                if (attempt < maxRetries) {
                    delay(500L * (1L shl (attempt - 1)))
                }
            }
        }

        throw lastException ?: RuntimeException("AI request failed after $maxRetries attempts")
    }

    private suspend fun executeRequest(model: String, messages: List<ChatMessage>): ChatResponse {
        val request = ChatRequest(model = model, messages = messages, maxTokens = maxTokens)

        val response: HttpResponse = client.post("https://openrouter.ai/api/v1/chat/completions") {
            headers {
                append("Authorization", "Bearer $apiKey")
                append("HTTP-Referer", "https://github.com/rfm/whatsapp-bot")
                append("X-Title", "WhatsApp AI Bot")
            }
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        val responseText = response.bodyAsText()

        if (response.status.value >= 500) {
            throw RuntimeException("OpenRouter server error: ${response.status.value}")
        }

        if (response.status.value >= 400) {
            throw RuntimeException("OpenRouter client error: ${response.status.value} - $responseText")
        }

        return json.decodeFromString<ChatResponse>(responseText)
    }

    fun close() {
        client.close()
    }
}
