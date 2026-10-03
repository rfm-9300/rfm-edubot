package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.model.Agent
import com.rfm.edubot.mobile.core.model.AgentApproval
import com.rfm.edubot.mobile.core.model.AgentRun
import com.rfm.edubot.mobile.core.model.AgentTask
import com.rfm.edubot.mobile.core.model.AgentsOverview
import com.rfm.edubot.mobile.core.model.SaveTask
import com.rfm.edubot.mobile.core.model.TaskStatus
import com.rfm.edubot.mobile.core.network.AgentsApi
import kotlinx.serialization.builtins.ListSerializer

class AgentsRepository(
    private val api: AgentsApi,
    cache: SnapshotCache,
) {
    val summary = CachedResource(
        key = "agents.overview",
        serializer = AgentsOverview.serializer(),
        cache = cache,
        fetch = { api.overview() },
    )

    val agents = CachedResource(
        key = "agents.list",
        serializer = ListSerializer(Agent.serializer()),
        cache = cache,
        fetch = { api.agents() },
    )

    /**
     * The decisions waiting on a person. This is the reason the Agents module belongs on a phone:
     * an agent that asks before sending is blocked until somebody approves it.
     */
    val approvals = CachedResource(
        key = "agents.approvals",
        serializer = ListSerializer(AgentApproval.serializer()),
        cache = cache,
        fetch = { api.approvals(status = "PENDING") },
    )

    val tasks = CachedResource(
        key = "agents.tasks",
        serializer = ListSerializer(AgentTask.serializer()),
        cache = cache,
        fetch = { api.tasks(status = TaskStatus.OPEN) },
    )

    suspend fun runs(agentId: String? = null): Outcome<List<AgentRun>> = apiCall { api.runs(agentId) }

    suspend fun approve(approval: AgentApproval): Outcome<AgentApproval> = decide(approval) { api.approve(approval.id) }

    suspend fun reject(approval: AgentApproval, reason: String?): Outcome<AgentApproval> =
        decide(approval) { api.reject(approval.id, reason?.takeIf { it.isNotBlank() }) }

    suspend fun completeTask(task: AgentTask): Outcome<AgentTask> = setTaskStatus(task, TaskStatus.DONE)

    suspend fun reopenTask(task: AgentTask): Outcome<AgentTask> = setTaskStatus(task, TaskStatus.OPEN)

    suspend fun setPaused(agent: Agent, paused: Boolean): Outcome<Agent> {
        val updated = apiCall { if (paused) api.pauseAgent(agent.id) else api.activateAgent(agent.id) }
        updated.valueOrNull?.let { saved ->
            agents.mutate { current -> current.map { if (it.id == saved.id) saved else it } }
            summary.refresh()
        }
        return updated
    }

    private suspend fun setTaskStatus(task: AgentTask, status: String): Outcome<AgentTask> {
        val updated = apiCall { api.saveTask(SaveTask(status = status), task.id) }
        updated.valueOrNull?.let { saved ->
            // The open-tasks list drops anything no longer open rather than showing a stale row.
            tasks.mutate { current ->
                if (saved.open) current.map { if (it.id == saved.id) saved else it }
                else current.filterNot { it.id == saved.id }
            }
            summary.refresh()
        }
        return updated
    }

    private suspend fun decide(
        approval: AgentApproval,
        action: suspend () -> AgentApproval,
    ): Outcome<AgentApproval> {
        val decided = apiCall { action() }
        if (decided is Outcome.Success) {
            approvals.mutate { current -> current.filterNot { it.id == approval.id } }
            summary.refresh()
        }
        return decided
    }
}
