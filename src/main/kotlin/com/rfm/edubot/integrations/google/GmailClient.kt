package com.rfm.edubot.integrations.google

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
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
 * The Gmail API calls made with a connection's access token: sending a message, reading the account's
 * profile and, for inbox sync, its history and messages. Logs only statuses and Google's reasons,
 * never tokens or message content.
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

    sealed interface Read<out T> {
        data class Ok<T>(val value: T) : Read<T>

        /**
         * [key]: `unauthorized` (the access token was refused), `needs_reconnect` (the grant can't read the
         * mailbox), `not_found` (a history cursor Gmail no longer keeps, or a message deleted since),
         * `rate_limited` or `read_failed`.
         */
        data class Failed(val key: String, val retryable: Boolean, val status: Int? = null) : Read<Nothing>
    }

    data class Profile(val emailAddress: String, val historyId: String?)

    /** One page of changes after a history cursor, oldest first; [historyId] is the mailbox's latest. */
    data class History(val records: List<HistoryRecord>, val historyId: String?, val nextPageToken: String?)

    /** A change and the inbox messages it added (none when it only touched labels). */
    data class HistoryRecord(val id: String, val messageIds: List<String>)

    /** One page of message ids, newest first. */
    data class MessagePage(val ids: List<String>, val nextPageToken: String?)

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
    suspend fun profile(accessToken: String): Profile? = (mailbox(accessToken) as? Read.Ok)?.value

    /** The account the token acts for and the mailbox's current history id, where reading new mail starts. */
    suspend fun mailbox(accessToken: String): Read<Profile> = read("profile", "$baseUrl/users/me/profile", accessToken) { body ->
        body.string("emailAddress")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { Profile(it, body.string("historyId")) }
    }

    /** Messages added to the inbox after [startHistoryId]; `not_found` when Gmail no longer keeps that point. */
    suspend fun history(accessToken: String, startHistoryId: String, pageToken: String? = null): Read<History> =
        read("history", "$baseUrl/users/me/history", accessToken, {
            parameter("startHistoryId", startHistoryId)
            parameter("historyTypes", "messageAdded")
            parameter("labelId", INBOX)
            parameter("maxResults", PAGE_SIZE)
            pageToken?.let { parameter("pageToken", it) }
        }) { body ->
            val records = (body["history"] as? JsonArray).orEmpty().mapNotNull { element ->
                val record = element as? JsonObject ?: return@mapNotNull null
                val id = record.string("id") ?: return@mapNotNull null
                val added = (record["messagesAdded"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("message") as? JsonObject }
                HistoryRecord(id, added.filter { it.labels()?.contains(INBOX) != false }.mapNotNull { it.string("id") }.distinct())
            }
            History(records, body.string("historyId"), body.string("nextPageToken"))
        }

    /** Inbox messages received after [after], a page at a time. */
    suspend fun inbox(accessToken: String, after: Instant, pageToken: String? = null): Read<MessagePage> =
        read("messages.list", "$baseUrl/users/me/messages", accessToken, {
            parameter("q", "in:inbox after:${after.epochSeconds}")
            parameter("maxResults", PAGE_SIZE)
            pageToken?.let { parameter("pageToken", it) }
        }) { body ->
            val ids = (body["messages"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("id") }
            MessagePage(ids, body.string("nextPageToken"))
        }

    /** The whole message, parsed; attachments are listed, never downloaded. */
    suspend fun message(accessToken: String, id: String): Read<GmailMessage> {
        if (!MESSAGE_ID.matches(id)) return Read.Failed(NOT_FOUND, retryable = false)
        return read("messages.get", "$baseUrl/users/me/messages/$id", accessToken, { parameter("format", "full") }) { GmailMessages.parse(it) }
    }

    private suspend fun <T> read(
        what: String,
        url: String,
        accessToken: String,
        query: HttpRequestBuilder.() -> Unit = {},
        decode: (JsonObject) -> T?,
    ): Read<T> {
        val response = try {
            httpClient.get(url) {
                bearerAuth(accessToken)
                query()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Gmail {} unreachable: {}", what, e.message)
            return Read.Failed(READ_FAILED, retryable = true)
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) {
            val value = parse(text)?.let(decode) ?: return Read.Failed(READ_FAILED, retryable = false, status = response.status.value)
            return Read.Ok(value)
        }
        val status = response.status
        val reason = reason(text)
        log.warn("Gmail {} refused: status={} reason={}", what, status.value, reason)
        val (key, retryable) = when {
            status == HttpStatusCode.Unauthorized -> UNAUTHORIZED to true
            status == HttpStatusCode.TooManyRequests || reason in RATE_LIMIT_REASONS -> RATE_LIMITED to true
            status == HttpStatusCode.Forbidden && reason == "insufficientPermissions" -> NEEDS_RECONNECT to false
            status == HttpStatusCode.NotFound -> NOT_FOUND to false
            status.value >= 500 -> READ_FAILED to true
            else -> READ_FAILED to false
        }
        return Read.Failed(key, retryable, status.value)
    }

    private fun JsonObject.labels(): List<String>? = (this["labelIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun classify(response: HttpResponse, text: String): Pair<String, Boolean> {
        val status = response.status
        val reason = reason(text)
        val message = ((parse(text)?.get("error") as? JsonObject)?.string("message")).orEmpty()
        log.warn("Gmail send refused: status={} reason={}", status.value, reason)
        return when {
            status == HttpStatusCode.Unauthorized -> "unauthorized" to true
            reason == "dailyLimitExceeded" || SENDING_LIMIT.containsMatchIn(message) -> "daily_send_limit" to false
            status == HttpStatusCode.TooManyRequests || reason in RATE_LIMIT_REASONS -> RATE_LIMITED to true
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

        const val RATE_LIMITED = "rate_limited"
        const val UNAUTHORIZED = "unauthorized"
        const val NEEDS_RECONNECT = "needs_reconnect"
        const val NOT_FOUND = "not_found"
        const val READ_FAILED = "read_failed"
        const val INBOX = "INBOX"

        private const val PAGE_SIZE = 100
        private val MESSAGE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded", "RESOURCE_EXHAUSTED")
        private val SENDING_LIMIT = Regex("sending limit|daily user sending", RegexOption.IGNORE_CASE)
        private val INVALID_RECIPIENT = Regex("invalid (to|cc|bcc) header|recipient address required", RegexOption.IGNORE_CASE)
    }
}
