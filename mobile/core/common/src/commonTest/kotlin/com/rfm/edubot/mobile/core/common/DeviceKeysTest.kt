package com.rfm.edubot.mobile.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceKeysTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun `sha256 matches the standard vectors`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(Sha256.digest(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(Sha256.digest("abc".encodeToByteArray())))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
        assertEquals("41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3", hex(Sha256.digest(ByteArray(1000) { 'a'.code.toByte() })))
    }

    @Test
    fun `an iOS raw point and its SubjectPublicKeyInfo are the same key`() {
        // An iOS export (04 ‖ X ‖ Y) and the Android-style SubjectPublicKeyInfo of the same point; the
        // id is what the backend computes (SHA-256 of the SubjectPublicKeyInfo, base64url).
        val raw = "BAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+P0A="
        val spki = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4/QA=="
        assertEquals("FViyFte_0z13dcRV1gOA06LPYfH9mA6aNIOFWQYr1d4", DeviceKeys.keyId(raw))
        assertEquals(DeviceKeys.keyId(raw), DeviceKeys.keyId(spki))
        assertEquals(spki, DeviceKeys.subjectPublicKeyInfo(raw))
        assertEquals(spki, DeviceKeys.subjectPublicKeyInfo(spki))
        assertNull(DeviceKeys.keyId("not base64!"))
        assertNull(DeviceKeys.keyId(""))
    }

    @Test
    fun `a signature covers the nonce and what it is for`() {
        assertEquals("n0nce:IN", DeviceKeys.message("n0nce", "IN"))
        assertEquals("timeclock.e1", DeviceKeys.alias("e1"))
    }
}
