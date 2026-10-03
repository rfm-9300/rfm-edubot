package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.InMemorySnapshotStore
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.model.Agent
import com.rfm.edubot.mobile.core.model.AgentApproval
import com.rfm.edubot.mobile.core.model.AgentRun
import com.rfm.edubot.mobile.core.model.AgentStatus
import com.rfm.edubot.mobile.core.model.AgentTask
import com.rfm.edubot.mobile.core.model.AgentsOverview
import com.rfm.edubot.mobile.core.model.SaveTask
import com.rfm.edubot.mobile.core.model.TaskStatus
import com.rfm.edubot.mobile.core.network.AgentsApi
import com.rfm.edubot.mobile.core.network.ApiException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private open class FakeAgentsApi : AgentsApi {
    var approvals = mutableListOf(
        AgentApproval(id = "a1", agentName = "Chase overdue", canDecide = true),
        AgentApproval(id = "a2", agentName = "Send quote", canDecide = true),
    )
    var tasks = mutableListOf(AgentTask(id = "t1", title = "Call Maria"))
    var agents = mutableListOf(Agent(id = "ag1", name = "Chase overdue", status = AgentStatus.ACTIVE))
    var overviewCalls = 0
        private set

    override suspend fun overview(): AgentsOverview {
        overviewCalls++
        return AgentsOverview(pendingApprovals = approvals.size.toLong(), openTasks = tasks.size.toLong())
    }

    override suspend fun agents(archived: Boolean): List<Agent> = agents

    override suspend fun pauseAgent(id: String): Agent =
        agents.first { it.id == id }.copy(status = AgentStatus.PAUSED)

    override suspend fun activateAgent(id: String): Agent =
        agents.first { it.id == id }.copy(status = AgentStatus.ACTIVE)

    override suspend fun approvals(status: String?): List<AgentApproval> = approvals

    override suspend fun approve(id: String): AgentApproval =
        approvals.first { it.id == id }.copy(status = "APPROVED")

    override suspend fun reject(id: String, reason: String?): AgentApproval =
        approvals.first { it.id == id }.copy(status = "REJECTED", reason = reason)

    override suspend fun tasks(status: String?, mine: Boolean): List<AgentTask> = tasks

    override suspend fun saveTask(request: SaveTask, id: String?): AgentTask =
        tasks.first { it.id == id }.copy(status = request.status ?: TaskStatus.OPEN)

    override suspend fun runs(agentId: String?, limit: Int): List<AgentRun> = emptyList()
}

class AgentsRepositoryTest {
    private fun repository(api: FakeAgentsApi = FakeAgentsApi()) =
        AgentsRepository(api, SnapshotCache(InMemorySnapshotStore())) to api

    @Test
    fun `approving removes the decision from the waiting list`() = runTest {
        val (repository, _) = repository()
        repository.approvals.load()

        repository.approve(repository.approvals.state.value.value!!.first())

        assertEquals(listOf("a2"), repository.approvals.state.value.value?.map { it.id })
    }

    @Test
    fun `rejecting also removes it, and passes the reason on`() = runTest {
        val (repository, api) = repository()
        repository.approvals.load()

        val rejected = repository.reject(api.approvals.first(), "wrong client")

        assertEquals("wrong client", (rejected as Outcome.Success).value.reason)
        assertEquals(listOf("a2"), repository.approvals.state.value.value?.map { it.id })
    }

    @Test
    fun `an empty reason is sent as none rather than as an empty string`() = runTest {
        val (repository, api) = repository()
        val rejected = repository.reject(api.approvals.first(), "   ")
        assertEquals(null, (rejected as Outcome.Success).value.reason)
    }

    @Test
    fun `a failed decision leaves it in the list to try again`() = runTest {
        val api = object : FakeAgentsApi() {
            override suspend fun approve(id: String): AgentApproval = throw ApiException(AppError.Forbidden)
        }
        val (repository, _) = repository(api)
        repository.approvals.load()

        val result = repository.approve(repository.approvals.state.value.value!!.first())

        assertEquals(AppError.Forbidden, (result as Outcome.Failure).error)
        assertEquals(listOf("a1", "a2"), repository.approvals.state.value.value?.map { it.id })
    }

    @Test
    fun `deciding refreshes the counts the Home and Agents screens show`() = runTest {
        val (repository, api) = repository()
        repository.summary.load()
        repository.approvals.load()
        val before = api.overviewCalls

        repository.approve(repository.approvals.state.value.value!!.first())

        assertTrue(api.overviewCalls > before, "the approval badge would otherwise stay stale")
    }

    @Test
    fun `completing a task drops it from the open list`() = runTest {
        val (repository, _) = repository()
        repository.tasks.load()

        repository.completeTask(repository.tasks.state.value.value!!.first())

        assertEquals(emptyList(), repository.tasks.state.value.value)
    }

    @Test
    fun `reopening a task keeps it on the open list`() = runTest {
        val (repository, _) = repository()
        repository.tasks.load()

        repository.reopenTask(repository.tasks.state.value.value!!.first())

        assertEquals(listOf("t1"), repository.tasks.state.value.value?.map { it.id })
    }

    @Test
    fun `pausing an agent updates its row`() = runTest {
        val (repository, _) = repository()
        repository.agents.load()

        repository.setPaused(repository.agents.state.value.value!!.first(), paused = true)

        assertEquals(AgentStatus.PAUSED, repository.agents.state.value.value?.single()?.status)
    }
}
