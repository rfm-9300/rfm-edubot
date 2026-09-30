package com.rfm.edubot.agents.model

import com.rfm.edubot.events.SubjectRef
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.bson.types.ObjectId

enum class AgentStatus { DRAFT, ACTIVE, PAUSED, ARCHIVED }

/** Gallery grouping only: every kind runs on the same engine. */
enum class AgentKind { WORKFLOW, AI_WORKER, DIGEST, MONITOR }

/** What a step that changes something does: act, ask a person first, or only write down what it would do. */
enum class Autonomy { AUTO, APPROVE, DRAFT }

enum class ErrorPolicy { RETRY_THEN_FAIL, CONTINUE, STOP }

enum class ConditionMatch { ALL, ANY }

/** Who may approve an agent's actions. */
enum class Approvers { ANY_MEMBER, ADMINS }

@Serializable
data class Condition(val field: String, val op: String, val value: JsonElement? = null)

@Serializable
data class ConditionGroup(val match: ConditionMatch = ConditionMatch.ALL, val conditions: List<Condition> = emptyList()) {
    val isEmpty: Boolean get() = conditions.isEmpty()
}

@Serializable
data class TriggerSpec(val id: String, val type: String, val config: JsonObject = JsonObject(emptyMap()))

@Serializable
data class StepSpec(
    val id: String,
    val action: String,
    val input: JsonObject = JsonObject(emptyMap()),
    val guard: ConditionGroup? = null,
    val autonomy: Autonomy? = null,
    val onError: ErrorPolicy = ErrorPolicy.RETRY_THEN_FAIL,
    val label: String? = null,
)

/** A later event on the run's record that ends the sequence, e.g. `invoice.paid` stops the reminders. */
@Serializable
data class ExitRule(val event: String, val conditions: ConditionGroup? = null)

/** Local wall-clock window in which agents don't message customers; [start] after [end] spans midnight. */
@Serializable
data class QuietHours(val start: String = "21:00", val end: String = "08:00")

@Serializable
data class AgentPolicy(
    val autonomy: Autonomy = Autonomy.APPROVE,
    /** Null uses the company default. */
    val quietHours: QuietHours? = null,
    val businessDaysOnly: Boolean? = null,
    val maxRunsPerDay: Int = 200,
    /** Minimum hours between two runs of this agent on the same record. */
    val cooldownHours: Int = 0,
    val approvers: Approvers = Approvers.ANY_MEMBER,
    /** Dashboard users notified on failures and approvals; empty = the company's admins. */
    val notifyUserIds: List<String> = emptyList(),
    val notifyOnFailure: Boolean = true,
    val maxTokensPerRun: Int = 20_000,
)

@Serializable
data class AgentVoice(
    /** friendly, formal or brief. */
    val tone: String = "friendly",
    /** Null uses the client's or the company's language. */
    val language: String? = null,
    val signature: String? = null,
    val usePersona: Boolean = false,
    val instructions: String? = null,
    val emoji: Boolean = false,
)

/** The executable part of an agent, snapshotted onto each run so edits never change a sequence in flight. */
@Serializable
data class AgentDefinition(
    val triggers: List<TriggerSpec> = emptyList(),
    val conditions: ConditionGroup? = null,
    val steps: List<StepSpec> = emptyList(),
    val exitRules: List<ExitRule> = emptyList(),
    val policy: AgentPolicy = AgentPolicy(),
    val voice: AgentVoice = AgentVoice(),
)

@Serializable
data class AgentStats(
    val runs: Long = 0,
    val succeeded: Long = 0,
    val failed: Long = 0,
    val lastRunAt: Instant? = null,
    val consecutiveFailures: Int = 0,
    /** Approvals granted without edits since the last edit or rejection; the UI suggests Auto after a few. */
    val approvalsInARow: Int = 0,
)

data class Agent(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val name: String,
    val description: String? = null,
    val icon: String? = null,
    val kind: AgentKind = AgentKind.WORKFLOW,
    val templateKey: String? = null,
    val templateParams: JsonObject? = null,
    val status: AgentStatus = AgentStatus.DRAFT,
    val definition: AgentDefinition = AgentDefinition(),
    val version: Int = 1,
    val createdBy: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val stats: AgentStats = AgentStats(),
    /** Why the agent stopped by itself (circuit breaker), shown until someone reactivates it. */
    val pausedReason: String? = null,
    /** Next fire time of each schedule trigger, by trigger id. */
    val scheduleState: Map<String, Instant> = emptyMap(),
)

enum class RunStatus {
    QUEUED, RUNNING, WAITING, AWAITING_APPROVAL, SUCCEEDED, FAILED, CANCELLED, SKIPPED, NEEDS_REVIEW;

    val finished: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED || this == SKIPPED

    /** Runs that still have work ahead: shown as "upcoming" on records, cancelled by exit rules. */
    val open: Boolean get() = this == QUEUED || this == RUNNING || this == WAITING || this == AWAITING_APPROVAL
}

