package com.rfm.edubot.integrations.google

import com.rfm.edubot.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import java.util.Base64

/** Google's token and revoke endpoints for tests: answers come from [onToken], and every call is recorded. */
internal class FakeGoogle(val clientId: String = "client-1.apps.googleusercontent.com") {
    val config = AppConfig.GoogleConfig(clientId = clientId, clientSecret = "google-secret", redirectUri = "https://bots.example/integrations/google/callback")

    /** The form fields of each call to the token endpoint. */
    val tokenCalls = mutableListOf<Map<String, String>>()
    val revoked = mutableListOf<String>()

    var onToken: (Map<String, String>) -> Pair<HttpStatusCode, String> = { form ->
        when (form["grant_type"]) {
            "authorization_code" -> HttpStatusCode.OK to grant()
            else -> HttpStatusCode.OK to """{"access_token":"access-refreshed","expires_in":3599,"scope":"${GoogleScopes.GMAIL_SEND}","token_type":"Bearer"}"""
        }
    }
    var revokeStatus = HttpStatusCode.OK

    /** How long the token endpoint takes to answer, so concurrent callers overlap. */
    var tokenDelayMs = 0L

    val http = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val form = (request.body as FormDataContent).formData.entries().associate { (name, values) -> name to values.first() }
                when (request.url.toString().substringBefore('?')) {
                    GoogleOAuthClient.TOKEN_URL -> {
                        synchronized(tokenCalls) { tokenCalls += form }
                        if (tokenDelayMs > 0) delay(tokenDelayMs)
                        val (status, body) = onToken(form)
                        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                    }
                    GoogleOAuthClient.REVOKE_URL -> {
                        revoked += form.getValue("token")
                        respond("", revokeStatus)
                    }
                    else -> error("Unexpected call ${request.url}")
                }
            }
        }
    }

    fun client() = GoogleOAuthClient({ config }, http)

    fun grant(
        email: String = "Obras@Example.pt",
        scope: String = "openid https://www.googleapis.com/auth/userinfo.email ${GoogleScopes.GMAIL_SEND}",
        refreshToken: String? = "refresh-1",
        verified: Boolean = true,
        audience: String = clientId,
    ): String {
        val refresh = refreshToken?.let { ""","refresh_token":"$it"""" }.orEmpty()
        return """{"access_token":"access-1","expires_in":3599$refresh,"scope":"$scope","token_type":"Bearer","id_token":"${idToken(email, verified, audience)}"}"""
    }

    fun idToken(email: String, verified: Boolean = true, audience: String = clientId): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val claims = """{"iss":"https://accounts.google.com","aud":"$audience","sub":"1001","email":"$email","email_verified":$verified,"exp":4102444800}"""
        return listOf("""{"alg":"RS256"}""", claims).joinToString(".") { encoder.encodeToString(it.toByteArray()) } + ".signature"
    }
}
