package com.rfm.edubot.agents.actions

import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.AgentSettings
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.model.OutboundStatus
import com.rfm.edubot.agents.model.RunTrigger
import com.rfm.edubot.agents.model.StepSpec
import com.rfm.edubot.agents.model.TaskStatus
import com.rfm.edubot.agents.registry.ActionResult
import com.rfm.edubot.agents.registry.AgentAction
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SchemaValidator
import com.rfm.edubot.agents.runtime.AgentContextBuilder
import com.rfm.edubot.agents.runtime.AgentServices
import com.rfm.edubot.agents.runtime.RunContext
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.MessageAuthor
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.lineItem
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.crm.model.QuoteStatus
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
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class AgentActionsTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("agent_actions")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    /** Thursday 1 October 2026, 10:00 UTC. */
    private val now = Instant.parse("2026-10-01T10:00:00Z")
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val graph = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val url = request.url.toString()
                requests += "${request.method.value} $url ${(request.body as? TextContent)?.text.orEmpty()}"
                when {
                    request.method == HttpMethod.Get && url.contains("message_templates") -> respond(
                        """{"data":[{"name":"lembrete_fatura","language":"pt_PT","status":"APPROVED","category":"UTILITY","components":[{"type":"BODY","text":"Olá {{1}}, a fatura {{2}} está por pagar."}]}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    else -> respond(
                        """{"contacts":[{"wa_id":"351911000111"}],"messages":[{"id":"wamid.${requests.size}"}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }
    }

    private fun tenant() = Tenant(
        slug = "t-${ObjectId().toHexString().takeLast(6)}",
        name = "Obras Silva",
        channels = listOf(ChannelBinding(Platform.WHATSAPP, "phone-1", "token", wabaId = "waba-1")),
        enabledModules = listOf(DashboardModules.CLIENTS, DashboardModules.QUOTES, DashboardModules.INVOICES, DashboardModules.SERVICES, DashboardModules.AGENTS),
        createdAt = now,
        updatedAt = now,
    )

    private fun services() = AgentServices(
        mongo = mongo,
        whatsApp = { WhatsAppClient("token", "phone-1", maxRetries = 1, httpClient = graph) },
        clock = { now },
    )

    private suspend fun context(tenant: Tenant, subject: SubjectRef?, step: StepSpec, services: AgentServices = services(), runId: ObjectId = ObjectId()): RunContext {
        val built = AgentContextBuilder(mongo) { now }.build(tenant, subject)
        val run = AgentRun(
            id = runId,
            tenantId = tenant.id,
            agentId = ObjectId(),
            agentName = "Lembretes",
            agentVersion = 1,
            definition = AgentDefinition(steps = listOf(step)),
            trigger = RunTrigger(type = "manual", firedAt = now),
            subject = subject,
            subjectLabel = built.label,
            dedupeKey = "k",
            createdAt = now,
            updatedAt = now,
        )
        return RunContext(tenant, run, step, built.variables, AgentSettings(tenant.id), services, now, Autonomy.AUTO)
    }

    private suspend fun execute(action: AgentAction, input: JsonObject, ctx: RunContext): ActionResult =
        action.execute(SchemaValidator.coerce(action.inputSchema, Schema.withDefaults(action.inputSchema, input)), ctx)

    private suspend fun invoiceFor(tenant: Tenant, phone: String = "+351 911 000 111"): Pair<ObjectId, ObjectId> {
        val client = ClientRepository(mongo, tenant.id).create("Ana Ribeiro", phone, email = "ana@example.pt")
        val invoice = InvoiceRepository(mongo, tenant.id).create(client.id, null, listOf(lineItem("Obra", unitPriceEur = 250.0)), LocalDate(2026, 9, 20))
        return client.id to invoice.id
    }

    private suspend fun openWindow(tenant: Tenant, waId: String) {
        val user = UserRepository(mongo, tenant.id).findOrCreate(waId, "Ana", Platform.WHATSAPP)
        val conversations = ConversationRepository(mongo, tenant.id)
        val conversation = conversations.findOrCreate(user.id, waId, Platform.WHATSAPP)
        conversations.recordInbound(conversation.id, now - 1.hours)
    }

    @Test
    fun `inside the window the message goes as text, joins the conversation and is never sent twice`() = runBlocking {
        val tenant = tenant()
        val (_, invoiceId) = invoiceFor(tenant)
        openWindow(tenant, "351911000111")
        val step = StepSpec("s1", "whatsapp.send")
        val ctx = context(tenant, SubjectRef.of(SubjectTypes.INVOICE, invoiceId), step)

        val first = execute(WhatsAppSendAction, buildJsonObject { put("text", "Olá Ana") }, ctx)
        val second = execute(WhatsAppSendAction, buildJsonObject { put("text", "Olá Ana") }, ctx)

        assertIs<ActionResult.Done>(first)
        assertEquals("already_sent", (second as ActionResult.Done).note)
        assertEquals(1, requests.count { it.contains("/messages") && it.contains("Olá Ana") })
        val conversation = ConversationRepository(mongo, tenant.id).findByWaId("351911000111", Platform.WHATSAPP)!!
        val stored = MessageRepository(mongo, tenant.id).threadByConversation(conversation.id).last()
        assertEquals(MessageAuthor.AUTOMATION, stored.author)
        assertEquals("Lembretes", stored.agentName)
        assertTrue(stored.origin!!.startsWith("agent:"))
        assertEquals(OutboundStatus.SENT, ctx.services.outboundLog.find(ctx.idempotencyKey)?.status)
    }

    @Test
    fun `outside the window a mapped template goes, otherwise a person gets a task`() = runBlocking {
        val tenant = tenant()
        val (_, invoiceId) = invoiceFor(tenant, "+351 922 000 222")
        val subject = SubjectRef.of(SubjectTypes.INVOICE, invoiceId)

        val withTemplate = execute(
            WhatsAppSendAction,
            buildJsonObject {
                put("text", "Olá")
                put("template", "lembrete_fatura")
                put("templateLanguage", "pt_PT")
                put("templateParams", buildJsonArray { add(JsonPrimitive("Ana")); add(JsonPrimitive("FAT-001")) })
            },
            context(tenant, subject, StepSpec("s1", "whatsapp.send")),
        )
        assertEquals("template", (withTemplate as ActionResult.Done).note)
        assertTrue(requests.any { it.contains("\"type\":\"template\"") && it.contains("lembrete_fatura") })
        val conversation = ConversationRepository(mongo, tenant.id).findByWaId("351922000222", Platform.WHATSAPP)!!
        val stored = MessageRepository(mongo, tenant.id).threadByConversation(conversation.id).last().content
        assertEquals("Olá Ana, a fatura FAT-001 está por pagar.", (stored as MessageContent.Template).body)

        val ctx = context(tenant, subject, StepSpec("s2", "whatsapp.send"))
        val fallback = execute(WhatsAppSendAction, buildJsonObject { put("text", "Lembrete da fatura") }, ctx)
        assertTrue((fallback as ActionResult.Done).note!!.startsWith("fallback_task"))
        val task = ctx.services.tasks.list(tenant.id).single()
        assertEquals("Contactar Ana Ribeiro", task.title)
        assertTrue(task.detail!!.contains("Lembrete da fatura"))
        assertEquals(TaskStatus.OPEN, task.status)
    }

    @Test
    fun `team notices and tasks land in the dashboard`() = runBlocking {
        val tenant = tenant()
        val (_, invoiceId) = invoiceFor(tenant)
        val ctx = context(tenant, SubjectRef.of(SubjectTypes.INVOICE, invoiceId), StepSpec("s1", "team.notify"))

        execute(NotifyTeamAction, buildJsonObject { put("message", "A fatura venceu") }, ctx)
        execute(CreateTaskAction, buildJsonObject { put("title", "Ligar ao cliente"); put("dueInDays", 2) }, ctx)

        val notices = ctx.services.notifications.listFor(tenant.id, "admin", isAdmin = true)
        assertEquals(listOf("A fatura venceu"), notices.mapNotNull { it.body })
        assertEquals("Ligar ao cliente", ctx.services.tasks.list(tenant.id).single().title)
    }

    @Test
    fun `a quote becomes a deposit invoice once, and open services become one invoice`(): Unit = runBlocking {
        val tenant = tenant()
        val client = ClientRepository(mongo, tenant.id).create("Rui", "+351 933 000 333")
        val quotes = QuoteRepository(mongo, tenant.id)
        val quote = quotes.create(client.id, listOf(lineItem("Telhado", unitPriceEur = 1_000.0)), null, null)
        val quoteCtx = context(tenant, SubjectRef.of(SubjectTypes.QUOTE, quote.id), StepSpec("s1", "crm.invoice.from_quote"))

        val first = execute(InvoiceFromQuoteAction, buildJsonObject { put("depositPercent", 30) }, quoteCtx) as ActionResult.Done
        val again = execute(InvoiceFromQuoteAction, buildJsonObject { put("depositPercent", 30) }, quoteCtx) as ActionResult.Done
        assertEquals(30_000L, first.output["totalCents"]?.jsonPrimitive?.content?.toLong())
        assertEquals("already_invoiced", again.note)
        assertEquals(QuoteStatus.ACEITO, quotes.findById(quote.id)!!.status)

        val rows = ClientServiceRepository(mongo, tenant.id)
        repeat(2) { rows.create(client.id, "Visita", null, 1.0, "", 5_000, null, null, null) }
        val clientCtx = context(tenant, SubjectRef.of(SubjectTypes.CLIENT, client.id), StepSpec("s2", "crm.invoice.from_open_services"))
        val billed = execute(InvoiceOpenServicesAction, JsonObject(emptyMap()), clientCtx) as ActionResult.Done
        assertEquals(2, billed.output["rows"]?.jsonPrimitive?.content?.toInt())
        assertTrue(rows.list(client.id).all { it.status == ClientServiceStatus.INVOICED })
        assertIs<ActionResult.Skipped>(execute(InvoiceOpenServicesAction, JsonObject(emptyMap()), clientCtx))
    }

    @Test
    fun `digests read the company's data in its language`() = runBlocking {
        val tenant = tenant()
        invoiceFor(tenant)
        val ctx = context(tenant, null, StepSpec("s1", "data.summary"))

        val digest = execute(SummaryAction, buildJsonObject { put("kind", "receivables_overdue") }, ctx) as ActionResult.Done

        val text = digest.output["text"]!!.jsonPrimitive.content
        assertTrue(text.startsWith("Faturas em atraso"), text)
        assertTrue(text.contains("Ana Ribeiro") && text.contains("vence a"), text)
    }

    @Test
    fun `waits land on a business-day morning and branches jump or stop`(): Unit = runBlocking {
        val tenant = tenant()
        val ctx = context(tenant, null, StepSpec("s1", "flow.wait"))
        // Thursday + 2 days = Saturday → Monday 09:00 Lisbon (08:00 UTC).
        val wait = execute(WaitAction, buildJsonObject { put("days", 2); put("at", "09:00"); put("businessDay", true) }, ctx) as ActionResult.Wait
        assertEquals(Instant.parse("2026-10-05T08:00:00Z"), wait.until)

        val branch = buildJsonObject {
            putJsonObject("conditions") {
                put("match", "ALL")
                putJsonArray("conditions") { add(buildJsonObject { put("field", "company.name"); put("op", "eq"); put("value", "Obras Silva") }) }
            }
            put("thenGoTo", "s9")
            put("elseGoTo", "end")
        }
        assertEquals(ActionResult.Jump("s9"), execute(BranchAction, branch, ctx))
        val other = JsonObject(branch + ("thenGoTo" to JsonPrimitive("end")))
        assertIs<ActionResult.Stop>(execute(BranchAction, other, ctx))
    }
}
