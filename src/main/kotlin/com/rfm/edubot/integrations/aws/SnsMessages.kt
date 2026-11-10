package com.rfm.edubot.integrations.aws

import com.rfm.edubot.shared.SystemClock
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** An Amazon SNS message as it is POSTed to an HTTPS subscription. */
data class SnsMessage(
    val type: String,
    val messageId: String,
    val topicArn: String,
    val message: String,
    val timestamp: String,
    val signatureVersion: String,
    val signature: String,
    val signingCertUrl: String,
    val subject: String? = null,
    val subscribeUrl: String? = null,
    val token: String? = null,
) {
    companion object {
        const val NOTIFICATION = "Notification"
        const val SUBSCRIPTION_CONFIRMATION = "SubscriptionConfirmation"
        const val UNSUBSCRIBE_CONFIRMATION = "UnsubscribeConfirmation"

        private val json = Json { ignoreUnknownKeys = true }

        /** Null when [body] isn't an SNS message. */
        fun parse(body: String): SnsMessage? {
            val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            fun string(name: String) = (obj[name] as? JsonPrimitive)?.contentOrNull
            return SnsMessage(
                type = string("Type") ?: return null,
                messageId = string("MessageId") ?: return null,
                topicArn = string("TopicArn") ?: return null,
                message = string("Message") ?: return null,
                timestamp = string("Timestamp") ?: return null,
                signatureVersion = string("SignatureVersion") ?: return null,
                signature = string("Signature") ?: return null,
                signingCertUrl = string("SigningCertURL") ?: string("SigningCertUrl") ?: return null,
                subject = string("Subject"),
                subscribeUrl = string("SubscribeURL"),
                token = string("Token"),
            )
        }

        /** What SNS signed, by message type (AWS's "Verifying the signatures of Amazon SNS messages"). */
        fun stringToSign(m: SnsMessage): String? {
            val fields = when (m.type) {
                NOTIFICATION -> listOfNotNull(
                    "Message" to m.message,
                    "MessageId" to m.messageId,
                    m.subject?.let { "Subject" to it },
                    "Timestamp" to m.timestamp,
                    "TopicArn" to m.topicArn,
                    "Type" to m.type,
                )
                SUBSCRIPTION_CONFIRMATION, UNSUBSCRIBE_CONFIRMATION -> listOf(
                    "Message" to m.message,
                    "MessageId" to m.messageId,
                    "SubscribeURL" to (m.subscribeUrl ?: return null),
                    "Timestamp" to m.timestamp,
                    "Token" to (m.token ?: return null),
                    "TopicArn" to m.topicArn,
                    "Type" to m.type,
                )
                else -> return null
            }
            return fields.joinToString("") { (name, value) -> "$name\n$value\n" }
        }

        private val SNS_HOST = Regex("^sns\\.[a-z0-9-]+\\.amazonaws\\.com(\\.cn)?$")

        /** SNS's own endpoint in some region, over HTTPS: where its certificates and confirmation links live. */
        fun isSnsUrl(raw: String, pathSuffix: String? = null): Boolean {
            val url = runCatching { Url(raw) }.getOrNull() ?: return false
            if (url.protocol.name != "https" || !SNS_HOST.matches(url.host.lowercase())) return false
            if (url.specifiedPort != 0 && url.specifiedPort != 443) return false
            return pathSuffix == null || url.encodedPath.endsWith(pathSuffix)
        }
    }
}

/**
 * Checks that an SNS message was signed by SNS: the signing certificate must come from SNS's own host
 * over HTTPS (or be the one [trustedCertUrl] a local end-to-end run names), be valid now, and verify
 * the message's signature (SHA1withRSA for version 1, SHA256withRSA for 2). Certificates are kept by URL.
 * Messages older than [maxAge] are refused: SNS stops retrying long before.
 */
class SnsVerifier(
    private val httpClient: HttpClient,
    private val trustedCertUrl: () -> String = { "" },
    private val clock: () -> Instant = SystemClock::now,
    private val maxAge: Duration = 6.hours,
) {
    private val log = LoggerFactory.getLogger("SnsVerifier")
    private val certificates = ConcurrentHashMap<String, X509Certificate>()

    suspend fun verify(message: SnsMessage): Boolean {
        val url = message.signingCertUrl
        val trusted = trustedCertUrl().takeIf { it.isNotBlank() }
        if (url != trusted && !SnsMessage.isSnsUrl(url, ".pem")) {
            log.warn("SNS message refused: signing certificate not on SNS's host")
            return false
        }
        val sent = runCatching { Instant.parse(message.timestamp) }.getOrNull() ?: return false
        if (clock() - sent > maxAge) {
            log.warn("SNS message refused: too old ({})", message.timestamp)
            return false
        }
        val algorithm = when (message.signatureVersion) {
            "1" -> "SHA1withRSA"
            "2" -> "SHA256withRSA"
            else -> return false
        }
        val toSign = SnsMessage.stringToSign(message) ?: return false
        val signature = runCatching { Base64.getDecoder().decode(message.signature) }.getOrNull() ?: return false
        val certificate = certificate(url) ?: return false
        return try {
            certificate.checkValidity()
            Signature.getInstance(algorithm).run {
                initVerify(certificate.publicKey)
                update(toSign.toByteArray(Charsets.UTF_8))
                verify(signature)
            }
        } catch (e: Exception) {
            log.warn("SNS signature check failed: {}", e::class.simpleName)
            false
        }
    }

    private suspend fun certificate(url: String): X509Certificate? {
        certificates[url]?.let { return it }
        val bytes = try {
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) return null.also { log.warn("SNS certificate not fetched: status={}", response.status.value) }
            response.body<ByteArray>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("SNS certificate unreachable: {}", e::class.simpleName)
            return null
        }
        val certificate = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
        }.getOrNull() ?: return null
        if (certificates.size > MAX_CERTIFICATES) certificates.clear()
        certificates[url] = certificate
        return certificate
    }

    private companion object {
        const val MAX_CERTIFICATES = 20
    }
}
