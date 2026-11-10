package com.rfm.edubot.integrations.aws

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.shared.SystemClock
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import java.util.Base64

/**
 * Amazon SES (API v2) `SendEmail` with a raw MIME message. The recipients go in `Destination`, Bcc
 * included, so the message itself carries no Bcc header. SES replaces the message's `Message-ID`
 * with its own ([messageIdHeader]). Logs statuses and SES's error type, never addresses or content.
 */
class SesClient(
    private val httpClient: HttpClient,
    private val config: () -> AppConfig.MailboxConfig,
    private val clock: () -> Instant = SystemClock::now,
) {
    private val log = LoggerFactory.getLogger("SesClient")
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Send {
        /** [messageId] is SES's id, which recipients see in the `Message-ID` header. */
        data class Sent(val messageId: String) : Send

        /**
         * [key] is a dashboard error key: `rate_limited`, `daily_send_limit`, `recipient_not_verified` (SES's
         * sandbox only sends to verified addresses), `sending_paused`, `invalid_message`, `message_too_large`,
         * `send_in_doubt` (the request may have gone through) or `send_failed`.
         */
        data class Failed(val key: String, val retryable: Boolean, val status: Int? = null) : Send
    }

    suspend fun sendRaw(raw: ByteArray, from: String, to: List<String>, cc: List<String>, bcc: List<String>): Send {
        if (raw.size > MAX_RAW_BYTES) return Send.Failed(TOO_LARGE, retryable = false)
        val cfg = config()
        val body = buildJsonObject {
            putJsonObject("Content") { putJsonObject("Raw") { put("Data", Base64.getEncoder().encodeToString(raw)) } }
            putJsonObject("Destination") {
                if (to.isNotEmpty()) putJsonArray("ToAddresses") { to.forEach { add(JsonPrimitive(it)) } }
                if (cc.isNotEmpty()) putJsonArray("CcAddresses") { cc.forEach { add(JsonPrimitive(it)) } }
                if (bcc.isNotEmpty()) putJsonArray("BccAddresses") { bcc.forEach { add(JsonPrimitive(it)) } }
            }
        }.toString().toByteArray(Charsets.UTF_8)
        val url = Url("${cfg.sesUrl.ifBlank { "https://email.${cfg.region}.amazonaws.com" }}/v2/email/outbound-emails")
        val signature = AwsSigV4.sign(
            method = "POST",
            url = url,
            headers = mapOf("Content-Type" to JSON),
            payloadSha256 = AwsSigV4.sha256Hex(body),
            credentials = AwsCredentials(cfg.accessKeyId, cfg.secretAccessKey),
            region = cfg.region,
            service = "ses",
            at = clock(),
        )
        val response = try {
            httpClient.post(url) {
                headers { signature.forEach { (name, value) -> append(name, value) } }
                setBody(ByteArrayContent(body, ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Refused before anything was sent: safe to try again. Any later failure may have reached SES.
            val unsent = e is ConnectException || e is ConnectTimeoutException || e is UnresolvedAddressException || e.cause is ConnectException
            log.warn("SES send unreachable: error={} unsent={}", e::class.simpleName, unsent)
            return if (unsent) Send.Failed(SEND_FAILED, retryable = true) else Send.Failed(IN_DOUBT, retryable = false)
        }
        val text = response.bodyAsText()
        val status = response.status.value
        if (response.status.isSuccess()) {
            // SES took it: without an id it went out all the same, so it must not be tried again.
            val id = parse(text)?.string("MessageId")?.takeIf { it.isNotBlank() } ?: return Send.Failed(IN_DOUBT, retryable = false, status)
            return Send.Sent(id)
        }
        val type = (response.headers["x-amzn-ErrorType"] ?: parse(text)?.string("__type")).orEmpty().substringBefore(':').substringAfterLast('#')
        val message = parse(text)?.let { it.string("message") ?: it.string("Message") }.orEmpty()
        val failed = classify(status, type, message, from)
        log.warn("SES refused a send: status={} type={} key={}", status, type.ifEmpty { "-" }, failed.key)
        return failed
    }

    private fun classify(status: Int, type: String, message: String, from: String): Send.Failed {
        val lower = message.lowercase()
        return when {
            status == 429 || type == "TooManyRequestsException" || type == "Throttling" ->
                if ("daily" in lower || "quota" in lower) Send.Failed(DAILY_LIMIT, false, status) else Send.Failed(RATE_LIMITED, true, status)
            "quota" in lower -> Send.Failed(DAILY_LIMIT, false, status)
            "rate exceeded" in lower -> Send.Failed(RATE_LIMITED, true, status)
            type == "AccountSuspendedException" || type == "SendingPausedException" -> Send.Failed(SENDING_PAUSED, false, status)
            type == "MessageRejected" && "not verified" in lower -> {
                // The sandbox names the identities it refused; ours means the platform's domain isn't verified.
                val failing = lower.substringAfter("check in region", "").substringAfter(':', "")
                val ownDomain = from.substringAfter('@').lowercase()
                if (failing.isNotBlank() && ownDomain.isNotBlank() && ownDomain in failing) Send.Failed(SEND_FAILED, false, status)
                else Send.Failed(RECIPIENT_NOT_VERIFIED, false, status)
            }
            type == "MessageRejected" || type == "BadRequestException" -> Send.Failed(INVALID_MESSAGE, false, status)
            status >= 500 -> Send.Failed(SEND_FAILED, true, status)
            else -> Send.Failed(SEND_FAILED, false, status)
        }
    }

    private fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** SES v2 takes messages up to 40 MB; the dashboard's attachments are far smaller. */
        const val MAX_RAW_BYTES = 10_000_000
        const val RATE_LIMITED = "rate_limited"
        const val DAILY_LIMIT = "daily_send_limit"
        const val RECIPIENT_NOT_VERIFIED = "recipient_not_verified"
        const val SENDING_PAUSED = "sending_paused"
        const val INVALID_MESSAGE = "invalid_message"
        const val TOO_LARGE = "message_too_large"
        const val IN_DOUBT = "send_in_doubt"
        const val SEND_FAILED = "send_failed"
        private const val JSON = "application/json"

        /**
         * The `Message-ID` recipients see for SES's [messageId]: SES writes it on its region's domain, and
         * `email.amazonses.com` for us-east-1. Replies name it in `In-Reply-To`, which threads them.
         */
        fun messageIdHeader(messageId: String, region: String): String =
            "<$messageId@${if (region == "us-east-1") "email" else region}.amazonses.com>"
    }
}
