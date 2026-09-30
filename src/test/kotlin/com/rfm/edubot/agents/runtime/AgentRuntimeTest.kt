package com.rfm.edubot.agents.runtime

import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentPolicy
import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.AgentStatus
import com.rfm.edubot.agents.model.ApprovalStatus
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.model.Condition
import com.rfm.edubot.agents.model.ConditionGroup
import com.rfm.edubot.agents.model.ExitRule
import com.rfm.edubot.agents.model.OutboundLogEntry
import com.rfm.edubot.agents.model.OutboundStatus
import com.rfm.edubot.agents.model.RunStatus
import com.rfm.edubot.agents.model.RunTrigger
import com.rfm.edubot.agents.model.StepResult
import com.rfm.edubot.agents.model.StepSpec
import com.rfm.edubot.agents.model.StepStatus
import com.rfm.edubot.agents.model.TriggerSpec
import com.rfm.edubot.agents.registry.ActionCategory
import com.rfm.edubot.agents.registry.ActionResult
import com.rfm.edubot.agents.registry.AgentAction
import com.rfm.edubot.agents.registry.AgentRegistry
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SideEffect
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.agents.registry.bool
import com.rfm.edubot.agents.registry.int
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.lineItem
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.integrations.email.EmailDirection
import com.rfm.edubot.integrations.email.EmailMessage
import com.rfm.edubot.integrations.email.EmailMessageRepository
import com.rfm.edubot.notifications.NotificationKinds
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class AgentRuntimeTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("agent_runtime")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    /** Thursday 1 October 2026, 10:00 UTC (11:00 in Lisbon): outside quiet hours. */
    @Volatile private var now: Instant = Instant.parse("2026-10-01T10:00:00Z")
    private val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val noted: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var scope: CoroutineScope
    private lateinit var module: AgentsModule
    private lateinit var runtime: AgentRuntime
    private lateinit var tenant: Tenant

    private inner class Send : AgentAction {
        override val key = "test.send"
        override val category = ActionCategory.MESSAGE
        override val sideEffect = SideEffect.EXTERNAL_MESSAGE
        override val inputSchema = Schema.obj("text" to Schema.string())
        override val toolDescription = key
        override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(kind = "message", body = input.string("text"), editable = listOf("text"))
        override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
            sent += input.string("text").orEmpty()
            return ActionResult.Done(buildJsonObject { put("sent", true) })
        }
    }

    private inner class Note : AgentAction {
        override val key = "test.note"
        override val category = ActionCategory.TEAM
        override val sideEffect = SideEffect.INTERNAL_WRITE
        override val inputSchema = Schema.obj("text" to Schema.string())
        override val toolDescription = key
        override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(kind = "task", body = input.string("text"))
        override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
            noted += input.string("text").orEmpty()
            return ActionResult.Done(buildJsonObject { put("noted", input.string("text").orEmpty()) })
        }
    }

    private inner class Wait : AgentAction {
        override val key = "test.wait"
        override val category = ActionCategory.FLOW
        override val sideEffect = SideEffect.NONE
        override val inputSchema = Schema.obj("days" to Schema.integer())
        override val toolDescription = key
        override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(kind = "generic")
        override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult = ActionResult.Wait(ctx.now + (input.int("days") ?: 1).days)
    }

    private inner class Fail : AgentAction {
        override val key = "test.fail"
        override val category = ActionCategory.CRM
        override val sideEffect = SideEffect.INTERNAL_WRITE
        override val inputSchema = Schema.obj("retryable" to Schema.boolean())
        override val toolDescription = key
        override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(kind = "generic")
        override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult = ActionResult.Failed("boom", retryable = input.bool("retryable") == true)
    }

    @BeforeEach
    fun start() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        tenant = Tenant(
            slug = "obras-${ObjectId().toHexString().takeLast(6)}",
            name = "Obras Silva",
            channels = emptyList(),
            enabledModules = listOf(DashboardModules.CLIENTS, DashboardModules.INVOICES, DashboardModules.AGENTS),
            createdAt = now,
            updatedAt = now,
        )
        val services = AgentServices(mongo, clock = { now })
        module = AgentsModule(mongo, AgentRegistry(listOf(Send(), Note(), Wait(), Fail()), TriggerTypes.all), services)
        runtime = AgentRuntime(module, { id -> tenant.takeIf { it.id == id } }, scope)
    }

    @AfterEach
    fun stop() {
        scope.cancel()
    }

    private fun step(id: String, action: String, input: JsonObject = JsonObject(emptyMap()), autonomy: Autonomy? = null) = StepSpec(id, action, input, autonomy = autonomy)

    private fun text(value: String) = buildJsonObject { put("text", value) }

    private suspend fun agent(definition: AgentDefinition, status: AgentStatus = AgentStatus.ACTIVE): Agent {
        val agent = module.agents.insert(Agent(tenantId = tenant.id, name = "Reminders", status = status, definition = definition, createdAt = now, updatedAt = now))
        runtime.onAgentChanged(tenant, agent)
        return agent
    }

    private val onInvoice = TriggerSpec("t1", TriggerTypes.EVENT, buildJsonObject { put("event", "invoice.created") })

    private suspend fun invoice(totalEur: Double = 100.0): ObjectId {
        val client = ClientRepository(mongo, tenant.id).create("Ana Ribeiro", "+351 91${(1000000..9999999).random()}")
        return InvoiceRepository(mongo, tenant.id).create(client.id, null, listOf(lineItem("Obra", unitPriceEur = totalEur)), LocalDate(2026, 10, 20)).id
    }

    private suspend fun runsFor(agent: Agent): List<AgentRun> = module.runs.list(tenant.id, agent.id)

    private suspend fun awaitRun(agent: Agent, done: (AgentRun) -> Boolean): AgentRun {
        repeat(100) {
            runsFor(agent).firstOrNull(done)?.let { return it }
            delay(50)
        }
        error("run never reached the expected state: ${runsFor(agent).map { it.status to it.steps.map { s -> s.status } }}")
    }

    private suspend fun eventually(check: suspend () -> Boolean) {
        repeat(100) {
            if (check()) return
            delay(50)
        }
        error("condition never became true")
    }

    @Test
    fun `an event starts one run that goes through its steps`() = runBlocking {
        val agent = agent(AgentDefinition(listOf(onInvoice), steps = listOf(step("s1", "test.note", text("Fatura {{invoice.number}} de {{client.firstName}}")), step("s2", "test.send", text("Olá {{client.firstName}}"))), policy = AgentPolicy(autonomy = Autonomy.AUTO)))
        val invoiceId = invoice()

        runtime.dispatcher.drain()
        val run = awaitRun(agent) { it.status == RunStatus.SUCCEEDED }

        assertEquals(listOf("Olá Ana"), sent)
        assertTrue(noted.single().startsWith("Fatura FAT-"))
        assertEquals(SubjectRef.of(SubjectTypes.INVOICE, invoiceId), run.subject)
        assertEquals(InvoiceRepository(mongo, tenant.id).findById(invoiceId)!!.clientId, run.clientId, "the run knows whose invoice it is")
        val event = com.rfm.edubot.events.DomainEventLog(mongo).findById(tenant.id, ObjectId(run.trigger.eventId))!!
        runtime.dispatcher.handle(event)
        assertEquals(1, runsFor(agent).size, "the same event never starts a second run")
        assertEquals(1L, module.agents.findById(tenant.id, agent.id)!!.stats.succeeded)
    }

    @Test
    fun `a wait holds the run until the scheduler resumes it, and an exit rule ends it`() = runBlocking {
        val definition = AgentDefinition(
            listOf(onInvoice),
            steps = listOf(step("s1", "test.wait", buildJsonObject { put("days", 3) }), step("s2", "test.send", text("Lembrete"))),
            exitRules = listOf(ExitRule("invoice.paid")),
            policy = AgentPolicy(autonomy = Autonomy.AUTO),
        )
        val agent = agent(definition)
        invoice()
        runtime.dispatcher.drain()
        val waiting = awaitRun(agent) { it.status == RunStatus.WAITING }
        assertEquals(now + 3.days, waiting.resumeAt)

        now += 3.days + 1.minutes
        runtime.scheduler.tick()
        awaitRun(agent) { it.status == RunStatus.SUCCEEDED }
        assertEquals(listOf("Lembrete"), sent)

        val second = invoice()
        runtime.dispatcher.drain()
        eventually { runsFor(agent).count { it.status == RunStatus.WAITING } == 1 }
        InvoiceRepository(mongo, tenant.id).markPaid(second)
        runtime.dispatcher.drain()
        val ended = runsFor(agent).first { it.subject?.id == second.toHexString() }
        assertEquals(RunStatus.CANCELLED, ended.status)
        assertEquals("exit:invoice.paid", ended.outcome)
    }

    @Test
    fun `a step that needs approval waits for a person, who can edit it or say no`() = runBlocking {
        val agent = agent(AgentDefinition(listOf(onInvoice), steps = listOf(step("s1", "test.send", text("Olá {{client.firstName}}")))))
        invoice()
        runtime.dispatcher.drain()
        awaitRun(agent) { it.status == RunStatus.AWAITING_APPROVAL }
        val approval = module.approvals.list(tenant.id).single { it.agentId == agent.id }
        assertEquals("Olá Ana", approval.preview.body)
        assertTrue(
            module.notifications.listFor(tenant.id, "admin", isAdmin = true).any { it.kind == NotificationKinds.AGENT_APPROVAL && it.ref == "approval:${approval.id.toHexString()}" },
            "one proposal opens straight from the bell",
        )

        runtime.approve(tenant, approval.id, "u1", "Rui", buildJsonObject { put("text", "Olá Ana, tudo bem?") })
        awaitRun(agent) { it.status == RunStatus.SUCCEEDED }
        assertEquals(listOf("Olá Ana, tudo bem?"), sent)

        invoice()
        runtime.dispatcher.drain()
        eventually { module.approvals.list(tenant.id).any { it.agentId == agent.id && it.status == ApprovalStatus.PENDING } }
        val next = module.approvals.list(tenant.id).single { it.agentId == agent.id && it.status == ApprovalStatus.PENDING }
        runtime.reject(tenant, next.id, "u1", "Rui", "not now")
        eventually { module.runs.load(next.runId)?.status == RunStatus.CANCELLED }
        assertEquals("rejected", module.runs.load(next.runId)?.outcome)
        assertEquals(1, sent.size)
    }

    @Test
    fun `conditions keep runs away, and a test run previews without acting`() = runBlocking {
        val definition = AgentDefinition(
            listOf(onInvoice),
            conditions = ConditionGroup(conditions = listOf(Condition("invoice.totalCents", "gt", JsonPrimitive(100_000)))),
            steps = listOf(step("s1", "test.wait", buildJsonObject { put("days", 2) }), step("s2", "test.send", text("Olá")), step("s3", "test.note", text("done"))),
            policy = AgentPolicy(autonomy = Autonomy.AUTO),
        )
        val agent = agent(definition)
        invoice(totalEur = 50.0)
        runtime.dispatcher.drain()
        delay(200)
        assertEquals(0, runsFor(agent).size)

        val big = invoice(totalEur = 5_000.0)
        val test = runtime.test(tenant, agent, SubjectRef.of(SubjectTypes.INVOICE, big))!!
        assertEquals(RunStatus.SUCCEEDED, test.status)
        assertEquals(listOf(StepStatus.DONE, StepStatus.DRAFTED, StepStatus.DRAFTED), test.steps.map { it.status })
        assertEquals("Olá", test.steps[1].output?.get("body")?.jsonPrimitive?.content)
        assertTrue(sent.isEmpty() && noted.isEmpty())
    }

    @Test
    fun `retryable failures back off, then the run fails and the agent is paused after repeated failures`() = runBlocking {
        val agent = agent(AgentDefinition(listOf(onInvoice), steps = listOf(step("s1", "test.fail", buildJsonObject { put("retryable", true) })), policy = AgentPolicy(autonomy = Autonomy.AUTO)))
        invoice()
        runtime.dispatcher.drain()
        awaitRun(agent) { it.status == RunStatus.WAITING }
        repeat(2) {
            now += 1.hours
            runtime.scheduler.tick()
            delay(300)
        }
        val failed = awaitRun(agent) { it.status == RunStatus.FAILED }
        assertEquals(3, failed.steps.single().attempts)
        assertTrue(module.notifications.listFor(tenant.id, "admin", isAdmin = true).any { it.kind == NotificationKinds.AGENT_FAILED && it.ref == "run:${failed.id.toHexString()}" })

        val fragile = agent(AgentDefinition(listOf(onInvoice), steps = listOf(step("s1", "test.fail")), policy = AgentPolicy(autonomy = Autonomy.AUTO)))
        repeat(AgentRunExecutor.CIRCUIT_BREAKER) { invoice() }
        runtime.dispatcher.drain()
        eventually { module.agents.findById(tenant.id, fragile.id)!!.status == AgentStatus.PAUSED }
        val paused = module.agents.findById(tenant.id, fragile.id)!!
        assertEquals(AgentStatus.PAUSED, paused.status)
        assertEquals("too_many_failures", paused.pausedReason)
        eventually { module.notifications.listFor(tenant.id, "admin", isAdmin = true).any { it.kind == NotificationKinds.AGENT_PAUSED && it.ref == "agent:${fragile.id.toHexString()}" } }
    }

    @Test
    fun `an interrupted send is never repeated blindly`() = runBlocking {
        val agent = agent(AgentDefinition(listOf(onInvoice), steps = listOf(step("s1", "test.send", text("x")), step("s2", "test.note", text("after")))))
        fun interrupted() = AgentRun(
            tenantId = tenant.id, agentId = agent.id, agentName = agent.name, agentVersion = 1, definition = agent.definition,
            trigger = RunTrigger(type = "event", firedAt = now), dedupeKey = "event:${ObjectId().toHexString()}:t1",
            status = RunStatus.RUNNING, steps = listOf(StepResult("s1", "test.send", StepStatus.RUNNING)),
            createdAt = now - 1.hours, updatedAt = now - 1.hours, claimedAt = now - 1.hours,
        )
        val unsure = module.runs.insertIfAbsent(interrupted())!!
        val delivered = module.runs.insertIfAbsent(interrupted())!!
        module.services.outboundLog.begin(OutboundLogEntry(tenantId = tenant.id, idempotencyKey = "${unsure.id.toHexString()}:s1", channel = "whatsapp", recipient = "1", status = OutboundStatus.SENDING, at = now))
        module.services.outboundLog.begin(OutboundLogEntry(tenantId = tenant.id, idempotencyKey = "${delivered.id.toHexString()}:s1", channel = "whatsapp", recipient = "1", status = OutboundStatus.SENDING, at = now))
        module.services.outboundLog.markSent("${delivered.id.toHexString()}:s1", "wamid")

        runtime.scheduler.recoverInterrupted(now)

        assertEquals(RunStatus.NEEDS_REVIEW, module.runs.load(unsure.id)!!.status)
        val resumed = module.runs.load(delivered.id)!!
        assertEquals(RunStatus.WAITING, resumed.status)
        assertEquals(1, resumed.currentStep)
        assertEquals(StepStatus.DONE, resumed.steps.single().status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a received email starts the agents looking for its words, and the run keeps none of its text`() = runBlocking {
        fun lookingFor(word: String) = TriggerSpec("e1", TriggerTypes.EMAIL_RECEIVED, buildJsonObject { put("bodyContains", buildJsonArray { add(JsonPrimitive(word)) }) })
        val leads = agent(AgentDefinition(listOf(lookingFor("orçamento")), steps = listOf(step("s1", "test.note", text("{{email.fromName}}: {{email.subject}} ({{email.text}})"))), policy = AgentPolicy(autonomy = Autonomy.AUTO)))
        val bills = agent(AgentDefinition(listOf(lookingFor("fatura")), steps = listOf(step("s1", "test.note", text("bill"))), policy = AgentPolicy(autonomy = Autonomy.AUTO)))
        val client = ClientRepository(mongo, tenant.id).create("Maria Silva", "+351 912 345 678", email = "maria@cliente.pt")
        val email = EmailMessageRepository(mongo).insert(
            EmailMessage(
                tenantId = tenant.id, connectionId = ObjectId(), providerMessageId = "m1", direction = EmailDirection.INBOUND,
                from = "maria@cliente.pt", fromName = "Maria Silva", to = listOf("obras@example.pt"), subject = "Pintura da sala",
                snippet = "Olá, queria um orçamento", bodyText = "Olá, queria um orçamento para pintar a sala.", clientId = client.id, date = now, createdAt = now,
            ),
        )
        DomainEventLog(mongo).append(
            tenant.id, DomainEventTypes.EMAIL_RECEIVED, SubjectRef.of(SubjectTypes.EMAIL, email.id),
            buildJsonObject { put("from", email.from); put("subject", email.subject); put("clientId", client.id.toHexString()) },
            related = listOf(SubjectRef.of(SubjectTypes.CLIENT, client.id)),
        )

        runtime.dispatcher.drain()
        val run = awaitRun(leads) { it.status == RunStatus.SUCCEEDED }

        assertEquals(listOf("Maria Silva: Pintura da sala (Olá, queria um orçamento para pintar a sala.)"), noted, "steps read the email's text from the email itself")
        assertEquals(0, runsFor(bills).size, "the words weren't in this email")
        assertEquals(client.id, run.clientId)
        assertEquals("Pintura da sala", run.subjectLabel)
        val stored = run.context["email"]!!.jsonObject
        assertEquals("maria@cliente.pt", stored["from"]!!.jsonPrimitive.content)
        assertEquals("true", stored["knownClient"]!!.jsonPrimitive.content)
        assertFalse("text" in stored || "snippet" in stored, "the run's context keeps no copy of the email's text")
    }

    @Test
    fun `a daily schedule fires once when its time comes`() = runBlocking {
        val daily = TriggerSpec("d1", TriggerTypes.SCHEDULE, buildJsonObject { put("frequency", "daily"); put("time", "09:00") })
        val agent = agent(AgentDefinition(listOf(daily), steps = listOf(step("s1", "test.note", text("Agenda de {{company.name}}"))), policy = AgentPolicy(autonomy = Autonomy.AUTO)))
        val stored = module.agents.findById(tenant.id, agent.id)!!
        assertEquals(Instant.parse("2026-10-02T08:00:00Z"), stored.scheduleState["d1"])

        now = Instant.parse("2026-10-02T08:00:30Z")
        runtime.scheduler.tick()
        runtime.scheduler.tick()
        val run = awaitRun(agent) { it.status == RunStatus.SUCCEEDED }
        assertEquals(1, runsFor(agent).size)
        assertEquals(null, run.subject)
        assertEquals(listOf("Agenda de Obras Silva"), noted)
        val next = module.agents.findById(tenant.id, agent.id)!!.scheduleState["d1"]
        assertEquals(Instant.parse("2026-10-03T08:00:00Z"), next)
    }
}
