package com.rfm.edubot.integrations

import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenCipherTest {
    private fun key(seed: Int): String = Base64.getEncoder().encodeToString(Random(seed).nextBytes(32))

    private val cipher = TokenCipher.fromConfig(key(1))!!

    @Test
    fun `a sealed token opens back to the same text and never shows it`() {
        val token = "ya29.a0AfH6SMB-refresh/token_with:colons"
        val sealed = cipher.seal(token)
        assertFalse(sealed.contains("ya29"))
        assertTrue(sealed.startsWith("v1:${cipher.keyId}:"))
        assertEquals(token, cipher.open(sealed))
        assertEquals("olá", cipher.open(cipher.seal("olá")))
    }

    @Test
    fun `sealing the same token twice uses a fresh iv`() {
        assertNotEquals(cipher.seal("same"), cipher.seal("same"))
    }

    @Test
    fun `an altered value doesn't open`() {
        val sealed = cipher.seal("secret")
        val body = Base64.getDecoder().decode(sealed.substringAfterLast(':'))
        body[body.size - 1] = (body[body.size - 1].toInt() xor 1).toByte()
        assertNull(cipher.open(sealed.substringBeforeLast(':') + ":" + Base64.getEncoder().encodeToString(body)))
        assertNull(cipher.open("v2:${cipher.keyId}:" + sealed.substringAfterLast(':')))
        assertNull(cipher.open("not sealed"))
        assertNull(cipher.open("v1:${cipher.keyId}:AAAA"))
    }

    @Test
    fun `another key can't open it`() {
        val other = TokenCipher.fromConfig(key(2))!!
        assertNull(other.open(cipher.seal("secret")))
    }

    @Test
    fun `after a rotation the old key still opens older values and new ones use the new key`() {
        val sealedBefore = cipher.seal("before")
        val rotated = TokenCipher.fromConfig("${key(2)}, ${key(1)}")!!
        assertEquals("before", rotated.open(sealedBefore))
        assertTrue(rotated.isStale(sealedBefore))
        val sealedAfter = rotated.seal("after")
        assertFalse(rotated.isStale(sealedAfter))
        assertNull(cipher.open(sealedAfter), "the old key alone can't open what the new key sealed")
    }

    @Test
    fun `a missing or malformed key turns encryption off`() {
        assertNull(TokenCipher.fromConfig(""))
        assertNull(TokenCipher.fromConfig(" , "))
        assertNull(TokenCipher.fromConfig("not base64!"))
        assertNull(TokenCipher.fromConfig(Base64.getEncoder().encodeToString(ByteArray(16))), "AES-256 needs 32 bytes")
        assertNull(TokenCipher.fromConfig("${key(1)},short"), "one bad key makes the whole setting invalid")
        assertNotNull(TokenCipher.fromConfig(" ${key(3)} "))
    }
}
