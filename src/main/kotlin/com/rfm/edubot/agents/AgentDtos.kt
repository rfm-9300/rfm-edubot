package com.rfm.edubot.agents

import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.AgentApproval
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.AgentTask
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.model.CompanyAgentSettings
import com.rfm.edubot.agents.model.StepResult
import com.rfm.edubot.agents.model.StepStatus
import com.rfm.edubot.agents.registry.DefinitionProblem
import com.rfm.edubot.agents.store.AgentJson
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.notifications.Notification
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable data class SubjectDto(val type: String, val id: String)

@Serializable data class AgentStatsDto(
    val runs: Long,
    val succeeded: Long,
    val failed: Long,
    val lastRunAt: String? = null,
    val consecutiveFailures: Int,
    val approvalsInARow: Int,
)

@Serializable data class AgentDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val icon: String? = null,
    val kind: String,
    val templateKey: String? = null,
    val templateParams: JsonObject? = null,
    val status: String,
    val version: Int,
    val definition: JsonObject,
    val stats: AgentStatsDto,
    val pausedReason: String? = null,
    val nextFireAt: String? = null,
    val createdBy: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val problems: List<DefinitionProblem>,
    val pendingApprovals: Int,
    /** The agent asks before acting and people approved it several times in a row without edits. */
    val suggestAuto: Boolean,
)

@Serializable data class StepDto(
    val stepId: String,
    val action: String,
    val status: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val attempts: Int,
    val input: JsonObject? = null,
    val output: JsonObject? = null,
    val error: String? = null,
    val note: String? = null,
)

/** A step as the run's definition snapshot has it, so a run shows the steps it hasn't reached yet. */
@Serializable data class PlannedStepDto(val id: String, val action: String, val label: String? = null, val input: JsonObject? = null)

@Serializable data class RunTriggerDto(val type: String, val eventType: String? = null, val firedAt: String, val byUserId: String? = null)

@Serializable data class RunDto(
    val id: String,
    val agentId: String,
    val agentName: String,
    val status: String,
    val subject: SubjectDto? = null,
    val subjectLabel: String? = null,
    val trigger: RunTriggerDto,
    val currentStep: Int,
    val stepCount: Int,
    val nextStep: String? = null,
    val resumeAt: String? = null,
    val createdAt: String,
    val finishedAt: String? = null,
    val outcome: String? = null,
    val error: String? = null,
    val dryRun: Boolean,
    val tokens: Int,
    /** Actions the run carried out (flow steps aside), for "what the agent did" lines on records. */
    val done: List<String>,
    val steps: List<StepDto>? = null,
    val plan: List<PlannedStepDto>? = null,
)

@Serializable data class ApprovalDto(
    val id: String,
    val agentId: String,
    val agentName: String,
    val runId: String,
    val stepId: String,
    val seq: Int,
    val action: String,
    val input: JsonObject,
    /** Every [ActionPreview] field, defaults included. */
    val preview: JsonObject,
    val subject: SubjectDto? = null,
    val subjectLabel: String? = null,
    val status: String,
    val approvers: String,
    val createdAt: String,
    val expiresAt: String,
    val decidedAt: String? = null,
    val decidedByName: String? = null,
    val edited: Boolean,
    val reason: String? = null,
    val canDecide: Boolean,
)

@Serializable data class TaskDto(
    val id: String,
    val title: String,
    val detail: String? = null,
    val subject: SubjectDto? = null,
    val subjectLabel: String? = null,
    val assigneeUserId: String? = null,
    val assigneeName: String? = null,
    val dueAt: String? = null,
    val status: String,
    val agentId: String? = null,
    val agentName: String? = null,
    val runId: String? = null,
    val createdAt: String,
    val completedAt: String? = null,
)

@Serializable data class NotificationDto(
    val id: String,
    val kind: String,
    val params: Map<String, String>,
    val body: String? = null,
    val link: String? = null,
    val subject: SubjectDto? = null,
    val ref: String? = null,
    val read: Boolean,
    val createdAt: String,
)

@Serializable data class NotificationsDto(val items: List<NotificationDto>, val unread: Long)

@Serializable data class AgentUsageDto(
    val runsToday: Long,
    val activeAgents: Long,
    val maxActiveAgents: Int,
    val runsPerDay: Int,
    /** Every AI token the company spent this month; [tokenBudget] caps this total. */
    val tokensThisMonth: Long,
    val agentTokensThisMonth: Long,
    val tokenBudget: Long,
)

/** [company] and [platform] carry every field, defaults included; an absent `quietHours` means none. */
@Serializable data class AgentSettingsDto(
    val company: JsonObject,
    val platform: JsonObject,
    val usage: AgentUsageDto,
    val canManage: Boolean,
)

@Serializable data class AgentsOverviewDto(
    val activeAgents: Long,
    val runsToday: Long,
    val actionsThisWeek: Long,
    val pendingApprovals: Long,
    val openTasks: Long,
    val failedThisWeek: Long,
    val waitingRuns: Long,
    val paused: Boolean,
    /** `platform` when the backoffice paused them (only support can resume), else `company`. */
    val pausedBy: String? = null,
    val canManage: Boolean,
)

@Serializable data class ActivityDto(val type: String, val agentName: String? = null, val occurredAt: String, val payload: JsonObject, val runId: String? = null)

@Serializable data class AgentOptionDto(val id: String, val name: String)

