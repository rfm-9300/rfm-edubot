package com.rfm.edubot.timesheets

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Keys and signatures as Android's Keystore and iOS's Secure Enclave produce them. */
class DeviceKeysTest {
    private fun keyPair(curve: String = "secp256r1"): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    private fun sign(pair: KeyPair, message: String, algorithm: String = "SHA256withECDSA"): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance(algorithm).run {
                initSign(pair.private)
                update(message.toByteArray())
                sign()
            },
        )

    private val b64 = Base64.getEncoder()

    @Test
    fun `an Android key and the same key as iOS exports it are one key`() {
        val pair = keyPair()
        val spki = pair.public.encoded
        val raw = spki.copyOfRange(spki.size - 65, spki.size)
        assertEquals(0x04.toByte(), raw[0])
        val fromAndroid = assertNotNull(DeviceKeys.parse(b64.encodeToString(spki)))
        val fromIos = assertNotNull(DeviceKeys.parse(b64.encodeToString(raw)))
        assertEquals(DeviceKeys.keyId(fromAndroid), DeviceKeys.keyId(fromIos))
        assertEquals(DeviceKeys.encode(fromAndroid), DeviceKeys.encode(fromIos))
        assertEquals(43, DeviceKeys.keyId(fromAndroid).length, "SHA-256 in base64url without padding")
    }

    @Test
    fun `a signature counts only for its own message and key`() {
        val pair = keyPair()
        val key = DeviceKeys.parse(b64.encodeToString(pair.public.encoded))!!
        val nonce = DeviceKeys.nonce()
        val message = DeviceKeys.message(nonce, "IN")
        assertEquals("$nonce:IN", message)
        val der = sign(pair, message)
        assertTrue(DeviceKeys.verify(key, message, der))
        assertTrue(DeviceKeys.verify(key, message, sign(pair, message, "SHA256withECDSAinP1363Format")), "raw r‖s is accepted too")
        assertFalse(DeviceKeys.verify(key, DeviceKeys.message(nonce, "OUT"), der))
        val other = DeviceKeys.parse(b64.encodeToString(keyPair().public.encoded))!!
        assertFalse(DeviceKeys.verify(other, message, der))
        assertFalse(DeviceKeys.verify(key, message, "not base64 at all!"))
        assertFalse(DeviceKeys.verify(key, message, ""))
    }

    @Test
    fun `only P-256 public keys are accepted`() {
        assertNull(DeviceKeys.parse(b64.encodeToString(keyPair("secp384r1").public.encoded)))
        assertNull(DeviceKeys.parse(b64.encodeToString(ByteArray(65) { 4 })))
        assertNull(DeviceKeys.parse(""))
        assertNull(DeviceKeys.parse(b64.encodeToString(ByteArray(4096))))
    }

    @Test
    fun `nonces don't repeat`() {
        val nonces = List(200) { DeviceKeys.nonce() }
        assertEquals(200, nonces.toSet().size)
        assertTrue(nonces.all { it.length == 43 })
    }
}
