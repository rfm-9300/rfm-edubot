package com.rfm.edubot.admin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.RSAKeyProvider
import com.rfm.edubot.config.AppConfig
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals

class FirebaseIdTokenVerifierTest {
    private val project = "thebotslab"
    private val googleKey = rsaKeyPair()
    private val verifier = FirebaseIdTokenVerifier(keysFor(mapOf("k1" to googleKey)))
    private val config = AppConfig.GoogleSignInConfig(
        firebaseProjectId = project,
        webApiKey = "key",
        allowedEmails = setOf("owner@gmail.com"),
    )

    @Test
    fun `an allowlisted Google account is let in`() {
        assertEquals(FirebaseIdTokenVerifier.Result.Allowed("owner@gmail.com", "uid-1"), verifier.verify(token(), config))
    }

    @Test
    fun `the email match ignores case`() {
        assertEquals("owner@gmail.com", (verifier.verify(token(email = "Owner@Gmail.com"), config) as FirebaseIdTokenVerifier.Result.Allowed).email)
    }

    @Test
    fun `other accounts are refused with a reason`() {
        assertEquals(FirebaseIdTokenVerifier.NOT_ALLOWED, reason(token(email = "someone@gmail.com")))
        assertEquals(FirebaseIdTokenVerifier.EMAIL_NOT_VERIFIED, reason(token(emailVerified = false)))
        assertEquals(FirebaseIdTokenVerifier.NOT_GOOGLE, reason(token(provider = "password")))
    }

    @Test
    fun `tokens for another project, issuer, key or time are invalid`() {
        assertEquals(FirebaseIdTokenVerifier.INVALID_TOKEN, reason(token(audience = "other-project")))
        assertEquals(FirebaseIdTokenVerifier.INVALID_TOKEN, reason(token(issuer = "https://securetoken.google.com/other-project")))
        assertEquals(FirebaseIdTokenVerifier.INVALID_TOKEN, reason(token(expiresAt = Instant.now().minusSeconds(3600))))
        assertEquals(FirebaseIdTokenVerifier.INVALID_TOKEN, reason(token(keyId = "unknown")))
        assertEquals(FirebaseIdTokenVerifier.INVALID_TOKEN, reason(token(signingKey = rsaKeyPair())))
        assertEquals(FirebaseIdTokenVerifier.INVALID_TOKEN, reason(token(authTime = Instant.now().plusSeconds(3600))))
        assertEquals(FirebaseIdTokenVerifier.INVALID_TOKEN, reason("not-a-jwt"))
    }

    @Test
    fun `ADMIN_EMAILS accepts commas, semicolons and spaces`() {
        assertEquals(setOf("a@x.com", "b@y.com"), AppConfig.parseEmails(" A@x.com, b@y.com;; not-an-email "))
    }

    private fun reason(idToken: String) = (verifier.verify(idToken, config) as FirebaseIdTokenVerifier.Result.Rejected).reason

    private fun token(
        email: String = "owner@gmail.com",
        emailVerified: Boolean = true,
        provider: String = "google.com",
        audience: String = project,
        issuer: String = "https://securetoken.google.com/$project",
        expiresAt: Instant = Instant.now().plusSeconds(3600),
        authTime: Instant = Instant.now().minusSeconds(5),
        keyId: String = "k1",
        signingKey: KeyPair = googleKey,
    ): String = JWT.create()
        .withKeyId(keyId)
        .withIssuer(issuer)
        .withAudience(audience)
        .withSubject("uid-1")
        .withIssuedAt(Date.from(Instant.now().minusSeconds(5)))
        .withExpiresAt(Date.from(expiresAt))
        .withClaim("auth_time", authTime.epochSecond)
        .withClaim("email", email)
        .withClaim("email_verified", emailVerified)
        .withClaim("firebase", mapOf("sign_in_provider" to provider))
        .sign(Algorithm.RSA256(signingKey.public as RSAPublicKey, signingKey.private as RSAPrivateKey))

    private fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun keysFor(byId: Map<String, KeyPair>) = object : RSAKeyProvider {
        override fun getPublicKeyById(keyId: String?): RSAPublicKey? = byId[keyId]?.public as? RSAPublicKey
        override fun getPrivateKey(): RSAPrivateKey? = null
        override fun getPrivateKeyId(): String? = null
    }
}
