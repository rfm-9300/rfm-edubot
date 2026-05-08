package com.rfm.edubot.webhook

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object WebhookVerifier {
    private const val ALGORITHM = "HmacSHA256"

    fun verifySignature(payload: String, signature: String, appSecret: String): Boolean {
        val expected = computeHmacSHA256(payload, appSecret)
        return expected.equals(signature, ignoreCase = true)
    }

    private fun computeHmacSHA256(data: String, key: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), ALGORITHM)
        mac.init(secretKey)
        val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
