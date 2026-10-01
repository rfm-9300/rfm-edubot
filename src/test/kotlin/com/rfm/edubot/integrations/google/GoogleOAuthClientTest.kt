package com.rfm.edubot.integrations.google

import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleOAuthClientTest {
    @Test
    fun `the consent url asks for offline access and a fresh consent`() {
        val google = FakeGoogle()
        val url = Url(google.client().authorizeUrl("signed-state"))
        assertEquals("accounts.google.com", url.host)
        assertEquals(google.clientId, url.parameters["client_id"])
        assertEquals(google.config.redirectUri, url.parameters["redirect_uri"])
        assertEquals("code", url.parameters["response_type"])
        assertEquals("openid email ${GoogleScopes.GMAIL_SEND}", url.parameters["scope"])
        assertEquals("offline", url.parameters["access_type"])
        assertEquals("consent", url.parameters["prompt"])
        assertEquals("true", url.parameters["include_granted_scopes"])
        assertEquals("signed-state", url.parameters["state"])
        assertNull(url.parameters["login_hint"])
        val reconnect = Url(google.client().authorizeUrl("signed-state", loginHint = "obras@example.pt"))
        assertEquals("obras@example.pt", reconnect.parameters["login_hint"])
    }

    @Test
    fun `the code exchange returns the tokens, the granted scopes and the verified email`(): Unit = runBlocking {
        val google = FakeGoogle()
        val grant = google.client().exchange("code-1")!!
        assertEquals("access-1", grant.accessToken)
        assertEquals("refresh-1", grant.refreshToken)
        assertEquals(3599, grant.expiresInSeconds)
        assertTrue(GoogleScopes.canSend(grant.scopes))
        assertEquals("obras@example.pt", grant.email)
        assertTrue(grant.emailVerified)
        val call = google.tokenCalls.single()
        assertEquals("code-1", call["code"])
        assertEquals("authorization_code", call["grant_type"])
        assertEquals(google.config.redirectUri, call["redirect_uri"])
        assertEquals("google-secret", call["client_secret"])
    }

    @Test
    fun `an id token issued to another client gives no email`(): Unit = runBlocking {
        val google = FakeGoogle()
        google.onToken = { HttpStatusCode.OK to google.grant(audience = "someone-else") }
        val grant = google.client().exchange("code-1")!!
        assertNull(grant.email)
        assertFalse(grant.emailVerified)
    }

    @Test
    fun `a refused code or an unreachable google gives nothing`(): Unit = runBlocking {
        val google = FakeGoogle()
        google.onToken = { HttpStatusCode.BadRequest to """{"error":"invalid_grant","error_description":"Bad Request"}""" }
        assertNull(google.client().exchange("used-code"))
        google.onToken = { error("connection reset") }
        assertNull(google.client().exchange("code-1"))
    }

    @Test
    fun `a refresh tells a revoked grant apart from a passing failure`(): Unit = runBlocking {
        val google = FakeGoogle()
        val ok = assertIs<GoogleOAuthClient.Refresh.Ok>(google.client().refresh("refresh-1"))
        assertEquals("access-refreshed", ok.accessToken)
        assertEquals("refresh_token", google.tokenCalls.single()["grant_type"])
        assertEquals("refresh-1", google.tokenCalls.single()["refresh_token"])

        google.onToken = { HttpStatusCode.BadRequest to """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""" }
        assertEquals(GoogleOAuthClient.Refresh.InvalidGrant, google.client().refresh("refresh-1"))

        google.onToken = { HttpStatusCode.ServiceUnavailable to "unavailable" }
        assertIs<GoogleOAuthClient.Refresh.Failed>(google.client().refresh("refresh-1"))
        google.onToken = { error("timeout") }
        assertEquals(GoogleOAuthClient.Refresh.Failed("network"), google.client().refresh("refresh-1"))
    }

    @Test
    fun `revoke posts the token and reports whether google took it`(): Unit = runBlocking {
        val google = FakeGoogle()
        assertTrue(google.client().revoke("refresh-1"))
        assertEquals(listOf("refresh-1"), google.revoked)
        google.revokeStatus = HttpStatusCode.BadRequest
        assertFalse(google.client().revoke("refresh-1"))
    }

    @Test
    fun `sending works with gmail send or any wider gmail scope`() {
        assertTrue(GoogleScopes.canSend(GoogleScopes.parse("openid ${GoogleScopes.GMAIL_SEND}")))
        assertTrue(GoogleScopes.canSend(listOf(GoogleScopes.GMAIL_MODIFY)))
        assertFalse(GoogleScopes.canSend(GoogleScopes.parse("openid https://www.googleapis.com/auth/userinfo.email")))
        assertEquals(emptyList(), GoogleScopes.parse(null))
    }
}
