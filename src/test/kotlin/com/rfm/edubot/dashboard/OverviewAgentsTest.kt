package com.rfm.edubot.dashboard

import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.AgentApproval
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.AgentStatus
import com.rfm.edubot.agents.model.AgentTask
import com.rfm.edubot.agents.model.CompanyAgentSettings
import com.rfm.edubot.agents.model.RunStatus
import com.rfm.edubot.agents.model.RunTrigger
import com.rfm.edubot.agents.model.TaskStatus
import com.rfm.edubot.agents.store.AgentApprovalRepository
import com.rfm.edubot.agents.store.AgentRepository
import com.rfm.edubot.agents.store.AgentRunRepository
import com.rfm.edubot.agents.store.AgentSettingsRepository
import com.rfm.edubot.agents.store.AgentTaskRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class OverviewAgentsTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("overview_agents")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val now: Instant = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private suspend fun tenant(modules: List<String>): Tenant = TenantRepository(mongo).create(
        Tenant(slug = "t-${ObjectId().toHexString().takeLast(8)}", name = "Obras", channels = emptyList(), enabledModules = modules, createdAt = now, updatedAt = now),
    )

    private suspend fun approval(tenantId: ObjectId, expiresIn: Duration, dryRun: Boolean = false) = AgentApprovalRepository(mongo).insert(
        AgentApproval(
            tenantId = tenantId, agentId = ObjectId(), agentName = "Lembretes", runId = ObjectId(), stepId = "s1",
            action = "team.notify", input = buildJsonObject {}, preview = ActionPreview(kind = "notify"),
            subjectLabel = "FT-7 · Ana", createdAt = now, expiresAt = now + expiresIn, dryRun = dryRun,
        ),
    )

    private suspend fun task(tenantId: ObjectId, dueAt: Instant?, status: TaskStatus = TaskStatus.OPEN) = AgentTaskRepository(mongo).insert(
        AgentTask(tenantId = tenantId, title = "Ligar à Ana", dueAt = dueAt, status = status, createdAt = now, updatedAt = now),
    )

    private suspend fun run(tenantId: ObjectId, status: RunStatus, ago: Duration, dryRun: Boolean = false) = AgentRunRepository(mongo).insertIfAbsent(
        AgentRun(
            tenantId = tenantId, agentId = ObjectId(), agentName = "Cobranças", agentVersion = 1, definition = AgentDefinition(),
            trigger = RunTrigger(type = "event", firedAt = now - ago), dedupeKey = ObjectId().toHexString(), status = status,
            dryRun = dryRun, createdAt = now - ago, updatedAt = now - ago, finishedAt = now - ago,
        ),
    )!!

    @Test
    fun `the agents card counts the module's work and Needs you lists what waits for a person`(): Unit = runBlocking {
        val tenant = tenant(listOf(DashboardModules.CLIENTS, DashboardModules.AGENTS))
        AgentRepository(mongo).insert(Agent(tenantId = tenant.id, name = "Cobranças", status = AgentStatus.ACTIVE, createdAt = now, updatedAt = now))
        val later = approval(tenant.id, expiresIn = 3.days)
        val sooner = approval(tenant.id, expiresIn = 1.days)
        approval(tenant.id, expiresIn = 1.days, dryRun = true)
        val overdue = task(tenant.id, dueAt = now - 1.days)
        task(tenant.id, dueAt = now + 3.days)
        task(tenant.id, dueAt = null)
        task(tenant.id, dueAt = now - 2.days, status = TaskStatus.DONE)
        val failed = run(tenant.id, RunStatus.FAILED, ago = 2.hours)
        run(tenant.id, RunStatus.NEEDS_REVIEW, ago = 5.days)
        run(tenant.id, RunStatus.FAILED, ago = 1.hours, dryRun = true)
        run(tenant.id, RunStatus.SUCCEEDED, ago = 1.hours)

        val overview = OverviewService(mongo).build(tenant)
        val agents = overview.agents!!
        assertEquals(1, agents.activeAgents)
        assertEquals(2, agents.runsToday, "test runs don't count")
        assertEquals(2, agents.pendingApprovals)
        assertEquals(3, agents.openTasks)
        assertEquals(1, agents.tasksDue)
        assertEquals(2, agents.failedThisWeek)
        assertEquals(false, agents.paused)

        val attention = overview.attention.filter { it.tab == DashboardModules.AGENTS }
        assertEquals(
            listOf(OverviewMath.KIND_AGENT_APPROVAL, OverviewMath.KIND_AGENT_APPROVAL, OverviewMath.KIND_AGENT_TASK_DUE, OverviewMath.KIND_AGENT_FAILED),
            attention.map { it.kind },
        )
        assertEquals(listOf(sooner.id, later.id).map { it.toHexString() }, attention.take(2).map { it.id }, "the approval that expires first comes first")
        assertEquals("Lembretes · FT-7 · Ana", attention.first().detail)
        assertEquals(overdue.id.toHexString(), attention[2].id)
        assertEquals(failed.id.toHexString(), attention[3].id, "older failures stay on the card, not in Needs you")
        assertEquals(OverviewMath.HEALTH_WATCH, overview.health)
        assertTrue(OverviewHomeLayout.AGENTS in OverviewHomeLayout.available(DashboardModules.effectiveFor(tenant).toSet()).map { it.id })
    }

    @Test
    fun `without the module there is no agents card, and a company pause shows on it`(): Unit = runBlocking {
        val off = tenant(listOf(DashboardModules.CLIENTS))
        approval(off.id, expiresIn = 1.days)
        val without = OverviewService(mongo).build(off)
        assertNull(without.agents)
        assertTrue(without.attention.none { it.tab == DashboardModules.AGENTS })
        assertTrue(OverviewHomeLayout.AGENTS !in OverviewHomeLayout.available(DashboardModules.effectiveFor(off).toSet()).map { it.id })

        val quiet = tenant(listOf(DashboardModules.CLIENTS, DashboardModules.AGENTS))
        AgentSettingsRepository(mongo).saveCompany(quiet.id, CompanyAgentSettings(paused = true))
        val paused = OverviewService(mongo).build(quiet)
        assertEquals(true, paused.agents!!.paused)
        assertEquals(OverviewMath.HEALTH_OK, paused.health, "nothing waits for a person")
    }
}
