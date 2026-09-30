package com.rfm.edubot.whatsapp

import com.rfm.edubot.channel.OutboundDeliveryException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatsAppClientTest {
    @Test
    fun `permanent Graph failure is reported without retrying`() = runBlocking {
        var attempts = 0
        val http = HttpClient(MockEngine) {
            engine {
                addHandler {
                    attempts += 1
                    respond(
                        """{"error":{"message":"Invalid token","code":190}}""",
                        status = HttpStatusCode.Unauthorized,
                    )
                }
            }
        }

        assertFailsWith<OutboundDeliveryException> {
            WhatsAppClient("bad-token", "phone-1", maxRetries = 3, httpClient = http).sendText("351900000000", "Hello")
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `a sent text returns its message id and the WhatsApp id Meta resolved`() = runBlocking {
        val client = client { json("""{"messaging_product":"whatsapp","contacts":[{"input":"+351 900 000 000","wa_id":"351900000000"}],"messages":[{"id":"wamid.abc"}]}""") }

        val sent = client.sendTextMessage("351900000000", "Olá")

        assertEquals("wamid.abc", sent.id)
        assertEquals("351900000000", sent.waId)
    }

    @Test
    fun `a Graph error keeps Meta's code and explanation and maps to a dashboard key`() = runBlocking {
        val client = client {
            json(
                """{"error":{"message":"(#131030) Recipient phone number not in allowed list","code":131030,"error_data":{"details":"Recipient phone number not in allowed list: Add recipient phone number to recipient list and try again."},"fbtrace_id":"trace-9"}}""",
                HttpStatusCode.BadRequest,
            )
        }

        val error = assertFailsWith<WhatsAppApiException> { client.sendTextMessage("351900000000", "Olá") }

        assertEquals(131030, error.code)
        assertEquals("recipient_not_allowed", error.key)
        assertEquals("trace-9", error.traceId)
        assertTrue(error.detail!!.startsWith("Recipient phone number not in allowed list"))
    }

    @Test
    fun `unknown Meta codes fall back to a generic key`() {
        assertEquals("window_closed", WhatsAppErrors.key(131047))
        assertEquals("display_name_unapproved", WhatsAppErrors.key(131037))
        assertEquals("send_failed", WhatsAppErrors.key(999999))
        assertEquals("send_failed", WhatsAppErrors.key(null))
    }

    @Test
    fun `a template is sent with its language and body parameters`() = runBlocking {
        var sentBody: JsonObject? = null
        val client = client { request ->
            sentBody = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            json("""{"messages":[{"id":"wamid.tpl"}]}""")
        }

        val sent = client.sendTemplate("351900000000", "order_update", "pt_PT", listOf(TemplateParameter(text = "Ana"), TemplateParameter(text = "#42")))

        val body = sentBody!!
        assertEquals("wamid.tpl", sent.id)
        assertEquals("template", body["type"]!!.jsonPrimitive.content)
        assertEquals("whatsapp", body["messaging_product"]!!.jsonPrimitive.content)
        val template = body["template"]!!.jsonObject
        assertEquals("order_update", template["name"]!!.jsonPrimitive.content)
        assertEquals("pt_PT", template["language"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        val parameters = template["components"]!!.jsonArray.single().jsonObject["parameters"]!!.jsonArray
        assertEquals(listOf("Ana", "#42"), parameters.map { it.jsonObject["text"]!!.jsonPrimitive.content })
        assertNull(parameters.first().jsonObject["parameter_name"])
        assertNull(body["text"])
    }

    @Test
    fun `a template without variables sends no components`() = runBlocking {
        var sentBody: JsonObject? = null
        val client = client { request ->
            sentBody = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            json("""{"messages":[{"id":"wamid.hello"}]}""")
        }

        client.sendTemplate("351900000000", "hello_world", "en_US", emptyList())

        assertNull(sentBody!!["template"]!!.jsonObject["components"])
    }

    @Test
    fun `templates come with their review status and flag what the dashboard cannot fill`() = runBlocking {
        val requested = mutableListOf<String>()
        val client = client { request ->
            requested.add(request.url.toString())
            if (request.url.parameters["after"] == null) {
                json(
                    """{"data":[
                        {"name":"hello_world","language":"en_US","status":"APPROVED","category":"UTILITY","components":[
                          {"type":"HEADER","format":"TEXT","text":"Hello World"},
                          {"type":"BODY","text":"Welcome and congratulations!!"},
                          {"type":"FOOTER","text":"WhatsApp Business Platform sample message"}]},
                        {"name":"promo_photo","language":"pt_PT","status":"APPROVED","category":"MARKETING","components":[
                          {"type":"HEADER","format":"IMAGE"},
                          {"type":"BODY","text":"Olá {{1}}, veja a novidade"}]},
                        {"id":"tpl-9","name":"draft","language":"pt_PT","status":"REJECTED","rejected_reason":"INVALID_FORMAT","components":[{"type":"BODY","text":"Ainda não"}]}
                      ],"paging":{"next":"https://graph.facebook.com/v21.0/waba-1/message_templates?after=cursor-2"}}""",
                )
            } else {
                json(
                    """{"data":[
                        {"name":"booking_reminder","language":"pt_PT","status":"APPROVED","category":"UTILITY","parameter_format":"NAMED","components":[
                          {"type":"BODY","text":"Olá {{first_name}}, a sua marcação é {{when}}. Até {{when}}!"},
                          {"type":"BUTTONS","buttons":[{"type":"QUICK_REPLY","text":"Confirmar"}]}]},
                        {"name":"verify","language":"en_US","status":"APPROVED","category":"AUTHENTICATION","components":[{"type":"BODY","text":"{{1}} is your code"}]}
                      ]}""",
                )
            }
        }

        val templates = client.templates("waba-1").associateBy { it.name }

        assertEquals(2, requested.size)
        assertEquals(setOf("hello_world", "promo_photo", "draft", "booking_reminder", "verify"), templates.keys)
        val rejected = templates.getValue("draft")
        assertFalse(rejected.approved)
        assertEquals("REJECTED", rejected.status)
        assertEquals("INVALID_FORMAT", rejected.rejectedReason)
        assertEquals("tpl-9", rejected.id)
        val hello = templates.getValue("hello_world")
        assertTrue(hello.approved)
        assertTrue(hello.sendable)
        assertEquals(emptyList(), hello.params)
        assertEquals("Hello World", hello.header)
        assertFalse(templates.getValue("promo_photo").sendable)
        val reminder = templates.getValue("booking_reminder")
        assertTrue(reminder.sendable)
        assertTrue(reminder.namedParams)
        assertEquals(listOf("first_name", "when"), reminder.params)
        assertEquals(listOf("Confirmar"), reminder.buttons)
        assertFalse(templates.getValue("verify").sendable)
    }

    @Test
    fun `a new template is submitted with its components and examples`() = runBlocking {
        var sent: JsonObject? = null
        var path = ""
        val client = client { request ->
            path = request.url.encodedPath
            sent = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            json("""{"id":"tpl-1","status":"PENDING","category":"UTILITY"}""")
        }

        val created = client.createTemplate(
            "waba-1",
            TemplateDraft("order_ready", "pt_PT", "UTILITY", "Encomenda", "Olá {{1}}, a encomenda {{2}} está pronta.", listOf("Ana", "#42"), "Obrigado", listOf("Vou buscar")),
        )

        assertEquals("/v21.0/waba-1/message_templates", path)
        assertEquals("PENDING", created.status)
        val body = sent!!
        assertEquals("order_ready", body["name"]!!.jsonPrimitive.content)
        assertEquals("UTILITY", body["category"]!!.jsonPrimitive.content)
        val components = body["components"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("HEADER", "BODY", "FOOTER", "BUTTONS"), components.map { it["type"]!!.jsonPrimitive.content })
        assertEquals(listOf("Ana", "#42"), components[1]["example"]!!.jsonObject["body_text"]!!.jsonArray.single().jsonArray.map { it.jsonPrimitive.content })
        assertEquals("QUICK_REPLY", components[3]["buttons"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(components[2]["example"])
    }

    @Test
    fun `deleting a template names the exact language version`() = runBlocking {
        var method = ""
        var query = ""
        val client = client { request ->
            method = request.method.value
            query = "${request.url.parameters["name"]}|${request.url.parameters["hsm_id"]}"
            json("""{"success":true}""")
        }

        client.deleteTemplate("waba-1", "order_ready", "tpl-1")

        assertEquals("DELETE", method)
        assertEquals("order_ready|tpl-1", query)
    }

    @Test
    fun `customer media is downloaded from Meta's short-lived URL with the same token`() = runBlocking {
        val calls = mutableListOf<String>()
        val client = client { request ->
            calls.add("${request.url.host}${request.url.encodedPath} auth=${request.headers[HttpHeaders.Authorization]}")
            if (request.url.host == "graph.facebook.com") {
                json("""{"url":"https://lookaside.fbsbx.com/whatsapp_business/attachments/?mid=1","mime_type":"image/jpeg","file_size":4,"id":"media-1"}""")
            } else {
                respond(content = byteArrayOf(1, 2, 3, 4), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
            }
        }

        val file = client.downloadMedia("media-1", maxBytes = 1024)

        assertEquals("image/jpeg", file.mimeType)
        assertEquals(listOf<Byte>(1, 2, 3, 4), file.bytes.toList())
        assertEquals(listOf("graph.facebook.com/v21.0/media-1 auth=Bearer token", "lookaside.fbsbx.com/whatsapp_business/attachments/ auth=Bearer token"), calls)
    }

    @Test
    fun `media over the size limit is refused before it is downloaded`() = runBlocking {
        var calls = 0
        val client = client {
            calls += 1
            json("""{"url":"https://lookaside.fbsbx.com/x","mime_type":"video/mp4","file_size":50000000}""")
        }

        assertFailsWith<MediaTooLargeException> { client.downloadMedia("media-2", maxBytes = 1024) }
        assertEquals(1, calls)
    }

    @Test
    fun `a rendered template fills its variables and keeps header and footer`() {
        val template = WhatsAppTemplate(
            name = "order_update", language = "pt_PT", category = "UTILITY",
            header = "Encomenda", body = "Olá {{1}}, a encomenda {{2}} está pronta.", footer = "Obrigado",
            buttons = emptyList(), params = listOf("1", "2"), namedParams = false, sendable = true,
        )

        assertEquals("Encomenda\n\nOlá Ana, a encomenda #42 está pronta.\n\nObrigado", template.render(mapOf("1" to "Ana", "2" to "#42")))
        assertEquals(listOf("Ana", "#42"), template.bodyParameters(mapOf("1" to "Ana", "2" to "#42")).map { it.text })
    }

    private fun client(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        WhatsAppClient("token", "phone-1", maxRetries = 1, httpClient = HttpClient(MockEngine) { engine { addHandler(handler) } })

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}
