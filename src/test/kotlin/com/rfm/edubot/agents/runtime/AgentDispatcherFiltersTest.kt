package com.rfm.edubot.agents.runtime

import com.rfm.edubot.events.DomainEvent
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentDispatcherFiltersTest {
    private fun event(type: String, payload: JsonObject) = DomainEvent(
        tenantId = ObjectId(),
        type = type,
        subject = SubjectRef(SubjectTypes.QUOTE, "q1"),
        payload = payload,
        occurredAt = Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun `a status filter matches the new status only`() {
        val sent = event("quote.status_changed", buildJsonObject { put("from", "PENDENTE"); put("to", "SENT") })
        assertTrue(AgentDispatcher.eventFilters(buildJsonObject { put("toStatus", "SENT") }, sent))
        assertFalse(AgentDispatcher.eventFilters(buildJsonObject { put("toStatus", "ACEITO") }, sent))
    }

    @Test
    fun `message filters check the channel and keywords`() {
        val message = event("message.received", buildJsonObject { put("channel", "WHATSAPP"); put("text", "Queria um orçamento para pintura") })
        val config = buildJsonObject {
            put("channel", "WHATSAPP")
            put("keywords", buildJsonArray { add(JsonPrimitive("orçamento")); add(JsonPrimitive("preço")) })
        }
        assertTrue(AgentDispatcher.eventFilters(config, message))
        assertFalse(AgentDispatcher.eventFilters(buildJsonObject { put("channel", "INSTAGRAM") }, message))
        assertFalse(AgentDispatcher.eventFilters(buildJsonObject { put("keywords", buildJsonArray { add(JsonPrimitive("fatura")) }) }, message))
    }

    @Test
    fun `email filters check the sender, words and attachments`(): Unit = runBlocking {
        val email = event(
            "email.received",
            buildJsonObject { put("from", "compras@fornecedor.pt"); put("subject", "Fatura 2026/88"); put("hasPdf", true) },
        )
        var reads = 0
        val body: suspend () -> String = { reads++; "Segue em anexo a fatura de setembro, a pagar até dia 15." }
        assertTrue(AgentDispatcher.emailFilters(buildJsonObject { put("sender", "unknown"); put("hasPdf", true) }, email, body))
        assertFalse(AgentDispatcher.emailFilters(buildJsonObject { put("sender", "known") }, email, body))
        assertTrue(AgentDispatcher.emailFilters(buildJsonObject { put("subjectContains", buildJsonArray { add(JsonPrimitive("fatura")) }) }, email, body))
        assertFalse(AgentDispatcher.emailFilters(buildJsonObject { put("fromContains", "cliente") }, email, body))
        assertEquals(0, reads, "the text is only read to look for words in it")

        assertTrue(AgentDispatcher.emailFilters(buildJsonObject { put("bodyContains", buildJsonArray { add(JsonPrimitive("A PAGAR")) }) }, email, body))
        assertFalse(AgentDispatcher.emailFilters(buildJsonObject { put("bodyContains", buildJsonArray { add(JsonPrimitive("orçamento")) }) }, email, body))
        val narrowed = buildJsonObject { put("fromContains", "cliente"); put("bodyContains", buildJsonArray { add(JsonPrimitive("fatura")) }) }
        assertFalse(AgentDispatcher.emailFilters(narrowed, email, body))
        assertEquals(2, reads, "nor when the sender already rules it out")
    }
}