@Serializable data class SubjectAutomationsDto(
    val upcoming: List<RunDto>,
    val recent: List<RunDto>,
    val activity: List<ActivityDto>,
    val tasks: List<TaskDto>,
    /** Active agents that can be run by hand on this kind of record. */
    val manualAgents: List<AgentOptionDto>,
    val automationPaused: Boolean? = null,
)

@Serializable data class PersonDto(val id: String, val email: String, val role: String)

@Serializable data class AgentWriteRequest(
    val name: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val kind: String? = null,
    val definition: JsonObject? = null,
    val templateKey: String? = null,
    val params: JsonObject? = null,
)

@Serializable data class RunOnRecordRequest(val subjectType: String? = null, val subjectId: String? = null, val definition: JsonObject? = null)
@Serializable data class ApproveRequest(val input: JsonObject? = null)
@Serializable data class RejectRequest(val reason: String? = null)
@Serializable data class DuplicateRequest(val name: String? = null)
@Serializable data class CopyRequest(val companyId: String, val name: String? = null)
@Serializable data class TaskWriteRequest(
    val title: String? = null,
    val detail: String? = null,
    val dueAt: String? = null,
    val assigneeUserId: String? = null,
    val subjectType: String? = null,
    val subjectId: String? = null,
    val status: String? = null,
    val clearAssignee: Boolean = false,
    val clearDue: Boolean = false,
)
@Serializable data class CompanySettingsRequest(val settings: CompanyAgentSettings)
@Serializable data class AutomationPauseRequest(val paused: Boolean)
@Serializable data class AutomationPauseDto(val automationPaused: Boolean)

internal fun SubjectRef.dto() = SubjectDto(type, id)

internal fun AgentDefinition.json(): JsonObject = AgentJson.json.encodeToJsonElement(AgentDefinition.serializer(), this).jsonObject

internal fun Agent.dto(problems: List<DefinitionProblem> = emptyList(), pendingApprovals: Int = 0) = AgentDto(
    id = id.toHexString(),
    name = name,
    description = description,
    icon = icon,
    kind = kind.name,
    templateKey = templateKey,
    templateParams = templateParams,
    status = status.name,
    version = version,
    definition = definition.json(),
    stats = AgentStatsDto(stats.runs, stats.succeeded, stats.failed, stats.lastRunAt?.toString(), stats.consecutiveFailures, stats.approvalsInARow),
    pausedReason = pausedReason,
    nextFireAt = scheduleState.values.minOrNull()?.toString(),
    createdBy = createdBy,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    problems = problems,
    pendingApprovals = pendingApprovals,
    suggestAuto = definition.policy.autonomy == Autonomy.APPROVE && stats.approvalsInARow >= SUGGEST_AUTO_AFTER,
)

internal const val SUGGEST_AUTO_AFTER = 5

internal fun StepResult.dto() = StepDto(stepId, action, status.name, startedAt?.toString(), finishedAt?.toString(), attempts, input, output, error, note)

internal fun AgentRun.dto(withSteps: Boolean = false) = RunDto(
    id = id.toHexString(),
    agentId = agentId.toHexString(),
    agentName = agentName,
    status = status.name,
    subject = subject?.dto(),
    subjectLabel = subjectLabel,
    trigger = RunTriggerDto(trigger.type, trigger.eventType, trigger.firedAt.toString(), trigger.byUserId),
    currentStep = currentStep,
    stepCount = definition.steps.size,
    nextStep = definition.steps.getOrNull(currentStep)?.action,
    resumeAt = resumeAt?.toString(),
    createdAt = createdAt.toString(),
    finishedAt = finishedAt?.toString(),
    outcome = outcome,
    error = error,
    dryRun = dryRun,
    tokens = promptTokens + completionTokens,
    done = steps.filter { it.status == StepStatus.DONE && !it.action.startsWith("flow.") }.map { it.action }.distinct(),
    steps = if (withSteps) steps.map { it.dto() } else null,
    plan = if (withSteps) definition.steps.map { PlannedStepDto(it.id, it.action, it.label, it.input) } else null,
)

internal fun AgentApproval.dto(canDecide: Boolean) = ApprovalDto(
    id = id.toHexString(),
    agentId = agentId.toHexString(),
    agentName = agentName,
    runId = runId.toHexString(),
    stepId = stepId,
    seq = seq,
    action = action,
    input = input,
    preview = AgentJson.json.encodeToJsonElement(ActionPreview.serializer(), preview).jsonObject,
    subject = subject?.dto(),
    subjectLabel = subjectLabel,
    status = status.name,
    approvers = approvers.name,
    createdAt = createdAt.toString(),
    expiresAt = expiresAt.toString(),
    decidedAt = decidedAt?.toString(),
    decidedByName = decidedByName,
    edited = edited,
    reason = reason,
    canDecide = canDecide,
)

internal fun AgentTask.dto() = TaskDto(
    id = id.toHexString(),
    title = title,
    detail = detail,
    subject = subject?.dto(),
    subjectLabel = subjectLabel,
    assigneeUserId = assigneeUserId,
    assigneeName = assigneeName,
    dueAt = dueAt?.toString(),
    status = status.name,
    agentId = agentId?.toHexString(),
    agentName = agentName,
    runId = runId?.toHexString(),
    createdAt = createdAt.toString(),
    completedAt = completedAt?.toString(),
)

internal fun Notification.dto(reader: String) = NotificationDto(
    id = id.toHexString(),
    kind = kind,
    params = params,
    body = body,
    link = link,
    subject = subject?.dto(),
    ref = ref,
    read = reader in readBy,
    createdAt = createdAt.toString(),
)
