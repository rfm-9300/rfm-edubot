package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** What an agent run or task is about: a client, a quote, a booking, and so on. */
@Serializable
data class AgentSubject(val type: String, val id: String)

@Serializable
data class AgentStats(
    val runs: Long = 0,
    val succeeded: Long = 0,
    val failed: Long = 0,
    val lastRunAt: String? = null,
    val consecutiveFailures: Int = 0,
    val approvalsInARow: Int = 0,
)

@Serializable
data class Agent(
    val id: String,
    val name: String,
    val description: String? = null,
    val icon: String? = null,
    val kind: String = "",
    val status: String = AgentStatus.DRAFT,
    val version: Int = 1,
    val stats: AgentStats = AgentStats(),
    val pausedReason: String? = null,
    val nextFireAt: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val pendingApprovals: Int = 0,
    /** The backend noticed people keep approving this agent unedited and suggests letting it act. */
    val suggestAuto: Boolean = false,
) {
    val active: Boolean get() = status == AgentStatus.ACTIVE
}

object AgentStatus {
    const val DRAFT = "DRAFT"
    const val ACTIVE = "ACTIVE"
    const val PAUSED = "PAUSED"
    const val ARCHIVED = "ARCHIVED"
}

/**
 * An action an agent wants to take and is waiting on a person for. [preview] is the rendered
 * action (recipients, subject, body) as a raw object, because its shape depends on the action.
 */
@Serializable
data class AgentApproval(
    val id: String,
    val agentId: String = "",
    val agentName: String = "",
    val runId: String = "",
    val action: String = "",
    val preview: JsonObject? = null,
    val subject: AgentSubject? = null,
    val subjectLabel: String? = null,
    val status: String = "PENDING",
    val createdAt: String = "",
    val expiresAt: String = "",
    val decidedAt: String? = null,
    val decidedByName: String? = null,
    val edited: Boolean = false,
    val reason: String? = null,
    /** False when this member's role may not decide it; the UI shows it read-only. */
    val canDecide: Boolean = false,
) {
    val pending: Boolean get() = status == "PENDING"
}

@Serializable
data class AgentTask(
    val id: String,
    val title: String,
    val detail: String? = null,
    val subject: AgentSubject? = null,
    val subjectLabel: String? = null,
    val assigneeUserId: String? = null,
    val assigneeName: String? = null,
    val dueAt: String? = null,
    val status: String = TaskStatus.OPEN,
    val agentId: String? = null,
    val agentName: String? = null,
    val runId: String? = null,
    val createdAt: String = "",
    val completedAt: String? = null,
) {
    val open: Boolean get() = status == TaskStatus.OPEN
}

object TaskStatus {
    const val OPEN = "OPEN"
    const val DONE = "DONE"
    const val CANCELLED = "CANCELLED"
}

@Serializable
data class AgentRunTrigger(
    val type: String = "",
    val eventType: String? = null,
    val firedAt: String = "",
)

@Serializable
data class AgentRun(
    val id: String,
    val agentId: String = "",
    val agentName: String = "",
    val status: String = "",
    val subject: AgentSubject? = null,
    val subjectLabel: String? = null,
    val trigger: AgentRunTrigger = AgentRunTrigger(),
    val currentStep: Int = 0,
    val stepCount: Int = 0,
    val createdAt: String = "",
    val finishedAt: String? = null,
    val outcome: String? = null,
    val error: String? = null,
    val dryRun: Boolean = false,
    /** Plain-language list of what the run actually did, ready to show on a record. */
    val done: List<String> = emptyList(),
)

@Serializable
data class AgentsOverview(
    val activeAgents: Long = 0,
    val runsToday: Long = 0,
    val actionsThisWeek: Long = 0,
    val pendingApprovals: Long = 0,
    val openTasks: Long = 0,
    val failedThisWeek: Long = 0,
    val waitingRuns: Long = 0,
    val paused: Boolean = false,
    /** `platform` when support paused them, in which case the tenant cannot resume. */
    val pausedBy: String? = null,
    val canManage: Boolean = false,
)

@Serializable
data class ApproveAction(val input: JsonObject? = null)

@Serializable
data class RejectAction(val reason: String? = null)

@Serializable
data class SaveTask(
    val title: String? = null,
    val detail: String? = null,
    val dueAt: String? = null,
    val assigneeUserId: String? = null,
    val subjectType: String? = null,
    val subjectId: String? = null,
    val status: String? = null,
)
