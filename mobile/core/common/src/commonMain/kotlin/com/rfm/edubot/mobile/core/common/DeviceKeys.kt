package com.rfm.edubot.mobile.core.common

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * How the phone names its time clock key and what it signs, matching the backend's `DeviceKeys`: a key
 * is known by the SHA-256 of its SubjectPublicKeyInfo, and a signature covers `nonce:ACTION`.
 */
@OptIn(ExperimentalEncodingApi::class)
object DeviceKeys {
    /** DER header of a P-256 SubjectPublicKeyInfo, which a 65-byte uncompressed point completes. */
    private val P256_SPKI_PREFIX = "3059301306072a8648ce3d020106082a8648ce3d030107034200"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** One key per employee per phone, so two people sharing a phone don't share a key. */
    fun alias(employeeId: String): String = "timeclock.$employeeId"

    fun message(nonce: String, action: String): String = "$nonce:$action"

    /** [publicKeyBase64] as a SubjectPublicKeyInfo, base64: what the phone sends when it enrolls. */
    fun subjectPublicKeyInfo(publicKeyBase64: String): String? =
        decode(publicKeyBase64)?.let { Base64.Default.encode(spki(it)) }

    /** SHA-256 of the SubjectPublicKeyInfo, base64url without padding; null when the key isn't base64. */
    fun keyId(publicKeyBase64: String): String? =
        decode(publicKeyBase64)?.let { Base64.UrlSafe.encode(Sha256.digest(spki(it))).trimEnd('=') }

    private fun spki(key: ByteArray): ByteArray =
        if (key.size == 65 && key[0] == 0x04.toByte()) P256_SPKI_PREFIX + key else key

    private fun decode(value: String): ByteArray? =
        value.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Base64.Default.decode(it) }.getOrNull() }
}

/** SHA-256 (FIPS 180-4), small enough to keep the key id identical on every platform. */
internal object Sha256 {
    private val K: IntArray = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ).map { it.toInt() }.toIntArray()

    private fun Int.rotr(n: Int): Int = (this ushr n) or (this shl (32 - n))

    fun digest(input: ByteArray): ByteArray {
        val h = longArrayOf(0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19)
            .map { it.toInt() }.toIntArray()
        val paddedLength = ((input.size + 9 + 63) / 64) * 64
        val message = input.copyOf(paddedLength)
        message[input.size] = 0x80.toByte()
        val bits = input.size.toLong() * 8
        for (i in 0 until 8) message[paddedLength - 1 - i] = (bits ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (chunk in 0 until paddedLength / 64) {
            for (t in 0 until 16) {
                val o = chunk * 64 + t * 4
                w[t] = ((message[o].toInt() and 0xff) shl 24) or ((message[o + 1].toInt() and 0xff) shl 16) or
                    ((message[o + 2].toInt() and 0xff) shl 8) or (message[o + 3].toInt() and 0xff)
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotr(7) xor w[t - 15].rotr(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotr(17) xor w[t - 2].rotr(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]
            for (t in 0 until 64) {
                val t1 = hh + (e.rotr(6) xor e.rotr(11) xor e.rotr(25)) + ((e and f) xor (e.inv() and g)) + K[t] + w[t]
                val t2 = (a.rotr(2) xor a.rotr(13) xor a.rotr(22)) + ((a and b) xor (a and c) xor (b and c))
                hh = g
                g = f
                f = e
                e = d + t1
                d = c
                c = b
                b = a
                a = t1 + t2
            }
            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh
        }
        return ByteArray(32) { i -> (h[i / 4] ushr (24 - 8 * (i % 4))).toByte() }
    }
}
