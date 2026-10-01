package com.rfm.edubot.agents.templates

import com.rfm.edubot.agents.actions.AgentActions
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.model.CompanyAgentSettings
import com.rfm.edubot.agents.registry.AgentDefinitionValidator
import com.rfm.edubot.agents.registry.AgentRegistry
import com.rfm.edubot.agents.registry.Availability
import com.rfm.edubot.agents.registry.IntegrationKind
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.agents.runtime.TemplateRenderer
import com.rfm.edubot.agents.runtime.ValueFormatter
import com.rfm.edubot.dashboard.DashboardModules
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentTemplatesTest {
    private val validator = AgentDefinitionValidator(AgentRegistry(AgentActions.builtIn, TriggerTypes.all))
    private val everything = Availability(DashboardModules.catalog.toSet(), IntegrationKind.entries.toSet())

    private fun built(key: String, params: JsonObject? = null, locale: String = "pt-PT", company: CompanyAgentSettings = CompanyAgentSettings()): TemplatedAgent {
        val result = AgentTemplates.build(key, params, locale, company)
        assertIs<TemplateBuild.Built>(result, "$key: $result")
        return result.agent
    }

    private fun problems(key: String, params: JsonObject): List<String> {
        val result = AgentTemplates.build(key, params, "en", CompanyAgentSettings())
        assertIs<TemplateBuild.InvalidParams>(result, "$key: $result")
        return result.problems.map { "${it.path}:${it.code}" }
    }

    @Test
    fun `every template builds an agent that can be activated, in every language`() {
        for (locale in listOf("en", "pt-PT", "es")) {
            for (key in AgentTemplates.keys) {
                val agent = built(key, locale = locale)
                assertEquals(emptyList(), validator.validate(agent.definition, everything, agent.params), "$key ($locale)")
                assertFalse(agent.name.contains('.'), "$key has a name in $locale")
            }
        }
    }

    @Test
    fun `the copy exists in the three languages`() {
        val en = TemplateCopy.keys("en")
        assertEquals(en, TemplateCopy.keys("pt-PT"))
        assertEquals(en, TemplateCopy.keys("es"))
        AgentTemplates.keys.forEach { assertTrue("$it.name" in en, it) }
    }

    @Test
    fun `names and messages follow the company's language`() {
        val pt = built("invoice_due_reminder", locale = "pt-PT")
        val es = built("invoice_due_reminder", locale = "es")
        assertEquals("Lembrete de fatura a vencer", pt.name)
        assertTrue(pt.definition.steps.single().input["text"]!!.jsonPrimitive.content.startsWith("Olá {{client.firstName}}"))
        assertTrue(es.definition.steps.single().input["text"]!!.jsonPrimitive.content.startsWith("Hola {{client.firstName}}"))
    }

    @Test
    fun `answers shape the definition, even when a form sends them as text`() {
        val agent = built(
            "invoice_due_reminder",
            buildJsonObject { put("daysBefore", "5"); put("at", "08:30"); put("attachPdf", false); put("fallback", "task") },
        )
        val trigger = agent.definition.triggers.single().config
        assertEquals(-5, trigger["offsetDays"]!!.jsonPrimitive.int)
        assertEquals("08:30", trigger["at"]!!.jsonPrimitive.content)
        val input = agent.definition.steps.single().input
        assertEquals("none", input["attachPdf"]!!.jsonPrimitive.content)
        assertEquals("task", input["fallback"]!!.jsonPrimitive.content)
        assertEquals(5, agent.params["daysBefore"]!!.jsonPrimitive.int, "the answers are kept on the agent")
        assertEquals(true, agent.params.containsKey("autonomy"), "with the defaults filled in")
    }

    @Test
    fun `optional steps come and go with the answers`() {
        assertEquals(4, built("quote_follow_up").definition.steps.size)
        assertEquals(listOf("flow.wait", "whatsapp.send"), built("quote_follow_up", buildJsonObject { put("taskAfterDays", 0) }).definition.steps.map { it.action })
        assertEquals(2, built("new_lead_intake", buildJsonObject { put("createTask", true) }).definition.steps.size)
        assertEquals(1, built("month_end_billing", buildJsonObject { put("notify", false) }).definition.steps.size)
        assertEquals(listOf("whatsapp.send"), built("no_show_recovery", buildJsonObject { put("afterHours", 0) }).definition.steps.map { it.action })
        assertEquals((1..5).toList(), built("daily_agenda").definition.triggers.single().config["weekdays"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(null, built("daily_agenda", buildJsonObject { put("weekdaysOnly", false) }).definition.triggers.single().config["weekdays"])
    }

    @Test
    fun `the chat qualifier sorts messages in one AI call, in the company's words, and only tells the team about real ones`() {
        val agent = built("lead_qualifier", locale = "pt-PT")
        val (classify, notify) = agent.definition.steps
        assertEquals("ai.task", classify.action)
        assertEquals(false, classify.input["readData"]!!.jsonPrimitive.boolean)
        val intent = classify.input["outputs"]!!.jsonArray.first().jsonObject
        assertEquals(listOf("novo pedido", "pergunta", "reclamação", "outro"), intent["options"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("outro", notify.guard!!.conditions.single().value!!.jsonPrimitive.content)
        assertEquals(24, agent.definition.policy.cooldownHours)
        assertEquals(listOf("event.text", "client.id"), agent.definition.conditions!!.conditions.map { it.field })

        val everyone = built("lead_qualifier", buildJsonObject { put("newContactsOnly", false); put("createTask", true) })
        assertEquals(listOf("event.text"), everyone.definition.conditions!!.conditions.map { it.field })
        assertEquals(listOf("ai.task", "team.notify", "team.task.create"), everyone.definition.steps.map { it.action })
    }

    @Test
    fun `email lead capture reads new senders' mail, skips machines before any AI call, and files real requests as tasks`() {
        val agent = built("email_lead_capture", locale = "en")
        val trigger = agent.definition.triggers.single()
        assertEquals(TriggerTypes.EMAIL_RECEIVED, trigger.type)
        assertEquals("unknown", trigger.config["sender"]!!.jsonPrimitive.content)
        val automated = agent.definition.conditions!!.conditions.single()
        assertEquals("email.automated" to false, automated.field to automated.value!!.jsonPrimitive.boolean)

        val (read, file) = agent.definition.steps
        assertEquals("ai.task", read.action)
        assertEquals(listOf("isRequest", "name", "phone", "summary"), read.input["outputs"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals("team.task.create", file.action)
        assertEquals("steps.s1.output.isRequest", file.guard!!.conditions.single().field)
        assertTrue(file.input["detail"]!!.jsonPrimitive.content.contains("{{email.from}}"))

        val anyone = built("email_lead_capture", buildJsonObject { put("newContactsOnly", false) })
        assertEquals("any", anyone.definition.triggers.single().config["sender"]!!.jsonPrimitive.content)
    }

    @Test
    fun `supplier bill intake narrows the inbox as asked and tells the team only about bills`() {
        val agent = built("supplier_bill_intake", locale = "en")
        val config = agent.definition.triggers.single().config
        assertEquals(true, config["hasPdf"]!!.jsonPrimitive.boolean)
        assertEquals(null, config["fromContains"])
        assertEquals(listOf("ai.task", "team.notify"), agent.definition.steps.map { it.action })
        val outputs = agent.definition.steps.first().input["outputs"]!!.jsonArray.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(mapOf("isBill" to "boolean", "description" to "text", "amount" to "text", "dueDate" to "date"), outputs)
        assertEquals("steps.s1.output.isBill", agent.definition.steps.last().guard!!.conditions.single().field)

        val narrowed = built("supplier_bill_intake", buildJsonObject { put("fromContains", " edp.pt "); put("pdfOnly", false); put("createTask", true) })
        val narrowedConfig = narrowed.definition.triggers.single().config
        assertEquals("edp.pt", narrowedConfig["fromContains"]!!.jsonPrimitive.content)
        assertEquals(null, narrowedConfig["hasPdf"], "any email from them, PDF or not")
        assertEquals(listOf("ai.task", "team.notify", "team.task.create"), narrowed.definition.steps.map { it.action })
    }

    @Test
    fun `a bill whose email leaves out the amount or the date still reads well`() {
        val message = built("supplier_bill_intake", locale = "pt-PT").definition.steps[1].input["message"]!!.jsonPrimitive.content
        fun rendered(output: JsonObject) = TemplateRenderer.render(
            message,
            buildJsonObject {
                putJsonObject("email") { put("fromName", "EDP Comercial") }
                putJsonObject("steps") { putJsonObject("s1") { put("output", output) } }
            },
            ValueFormatter("pt-PT", TimeZone.of("Europe/Lisbon")),
        )

        assertEquals(
            "Fatura de fornecedor de EDP Comercial: Eletricidade de setembro · valor não indicado no email · vencimento: não indicado.",
            rendered(buildJsonObject { put("isBill", true); put("description", "Eletricidade de setembro") }),
        )
        assertEquals(
            "Fatura de fornecedor de EDP Comercial: Eletricidade de setembro · 84,20 € · vencimento: 30/10/2026.",
            rendered(buildJsonObject { put("isBill", true); put("description", "Eletricidade de setembro"); put("amount", "84,20 €"); put("dueDate", "2026-10-30") }),
        )
    }

    @Test
    fun `the thank-you after a service links the review page only when there is one`() {
        val plain = built("post_service_follow_up", locale = "en").definition.steps.last().input["text"]!!.jsonPrimitive.content
        val withLink = built("post_service_follow_up", buildJsonObject { put("reviewUrl", "https://g.page/r/obras/review") }, locale = "en")
            .definition.steps.last().input["text"]!!.jsonPrimitive.content
        assertFalse(plain.contains("http"))
        assertTrue(withLink.contains("https://g.page/r/obras/review"))
        assertFalse(withLink.contains("{reviewUrl}"))
    }

    @Test
    fun `invalid answers are refused with the field and the reason`() {
        assertEquals(listOf("daysBefore:too_large"), problems("invoice_due_reminder", buildJsonObject { put("daysBefore", 99) }))
        assertEquals(listOf("at:invalid_time"), problems("invoice_due_reminder", buildJsonObject { put("at", "25:00") }))
        assertEquals(listOf("colour:unknown_field"), problems("invoice_due_reminder", buildJsonObject { put("colour", "blue") }))
        assertEquals(listOf("reviewUrl:invalid_url"), problems("post_service_follow_up", buildJsonObject { put("reviewUrl", "my site") }))
        assertEquals(listOf("autonomy:not_allowed"), problems("booking_reminder", buildJsonObject { put("autonomy", "YOLO") }))
        assertIs<TemplateBuild.UnknownTemplate>(AgentTemplates.build("nope", null, "en", CompanyAgentSettings()))
    }

    @Test
    fun `messages to customers ask first even when the company lets agents act alone`() {
        val trusting = CompanyAgentSettings(defaultAutonomy = Autonomy.AUTO)
        for (key in AgentTemplates.keys) {
            val agent = built(key, company = trusting)
            assertEquals(Autonomy.AUTO, agent.definition.policy.autonomy, key)
            agent.definition.steps.filter { it.action == "whatsapp.send" || it.action == "email.send" || it.action.startsWith("crm.") }.forEach { step ->
                assertEquals(Autonomy.APPROVE, step.autonomy, "$key ${step.id}")
            }
        }
        val chosen = built("booking_reminder", buildJsonObject { put("autonomy", "AUTO") })
        assertEquals(Autonomy.AUTO, chosen.definition.steps.single().autonomy)
    }

    @Test
    fun `waits set every duration, so the action's one-day default never sneaks in`() {
        for (key in AgentTemplates.keys) {
            built(key).definition.steps.filter { it.action == "flow.wait" }.forEach { step ->
                assertTrue(listOf("days", "hours", "minutes").all { it in step.input }, "$key ${step.id}")
            }
        }
    }

    @Test
    fun `the gallery says what a company is missing`() {
        val small = Availability(setOf(DashboardModules.CLIENTS, DashboardModules.INVOICES, DashboardModules.PAYMENTS), emptySet())
        val entries = AgentTemplates.catalog("en", small, validator).associateBy { it["key"]!!.jsonPrimitive.content }
        assertEquals(AgentTemplates.keys.toSet(), entries.keys)

        fun available(key: String) = entries.getValue(key)["available"]!!.jsonPrimitive.content.toBoolean()
        fun reason(key: String) = entries.getValue(key)["reason"]?.jsonPrimitive?.content

        assertFalse(available("invoice_due_reminder"))
        assertEquals("needs_integration:WHATSAPP", reason("invoice_due_reminder"))
        assertFalse(available("daily_agenda"))
        assertEquals("needs_module:bookings", reason("daily_agenda"))
        assertFalse(available("email_invoice_when_created"))
        assertEquals("needs_integration:GMAIL", reason("email_invoice_when_created"))
        assertFalse(available("email_lead_capture"))
        assertEquals("needs_integration:GMAIL_INBOX", reason("supplier_bill_intake"))
        val sendingOnly = AgentTemplates.catalog("en", Availability(small.modules, setOf(IntegrationKind.GMAIL)), validator)
            .associateBy { it["key"]!!.jsonPrimitive.content }
        assertTrue(sendingOnly.getValue("email_invoice_when_created")["available"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("needs_integration:GMAIL_INBOX", sendingOnly.getValue("email_lead_capture")["reason"]?.jsonPrimitive?.content, "sending alone doesn't read the inbox")
        assertTrue(available("payables_digest"))
        assertTrue(available("weekly_cash_briefing"))
        assertEquals("Payments due this week", entries.getValue("payables_digest")["name"]!!.jsonPrimitive.content)
        assertTrue(entries.getValue("payables_digest")["definition"] is JsonObject, "the gallery draws the recipe from the definition")
    }
}
