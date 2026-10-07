package com.rfm.edubot.timesheets

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The keys employees' phones sign punches with: EC P-256, created in the phone's secure hardware and usable
 * only after a strong biometric. The server only ever sees the public key.
 */
object DeviceKeys {
    /** DER header of a P-256 SubjectPublicKeyInfo, which a 65-byte uncompressed point completes. */
    private val P256_SPKI_PREFIX = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200")
    private val P256_ORDER = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    private const val MAX_KEY_BYTES = 256
    private const val MAX_SIGNATURE_BYTES = 128
    private val random = SecureRandom()

    /** A SubjectPublicKeyInfo (what Android exports) or a raw X9.63 point (04 ‖ X ‖ Y, what iOS exports), base64. */
    fun parse(base64: String): ECPublicKey? {
        val bytes = decode(base64)?.takeIf { it.size <= MAX_KEY_BYTES } ?: return null
        val spki = if (bytes.size == 65 && bytes[0] == 0x04.toByte()) P256_SPKI_PREFIX + bytes else bytes
        val key = runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as? ECPublicKey }.getOrNull()
        return key?.takeIf { it.params.order == P256_ORDER && it.isOnCurve() }
    }

    /** The JDK takes any point; y² = x³ + ax + b (mod p) keeps out bytes that only look like a key. */
    private fun ECPublicKey.isOnCurve(): Boolean {
        val p = (params.curve.field as? ECFieldFp)?.p ?: return false
        val x = w.affineX
        val y = w.affineY
        if (x.signum() < 0 || y.signum() < 0 || x >= p || y >= p) return false
        val right = x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        return y.pow(2).mod(p) == right
    }

    /** SHA-256 of the key's SubjectPublicKeyInfo, base64url without padding: how a phone names its key. */
    fun keyId(key: PublicKey): String = urlEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(key.encoded))

    /** The canonical form stored for a key, whichever form the phone sent. */
    fun encode(key: PublicKey): String = Base64.getEncoder().encodeToString(key.encoded)

    /** The exact text a phone signs: the server's nonce and what it is for (`IN`, `OUT`, … or `ENROLL`). */
    fun message(nonce: String, action: String): String = "$nonce:$action"

    /** ECDSA P-256 with SHA-256; the signature as DER (what Android and iOS produce) or as raw r ‖ s. */
    fun verify(key: PublicKey, message: String, signatureBase64: String): Boolean {
        val raw = decode(signatureBase64)?.takeIf { it.size in 8..MAX_SIGNATURE_BYTES } ?: return false
        val der = if (raw.size == 64) rawToDer(raw) else raw
        return runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(message.toByteArray(Charsets.UTF_8))
                verify(der)
            }
        }.getOrDefault(false)
    }

    /** 32 random bytes, base64url: a challenge only the server can have issued. */
    fun nonce(): String = ByteArray(32).also(random::nextBytes).let(urlEncoder::encodeToString)

    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    private fun decode(value: String): ByteArray? {
        val text = value.trim()
        if (text.isEmpty()) return null
        return runCatching { Base64.getDecoder().decode(text) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()
    }

    private fun rawToDer(raw: ByteArray): ByteArray {
        fun integer(part: ByteArray): ByteArray {
            val trimmed = part.dropWhile { it == 0.toByte() }.toByteArray().takeIf { it.isNotEmpty() } ?: byteArrayOf(0)
            val value = if (trimmed[0] < 0) byteArrayOf(0) + trimmed else trimmed
            return byteArrayOf(0x02, value.size.toByte()) + value
        }
        val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return ByteArrayOutputStream().apply { write(0x30); write(body.size); write(body) }.toByteArray()
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
