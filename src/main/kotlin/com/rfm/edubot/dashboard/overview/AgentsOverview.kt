package com.rfm.edubot.dashboard.overview

import com.rfm.edubot.agents.model.AgentApproval
import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.AgentTask
import com.rfm.edubot.agents.model.RunStatus
import com.rfm.edubot.agents.store.AgentApprovalRepository
import com.rfm.edubot.agents.store.AgentRepository
import com.rfm.edubot.agents.store.AgentRunRepository
import com.rfm.edubot.agents.store.AgentSettingsRepository
import com.rfm.edubot.agents.store.AgentTaskRepository
import com.rfm.edubot.dashboard.OverviewAgentsDto
import com.rfm.edubot.dashboard.OverviewMath
import com.rfm.edubot.persistence.MongoModule
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.bson.types.ObjectId

/** The Agents card, and the approvals, due tasks and failed runs it lists. */
internal class AgentsOverview(mongo: MongoModule) : OverviewQueries(mongo) {
    /** The Agents card counts, plus the few approvals, due tasks and recent failures "Needs you" lists. */
    class AgentsBlock(val dto: OverviewAgentsDto, val approvals: List<AgentApproval>, val tasksDue: List<AgentTask>, val failed: List<AgentRun>)

    suspend fun agents(tenantId: ObjectId, window: OverviewMath.Window): AgentsBlock = coroutineScope {
        val runs = AgentRunRepository(mongo)
        val tasks = AgentTaskRepository(mongo)
        val approvals = AgentApprovalRepository(mongo)
        val settings = async { AgentSettingsRepository(mongo).get(tenantId) }
        val active = async { AgentRepository(mongo).countActive(tenantId) }
        val runsToday = async { runs.countSince(tenantId, window.now - 1.days) }
        val pending = async { approvals.countPending(tenantId) }
        val open = async { tasks.countOpen(tenantId) }
        val due = async { tasks.countOpen(tenantId, dueBefore = window.tomorrowStart) }
        val failedWeek = async { runs.countByStatus(tenantId, listOf(RunStatus.FAILED, RunStatus.NEEDS_REVIEW), window.now - 7.days) }
        val expiring = async { approvals.expiringFirst(tenantId, OverviewMath.AGENT_ITEMS) }
        val dueList = async { tasks.dueBefore(tenantId, window.tomorrowStart, OverviewMath.AGENT_ITEMS) }
        val failed = async { runs.failedSince(tenantId, window.now - OverviewMath.AGENT_FAILED_DAYS.days, OverviewMath.AGENT_ITEMS) }
        val limits = settings.await()
        AgentsBlock(
            dto = OverviewAgentsDto(
                activeAgents = active.await(),
                runsToday = runsToday.await(),
                pendingApprovals = pending.await(),
                openTasks = open.await(),
                tasksDue = due.await(),
                failedThisWeek = failedWeek.await(),
                paused = limits.company.paused || limits.platform.agentsPaused,
            ),
            approvals = expiring.await(),
            tasksDue = dueList.await(),
            failed = failed.await(),
        )
    }
}
