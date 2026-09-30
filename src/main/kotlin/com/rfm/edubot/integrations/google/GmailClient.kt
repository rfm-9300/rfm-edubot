package com.rfm.edubot.integrations.google

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * The Gmail API calls made with a connection's access token: sending a message and reading the
 * account's profile. Logs only statuses and Google's reasons, never tokens or message content.
 */
class GmailClient(private val httpClient: HttpClient, private val baseUrl: String = BASE_URL) {
    private val json = Json { ignoreUnknownKeys = true }
    private val log = LoggerFactory.getLogger("GmailClient")

    sealed interface Send {
        data class Sent(val id: String, val threadId: String?) : Send

        /**
         * [key] is a dashboard error key: `unauthorized` (the access token was refused), `needs_reconnect`
         * (the grant lacks gmail.send), `daily_send_limit`, `rate_limited`, `invalid_recipient`,
         * `invalid_message`, `message_too_large`, `send_in_doubt` or `send_failed`.
         */
        data class Failed(val key: String, val retryable: Boolean, val status: Int? = null) : Send
    }

    data class Profile(val emailAddress: String, val historyId: String?)

    /** Sends [raw], an RFC 5322 message; [threadId] files it in that Gmail conversation. */
    suspend fun send(accessToken: String, raw: ByteArray, threadId: String? = null): Send {
        if (raw.size > MAX_RAW_BYTES) return Send.Failed("message_too_large", retryable = false)
        val body = buildJsonObject {
            put("raw", Base64.getUrlEncoder().withoutPadding().encodeToString(raw))
            threadId?.let { put("threadId", it) }
        }
        val response = try {
            httpClient.post("$baseUrl/users/me/messages/send") {
                bearerAuth(accessToken)
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        } catch (e: Exception) {
            log.warn("Gmail send unreachable: {}", e.message)
            return Send.Failed("send_failed", retryable = true)
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) {
            val sent = parse(text)
            // Gmail accepted it: without an id it went out all the same, so it must not be retried.
            val id = sent?.string("id") ?: return Send.Failed("send_in_doubt", retryable = false, status = response.status.value)
            return Send.Sent(id, sent.string("threadId"))
        }
        val (key, retryable) = classify(response, text)
        return Send.Failed(key, retryable, response.status.value)
    }

    /** The account the token acts for; null when Google refuses the token or can't be reached. */
    suspend fun profile(accessToken: String): Profile? {
        val response = try {
            httpClient.get("$baseUrl/users/me/profile") { bearerAuth(accessToken) }
        } catch (e: Exception) {
            log.warn("Gmail profile unreachable: {}", e.message)
            return null
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            log.warn("Gmail profile refused: status={} reason={}", response.status.value, reason(text))
            return null
        }
        val body = parse(text) ?: return null
        val email = body.string("emailAddress")?.trim()?.lowercase() ?: return null
        return Profile(email, body.string("historyId"))
    }

    private fun classify(response: HttpResponse, text: String): Pair<String, Boolean> {
        val status = response.status
        val reason = reason(text)
        val message = ((parse(text)?.get("error") as? JsonObject)?.string("message")).orEmpty()
        log.warn("Gmail send refused: status={} reason={}", status.value, reason)
        return when {
            status == HttpStatusCode.Unauthorized -> "unauthorized" to true
            reason == "dailyLimitExceeded" || SENDING_LIMIT.containsMatchIn(message) -> "daily_send_limit" to false
            status == HttpStatusCode.TooManyRequests || reason in RATE_LIMIT_REASONS -> "rate_limited" to true
            status == HttpStatusCode.Forbidden && reason == "insufficientPermissions" -> "needs_reconnect" to false
            status == HttpStatusCode.PayloadTooLarge -> "message_too_large" to false
            status == HttpStatusCode.BadRequest && INVALID_RECIPIENT.containsMatchIn(message) -> "invalid_recipient" to false
            status == HttpStatusCode.BadRequest -> "invalid_message" to false
            status.value >= 500 -> "send_failed" to true
            else -> "send_failed" to false
        }
    }

    private fun reason(text: String): String? {
        val error = parse(text)?.get("error") as? JsonObject ?: return null
        val first = (error["errors"] as? JsonArray)?.firstOrNull() as? JsonObject
        return first?.string("reason") ?: error.string("status")
    }

    private fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val BASE_URL = "https://gmail.googleapis.com/gmail/v1"

        /** Gmail's JSON send takes messages of a few megabytes; quotes and invoices stay far below. */
        const val MAX_RAW_BYTES = 4_500_000

        private val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded", "RESOURCE_EXHAUSTED")
        private val SENDING_LIMIT = Regex("sending limit|daily user sending", RegexOption.IGNORE_CASE)
        private val INVALID_RECIPIENT = Regex("invalid (to|cc|bcc) header|recipient address required", RegexOption.IGNORE_CASE)
    }
}
