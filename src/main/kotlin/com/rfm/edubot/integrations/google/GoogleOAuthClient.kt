package com.rfm.edubot.integrations.google

import com.rfm.edubot.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Google's OAuth 2.0 endpoints for connecting a company's Gmail account: the consent URL, the code
 * exchange, access-token refresh and revocation. Logs only statuses and Google's error codes, never tokens.
 */
class GoogleOAuthClient(
    private val configProvider: () -> AppConfig.GoogleConfig,
    private val httpClient: HttpClient,
) {
    private val config: AppConfig.GoogleConfig get() = configProvider()
    private val json = Json { ignoreUnknownKeys = true }
    private val log = LoggerFactory.getLogger("GoogleOAuthClient")

    /** What Google granted. [email] comes from the ID token and is null unless it was issued to this client. */
    data class Grant(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Long,
        val scopes: List<String>,
        val email: String?,
        val emailVerified: Boolean,
    )

    sealed interface Refresh {
        data class Ok(val accessToken: String, val expiresInSeconds: Long, val scopes: List<String>) : Refresh

        /** Revoked, expired (Testing-mode grants last 7 days) or dropped by a password change: a person must reconnect. */
        data object InvalidGrant : Refresh

        data class Failed(val reason: String) : Refresh
    }

    @Serializable
    private data class TokenResponse(
        val access_token: String? = null,
        val refresh_token: String? = null,
        val expires_in: Long? = null,
        val scope: String? = null,
        val id_token: String? = null,
        val error: String? = null,
    )

    /**
     * `access_type=offline` + `prompt=consent` make Google return a refresh token every time, including on
     * a reconnect; `include_granted_scopes` keeps scopes the account granted this app before.
     */
    fun authorizeUrl(state: String, scopes: List<String> = GoogleScopes.send): String =
        AUTHORIZE_URL +
            "?client_id=${config.clientId.encodeURLParameter()}" +
            "&redirect_uri=${config.redirectUri.encodeURLParameter()}" +
            "&response_type=code" +
            "&scope=${scopes.joinToString(" ").encodeURLParameter()}" +
            "&access_type=offline" +
            "&prompt=consent" +
            "&include_granted_scopes=true" +
            "&state=${state.encodeURLParameter()}"

    /** Null when Google refuses the code or can't be reached. */
    suspend fun exchange(code: String): Grant? {
        val response = token(
            "code" to code,
            "redirect_uri" to config.redirectUri,
            "grant_type" to "authorization_code",
        ) ?: return null
        val (ok, body) = response
        if (!ok) {
            log.warn("Google code exchange refused: error={}", body.error)
            return null
        }
        val accessToken = body.access_token ?: run {
            log.warn("Google code exchange returned no access token")
            return null
        }
        val claims = body.id_token?.let { idClaims(it) }
        return Grant(
            accessToken = accessToken,
            refreshToken = body.refresh_token,
            expiresInSeconds = body.expires_in ?: DEFAULT_EXPIRES_IN,
            scopes = GoogleScopes.parse(body.scope),
            email = claims?.email,
            emailVerified = claims?.emailVerified == true,
        )
    }

    suspend fun refresh(refreshToken: String): Refresh {
        val (ok, body) = token("refresh_token" to refreshToken, "grant_type" to "refresh_token")
            ?: return Refresh.Failed("network")
        if (!ok) {
            log.warn("Google token refresh refused: error={}", body.error)
            return if (body.error == "invalid_grant") Refresh.InvalidGrant else Refresh.Failed(body.error ?: "refresh_failed")
        }
        val accessToken = body.access_token ?: return Refresh.Failed("no_access_token")
        return Refresh.Ok(accessToken, body.expires_in ?: DEFAULT_EXPIRES_IN, GoogleScopes.parse(body.scope))
    }

    /** Revoking either token ends the whole grant. False when Google couldn't be reached or the token was already gone. */
    suspend fun revoke(token: String): Boolean = try {
        val response = httpClient.submitForm(url = REVOKE_URL, formParameters = Parameters.build { append("token", token) })
        if (!response.status.isSuccess()) log.info("Google token revoke answered status={}", response.status.value)
        response.status.isSuccess()
    } catch (e: Exception) {
        log.warn("Google token revoke failed: {}", e.message)
        false
    }

    private suspend fun token(vararg params: Pair<String, String>): Pair<Boolean, TokenResponse>? = try {
        val response = httpClient.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build {
                append("client_id", config.clientId)
                append("client_secret", config.clientSecret)
                params.forEach { (name, value) -> append(name, value) }
            },
        )
        val body = runCatching { json.decodeFromString(TokenResponse.serializer(), response.bodyAsText()) }.getOrElse { TokenResponse() }
        response.status.isSuccess() to body
    } catch (e: Exception) {
        log.warn("Google token endpoint unreachable: {}", e.message)
        null
    }

    private data class IdClaims(val email: String?, val emailVerified: Boolean)

    /**
     * The ID token came straight from Google's token endpoint over TLS, so its claims are read without
     * checking the signature (OpenID Connect Core §3.1.3.7); the audience and issuer must still match.
     */
    private fun idClaims(idToken: String): IdClaims? {
        val payload = idToken.split('.').getOrNull(1) ?: return null
        val claims = runCatching { json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload))).jsonObject }.getOrNull()
            ?: return null
        val aud = (claims["aud"] as? JsonPrimitive)?.contentOrNull
        val iss = (claims["iss"] as? JsonPrimitive)?.contentOrNull
        if (aud != config.clientId || iss !in ISSUERS) {
            log.warn("Google ID token not issued to this client (iss={})", iss)
            return null
        }
        val verified = (claims["email_verified"] as? JsonPrimitive)?.booleanOrNull == true
        return IdClaims(email = (claims["email"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase(), emailVerified = verified)
    }

    companion object {
        const val AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        private val ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")
        private const val DEFAULT_EXPIRES_IN = 3600L
    }
}
