package com.rfm.edubot.agents.actions

import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.AgentSettings
import com.rfm.edubot.agents.model.AgentStatus
import com.rfm.edubot.agents.model.ApprovalStatus
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.model.RunStatus
import com.rfm.edubot.agents.model.RunTrigger
import com.rfm.edubot.agents.model.StepSpec
import com.rfm.edubot.agents.model.TriggerSpec
import com.rfm.edubot.agents.registry.ActionResult
import com.rfm.edubot.agents.registry.AgentRegistry
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SchemaValidator
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.agents.runtime.AgentContextBuilder
import com.rfm.edubot.agents.runtime.AgentRuntime
import com.rfm.edubot.agents.runtime.AgentServices
import com.rfm.edubot.agents.runtime.RunContext
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.lineItem
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.whatsapp.WhatsAppClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class WhatsAppTemplateSendingTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("agent_wa_templates")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }

        /** What Meta returns for the company's WhatsApp Business Account. */
        private val TEMPLATES = """{"data":[
            {"name":"lembrete_fatura","language":"pt_PT","status":"APPROVED","category":"UTILITY","components":[{"type":"BODY","text":"Olá {{1}}, a fatura {{2}} está por pagar."}]},
            {"name":"lembrete_fatura","language":"en_US","status":"APPROVED","category":"UTILITY","components":[{"type":"BODY","text":"Hi {{1}}, invoice {{2}} is due."}]},
            {"name":"ola_cliente","language":"pt_PT","status":"APPROVED","category":"MARKETING","parameter_format":"NAMED","components":[{"type":"BODY","text":"Olá {{nome}}, obrigado pela preferência."}]},
            {"name":"em_revisao","language":"pt_PT","status":"PENDING","category":"UTILITY","components":[{"type":"BODY","text":"Olá {{1}}."}]},
            {"name":"promo_foto","language":"pt_PT","status":"APPROVED","category":"MARKETING","components":[{"type":"HEADER","format":"IMAGE"},{"type":"BODY","text":"Olá {{1}}, veja a novidade."}]}
        ]}"""
    }

    /** Thursday 1 October 2026, 10:00 UTC (11:00 in Lisbon): outside quiet hours. */
    private val now = Instant.parse("2026-10-01T10:00:00Z")
    private val requests: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val templateLists = AtomicInteger()
    private lateinit var scope: CoroutineScope

    private val graph = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val url = request.url.toString()
                requests += "${request.method.value} ${request.url.encodedPath}" to (request.body as? TextContent)?.text.orEmpty()
                if (request.method == HttpMethod.Get && url.contains("message_templates")) {
                    templateLists.incrementAndGet()
                    respond(TEMPLATES, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    respond("""{"contacts":[{"wa_id":"x"}],"messages":[{"id":"wamid.${requests.size}"}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
    }

    @BeforeEach
    fun start() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @AfterEach
    fun stop() {
        scope.cancel()
    }

    private fun tenant() = Tenant(
        slug = "wa-${ObjectId().toHexString().takeLast(6)}",
        name = "Obras Silva",
        channels = listOf(ChannelBinding(Platform.WHATSAPP, "phone-1", "token", wabaId = "waba-1")),
        enabledModules = listOf(DashboardModules.CLIENTS, DashboardModules.INVOICES, DashboardModules.AGENTS, DashboardModules.CONVERSATIONS),
        createdAt = now,
        updatedAt = now,
    )

    private fun services() = AgentServices(
        mongo = mongo,
        whatsApp = { WhatsAppClient("token", "phone-1", maxRetries = 1, httpClient = graph) },
        clock = { now },
    )

    /** The templates that went to Meta, as sent. */
    private fun sentTemplates(): List<JsonObject> = requests
        .filter { (call, _) -> call.startsWith("POST") && call.endsWith("/messages") }
        .map { (_, body) -> Json.parseToJsonElement(body).jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == "template" }
        .map { it["template"]!!.jsonObject }

    private fun JsonObject.parameters(): List<JsonObject> =
        (this["components"] as? JsonArray)?.single()?.jsonObject?.get("parameters")?.jsonArray?.map { it.jsonObject }.orEmpty()

    private suspend fun invoiceFor(tenant: Tenant, phone: String): Pair<ObjectId, String> {
        val client = ClientRepository(mongo, tenant.id).create("Ana Ribeiro", phone)
        val invoice = InvoiceRepository(mongo, tenant.id).create(client.id, null, listOf(lineItem("Obra", unitPriceEur = 250.0)), LocalDate(2026, 10, 20))
        return invoice.id to invoice.number
    }

    private suspend fun context(tenant: Tenant, subject: SubjectRef, at: Instant = now): RunContext {
        val step = StepSpec("s1", "whatsapp.send")
        val built = AgentContextBuilder(mongo) { at }.build(tenant, subject)
        val run = AgentRun(
            tenantId = tenant.id,
            agentId = ObjectId(),
            agentName = "Lembretes",
            agentVersion = 1,
            definition = AgentDefinition(steps = listOf(step)),
            trigger = RunTrigger(type = "manual", firedAt = at),
            subject = subject,
            subjectLabel = built.label,
            dedupeKey = "k",
            createdAt = at,
            updatedAt = at,
        )
        return RunContext(tenant, run, step, built.variables, AgentSettings(tenant.id), services(), at, Autonomy.AUTO)
    }

    private fun prepared(input: JsonObject) = SchemaValidator.coerce(WhatsAppSendAction.inputSchema, Schema.withDefaults(WhatsAppSendAction.inputSchema, input))

    private fun templateStep(template: String, language: String, vararg params: String?) = buildJsonObject {
        put("text", "Olá Ana")
        put("template", template)
        put("templateLanguage", language)
        put("templateParams", buildJsonArray { params.forEach { add(it?.let(::JsonPrimitive) ?: JsonNull) } })
    }

    /** The step exactly as the builder saves it once a template is picked and its variables filled from the record. */
    private fun builderStep(firstVariable: String?) = Json.parseToJsonElement(
        """{"to":"client","text":"Olá {{client.firstName}}, a fatura {{invoice.number}} está por pagar.","fallback":"task",
            "template":"lembrete_fatura","templateLanguage":"pt_PT","templateParams":[${firstVariable?.let { "\"$it\"" } ?: "null"},"{{invoice.number}}"]}""",
    ).jsonObject

    private inner class Running(val tenant: Tenant, step: JsonObject) {
        val module = AgentsModule(mongo, AgentRegistry(listOf(WhatsAppSendAction), TriggerTypes.all), services())
        val runtime = AgentRuntime(module, { id -> tenant.takeIf { it.id == id } }, scope)
        private val definition = AgentDefinition(
            triggers = listOf(TriggerSpec("t1", TriggerTypes.EVENT, buildJsonObject { put("event", "invoice.created") })),
            steps = listOf(StepSpec("s1", "whatsapp.send", step)),
        )
        lateinit var agent: Agent

        suspend fun start(): Running {
            agent = module.agents.insert(Agent(tenantId = tenant.id, name = "Lembretes", status = AgentStatus.ACTIVE, definition = definition, createdAt = now, updatedAt = now))
            runtime.onAgentChanged(tenant, agent)
            return this
        }

        suspend fun awaitRun(done: (AgentRun) -> Boolean): AgentRun {
            repeat(100) {
                module.runs.list(tenant.id, agent.id).firstOrNull(done)?.let { return it }
                delay(50)
            }
            error("run never reached the expected state: ${module.runs.list(tenant.id, agent.id).map { it.status to it.steps.map { s -> s.status to s.error } }}")
        }
    }

    @Test
    fun `a step saved by the builder sends its template outside the window, filled from the record in order`() = runBlocking {
        val tenant = tenant()
        val running = Running(tenant, builderStep("{{client.firstName}}")).start()
        val (module, runtime, agent) = Triple(running.module, running.runtime, running.agent)
        val (_, number) = invoiceFor(tenant, "+351 912 000 001")
        runtime.dispatcher.drain()

        running.awaitRun { it.status == RunStatus.AWAITING_APPROVAL }
        val approval = module.approvals.list(tenant.id).single { it.agentId == agent.id }
        assertEquals("Olá Ana, a fatura $number está por pagar.", approval.preview.body, "the person approving reads what the customer will get")
        assertEquals(listOf("template_outside_window"), approval.preview.warnings)
        assertTrue(approval.preview.editable.isEmpty(), "an approved template can't be reworded")

        runtime.approve(tenant, approval.id, "u1", "Rui", null)
        running.awaitRun { it.status == RunStatus.SUCCEEDED }

        val sent = sentTemplates().single()
        assertEquals("lembrete_fatura", sent["name"]!!.jsonPrimitive.content)
        assertEquals("pt_PT", sent["language"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(listOf("Ana", number), sent.parameters().map { it["text"]!!.jsonPrimitive.content })
        val conversation = ConversationRepository(mongo, tenant.id).findByWaId("351912000001", Platform.WHATSAPP)!!
        val stored = MessageRepository(mongo, tenant.id).threadByConversation(conversation.id).last().content
        assertEquals("Olá Ana, a fatura $number está por pagar.", (stored as MessageContent.Template).body)
        assertEquals(ApprovalStatus.APPROVED, module.approvals.list(tenant.id, status = null).single { it.id == approval.id }.status)
    }

    @Test
    fun `a variable left empty in the builder keeps its place, is flagged before approval and never goes`() = runBlocking {
        val tenant = tenant()
        val running = Running(tenant, builderStep(null)).start()
        val (_, number) = invoiceFor(tenant, "+351 912 000 002")
        running.runtime.dispatcher.drain()

        running.awaitRun { it.status == RunStatus.AWAITING_APPROVAL }
        val approval = running.module.approvals.list(tenant.id).single { it.agentId == running.agent.id }
        assertEquals("Olá {{1}}, a fatura $number está por pagar.", approval.preview.body, "the invoice number stays in the second variable")
        assertEquals(listOf("template_params"), approval.preview.warnings)

        running.runtime.approve(tenant, approval.id, "u1", "Rui", null)
        val failed = running.awaitRun { it.status == RunStatus.FAILED }
        assertEquals("template_params", failed.steps.single().error)
        assertTrue(sentTemplates().isEmpty())
    }

    @Test
    fun `only an approved template that can be sent, in the step's language, goes`() = runBlocking {
        val tenant = tenant()
        val (invoiceId, number) = invoiceFor(tenant, "+351 912 000 003")
        val subject = SubjectRef.of(SubjectTypes.INVOICE, invoiceId)

        val english = WhatsAppSendAction.execute(prepared(templateStep("lembrete_fatura", "en_US", "Ana", number)), context(tenant, subject))
        assertEquals("template", (english as ActionResult.Done).note)
        assertEquals("en_US", sentTemplates().single()["language"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        for ((name, language) in listOf("em_revisao" to "pt_PT", "promo_foto" to "pt_PT", "lembrete_fatura" to "es_ES", "apagado" to "pt_PT")) {
            val input = prepared(templateStep(name, language, "Ana", number))
            val ctx = context(tenant, subject)
            assertEquals(ActionResult.Failed("template_not_found"), WhatsAppSendAction.execute(input, ctx), "$name · $language")
            val preview = WhatsAppSendAction.preview(input, ctx)
            assertEquals(listOf("template_not_found"), preview.warnings, "$name · $language")
            assertEquals("Olá Ana", preview.body, "without a template to show, the preview keeps the step's text")
        }
        assertEquals(1, sentTemplates().size, "nothing else went to Meta")
    }

    @Test
    fun `named variables go by name, and a value WhatsApp won't take stops the send`() = runBlocking {
        val tenant = tenant()
        val (invoiceId, number) = invoiceFor(tenant, "+351 912 000 004")
        val subject = SubjectRef.of(SubjectTypes.INVOICE, invoiceId)

        val named = WhatsAppSendAction.execute(prepared(templateStep("ola_cliente", "pt_PT", " Ana ")), context(tenant, subject))
        assertIs<ActionResult.Done>(named)
        val parameter = sentTemplates().single().parameters().single()
        assertEquals("nome", parameter["parameter_name"]!!.jsonPrimitive.content)
        assertEquals("Ana", parameter["text"]!!.jsonPrimitive.content, "values are trimmed")

        val tooLong = WhatsAppSendAction.execute(prepared(templateStep("lembrete_fatura", "pt_PT", "a".repeat(1025), number)), context(tenant, subject))
        assertEquals(ActionResult.Failed("template_params"), tooLong)
        val missing = WhatsAppSendAction.execute(prepared(templateStep("lembrete_fatura", "pt_PT", "Ana")), context(tenant, subject))
        assertEquals(ActionResult.Failed("template_params"), missing, "a template with two variables needs two values")
        assertEquals(1, sentTemplates().size)
    }

    @Test
    fun `the template goes only to a known number outside the window`() = runBlocking {
        val tenant = tenant()
        val (invoiceId, number) = invoiceFor(tenant, "+351 912 000 005")
        val subject = SubjectRef.of(SubjectTypes.INVOICE, invoiceId)
        val input = prepared(templateStep("lembrete_fatura", "pt_PT", "Ana", number))

        val user = UserRepository(mongo, tenant.id).findOrCreate("351912000005", "Ana", Platform.WHATSAPP)
        val conversations = ConversationRepository(mongo, tenant.id)
        conversations.recordInbound(conversations.findOrCreate(user.id, "351912000005", Platform.WHATSAPP).id, now - 1.hours)
        val inside = WhatsAppSendAction.preview(input, context(tenant, subject))
        assertEquals("Olá Ana", inside.body)
        assertEquals(listOf("text"), inside.editable)
        assertTrue(inside.warnings.isEmpty())
        assertIs<ActionResult.Done>(WhatsAppSendAction.execute(input, context(tenant, subject)))
        assertTrue(sentTemplates().isEmpty(), "inside the window the step's own text goes")

        val (noPhoneInvoice, _) = invoiceFor(tenant, "")
        val noPhone = WhatsAppSendAction.preview(input, context(tenant, SubjectRef.of(SubjectTypes.INVOICE, noPhoneInvoice)))
        assertEquals("Olá Ana", noPhone.body, "the fallback gets the step's text, not the template")
        assertEquals(listOf("no_phone"), noPhone.warnings)
        assertEquals(listOf("text"), noPhone.editable)
    }

    @Test
    fun `the company's templates are fetched once every five minutes`() = runBlocking {
        val tenant = tenant()
        val (invoiceId, number) = invoiceFor(tenant, "+351 912 000 006")
        val subject = SubjectRef.of(SubjectTypes.INVOICE, invoiceId)
        val input = prepared(templateStep("lembrete_fatura", "pt_PT", "Ana", number))

        WhatsAppSendAction.preview(input, context(tenant, subject))
        WhatsAppSendAction.execute(input, context(tenant, subject, now + 1.minutes))
        assertEquals(1, templateLists.get())
        WhatsAppSendAction.execute(input, context(tenant, subject, now + 6.minutes))
        assertEquals(2, templateLists.get())
        assertEquals(2, sentTemplates().size)
    }
}
