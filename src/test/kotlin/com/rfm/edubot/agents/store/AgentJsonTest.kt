package com.rfm.edubot.agents.store

import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentPolicy
import com.rfm.edubot.agents.model.AgentVoice
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.model.Condition
import com.rfm.edubot.agents.model.ConditionGroup
import com.rfm.edubot.agents.model.ConditionMatch
import com.rfm.edubot.agents.model.ExitRule
import com.rfm.edubot.agents.model.QuietHours
import com.rfm.edubot.agents.model.StepSpec
import com.rfm.edubot.agents.model.TriggerSpec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.Document
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentJsonTest {
    private val definition = AgentDefinition(
        triggers = listOf(
            TriggerSpec("t1", "date_offset", buildJsonObject { put("entity", "invoice"); put("field", "dueDate"); put("offsetDays", -3); put("at", "09:00") }),
            TriggerSpec("t2", "event", buildJsonObject { put("event", "invoice.created") }),
        ),
        conditions = ConditionGroup(ConditionMatch.ALL, listOf(Condition("invoice.totalCents", "gt", JsonPrimitive(50_000)))),
        steps = listOf(
            StepSpec("s1", "whatsapp.send", buildJsonObject { put("to", "client"); put("text", "Olá {{client.firstName}}") }, autonomy = Autonomy.APPROVE),
            StepSpec("s2", "flow.wait", buildJsonObject { put("days", 7) }),
        ),
        exitRules = listOf(ExitRule("invoice.paid")),
        policy = AgentPolicy(autonomy = Autonomy.AUTO, quietHours = QuietHours("20:00", "09:00"), cooldownHours = 24),
        voice = AgentVoice(tone = "formal", signature = "Equipa Obras"),
    )

    @Test
    fun `a definition survives being stored as a document`() {
        val doc = AgentJson.toDocument(AgentDefinition.serializer(), definition)

        assertEquals(definition, AgentJson.fromDocument(AgentDefinition.serializer(), doc, AgentDefinition()))
    }

    @Test
    fun `an unreadable document falls back instead of breaking the agent list`() {
        val broken = Document("steps", "not a list")

        assertEquals(AgentDefinition(), AgentJson.fromDocument(AgentDefinition.serializer(), broken, AgentDefinition()))
    }

    @Test
    fun `event triggers and exit rules are listed for the dispatcher`() {
        assertEquals(listOf("invoice.created", "invoice.paid"), definition.eventTypes())
        assertEquals(listOf("date_offset", "event"), definition.triggerTypes())
    }
}
