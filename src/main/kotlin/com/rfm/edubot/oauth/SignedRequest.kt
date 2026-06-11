package com.rfm.edubot.oauth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Parses + verifies Meta's `signed_request`, the payload sent to the Instagram **Deauthorize** and
 * **Data Deletion Request** callbacks (App Review requirement). Format:
 *
 *     signed_request = base64url(HMAC_SHA256(encodedPayload, appSecret)) + "." + base64url(payload)
 *
 * The HMAC is taken over the *encoded* payload string and keyed with the **Instagram app secret**
 * (`app.instagram.appSecret`) — a different value from the WhatsApp/Facebook app secret. Distinct
 * from [com.rfm.edubot.webhook.WebhookVerifier], which verifies the hex `X-Hub-Signature-256` over
 * the raw webhook body; this is the base64url signed_request scheme Meta uses only for these
 * account-removal callbacks.
 */
object SignedRequest {
    @Serializable
    data class Payload(
        val user_id: String? = null,
        val algorithm: String? = null,
        val issued_at: Long? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the verified payload, or null if malformed, wrong algorithm, or signature mismatch. */
    fun parse(signedRequest: String, appSecret: String): Payload? {
        if (appSecret.isBlank()) return null
        val parts = signedRequest.split('.', limit = 2)
        if (parts.size != 2) return null
        val (encodedSig, encodedPayload) = parts

        val expectedSig = hmacSha256Base64Url(encodedPayload, appSecret)
        if (!constantTimeEquals(expectedSig, encodedSig)) return null

        val payloadJson = runCatching { String(decodeBase64Url(encodedPayload), Charsets.UTF_8) }.getOrNull()
            ?: return null
        val payload = runCatching { json.decodeFromString(Payload.serializer(), payloadJson) }.getOrNull()
            ?: return null
        if (payload.algorithm != null && !payload.algorithm.equals("HMAC-SHA256", ignoreCase = true)) return null
        return payload
    }

    private fun decodeBase64Url(s: String): ByteArray = Base64.getUrlDecoder().decode(pad(s))

    /** Meta omits base64url padding; restore it so the JDK decoder accepts the string. */
    private fun pad(s: String): String = when (s.length % 4) {
        0 -> s
        else -> s + "=".repeat(4 - s.length % 4)
    }

    private fun hmacSha256Base64Url(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
}
