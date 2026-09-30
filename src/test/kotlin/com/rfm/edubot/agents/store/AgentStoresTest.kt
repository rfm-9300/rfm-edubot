package com.rfm.edubot.agents.store

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.AgentApproval
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.AgentStatus
import com.rfm.edubot.agents.model.ApprovalStatus
import com.rfm.edubot.agents.model.OutboundLogEntry
import com.rfm.edubot.agents.model.OutboundStatus
import com.rfm.edubot.agents.model.RunStatus
import com.rfm.edubot.agents.model.RunTrigger
import com.rfm.edubot.agents.model.StepResult
import com.rfm.edubot.agents.model.StepStatus
import com.rfm.edubot.agents.model.TriggerSpec
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.notifications.NotificationAudience
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.TestMongo
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class AgentStoresTest {

    companion object {
        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongoModule = TestMongo.module("agent_stores")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
        }
    }

    private val now: Instant = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private fun agent(tenantId: ObjectId) = Agent(
        tenantId = tenantId,
        name = "Invoice reminders",
        status = AgentStatus.ACTIVE,
        definition = AgentDefinition(triggers = listOf(TriggerSpec("t1", "event", buildJsonObject { put("event", "invoice.created") }))),
        createdAt = now,
        updatedAt = now,
    )

    private fun run(tenantId: ObjectId, agentId: ObjectId, key: String) = AgentRun(
        tenantId = tenantId,
        agentId = agentId,
        agentName = "Invoice reminders",
        agentVersion = 1,
        definition = AgentDefinition(),
        trigger = RunTrigger(type = "event", firedAt = now),
        subject = SubjectRef(SubjectTypes.INVOICE, "inv-1"),
        dedupeKey = key,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `agents round-trip and editing bumps the version`() = runBlocking {
        val repo = AgentRepository(mongoModule) { now }
        val tenantId = ObjectId()
        val stored = repo.insert(agent(tenantId))

        assertEquals(stored, repo.findById(tenantId, stored.id))
        assertNull(repo.findById(ObjectId(), stored.id), "another company never sees it")
        val edited = repo.update(tenantId, stored.id, "Renamed", null, null, stored.kind, stored.definition, null)!!
        assertEquals(2, edited.version)
        assertEquals(listOf(stored.id), repo.activeFor(tenantId).map { it.id })
    }

    @Test
    fun `only one tick advances a schedule from the same due time`() = runBlocking {
        val repo = AgentRepository(mongoModule) { now }
        val tenantId = ObjectId()
        val stored = repo.insert(agent(tenantId))
        repo.setSchedule(tenantId, stored.id, mapOf("t1" to now - 1.minutes))

        assertEquals(listOf(stored.id), repo.dueSchedules(now).filter { it.tenantId == tenantId }.map { it.id })
        assertTrue(repo.advanceSchedule(tenantId, stored.id, now - 1.minutes, mapOf("t1" to now + 1.days)))
        assertFalse(repo.advanceSchedule(tenantId, stored.id, now - 1.minutes, mapOf("t1" to now + 2.days)))
    }

    @Test
    fun `a trigger fires once per dedupe key and a run is claimed once`() = runBlocking {
        val runs = AgentRunRepository(mongoModule) { now }
        val tenantId = ObjectId()
        val agentId = ObjectId()
        val first = runs.insertIfAbsent(run(tenantId, agentId, "event:e1:t1"))

        assertNotNull(first)
        assertNull(runs.insertIfAbsent(run(tenantId, agentId, "event:e1:t1")))
        assertNotNull(runs.claim(first.id, setOf(RunStatus.QUEUED)))
        assertNull(runs.claim(first.id, setOf(RunStatus.QUEUED)))

        runs.save(runs.load(first.id)!!.copy(status = RunStatus.WAITING, resumeAt = now - 1.hours))
        assertEquals(listOf(first.id), runs.dueWaiting(now).filter { it.tenantId == tenantId }.map { it.id })
        assertEquals(listOf(first.id), runs.openForSubject(tenantId, SubjectRef(SubjectTypes.INVOICE, "inv-1")).map { it.id })
        assertEquals(RunStatus.CANCELLED, runs.cancelIfOpen(first.id, "exit_rule")?.status)
        assertNull(runs.cancelIfOpen(first.id, "exit_rule"))
    }

    @Test
    fun `an approval is decided once and can carry the person's edits`() = runBlocking {
        val approvals = AgentApprovalRepository(mongoModule) { now }
        val tenantId = ObjectId()
        val approval = approvals.insert(
            AgentApproval(
                tenantId = tenantId, agentId = ObjectId(), agentName = "Reminders", runId = ObjectId(), stepId = "s1",
                action = "whatsapp.send", input = buildJsonObject { put("text", "Olá") },
                preview = ActionPreview(kind = "message", body = "Olá"), createdAt = now, expiresAt = now + 3.days,
            ),
        )

        val approved = approvals.approve(tenantId, approval.id, "u1", "Ana", buildJsonObject { put("text", "Olá Rui") })
        assertEquals("Olá Rui", approved?.input?.get("text")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertTrue(approved!!.edited)
        assertNull(approvals.approve(tenantId, approval.id, "u2", "Rui", null))
        assertNull(approvals.reject(tenantId, approval.id, "u2", "Rui", "late"))
        assertEquals(0, approvals.countPending(tenantId))
    }

    @Test
    fun `a send is attempted once per run step and counts towards the recipient cap`() = runBlocking {
        val log = OutboundLogRepository(mongoModule) { now }
        val tenantId = ObjectId()
        val entry = OutboundLogEntry(tenantId = tenantId, idempotencyKey = "run1:s1", channel = "whatsapp", recipient = "351911000111", status = OutboundStatus.SENDING, at = now)

        assertTrue(log.begin(entry))
        assertFalse(log.begin(entry.copy(id = ObjectId())))
        log.markSent("run1:s1", "wamid.1")
        assertEquals(OutboundStatus.SENT, log.find("run1:s1")?.status)
        assertEquals(1, log.countForRecipient(tenantId, "351911000111", now - 1.days))
    }

    @Test
    fun `notifications reach their audience and track who read them`() = runBlocking {
        val notifications = NotificationRepository(mongoModule) { now }
        val tenantId = ObjectId()
        notifications.notify(tenantId, "agent_failed")
        notifications.notify(tenantId, "agent_task", audience = NotificationAudience.USER, userId = "member-1")

        assertEquals(1, notifications.listFor(tenantId, "admin-1", isAdmin = true).size)
        assertEquals(1, notifications.listFor(tenantId, "member-1", isAdmin = false).size)
        assertEquals(1, notifications.unreadCount(tenantId, "admin-1", isAdmin = true))
        notifications.markAllRead(tenantId, "admin-1", isAdmin = true)
        assertEquals(0, notifications.unreadCount(tenantId, "admin-1", isAdmin = true))
        assertEquals(1, notifications.unreadCount(tenantId, "member-1", isAdmin = false))
    }

    private fun emailRun(tenantId: ObjectId, emailId: ObjectId, status: RunStatus) = run(tenantId, ObjectId(), "event:${ObjectId()}:e1").copy(
        subject = SubjectRef.of(SubjectTypes.EMAIL, emailId),
        subjectLabel = "Pintura da sala",
        status = status,
        context = buildJsonObject {
            put("email", buildJsonObject { put("from", "maria@cliente.pt"); put("subject", "Pintura da sala"); put("text", "Queria um orçamento."); put("snippet", "Queria um") })
            put("event", buildJsonObject { put("type", "email.received"); put("from", "maria@cliente.pt") })
        },
        steps = listOf(
            StepResult("s1", "ai.task", StepStatus.DONE, input = buildJsonObject { put("instructions", "Resume: Queria um orçamento.") }, output = buildJsonObject { put("summary", "Pede orçamento") }),
        ),
    )

    private fun emailApproval(tenantId: ObjectId, emailId: ObjectId, status: ApprovalStatus = ApprovalStatus.PENDING) = AgentApproval(
        tenantId = tenantId, agentId = ObjectId(), agentName = "Pedidos", runId = ObjectId(), stepId = "s2",
        action = "email.reply", input = buildJsonObject { put("text", "Olá Maria, sobre a pintura da sala…") },
        preview = ActionPreview(kind = "message", channel = "email", body = "Olá Maria, sobre a pintura da sala…"),
        subject = SubjectRef.of(SubjectTypes.EMAIL, emailId), subjectLabel = "Pintura da sala", status = status, createdAt = now, expiresAt = now + 3.days,
    )

    @Test
    fun `a run keeps who wrote an email and about what, never its text`() = runBlocking {
        val runs = AgentRunRepository(mongoModule) { now }
        val stored = runs.insertIfAbsent(emailRun(ObjectId(), ObjectId(), RunStatus.QUEUED))!!

        val email = runs.load(stored.id)!!.context["email"]!!.jsonObject
        assertEquals(setOf("from", "subject"), email.keys)
        runs.save(stored.copy(status = RunStatus.SUCCEEDED))
        assertEquals(setOf("from", "subject"), runs.load(stored.id)!!.context["email"]!!.jsonObject.keys, "nor when the executor checkpoints it")
        assertEquals("maria@cliente.pt", runs.load(stored.id)!!.context["event"]!!.jsonObject["from"]!!.jsonPrimitive.content)
    }

    @Test
    fun `forgetting emails ends the runs and approvals waiting on them and drops what all of them copied`() = runBlocking {
        val runs = AgentRunRepository(mongoModule) { now }
        val approvals = AgentApprovalRepository(mongoModule) { now }
        val tenantId = ObjectId()
        val email = ObjectId()
        val waiting = runs.insertIfAbsent(emailRun(tenantId, email, RunStatus.WAITING).copy(resumeAt = now + 1.days))!!
        val done = runs.insertIfAbsent(emailRun(tenantId, email, RunStatus.SUCCEEDED))!!
        // Stored before runs always had steps: `$[]` must not trip over it.
        val stepless = runs.insertIfAbsent(emailRun(tenantId, email, RunStatus.FAILED))!!
        mongoModule.database.getCollection<Document>(AgentRunRepository.COLLECTION).updateOne(Filters.eq("_id", stepless.id), Updates.unset("steps"))
        val otherEmail = runs.insertIfAbsent(emailRun(tenantId, ObjectId(), RunStatus.WAITING))!!
        val otherCompany = runs.insertIfAbsent(emailRun(ObjectId(), email, RunStatus.WAITING))!!
        val pending = approvals.insert(emailApproval(tenantId, email))
        val decided = approvals.insert(emailApproval(tenantId, email, ApprovalStatus.APPROVED))
        val untouched = approvals.insert(emailApproval(tenantId, ObjectId()))

        runs.forgetEmails(tenantId, listOf(email.toHexString()))
        approvals.forgetEmails(tenantId, listOf(email.toHexString()))

        val ended = runs.load(waiting.id)!!
        assertEquals(RunStatus.CANCELLED, ended.status)
        assertEquals("record_removed", ended.outcome)
        assertEquals(now, ended.finishedAt)
        assertNull(ended.resumeAt)
        for (forgotten in listOf(ended, runs.load(done.id)!!, runs.load(stepless.id)!!)) {
            assertFalse("email" in forgotten.context || "event" in forgotten.context)
            assertNull(forgotten.subjectLabel)
            assertTrue(forgotten.steps.all { it.input == null && it.output == null })
        }
        assertEquals(RunStatus.SUCCEEDED, runs.load(done.id)!!.status, "a finished run keeps its outcome")
        assertEquals(listOf(StepStatus.DONE), runs.load(done.id)!!.steps.map { it.status }, "and the record of its steps")
        for (kept in listOf(otherEmail, otherCompany)) {
            val again = runs.load(kept.id)!!
            assertEquals(RunStatus.WAITING, again.status)
            assertEquals("Pintura da sala", again.subjectLabel)
            assertEquals(kept.steps.single().input, again.steps.single().input)
        }

        assertEquals(ApprovalStatus.CANCELLED, approvals.findById(tenantId, pending.id)!!.status)
        assertEquals(ApprovalStatus.APPROVED, approvals.findById(tenantId, decided.id)!!.status)
        for (id in listOf(pending.id, decided.id)) {
            val forgotten = approvals.findById(tenantId, id)!!
            assertTrue(forgotten.input.isEmpty())
            assertEquals(ActionPreview(kind = "generic"), forgotten.preview)
            assertNull(forgotten.subjectLabel)
        }
        assertEquals(untouched, approvals.findById(tenantId, untouched.id))
    }

    @Test
    fun `when an email's text goes, what steps and decided approvals made of it goes too`() = runBlocking {
        val runs = AgentRunRepository(mongoModule) { now }
        val approvals = AgentApprovalRepository(mongoModule) { now }
        val tenantId = ObjectId()
        val email = ObjectId()
        val run = runs.insertIfAbsent(emailRun(tenantId, email, RunStatus.SUCCEEDED))!!
        val pending = approvals.insert(emailApproval(tenantId, email))
        val decided = approvals.insert(emailApproval(tenantId, email, ApprovalStatus.REJECTED))

        runs.forgetEmailText(tenantId, listOf(email.toHexString()))
        approvals.forgetEmailText(tenantId, listOf(email.toHexString()))

        val after = runs.load(run.id)!!
        assertTrue(after.steps.all { it.input == null && it.output == null })
        assertEquals("Pintura da sala", after.subjectLabel, "who wrote and about what stay, as on the email itself")
        assertEquals("maria@cliente.pt", after.context["email"]!!.jsonObject["from"]!!.jsonPrimitive.content)
        assertEquals(RunStatus.SUCCEEDED, after.status)
        assertEquals(pending, approvals.findById(tenantId, pending.id), "a pending approval still needs what it would do")
        val forgotten = approvals.findById(tenantId, decided.id)!!
        assertTrue(forgotten.input.isEmpty())
        assertEquals(ActionPreview(kind = "generic"), forgotten.preview)
        assertEquals("Pintura da sala", forgotten.subjectLabel)
    }

    @Test
    fun `settings default until the company or the backoffice saves them`() = runBlocking {
        val settings = AgentSettingsRepository(mongoModule) { now }
        val tenantId = ObjectId()

        assertEquals(2, settings.get(tenantId).company.perRecipientDailyCap)
        settings.saveCompany(tenantId, settings.get(tenantId).company.copy(paused = true))
        settings.savePlatform(tenantId, settings.get(tenantId).platform.copy(maxActiveAgents = 3))
        val saved = settings.get(tenantId)
        assertTrue(saved.company.paused)
        assertEquals(3, saved.platform.maxActiveAgents)
    }
}
