package com.rfm.edubot.whatsapp.signup

import com.rfm.edubot.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
        assertEquals(listOf("/v21.0/oauth/access_token", "/v21.0/pn-1", "/v21.0/pn-1/register", "/v21.0/waba-1/subscribed_apps"), paths)
    }

    @Test
    fun `number already on Cloud API is not registered again`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = client { request ->
            paths.add(request.url.encodedPath)
            when (request.url.encodedPath) {
                "/v21.0/oauth/access_token" -> json("""{"access_token":"business-token"}""")
                "/v21.0/pn-1" -> json("""{"id":"pn-1","platform_type":"CLOUD_API","is_pin_enabled":true}""")
                "/v21.0/waba-1/subscribed_apps" -> json("""{"success":true}""")
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
        }

        WhatsAppSignupClient(config(), client).connect("code-1", "waba-1", "pn-1")

        assertEquals(listOf("/v21.0/oauth/access_token", "/v21.0/pn-1", "/v21.0/waba-1/subscribed_apps"), paths)
    }

    @Test
    fun `existing two-step pin is replaced and reused for register`() = runBlocking {
        val pinWrites = mutableListOf<Pair<String, String>>()
        val client = client { request ->
            when ("${request.method.value} ${request.url.encodedPath}") {
                "POST /v21.0/oauth/access_token" -> json("""{"access_token":"business-token"}""")
                "GET /v21.0/pn-1" -> json("""{"id":"pn-1","platform_type":"NOT_APPLICABLE","is_pin_enabled":true}""")
                "POST /v21.0/pn-1", "POST /v21.0/pn-1/register" -> {
                    val pin = Json.parseToJsonElement((request.body as TextContent).text).jsonObject.getValue("pin").jsonPrimitive.content
                    pinWrites.add(request.url.encodedPath to pin)
                    json("""{"success":true}""")
                }
                "POST /v21.0/waba-1/subscribed_apps" -> json("""{"success":true}""")
                else -> error("Unexpected request ${request.method.value} ${request.url.encodedPath}")
            }
        }

        WhatsAppSignupClient(config(), client).connect("code-1", "waba-1", "pn-1")

        assertEquals(listOf("/v21.0/pn-1", "/v21.0/pn-1/register"), pinWrites.map { it.first })
        assertEquals(pinWrites[0].second, pinWrites[1].second)
    }

    @Test
    fun `register gets more time than the shared client timeout`() = runBlocking {
        val client = HttpClient(MockEngine) {
            install(HttpTimeout) { requestTimeoutMillis = 100 }
            engine {
                addHandler { request ->
                    when (request.url.encodedPath) {
                        "/v21.0/oauth/access_token" -> json("""{"access_token":"business-token"}""")
                        "/v21.0/pn-1" -> json("""{"id":"pn-1"}""")
                        "/v21.0/pn-1/register" -> {
                            delay(300)
                            json("""{"success":true}""")
                        }
                        "/v21.0/waba-1/subscribed_apps" -> json("""{"success":true}""")
                        else -> error("Unexpected path ${request.url.encodedPath}")
                    }
                }
            }
        }

        val result = WhatsAppSignupClient(config(), client).connect("code-1", "waba-1", "pn-1")

        assertEquals("pn-1", result.phoneNumberId)
    }

    @Test
    fun `register sends messaging product and a six digit pin`() = runBlocking {
        var registerBody: JsonObject? = null
        val client = client { request ->
            when (request.url.encodedPath) {
                "/v21.0/oauth/access_token" -> json("""{"access_token":"business-token"}""")
                "/v21.0/pn-1/register" -> {
                    registerBody = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    json("""{"success":true}""")
                }
                "/v21.0/waba-1/subscribed_apps" -> json("""{"success":true}""")
                "/v21.0/pn-1" -> json("""{"id":"pn-1"}""")
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
        }

        WhatsAppSignupClient(config(), client).connect("code-1", "waba-1", "pn-1")

        assertEquals("whatsapp", registerBody?.get("messaging_product")?.jsonPrimitive?.content)
        assertTrue(registerBody?.get("pin")?.jsonPrimitive?.content.orEmpty().matches(Regex("\\d{6}")))
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
                "/v21.0/pn-1" -> json("""{"id":"pn-1"}""")
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
