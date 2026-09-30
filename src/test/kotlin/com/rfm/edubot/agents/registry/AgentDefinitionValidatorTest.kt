package com.rfm.edubot.agents.registry

import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentPolicy
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.model.StepSpec
import com.rfm.edubot.agents.model.TriggerSpec
import com.rfm.edubot.agents.runtime.RunContext
import com.rfm.edubot.dashboard.DashboardModules
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentDefinitionValidatorTest {
    private class FakeAction(
        override val key: String,
        override val sideEffect: SideEffect,
        override val requiredIntegration: IntegrationKind? = null,
        override val recipientFields: Set<String> = emptySet(),
    ) : AgentAction {
        override val category = ActionCategory.MESSAGE
        override val inputSchema = Schema.obj(
            "to" to Schema.string(),
            "text" to Schema.string(widget = "template", maxLength = 100),
            "days" to Schema.integer(min = 1, max = 30),
            "thenGoTo" to Schema.string(),
            required = listOf("text"),
        )
        override val toolDescription = key
        override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(kind = "generic")
        override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult = ActionResult.Done()
    }

    private val registry = AgentRegistry(
        actions = listOf(
            FakeAction("send", SideEffect.EXTERNAL_MESSAGE, IntegrationKind.WHATSAPP, recipientFields = setOf("to")),
            FakeAction("note", SideEffect.INTERNAL_WRITE),
        ),
        triggers = TriggerTypes.all,
    )
    private val validator = AgentDefinitionValidator(registry)
    private val everything = Availability(DashboardModules.catalog.toSet(), IntegrationKind.entries.toSet())

    private val invoiceCreated = TriggerSpec("t1", TriggerTypes.EVENT, buildJsonObject { put("event", "invoice.created") })

    private fun codes(definition: AgentDefinition, availability: Availability = everything, params: JsonObject? = null) =
        validator.validate(definition, availability, params).map { it.code }

    @Test
    fun `a complete definition has no problems`() {
        val definition = AgentDefinition(
            triggers = listOf(invoiceCreated),
            steps = listOf(StepSpec("s1", "send", buildJsonObject { put("to", "client"); put("text", "Olá {{client.firstName}}, fatura {{invoice.number}}") })),
        )
        assertEquals(emptyList(), codes(definition))
    }

    @Test
    fun `unknown pieces and broken references are reported`() {
        val definition = AgentDefinition(
            triggers = listOf(TriggerSpec("t1", "telepathy")),
            steps = listOf(
                StepSpec("s1", "teleport"),
                StepSpec("s2", "note", buildJsonObject { put("text", "{{quote.number}} {{steps.s9.output.x}} {{params.missing}}") }),
            ),
        )
        val problems = codes(definition)
        assertTrue("unknown_trigger" in problems)
        assertTrue("unknown_action" in problems)
        assertEquals(3, problems.count { it == "unknown_variable" })
    }

    @Test
    fun `inputs are checked against the action schema, templates only once rendered`() {
        val tooLong = "x".repeat(101)
        val definition = AgentDefinition(
            triggers = listOf(invoiceCreated),
            steps = listOf(
                StepSpec("s1", "note", buildJsonObject { put("text", tooLong) }),
                StepSpec("s2", "note", buildJsonObject { put("text", "ok"); put("days", "{{params.days}}") }),
                StepSpec("s3", "note", buildJsonObject { put("days", 90) }),
            ),
        )
        val problems = codes(definition, params = buildJsonObject { put("days", 3) })
        assertEquals(listOf("too_long", "required", "too_large"), problems)
    }

    @Test
    fun `missing modules and integrations block activation`() {
        val definition = AgentDefinition(
            triggers = listOf(invoiceCreated),
            steps = listOf(StepSpec("s1", "send", buildJsonObject { put("text", "hi") })),
        )
        val bare = Availability(setOf(DashboardModules.OVERVIEW), emptySet())
        assertEquals(listOf("needs_module", "needs_integration"), codes(definition, bare))
    }

    @Test
    fun `triggers must agree on one kind of record`() {
        val definition = AgentDefinition(
            triggers = listOf(invoiceCreated, TriggerSpec("t2", TriggerTypes.EVENT, buildJsonObject { put("event", "booking.created") })),
            steps = listOf(StepSpec("s1", "note", buildJsonObject { put("text", "hi") })),
        )
        assertTrue("mixed_subjects" in codes(definition))
    }

    @Test
    fun `an automatic send may not go to a recipient an AI step chose`() {
        val definition = AgentDefinition(
            triggers = listOf(invoiceCreated),
            steps = listOf(
                StepSpec("s1", "note", buildJsonObject { put("text", "classify") }),
                StepSpec("s2", "send", buildJsonObject { put("to", "{{steps.s1.output.email}}"); put("text", "hi") }),
            ),
            policy = AgentPolicy(autonomy = Autonomy.AUTO),
        )
        assertTrue("unsafe_ai_recipient" in codes(definition))
        val approved = definition.copy(policy = AgentPolicy(autonomy = Autonomy.APPROVE))
        assertTrue("unsafe_ai_recipient" !in codes(approved))
    }

    @Test
    fun `branches only jump forward to steps that exist`() {
        val definition = AgentDefinition(
            triggers = listOf(invoiceCreated),
            steps = listOf(
                StepSpec("s1", "note", buildJsonObject { put("text", "a") }),
                StepSpec("s2", "note", buildJsonObject { put("text", "b"); put("thenGoTo", "s1") }),
                StepSpec("s3", "note", buildJsonObject { put("text", "c"); put("thenGoTo", "s9") }),
            ),
        )
        assertEquals(listOf("backward_jump", "unknown_step"), codes(definition))
    }
}
