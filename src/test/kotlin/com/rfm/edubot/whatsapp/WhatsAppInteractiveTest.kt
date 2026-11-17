package com.rfm.edubot.whatsapp

import com.rfm.edubot.channel.OutboundDeliveryException
import com.rfm.edubot.channel.QuickReply
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
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

/** WhatsApp's interactive messages: up to three reply buttons, a list for more, and the options' ids coming back on a tap. */
class WhatsAppInteractiveTest {
    private val bodies = mutableListOf<JsonObject>()
    private val client = WhatsAppClient(
        "token", "pn-1", maxRetries = 1,
        httpClient = HttpClient(MockEngine { request ->
            bodies += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond("""{"messages":[{"id":"wamid.out"}],"contacts":[{"wa_id":"351912345678"}]}""", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }),
    )

    private fun options(count: Int) = List(count) { QuickReply("Opção ${it + 1}", "flow:f:s$it:$it") }

    @Test
    fun `up to three options go as reply buttons carrying their payloads as ids`(): Unit = runBlocking {
        client.sendQuickReplies("351912345678", "O que deseja?", options(3))

        val body = bodies.single()
        assertEquals("interactive", body["type"]!!.jsonPrimitive.content)
        assertEquals("351912345678", body["to"]!!.jsonPrimitive.content)
        val interactive = body["interactive"]!!.jsonObject
        assertEquals("button", interactive["type"]!!.jsonPrimitive.content)
        assertEquals("O que deseja?", interactive["body"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        val buttons = interactive["action"]!!.jsonObject["buttons"]!!.jsonArray.map { it.jsonObject["reply"]!!.jsonObject }
        assertEquals(listOf("flow:f:s0:0", "flow:f:s1:1", "flow:f:s2:2"), buttons.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(listOf("Opção 1", "Opção 2", "Opção 3"), buttons.map { it["title"]!!.jsonPrimitive.content })
    }

    @Test
    fun `more than three go as a list opened by the label's button`(): Unit = runBlocking {
        client.sendQuickReplies("351912345678", "Que serviço?", options(7), listLabel = "Escolher")

        val interactive = bodies.single()["interactive"]!!.jsonObject
        assertEquals("list", interactive["type"]!!.jsonPrimitive.content)
        val action = interactive["action"]!!.jsonObject
        assertEquals("Escolher", action["button"]!!.jsonPrimitive.content)
        val rows = action["sections"]!!.jsonArray.single().jsonObject["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(7, rows.size)
        assertEquals("flow:f:s6:6", rows.last()["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a list with no label is refused before anything is sent, and no options is a plain text`(): Unit = runBlocking {
        assertFailsWith<OutboundDeliveryException> { client.sendQuickReplies("351912345678", "Que serviço?", options(7)) }
        assertEquals(0, bodies.size)

        client.sendQuickReplies("351912345678", "Olá", emptyList())
        assertEquals("text", bodies.single()["type"]!!.jsonPrimitive.content)
    }
}
