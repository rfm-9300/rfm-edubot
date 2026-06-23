package com.rfm.edubot.oauth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs and verifies the OAuth `state` parameter for the Instagram onboarding flow
 * (docs/plan-instagram-oauth-onboarding.md §6.3). The callback is unauthenticated, so this signed,
 * short-lived, single-use token is the trust anchor: it carries the tenant slug, an HMAC signature,
 * an expiry, and a nonce that is consumed on first verify to block replay.
 */
class OAuthState(
    private val secret: String,
    private val ttlMillis: Long = 10 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class Payload(val slug: String, val nonce: String, val exp: Long, val origin: String = ORIGIN_BACKOFFICE)

    /** Slug + which UI surface initiated the flow, so the callback can redirect back to it. */
    data class Verified(val slug: String, val origin: String)

    private val json = Json { encodeDefaults = true }
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()
    private val random = SecureRandom()
    private val consumedNonces = ConcurrentHashMap<String, Long>()

    fun mint(slug: String, origin: String = ORIGIN_BACKOFFICE): String {
        val payload = Payload(slug, randomNonce(), now() + ttlMillis, origin)
        val body = encoder.encodeToString(json.encodeToString(Payload.serializer(), payload).toByteArray())
        return "$body.${sign(body)}"
    }

    /** Returns slug + origin if the state is well-formed, unexpired and not previously consumed; else null. */
    fun verify(state: String): Verified? {
        val dot = state.lastIndexOf('.')
        if (dot <= 0) return null
        val body = state.substring(0, dot)
        val sig = state.substring(dot + 1)
        if (!constantTimeEquals(sig, sign(body))) return null
        val payload = runCatching {
            json.decodeFromString(Payload.serializer(), String(decoder.decode(body)))
        }.getOrNull() ?: return null
        if (now() > payload.exp) return null
        purgeExpired()
        // single-use: putIfAbsent returns non-null if the nonce was already consumed (replay)
        if (consumedNonces.putIfAbsent(payload.nonce, payload.exp) != null) return null
        return Verified(payload.slug, payload.origin)
    }

    companion object {
        const val ORIGIN_BACKOFFICE = "backoffice"
        const val ORIGIN_DASHBOARD = "app"
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return encoder.encodeToString(mac.doFinal(body.toByteArray()))
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    private fun purgeExpired() {
        val cutoff = now()
        consumedNonces.entries.removeIf { it.value < cutoff }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
