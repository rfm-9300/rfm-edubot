package com.rfm.edubot.webhook

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebhookVerifierTest {
    private val appSecret = "test-secret-key-123"

    private fun computeValidSignature(payload: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val hash = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `verifySignature returns true for valid signature`() {
        val payload = """{"object":"whatsapp_business_account","entry":[]}"""
        val signature = computeValidSignature(payload, appSecret)

        assertTrue(WebhookVerifier.verifySignature(payload, signature, appSecret))
    }

    @Test
    fun `verifySignature returns false for invalid signature`() {
        val payload = """{"object":"whatsapp_business_account","entry":[]}"""

        assertFalse(WebhookVerifier.verifySignature(payload, "invalid-signature", appSecret))
    }

    @Test
    fun `verifySignature is case insensitive`() {
        val payload = """{"object":"whatsapp_business_account","entry":[]}"""
        val signature = computeValidSignature(payload, appSecret).uppercase()

        assertTrue(WebhookVerifier.verifySignature(payload, signature, appSecret))
    }

    @Test
    fun `verifySignature returns false when secret is different`() {
        val payload = """{"object":"whatsapp_business_account","entry":[]}"""
        val signature = computeValidSignature(payload, appSecret)

        assertFalse(WebhookVerifier.verifySignature(payload, signature, "wrong-secret"))
    }

    @Test
    fun `verifySignature returns false for empty payload`() {
        val signature = computeValidSignature("", appSecret)

        assertFalse(WebhookVerifier.verifySignature("some-data", signature, appSecret))
    }
}
