package com.rfm.edubot.mobile.feature.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.data.AgentsRepository
import com.rfm.edubot.mobile.core.model.Agent
import com.rfm.edubot.mobile.core.model.AgentApproval
import com.rfm.edubot.mobile.core.model.AgentRun
import com.rfm.edubot.mobile.core.model.AgentTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AgentsTab { Inbox, Agents, Activity }

class AgentsViewModel(
    private val repository: AgentsRepository,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope

    private val mutableTab = MutableStateFlow(AgentsTab.Inbox)
    val tab: StateFlow<AgentsTab> = mutableTab.asStateFlow()

    private val mutableRuns = MutableStateFlow<List<AgentRun>>(emptyList())
    val runs: StateFlow<List<AgentRun>> = mutableRuns.asStateFlow()

    private val mutablePending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = mutablePending.asStateFlow()

    private val mutableFailure = MutableStateFlow<AppError?>(null)
    val failure: StateFlow<AppError?> = mutableFailure.asStateFlow()

    fun load() = scope.launch {
        repository.summary.load()
        repository.approvals.load()
        repository.tasks.load()
    }

    fun setTab(value: AgentsTab) {
        mutableTab.value = value
        when (value) {
            AgentsTab.Agents -> scope.launch { repository.agents.load() }
            AgentsTab.Activity -> loadRuns()
            AgentsTab.Inbox -> Unit
        }
    }

    fun refresh() = scope.launch {
        mutableFailure.value = null
        when (mutableTab.value) {
            AgentsTab.Inbox -> {
                repository.approvals.refresh()
                repository.tasks.refresh()
                repository.summary.refresh()
            }
            AgentsTab.Agents -> repository.agents.refresh()
            AgentsTab.Activity -> loadRuns()
        }
    }

    fun approve(approval: AgentApproval) = act(approval.id) { repository.approve(approval) }

    fun reject(approval: AgentApproval, reason: String?) = act(approval.id) { repository.reject(approval, reason) }

    fun completeTask(task: AgentTask) = act(task.id) { repository.completeTask(task) }

    fun setPaused(agent: Agent, paused: Boolean) = act(agent.id) { repository.setPaused(agent, paused) }

    private fun loadRuns() = scope.launch {
        when (val runs = repository.runs()) {
            is Outcome.Success -> mutableRuns.value = runs.value
            is Outcome.Failure -> mutableFailure.value = runs.error
        }
    }

    private fun act(id: String, block: suspend () -> Outcome<*>) = scope.launch {
        if (mutablePending.value != null) return@launch
        mutablePending.value = id
        mutableFailure.value = null
        val result = block()
        mutablePending.value = null
        if (result is Outcome.Failure) mutableFailure.value = result.error
    }
}
