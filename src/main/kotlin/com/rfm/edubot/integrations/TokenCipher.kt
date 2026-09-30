package com.rfm.edubot.integrations

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts integration tokens at rest with AES-256-GCM. A sealed value reads `v1:<keyId>:<base64(iv + ciphertext)>`,
 * where the key id is a short fingerprint of the key that sealed it. `INTEGRATIONS_ENCRYPTION_KEY` holds
 * base64 32-byte keys separated by commas: the first seals, the others only open values sealed before a rotation.
 */
class TokenCipher private constructor(private val keys: List<Key>) {
    private class Key(val id: String, val spec: SecretKeySpec)

    private val random = SecureRandom()

    val keyId: String get() = keys.first().id

    fun seal(plain: String): String {
        val key = keys.first()
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key.spec, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(header(key.id).toByteArray())
        val body = iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "${header(key.id)}:${Base64.getEncoder().encodeToString(body)}"
    }

    /** Null when [sealed] is malformed, was altered, or was sealed with a key that is no longer configured. */
    fun open(sealed: String): String? {
        val parts = sealed.split(':')
        if (parts.size != 3 || parts[0] != VERSION) return null
        val key = keys.firstOrNull { it.id == parts[1] } ?: return null
        val body = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return null
        if (body.size <= IV_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key.spec, GCMParameterSpec(TAG_BITS, body, 0, IV_BYTES))
            cipher.updateAAD(header(key.id).toByteArray())
            String(cipher.doFinal(body, IV_BYTES, body.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    /** Whether [sealed] was sealed with an older key and should be sealed again with the current one. */
    fun isStale(sealed: String): Boolean = sealed.split(':').getOrNull(1) != keyId

    private fun header(keyId: String) = "$VERSION:$keyId"

    companion object {
        private const val VERSION = "v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BYTES = 32

        /** Null when [raw] is blank or any key in it isn't base64 for exactly 32 bytes. */
        fun fromConfig(raw: String): TokenCipher? {
            val encoded = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (encoded.isEmpty()) return null
            val keys = encoded.map { value ->
                val bytes = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
                if (bytes == null || bytes.size != KEY_BYTES) return null
                Key(fingerprint(bytes), SecretKeySpec(bytes, "AES"))
            }
            return TokenCipher(keys.distinctBy { it.id })
        }

        private fun fingerprint(key: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(key).take(4).joinToString("") { "%02x".format(it) }
    }
}