enum class StepStatus { PENDING, RUNNING, DONE, SKIPPED, WAITING, AWAITING_APPROVAL, FAILED, REJECTED, DRAFTED }

@Serializable
data class StepResult(
    val stepId: String,
    val action: String,
    val status: StepStatus,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val attempts: Int = 0,
    val input: JsonObject? = null,
    val output: JsonObject? = null,
    val error: String? = null,
    /** Why a step behaved unusually: deferred to quiet hours, fell back to email, skipped by its guard. */
    val note: String? = null,
)

@Serializable
data class RunTrigger(
    val type: String,
    val triggerId: String? = null,
    val eventId: String? = null,
    val eventType: String? = null,
    val firedAt: Instant,
    /** The dashboard user who ran it by hand. */
    val byUserId: String? = null,
)

data class AgentRun(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val agentId: ObjectId,
    val agentName: String,
    val agentVersion: Int,
    val definition: AgentDefinition,
    val trigger: RunTrigger,
    val subject: SubjectRef? = null,
    val subjectLabel: String? = null,
    val dedupeKey: String,
    val status: RunStatus = RunStatus.QUEUED,
    val currentStep: Int = 0,
    val context: JsonObject = JsonObject(emptyMap()),
    val steps: List<StepResult> = emptyList(),
    val resumeAt: Instant? = null,
    val depth: Int = 0,
    val dryRun: Boolean = false,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val claimedAt: Instant? = null,
    val error: String? = null,
    val outcome: String? = null,
)

enum class ApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED }

/** What a person sees before an action runs: final text, recipients, attachments or field changes. */
@Serializable
data class ActionPreview(
    /** message, email, crm, task, notify, document or generic; the dashboard picks its layout from it. */
    val kind: String,
    val channel: String? = null,
    val recipients: List<String> = emptyList(),
    val subject: String? = null,
    val body: String? = null,
    val attachments: List<String> = emptyList(),
    /** Field changes or details, label key → value. */
    val fields: Map<String, String> = emptyMap(),
    /** Input keys a person may edit before approving (e.g. body, subject). */
    val editable: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class AgentApproval(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val agentId: ObjectId,
    val agentName: String,
    val runId: ObjectId,
    val stepId: String,
    val action: String,
    val input: JsonObject,
    val preview: ActionPreview,
    val subject: SubjectRef? = null,
    val subjectLabel: String? = null,
    val approvers: Approvers = Approvers.ANY_MEMBER,
    val status: ApprovalStatus = ApprovalStatus.PENDING,
    val createdAt: Instant,
    val expiresAt: Instant,
    val decidedAt: Instant? = null,
    val decidedBy: String? = null,
    val decidedByName: String? = null,
    val edited: Boolean = false,
    val reason: String? = null,
    val dryRun: Boolean = false,
)

enum class TaskStatus { OPEN, DONE, DISMISSED }

data class AgentTask(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val title: String,
    val detail: String? = null,
    val subject: SubjectRef? = null,
    val subjectLabel: String? = null,
    val assigneeUserId: String? = null,
    val assigneeName: String? = null,
    val dueAt: Instant? = null,
    val status: TaskStatus = TaskStatus.OPEN,
    val agentId: ObjectId? = null,
    val agentName: String? = null,
    val runId: ObjectId? = null,
    val createdBy: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
    val completedBy: String? = null,
)

/** Company defaults the tenant edits in Agents → Settings. */
@Serializable
data class CompanyAgentSettings(
    val paused: Boolean = false,
    val defaultAutonomy: Autonomy = Autonomy.APPROVE,
    val quietHours: QuietHours? = QuietHours(),
    val businessDaysOnly: Boolean = false,
    val perRecipientDailyCap: Int = 2,
    val perRecipientWeeklyCap: Int = 5,
    val approvalExpiryDays: Int = 3,
)

/** Limits only the backoffice sets. */
@Serializable
data class PlatformAgentLimits(
    val agentsPaused: Boolean = false,
    val maxActiveAgents: Int = 25,
    val runsPerDay: Int = 2_000,
    val emailSendsPerDay: Int = 300,
)

data class AgentSettings(
    val tenantId: ObjectId,
    val company: CompanyAgentSettings = CompanyAgentSettings(),
    val platform: PlatformAgentLimits = PlatformAgentLimits(),
    val updatedAt: Instant? = null,
)

enum class OutboundStatus { SENDING, SENT, FAILED }

data class OutboundLogEntry(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val idempotencyKey: String,
    /** whatsapp, instagram or email. */
    val channel: String,
    /** Normalised: digits for phones, lower-case for emails. */
    val recipient: String,
    val status: OutboundStatus,
    val at: Instant,
    val runId: ObjectId? = null,
    val agentId: ObjectId? = null,
    val providerMessageId: String? = null,
    val error: String? = null,
)
