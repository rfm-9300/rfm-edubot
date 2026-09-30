package com.rfm.edubot.integrations.google

import com.rfm.edubot.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.Properties

/**
 * Google's token, revoke and Gmail endpoints for tests: answers come from [onToken] and [onSend],
 * and every call is recorded.
 */
internal class FakeGoogle(val clientId: String = "client-1.apps.googleusercontent.com") {
    val config = AppConfig.GoogleConfig(clientId = clientId, clientSecret = "google-secret", redirectUri = "https://bots.example/integrations/google/callback")

    /** The form fields of each call to the token endpoint. */
    val tokenCalls = mutableListOf<Map<String, String>>()
    val revoked = mutableListOf<String>()

    /** A message Gmail was asked to send, with the token it came with. */
    class GmailSend(val accessToken: String, val raw: ByteArray, val threadId: String?) {
        fun parsed(): MimeMessage = MimeMessage(Session.getInstance(Properties()), raw.inputStream())
    }

    val sends = mutableListOf<GmailSend>()

    var onToken: (Map<String, String>) -> Pair<HttpStatusCode, String> = { form ->
        when (form["grant_type"]) {
            "authorization_code" -> HttpStatusCode.OK to grant()
            else -> HttpStatusCode.OK to """{"access_token":"access-refreshed","expires_in":3599,"scope":"${GoogleScopes.GMAIL_SEND}","token_type":"Bearer"}"""
        }
    }
    var revokeStatus = HttpStatusCode.OK

    /** Gmail's answer to a send; by default it takes the message and files it in a thread of its own. */
    var onSend: (GmailSend) -> Pair<HttpStatusCode, String> = { send ->
        val n = synchronized(sends) { sends.indexOf(send) + 1 }
        HttpStatusCode.OK to """{"id":"gm-$n","threadId":"${send.threadId ?: "th-$n"}","labelIds":["SENT"]}"""
    }

    /** How long the token endpoint takes to answer, so concurrent callers overlap. */
    var tokenDelayMs = 0L

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    val http = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                when (request.url.toString().substringBefore('?')) {
                    "${GmailClient.BASE_URL}/users/me/messages/send" -> {
                        val body = json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val send = GmailSend(
                            accessToken = request.headers[HttpHeaders.Authorization].orEmpty().removePrefix("Bearer "),
                            raw = Base64.getUrlDecoder().decode(body.getValue("raw").jsonPrimitive.content),
                            threadId = body["threadId"]?.jsonPrimitive?.content,
                        )
                        synchronized(sends) { sends += send }
                        val (status, answer) = onSend(send)
                        respond(answer, status, jsonHeaders)
                    }
                    GoogleOAuthClient.TOKEN_URL -> {
                        val form = form(request.body as FormDataContent)
                        synchronized(tokenCalls) { tokenCalls += form }
                        if (tokenDelayMs > 0) delay(tokenDelayMs)
                        val (status, body) = onToken(form)
                        respond(body, status, jsonHeaders)
                    }
                    GoogleOAuthClient.REVOKE_URL -> {
                        revoked += form(request.body as FormDataContent).getValue("token")
                        respond("", revokeStatus)
                    }
                    else -> error("Unexpected call ${request.url}")
                }
            }
        }
    }

    private fun form(content: FormDataContent) = content.formData.entries().associate { (name, values) -> name to values.first() }

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

    /** Google's error body for a refused call. */
    fun googleError(code: Int, reason: String, message: String = "refused") =
        """{"error":{"code":$code,"message":"$message","errors":[{"message":"$message","domain":"global","reason":"$reason"}],"status":"X"}}"""
}
