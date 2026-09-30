package com.rfm.edubot.agents.runtime

import com.rfm.edubot.events.DomainEvent
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import kotlin.test.Test
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
    fun `email filters check the sender, words and attachments`() {
        val email = event(
            "email.received",
            buildJsonObject { put("from", "compras@fornecedor.pt"); put("subject", "Fatura 2026/88"); put("hasPdf", "true"); put("clientId", "") },
        )
        assertTrue(AgentDispatcher.emailFilters(buildJsonObject { put("sender", "unknown"); put("hasPdf", true) }, email))
        assertFalse(AgentDispatcher.emailFilters(buildJsonObject { put("sender", "known") }, email))
        assertTrue(AgentDispatcher.emailFilters(buildJsonObject { put("subjectContains", buildJsonArray { add(JsonPrimitive("fatura")) }) }, email))
        assertFalse(AgentDispatcher.emailFilters(buildJsonObject { put("fromContains", "cliente") }, email))
    }
}
