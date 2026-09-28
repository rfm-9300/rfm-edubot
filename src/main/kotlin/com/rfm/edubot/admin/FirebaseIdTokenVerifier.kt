package com.rfm.edubot.admin

import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.RSAKeyProvider
import com.rfm.edubot.config.AppConfig
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Checks a Firebase Auth ID token the way Firebase documents for third-party JWT libraries: RS256
 * signed by Google's securetoken keys, audience = project id, issuer =
 * `https://securetoken.google.com/<project>`, not expired, `auth_time` in the past, a subject. On top
 * of that, the account must have signed in with Google and have a verified email on the allowlist.
 */
class FirebaseIdTokenVerifier(private val keys: RSAKeyProvider = googleSecureTokenKeys()) {

    sealed interface Result {
        data class Allowed(val email: String, val uid: String) : Result
        data class Rejected(val reason: String, val email: String? = null) : Result
    }

    fun verify(idToken: String, config: AppConfig.GoogleSignInConfig): Result {
        val token = try {
            JWT.require(Algorithm.RSA256(keys))
                .withIssuer("https://securetoken.google.com/${config.firebaseProjectId}")
                .withAudience(config.firebaseProjectId)
                .acceptLeeway(LEEWAY_SECONDS)
                .build()
                .verify(idToken)
        } catch (e: JWTVerificationException) {
            return Result.Rejected(INVALID_TOKEN)
        }
        val email = token.getClaim("email").asString()?.trim()?.lowercase()
        val authTime = token.getClaim("auth_time").asLong()
        val provider = token.getClaim("firebase").asMap()?.get("sign_in_provider") as? String
        return when {
            token.subject.isNullOrBlank() -> Result.Rejected(INVALID_TOKEN)
            authTime == null || authTime > Instant.now().epochSecond + LEEWAY_SECONDS -> Result.Rejected(INVALID_TOKEN)
            provider != GOOGLE_PROVIDER -> Result.Rejected(NOT_GOOGLE, email)
            email.isNullOrBlank() || token.getClaim("email_verified").asBoolean() != true -> Result.Rejected(EMAIL_NOT_VERIFIED, email)
            email !in config.allowedEmails -> Result.Rejected(NOT_ALLOWED, email)
            else -> Result.Allowed(email, token.subject)
        }
    }

    companion object {
        const val INVALID_TOKEN = "invalid_token"
        const val NOT_GOOGLE = "not_google_sign_in"
        const val EMAIL_NOT_VERIFIED = "email_not_verified"
        const val NOT_ALLOWED = "not_allowed"

        private const val GOOGLE_PROVIDER = "google.com"
        private const val LEEWAY_SECONDS = 60L
        private const val JWKS_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
        private val log = LoggerFactory.getLogger("FirebaseIdTokenVerifier")

        /** Google's signing keys, cached; an unknown key id or a failed fetch fails verification. */
        fun googleSecureTokenKeys(): RSAKeyProvider {
            val provider = JwkProviderBuilder(URI(JWKS_URL).toURL())
                .cached(10, 6, TimeUnit.HOURS)
                .rateLimited(10, 1, TimeUnit.MINUTES)
                .build()
            return object : RSAKeyProvider {
                override fun getPublicKeyById(keyId: String?): RSAPublicKey? = if (keyId == null) null else try {
                    provider.get(keyId).publicKey as? RSAPublicKey
                } catch (e: JwkException) {
                    log.warn("Could not resolve Firebase signing key {}: {}", keyId, e.message)
                    null
                }

                override fun getPrivateKey(): RSAPrivateKey? = null

                override fun getPrivateKeyId(): String? = null
            }
        }
    }
}
