package com.rfm.edubot.integrations.aws

import io.ktor.http.Url
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** An IAM user's access key; the secret never leaves the server and is never logged. */
data class AwsCredentials(val accessKeyId: String, val secretAccessKey: String) {
    override fun toString(): String = "AwsCredentials(accessKeyId=$accessKeyId)"
}

/**
 * AWS Signature Version 4 for the few calls the app makes (SES's SendEmail, S3's GetObject and
 * DeleteObject), so it needs no AWS SDK. Checked against the examples in AWS's documentation.
 */
object AwsSigV4 {
    /** The SHA-256 of an empty body, which GET and DELETE sign. */
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private const val ALGORITHM = "AWS4-HMAC-SHA256"

    /**
     * The headers that sign a request to [url]: `Authorization` and `X-Amz-Date`, plus
     * `x-amz-content-sha256` for S3, which wants the body's hash sent too. [headers] are the others the
     * request sends (its `Content-Type`, an S3 `Range`…); the host comes from [url]. All are signed.
     */
    fun sign(
        method: String,
        url: Url,
        headers: Map<String, String>,
        payloadSha256: String,
        credentials: AwsCredentials,
        region: String,
        service: String,
        at: Instant,
    ): Map<String, String> {
        val amzDate = amzDate(at)
        val date = amzDate.substring(0, 8)
        val added = buildMap {
            put("X-Amz-Date", amzDate)
            if (service == S3) put("x-amz-content-sha256", payloadSha256)
        }
        val signed = (headers + added + ("host" to host(url)))
            .entries.groupBy({ it.key.lowercase() }, { it.value.trim().replace(SPACES, " ") })
            .mapValues { (_, values) -> values.joinToString(",") }
            .toSortedMap()
        val signedHeaders = signed.keys.joinToString(";")
        val canonical = listOf(
            method.uppercase(),
            canonicalUri(url, service),
            canonicalQuery(url),
            signed.entries.joinToString("") { (name, value) -> "$name:$value\n" },
            signedHeaders,
            payloadSha256,
        ).joinToString("\n")
        val scope = "$date/$region/$service/aws4_request"
        val stringToSign = "$ALGORITHM\n$amzDate\n$scope\n${sha256Hex(canonical.toByteArray(Charsets.UTF_8))}"
        var key = hmac("AWS4${credentials.secretAccessKey}".toByteArray(Charsets.UTF_8), date)
        key = hmac(key, region)
        key = hmac(key, service)
        key = hmac(key, "aws4_request")
        val signature = hex(hmac(key, stringToSign))
        return added + ("Authorization" to "$ALGORITHM Credential=${credentials.accessKeyId}/$scope, SignedHeaders=$signedHeaders, Signature=$signature")
    }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** `20150830T123600Z`. */
    fun amzDate(at: Instant): String {
        val t = at.toLocalDateTime(TimeZone.UTC)
        return "%04d%02d%02dT%02d%02d%02dZ".format(t.year, t.monthNumber, t.dayOfMonth, t.hour, t.minute, t.second)
    }

    /** RFC 3986 percent-encoding, as SigV4 wants it: only unreserved characters stay as they are. */
    fun uriEncode(text: String, keepSlash: Boolean = false): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and 0xff).toChar()
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~' -> append(c)
                c == '/' && keepSlash -> append(c)
                else -> append('%').append(HEX[(byte.toInt() shr 4) and 0x0f]).append(HEX[byte.toInt() and 0x0f])
            }
        }
    }

    /** What the HTTP client sends as `Host`: the port only when it isn't the scheme's own. */
    private fun host(url: Url): String =
        if (url.specifiedPort == 0 || url.specifiedPort == url.protocol.defaultPort) url.host else "${url.host}:${url.specifiedPort}"

    /** The path as sent, each segment encoded once more for every service but S3. */
    private fun canonicalUri(url: Url, service: String): String {
        val path = url.encodedPath.ifEmpty { "/" }
        if (service == S3) return path
        return path.split('/').joinToString("/") { segment -> uriEncode(decode(segment)).let { uriEncode(it) } }
    }

    private fun canonicalQuery(url: Url): String {
        val query = url.encodedQuery
        if (query.isEmpty()) return ""
        return query.split('&').filter { it.isNotEmpty() }.map { pair ->
            val name = pair.substringBefore('=')
            val value = if ('=' in pair) pair.substringAfter('=') else ""
            uriEncode(decode(name)) to uriEncode(decode(value))
        }.sortedWith(compareBy({ it.first }, { it.second })).joinToString("&") { (name, value) -> "$name=$value" }
    }

    private fun decode(text: String): String = java.net.URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8)

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray(Charsets.UTF_8))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private const val S3 = "s3"
    private const val HEX = "0123456789ABCDEF"
    private val SPACES = Regex("\\s+")
}
