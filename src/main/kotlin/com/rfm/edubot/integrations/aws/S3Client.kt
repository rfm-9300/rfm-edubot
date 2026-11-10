package com.rfm.edubot.integrations.aws

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.shared.SystemClock
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory

/**
 * The two S3 calls the inbound mailbox needs: reading the raw email SES's receipt rule stored, and
 * deleting it once it was kept. Virtual-hosted style on AWS (path style for a bucket with dots, or a
 * local stand-in at [AppConfig.MailboxConfig.s3Url]).
 */
class S3Client(
    private val httpClient: HttpClient,
    private val config: () -> AppConfig.MailboxConfig,
    private val clock: () -> Instant = SystemClock::now,
) {
    private val log = LoggerFactory.getLogger("S3Client")

    sealed interface Get {
        class Ok(val bytes: ByteArray) : Get
        data object NotFound : Get

        /** S3 couldn't be read now; [retryable] is false when trying again won't help (a refused key, an oversized email). */
        data class Failed(val status: Int?, val retryable: Boolean) : Get
    }

    suspend fun get(bucket: String, key: String): Get {
        val url = objectUrl(bucket, key)
        val response = try {
            httpClient.get(url) { sign("GET", url) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("S3 unreachable: error={}", e::class.simpleName)
            return Get.Failed(null, retryable = true)
        }
        return when {
            response.status == HttpStatusCode.NotFound -> Get.NotFound
            !response.status.isSuccess() -> {
                log.warn("S3 refused a read: status={}", response.status.value)
                Get.Failed(response.status.value, retryable = response.status.value >= 500 || response.status.value == 429)
            }
            (response.headers["Content-Length"]?.toLongOrNull() ?: 0) > MAX_OBJECT_BYTES -> Get.Failed(response.status.value, retryable = false)
            else -> Get.Ok(response.body<ByteArray>())
        }
    }

    /** True once the object is gone (S3 answers a missing one the same way). */
    suspend fun delete(bucket: String, key: String): Boolean {
        val url = objectUrl(bucket, key)
        val response: HttpResponse = try {
            httpClient.delete(url) { sign("DELETE", url) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("S3 unreachable for a delete: error={}", e::class.simpleName)
            return false
        }
        if (!response.status.isSuccess()) log.warn("S3 refused a delete: status={}", response.status.value)
        return response.status.isSuccess()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.sign(method: String, url: Url) {
        val cfg = config()
        val signature = AwsSigV4.sign(
            method = method,
            url = url,
            headers = emptyMap(),
            payloadSha256 = AwsSigV4.EMPTY_SHA256,
            credentials = AwsCredentials(cfg.accessKeyId, cfg.secretAccessKey),
            region = cfg.region,
            service = "s3",
            at = clock(),
        )
        headers { signature.forEach { (name, value) -> append(name, value) } }
    }

    private fun objectUrl(bucket: String, key: String): Url {
        val cfg = config()
        val path = AwsSigV4.uriEncode(key, keepSlash = true)
        return when {
            cfg.s3Url.isNotBlank() -> Url("${cfg.s3Url}/${AwsSigV4.uriEncode(bucket)}/$path")
            '.' in bucket -> Url("https://s3.${cfg.region}.amazonaws.com/${AwsSigV4.uriEncode(bucket)}/$path")
            else -> Url("https://$bucket.s3.${cfg.region}.amazonaws.com/$path")
        }
    }

    companion object {
        /** SES receives emails up to 40 MB. */
        const val MAX_OBJECT_BYTES = 45L * 1024 * 1024
    }
}
