package com.rfm.edubot.whatsapp.signup

import com.rfm.edubot.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WhatsAppSignupClientTest {
    @Test
    fun `connect completes all graph steps`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = client { request ->
            paths.add(request.url.encodedPath)
            when (request.url.encodedPath) {
                "/v21.0/oauth/access_token" -> json("""{"access_token":"business-token","token_type":"bearer"}""")
                "/v21.0/pn-1/register" -> json("""{"success":true}""")
                "/v21.0/waba-1/subscribed_apps" -> json("""{"success":true}""")
                "/v21.0/pn-1" -> json("""{"id":"pn-1","display_phone_number":"+351 900 000 000","verified_name":"Client"}""")
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
        }

        val result = WhatsAppSignupClient(config(), client).connect("code-1", "waba-1", "pn-1")

        assertEquals("pn-1", result.phoneNumberId)
        assertEquals("business-token", result.accessToken)
        assertEquals("waba-1", result.wabaId)
        assertEquals("+351 900 000 000", result.displayPhoneNumber)
        assertEquals(listOf("/v21.0/oauth/access_token", "/v21.0/pn-1/register", "/v21.0/waba-1/subscribed_apps", "/v21.0/pn-1"), paths)
    }

    @Test
    fun `already registered phone number is treated as success`() = runBlocking {
        val client = client { request ->
            when (request.url.encodedPath) {
                "/v21.0/oauth/access_token" -> json("""{"access_token":"business-token"}""")
                "/v21.0/pn-1/register" -> json(
                    """{"error":{"message":"Phone number is already registered","code":100,"error_subcode":2388024}}""",
                    HttpStatusCode.BadRequest,
                )
                "/v21.0/waba-1/subscribed_apps" -> json("""{"success":true}""")
                "/v21.0/pn-1" -> json("""{"id":"pn-1"}""")
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
        }

        val result = WhatsAppSignupClient(config(), client).connect("code-1", "waba-1", "pn-1")

        assertEquals("pn-1", result.phoneNumberId)
    }

    @Test
    fun `subscribe failure aborts with typed reason`() = runBlocking {
        val client = client { request ->
            when (request.url.encodedPath) {
                "/v21.0/oauth/access_token" -> json("""{"access_token":"business-token"}""")
                "/v21.0/pn-1/register" -> json("""{"success":true}""")
                "/v21.0/waba-1/subscribed_apps" -> json(
                    """{"error":{"message":"Missing permission","code":10,"fbtrace_id":"trace-1"}}""",
                    HttpStatusCode.Forbidden,
                )
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
        }

        val error = assertFailsWith<SignupException> {
            WhatsAppSignupClient(config(), client).connect("code-1", "waba-1", "pn-1")
        }

        assertEquals("waba_subscribe_failed", error.reason)
        assertEquals(403, error.statusCode)
        assertEquals(10, error.graphError?.code)
    }

    private fun client(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine) { engine { addHandler(handler) } }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun config() = AppConfig.WhatsAppConfig(
        verifyToken = "verify",
        appSecret = "secret",
        phoneNumberId = "default-phone",
        accessToken = "default-token",
        apiVersion = "v21.0",
        embeddedSignup = AppConfig.WhatsAppConfig.EmbeddedSignupConfig(appId = "app-id", configId = "config-id"),
    )
}
