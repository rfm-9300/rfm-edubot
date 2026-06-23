package com.rfm.edubot.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OAuthStateTest {
    private val secret = "test-state-secret"

    @Test
    fun `mint then verify round-trips the slug`() {
        val state = OAuthState(secret)
        val token = state.mint("acme-bot")
        assertEquals("acme-bot", state.verify(token)?.slug)
    }

    @Test
    fun `mint defaults to backoffice origin`() {
        val state = OAuthState(secret)
        assertEquals(OAuthState.ORIGIN_BACKOFFICE, state.verify(state.mint("acme-bot"))?.origin)
    }

    @Test
    fun `mint round-trips a dashboard origin`() {
        val state = OAuthState(secret)
        val token = state.mint("acme-bot", origin = OAuthState.ORIGIN_DASHBOARD)
        val verified = state.verify(token)
        assertEquals("acme-bot", verified?.slug)
        assertEquals(OAuthState.ORIGIN_DASHBOARD, verified?.origin)
    }

    @Test
    fun `tampered payload is rejected`() {
        val state = OAuthState(secret)
        val token = state.mint("acme-bot")
        val tampered = "x" + token.substring(1)
        assertNull(state.verify(tampered))
    }

    @Test
    fun `signature from a different secret is rejected`() {
        val minted = OAuthState("secret-a").mint("acme-bot")
        assertNull(OAuthState("secret-b").verify(minted))
    }

    @Test
    fun `expired state is rejected`() {
        var clock = 1_000_000L
        val state = OAuthState(secret, ttlMillis = 1000, now = { clock })
        val token = state.mint("acme-bot")
        clock += 2000 // advance past TTL
        assertNull(state.verify(token))
    }

    @Test
    fun `replayed state is rejected on second verify`() {
        val state = OAuthState(secret)
        val token = state.mint("acme-bot")
        assertEquals("acme-bot", state.verify(token)?.slug)
        assertNull(state.verify(token)) // nonce already consumed
    }

    @Test
    fun `malformed state is rejected`() {
        val state = OAuthState(secret)
        assertNull(state.verify("not-a-valid-state"))
        assertNull(state.verify(""))
        assertNull(state.verify("only.onedot"))
    }
}
