package com.rfm.edubot.agents.briefings

import com.mongodb.client.model.Filters
import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.actions.AgentMessaging
import com.rfm.edubot.agents.actions.WhatsAppSendAction
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.AgentStatus
import com.rfm.edubot.agents.model.ApprovalStatus
import com.rfm.edubot.agents.model.CompanyAgentSettings
import com.rfm.edubot.agents.model.RunStatus
import com.rfm.edubot.agents.registry.AgentRegistry
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.agents.runtime.AgentRuntime
import com.rfm.edubot.agents.runtime.AgentServices
import com.rfm.edubot.agents.templates.AgentTemplates
import com.rfm.edubot.agents.templates.TemplateBuild
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.DeliveryStatusRecorder
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tags.RecordTagRepository
import com.rfm.edubot.tags.TagKind
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.timesheets.PlannedShiftRepository
import com.rfm.edubot.timesheets.model.PlannedShift
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Briefings page's service over a real agents runtime, with WhatsApp's Graph API mocked: who gets what today,
 * sending now (and the schedule not sending it twice), sending again under the team's own cap, approving, and an
 * approval that can't outlive its day.
 */
class BriefingServiceTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("agent_briefings")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }

        private val TEMPLATES = """{"data":[
            {"name":"resumo_turnos","language":"pt_PT","status":"APPROVED","category":"UTILITY","components":[{"type":"BODY","text":"Olá {{1}}, hoje trabalha: {{2}}."}]}
        ]}"""

        private val TODAY = LocalDate(2026, 10, 1)
    }

    /** Thursday 1 October 2026, 07:30 UTC: 08:30 in Lisbon, after quiet hours and before the 10:00 briefing. */
    @Volatile private var now: Instant = Instant.parse("2026-10-01T07:30:00Z")
    private val requests: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private lateinit var scope: CoroutineScope

    private val graph = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                requests += "${request.method.value} ${request.url.encodedPath}" to (request.body as? TextContent)?.text.orEmpty()
                if (request.method == HttpMethod.Get && request.url.toString().contains("message_templates")) {
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

    /** The template parameters each WhatsApp template send carried, in order. */
    private fun sentTemplates(): List<List<String>> = requests
        .filter { (call, _) -> call.startsWith("POST") && call.endsWith("/messages") }
        .map { (_, body) -> Json.parseToJsonElement(body).jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == "template" }
        .map { message ->
            val template = message["template"]!!.jsonObject
            (template["components"] as JsonArray).single().jsonObject["parameters"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        }

    private inner class Company {
        val tenant = Tenant(
            slug = "brief-${ObjectId().toHexString().takeLast(6)}",
            name = "Limpezas Sol",
            channels = listOf(ChannelBinding(Platform.WHATSAPP, "phone-1", "token", wabaId = "waba-1")),
            enabledModules = listOf(DashboardModules.AGENTS, DashboardModules.EMPLOYEES, DashboardModules.TIMESHEETS, DashboardModules.CONVERSATIONS),
            createdAt = now,
            updatedAt = now,
        )
        val module = AgentsModule(
            mongo,
            AgentRegistry(listOf(WhatsAppSendAction), TriggerTypes.all),
            AgentServices(mongo, whatsApp = { WhatsAppClient("token", "phone-1", maxRetries = 1, httpClient = graph) }, clock = { now }),
        )
        val runtime = AgentRuntime(module, { id -> tenant.takeIf { it.id == id } }, scope)
        val briefings = BriefingService(module, runtime)
        lateinit var agent: Agent

        /** The daily shift briefing at 10:00 with its template picked, on. */
        suspend fun briefing(autonomy: String = "AUTO"): Company {
            val params = buildJsonObject { put("time", "10:00"); put("autonomy", autonomy) }
            val built = assertIs<TemplateBuild.Built>(AgentTemplates.build("daily_shift_briefing", params, "pt-PT", CompanyAgentSettings())).agent
            val definition = built.definition.copy(
                steps = built.definition.steps.map { step ->
                    step.copy(input = JsonObject(step.input + mapOf("template" to JsonPrimitive("resumo_turnos"), "templateLanguage" to JsonPrimitive("pt_PT"))))
                },
            )
            val inserted = module.agents.insert(
                Agent(tenantId = tenant.id, name = built.name, templateKey = "daily_shift_briefing", templateParams = built.params, status = AgentStatus.ACTIVE, definition = definition, createdAt = now, updatedAt = now),
            )
            runtime.onAgentChanged(tenant, inserted)
            agent = module.agents.findById(tenant.id, inserted.id)!!
            return this
        }

        suspend fun employee(name: String, phone: String, site: String? = null, tags: List<String> = emptyList()): Employee {
            val employee = EmployeeRepository(mongo, tenant.id).create(name, phone)
            if (site != null) {
                PlannedShiftRepository(mongo, tenant.id).insert(
                    PlannedShift(
                        tenantId = tenant.id, employeeId = employee.id, day = TODAY,
                        startAt = Instant.parse("2026-10-01T07:00:00Z"), endAt = Instant.parse("2026-10-01T15:00:00Z"), siteName = site,
                        createdBy = "test", createdAt = now, updatedAt = now,
                    ),
                )
            }
            if (tags.isNotEmpty()) RecordTagRepository(mongo, tenant.id).set(TagKind.EMPLOYEE, employee.id.toHexString(), tags)
            return employee
        }

        suspend fun runsOf(employee: Employee): List<AgentRun> =
            module.runs.list(tenant.id, agent.id, subject = SubjectRef.of(SubjectTypes.EMPLOYEE, employee.id))

        suspend fun awaitRuns(employee: Employee, done: (List<AgentRun>) -> Boolean): List<AgentRun> {
            repeat(100) {
                val runs = runsOf(employee)
                if (done(runs)) return runs
                delay(50)
            }
            error("runs never reached the expected state: ${runsOf(employee).map { it.status to it.steps.map { s -> s.status to (s.note ?: s.error) } }}")
        }

        suspend fun day(day: LocalDate = TODAY): BriefingDayDto = briefings.day(tenant, module.agents.findById(tenant.id, agent.id)!!, day, canManage = true)

        suspend fun person(employee: Employee): BriefingPersonDto = day().people.single { it.employeeId == employee.id.toHexString() }

        suspend fun send(vararg employees: Employee, again: Boolean = false): BriefingSendDto =
            briefings.send(tenant, agent, employees.map { it.id.toHexString() }.ifEmpty { null }, again, "u1")
    }

    @Test
    fun `today's rows say who gets what and why the others don't, without storing anything`() = runBlocking {
        val company = Company().briefing()
        val ana = company.employee("Ana Costa", "+351 912 000 001", site = "Obra A")
        val rui = company.employee("Rui Lopes", "+351 912 000 002")
        val eva = company.employee("Eva Dias", "+351 912 000 003", site = "Obra B", tags = listOf("No-Briefing"))
        val teo = company.employee("Teo Sá", "", site = "Obra C")

        val day = company.day()

        assertEquals("2026-10-01T09:00:00Z", day.scheduledAt, "10:00 in Lisbon")
        val byId = day.people.associateBy { it.employeeId }
        val anaRow = byId.getValue(ana.id.toHexString())
        assertEquals(BriefingStates.SCHEDULED, anaRow.state)
        assertEquals("Olá Ana, hoje trabalha: 08:00-16:00 Obra A.", anaRow.message, "the approved template with her day in it")
        assertEquals("2026-10-01T09:00:00Z", anaRow.at)
        assertEquals(BriefingStates.NOTHING_TODAY, byId.getValue(rui.id.toHexString()).state)
        assertEquals(BriefingStates.OPTED_OUT, byId.getValue(eva.id.toHexString()).state)
        assertEquals(BriefingStates.NO_PHONE, byId.getValue(teo.id.toHexString()).state)
        assertTrue(company.module.runs.list(company.tenant.id, company.agent.id).isEmpty(), "a preview stores no run")
        assertTrue(sentTemplates().isEmpty(), "and sends nothing")
    }

    @Test
    fun `sending now goes as the template, keeps the employee's chat from the bot, and the schedule doesn't send it twice`() = runBlocking {
        val company = Company().briefing()
        val ana = company.employee("Ana Costa", "+351 912 000 011", site = "Obra A")

        val sent = company.send()

        assertEquals(1, sent.started)
        company.awaitRuns(ana) { runs -> runs.singleOrNull()?.status == RunStatus.SUCCEEDED }
        assertEquals(listOf(listOf("Ana", "08:00-16:00 Obra A")), sentTemplates())
        val chat = ConversationRepository(mongo, company.tenant.id).findByWaId("351912000011", Platform.WHATSAPP)!!
        assertFalse(chat.autoReplyEnabled, "the bot doesn't answer a member of the team")
        assertEquals(Conversation.TEAM_MEMBER, chat.autoReplyPausedBy)
        val contactsAnnounced = mongo.database.getCollection<Document>(DomainEventLog.COLLECTION)
            .countDocuments(Filters.and(Filters.eq("tenantId", company.tenant.id), Filters.eq("type", DomainEventTypes.CONTACT_CREATED)))
        assertEquals(0L, contactsAnnounced, "an employee isn't announced as a new lead")

        val row = company.person(ana)
        assertEquals(BriefingStates.SENT, row.state)
        assertEquals("Olá Ana, hoje trabalha: 08:00-16:00 Obra A.", row.message)
        val messageId = company.runsOf(ana).single().steps.single().output!!["messageId"]!!.jsonPrimitive.content
        DeliveryStatusRecorder(mongo).record(company.tenant.id, messageId, "read")
        assertEquals(BriefingStates.READ, company.person(ana).state)
        val listed = company.briefings.list(company.tenant).single()
        assertEquals(1, listed.today.sent)
        assertEquals(1, listed.today.read)

        now = Instant.parse("2026-10-01T09:00:30Z")
        company.runtime.scheduler.tick()
        delay(300)
        assertEquals(1, company.runsOf(ana).size, "the 10:00 fire finds today's message already started")
        assertEquals(1, sentTemplates().size)
    }

    @Test
    fun `again sends once more, and a member of the team's own cap stops a fourth message in a day`() = runBlocking {
        val company = Company().briefing()
        val ana = company.employee("Ana Costa", "+351 912 000 021", site = "Obra A")

        company.send()
        company.awaitRuns(ana) { runs -> runs.size == 1 && runs.all { it.status == RunStatus.SUCCEEDED } }
        assertEquals("already_sent", company.send(ana).skipped.single().reason, "without again, once a day")
        repeat(3) { n ->
            assertEquals(1, company.send(ana, again = true).started)
            company.awaitRuns(ana) { runs -> runs.size == n + 2 && runs.all { it.status == RunStatus.SUCCEEDED } }
        }

        assertEquals(AgentMessaging.TEAM_DAILY_CAP, sentTemplates().size, "the company's caps for customers (2 a day) don't apply, the team's 3 do")
        val row = company.person(ana)
        assertEquals(BriefingStates.SKIPPED, row.state)
        assertEquals("recipient_cap", row.detail)
        assertEquals(4, row.sends)
    }

    @Test
    fun `a briefing that asks first waits for approval until the end of its day, and approving sends it`() = runBlocking {
        val company = Company().briefing(autonomy = "APPROVE")
        val ana = company.employee("Ana Costa", "+351 912 000 031", site = "Obra A")

        company.send()
        company.awaitRuns(ana) { runs -> runs.singleOrNull()?.status == RunStatus.AWAITING_APPROVAL }

        val row = company.person(ana)
        assertEquals(BriefingStates.AWAITING_APPROVAL, row.state)
        assertNotNull(row.approvalId)
        assertEquals("Olá Ana, hoje trabalha: 08:00-16:00 Obra A.", row.message)
        val approval = company.module.approvals.list(company.tenant.id, ApprovalStatus.PENDING, company.agent.id).single()
        assertEquals(Instant.parse("2026-10-01T23:00:00Z"), approval.expiresAt, "midnight in Lisbon, not three days later")
        assertTrue(company.briefings.list(company.tenant).single().asksFirst, "the page says a send starts approvals")
        assertEquals(1, company.briefings.list(company.tenant).single().today.approval)

        assertEquals(1, company.briefings.approve(company.tenant, company.agent, null, { true }, "u1", "Rui"))
        company.awaitRuns(ana) { runs -> runs.single().status == RunStatus.SUCCEEDED }
        assertEquals(listOf(listOf("Ana", "08:00-16:00 Obra A")), sentTemplates())
    }

    @Test
    fun `an approval left to the next day can't send yesterday's day as today's`() = runBlocking {
        val company = Company().briefing(autonomy = "APPROVE")
        val ana = company.employee("Ana Costa", "+351 912 000 041", site = "Obra A")
        // She works the next day too, so the run would still have something to send then: yesterday's stored text.
        PlannedShiftRepository(mongo, company.tenant.id).insert(
            PlannedShift(
                tenantId = company.tenant.id, employeeId = ana.id, day = LocalDate(2026, 10, 2),
                startAt = Instant.parse("2026-10-02T12:00:00Z"), endAt = Instant.parse("2026-10-02T18:00:00Z"), siteName = "Obra Z",
                createdBy = "test", createdAt = now, updatedAt = now,
            ),
        )
        company.send()
        company.awaitRuns(ana) { runs -> runs.singleOrNull()?.status == RunStatus.AWAITING_APPROVAL }
        val approval = company.module.approvals.list(company.tenant.id, ApprovalStatus.PENDING, company.agent.id).single()

        now = Instant.parse("2026-10-02T06:30:00Z")
        assertNull(company.runtime.approve(company.tenant, approval.id, "u1", "Rui", null), "past its day, even before the sweep marks it")
        company.runtime.scheduler.tick()
        company.awaitRuns(ana) { runs -> runs.any { it.status == RunStatus.CANCELLED && it.outcome == "approval_expired" } }

        assertEquals(ApprovalStatus.EXPIRED, company.module.approvals.list(company.tenant.id, status = null, agentId = company.agent.id).single().status)
        assertTrue(sentTemplates().isEmpty(), "Monday's shifts never go out on Tuesday")
    }

    @Test
    fun `people asked for by name hear why they can't get it`() = runBlocking {
        val company = Company().briefing()
        val eva = company.employee("Eva Dias", "+351 912 000 051", site = "Obra B", tags = listOf("no-briefing"))
        val teo = company.employee("Teo Sá", "", site = "Obra C")
        val rui = company.employee("Rui Lopes", "+351 912 000 052")
        val gone = ObjectId().toHexString()

        val sent = company.briefings.send(company.tenant, company.agent, listOf(eva.id.toHexString(), teo.id.toHexString(), rui.id.toHexString(), gone), again = false, userId = "u1")

        assertEquals(0, sent.started)
        assertEquals(
            mapOf(eva.id.toHexString() to "opted_out", teo.id.toHexString() to "no_phone", rui.id.toHexString() to "nothing_today", gone to "not_found"),
            sent.skipped.associate { it.employeeId to it.reason },
        )
        assertTrue(company.runsOf(rui).isEmpty(), "no run for someone whose day has nothing for it")
        assertTrue(sentTemplates().isEmpty())
    }

    @Test
    fun `a briefing that only drafts shows the draft, never a sent message`() = runBlocking {
        val company = Company().briefing(autonomy = "DRAFT")
        val ana = company.employee("Ana Costa", "+351 912 000 071", site = "Obra A")

        company.send()
        company.awaitRuns(ana) { runs -> runs.singleOrNull()?.status == RunStatus.SUCCEEDED }

        val row = company.person(ana)
        assertEquals(BriefingStates.DRAFTED, row.state)
        assertEquals("Olá Ana, hoje trabalha: 08:00-16:00 Obra A.", row.message)
        assertTrue(sentTemplates().isEmpty())
        assertEquals(0, company.briefings.list(company.tenant).single().today.sent)
    }

    @Test
    fun `sending to everyone starts only the messages that go`() = runBlocking {
        val company = Company().briefing()
        val ana = company.employee("Ana Costa", "+351 912 000 061", site = "Obra A")
        company.employee("Rui Lopes", "+351 912 000 062")
        company.employee("Eva Dias", "+351 912 000 063", site = "Obra B", tags = listOf("no-briefing"))

        val sent = company.send()

        assertEquals(1, sent.started, "Rui has nothing today and Eva opted out")
        assertTrue(sent.skipped.isEmpty(), "everyone at once doesn't list the people it leaves out")
        company.awaitRuns(ana) { runs -> runs.singleOrNull()?.status == RunStatus.SUCCEEDED }
        assertEquals(1, company.module.runs.list(company.tenant.id, company.agent.id).size)
    }
}
