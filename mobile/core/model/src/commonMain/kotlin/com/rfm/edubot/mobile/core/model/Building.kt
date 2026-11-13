package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable

/*
 * The Building category (`/app/api/building/…`): construction projects and their progress, the site
 * diary, issues, change orders and measurements, and My sites on a site manager's own sign-in
 * (`/app/api/portal/sites…`). The backend sends every field of these; the defaults only keep an older
 * or partial response readable.
 */

object ProjectStatus {
    const val PLANNING = "PLANNING"
    const val ACTIVE = "ACTIVE"
    const val ON_HOLD = "ON_HOLD"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"

    val all = listOf(PLANNING, ACTIVE, ON_HOLD, COMPLETED, CANCELLED)
}

/** Computed by the backend against the plan: `PLANNING`, `ON_TRACK`, `AT_RISK`, `DELAYED`, `ON_HOLD`, `DONE`, `CANCELLED`. */
object ProjectHealth {
    const val ON_TRACK = "ON_TRACK"
    const val AT_RISK = "AT_RISK"
    const val DELAYED = "DELAYED"
}

@Serializable
data class Phase(
    val id: String,
    val name: String,
    val plannedStart: String? = null,
    val plannedEnd: String? = null,
    val actualStart: String? = null,
    val actualEnd: String? = null,
    val progressPct: Int = 0,
    val weight: Int? = null,
    /** What the plan expects done by the end of yesterday. */
    val plannedPct: Int? = null,
    /** `NOT_STARTED`, `IN_PROGRESS`, `DONE` or `LATE`. */
    val state: String = "",
)

@Serializable
data class ContractLine(
    val id: String,
    val description: String = "",
    val quantity: Double = 0.0,
    val unit: String = "",
    val unitPriceEur: Double = 0.0,
    val totalEur: Double = 0.0,
    val measuredQty: Double = 0.0,
    val measuredPct: Int = 0,
)

@Serializable
data class Project(
    val id: String,
    val number: String = "",
    val name: String,
    val status: String = ProjectStatus.PLANNING,
    val health: String = "",
    val clientId: String? = null,
    val clientName: String? = null,
    val clientPhone: String? = null,
    val quoteId: String? = null,
    val quoteNumber: String? = null,
    val managerId: String? = null,
    val managerName: String? = null,
    val address: String? = null,
    val description: String? = null,
    val plannedStart: String? = null,
    val plannedEnd: String? = null,
    /** [plannedEnd] moved by the days approved changes add. */
    val revisedEnd: String? = null,
    val actualStart: String? = null,
    val actualEnd: String? = null,
    val progressPct: Int = 0,
    val plannedPct: Int? = null,
    /** Ahead (positive) or behind (negative) the plan, in days. */
    val scheduleDays: Int? = null,
    val retentionPct: Double = 0.0,
    val contractEur: Double = 0.0,
    val changesEur: Double = 0.0,
    val changeDays: Int = 0,
    val currentContractEur: Double = 0.0,
    val measuredEur: Double = 0.0,
    val toInvoiceEur: Double = 0.0,
    val retainedEur: Double = 0.0,
    val retentionHeldEur: Double = 0.0,
    val phaseCount: Int = 0,
    val latePhases: Int = 0,
    val openIssues: Int = 0,
    val overdueIssues: Int = 0,
    val pendingChanges: Int = 0,
    val photoCount: Int = 0,
    val lastDiaryDay: String? = null,
    val phases: List<Phase> = emptyList(),
    val contract: List<ContractLine> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    val label: String get() = listOf(number, name).filter { it.isNotBlank() }.joinToString(" · ")

    /** Planning, active or on hold: a diary entry or an issue can still be written for it. */
    val running: Boolean get() = status in setOf(ProjectStatus.PLANNING, ProjectStatus.ACTIVE, ProjectStatus.ON_HOLD)
}

/** `PATCH /building/projects/{id}`: only the fields sent change. */
@Serializable
data class ProjectStatusChange(val status: String)

/** `PATCH /building/projects/{id}/phases/{phaseId}`. */
@Serializable
data class PhaseProgressChange(val progressPct: Int)

object Weather {
    val all = listOf("CLEAR", "CLOUDY", "RAIN", "WIND", "STORM", "FOG")
}

@Serializable
data class PhaseProgressMove(val phaseId: String, val phaseName: String = "", val fromPct: Int = 0, val toPct: Int = 0)

@Serializable
data class SitePhoto(
    val id: String,
    val projectId: String = "",
    val logId: String? = null,
    val issueId: String? = null,
    val caption: String? = null,
    val day: String = "",
    val shared: Boolean = false,
    val uploadedBy: String = "",
    val createdAt: String = "",
)

/** One day of a project's site diary. */
@Serializable
data class SiteLog(
    val id: String,
    val projectId: String = "",
    val projectNumber: String = "",
    val projectName: String = "",
    val day: String = "",
    val weather: String? = null,
    val temperatureC: Int? = null,
    val workers: Int? = null,
    val workDone: String = "",
    val equipment: String? = null,
    val materials: String? = null,
    val visitors: String? = null,
    val incidents: String? = null,
    val lostHours: Double? = null,
    /** Shown on the client's page. */
    val shared: Boolean = false,
    val progress: List<PhaseProgressMove> = emptyList(),
    val photos: List<SitePhoto> = emptyList(),
    val createdBy: String = "",
    val updatedBy: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class PhaseProgressEntry(val phaseId: String, val progressPct: Int)

/**
 * A diary entry to write or edit (team: `/building/diary`, site manager: `/portal/sites/{id}/diary`).
 * On an edit an omitted field keeps what is stored, an empty string clears a text, and [clear] names
 * the numbers to clear (a JSON null can't say it).
 */
@Serializable
data class SaveSiteLog(
    val projectId: String? = null,
    val day: String? = null,
    val weather: String? = null,
    val temperatureC: Int? = null,
    val workers: Int? = null,
    val workDone: String? = null,
    val equipment: String? = null,
    val materials: String? = null,
    val visitors: String? = null,
    val incidents: String? = null,
    val lostHours: Double? = null,
    val shared: Boolean? = null,
    val phaseProgress: List<PhaseProgressEntry>? = null,
    val clear: List<String> = emptyList(),
)

object IssueStatus {
    const val OPEN = "OPEN"
    const val IN_PROGRESS = "IN_PROGRESS"
    const val RESOLVED = "RESOLVED"
    const val CLOSED = "CLOSED"

    val all = listOf(OPEN, IN_PROGRESS, RESOLVED, CLOSED)
}

object IssueKind {
    val all = listOf("PUNCH", "DEFECT", "SAFETY", "OTHER")
}

object IssuePriority {
    val all = listOf("LOW", "MEDIUM", "HIGH")
}

@Serializable
data class IssueEvent(val at: String = "", val by: String = "", val from: String? = null, val to: String = "", val note: String? = null)

@Serializable
data class SiteIssue(
    val id: String,
    val number: String = "",
    val projectId: String = "",
    val projectNumber: String = "",
    val projectName: String = "",
    val title: String = "",
    val description: String? = null,
    val location: String? = null,
    val kind: String = "",
    val priority: String = "",
    val status: String = IssueStatus.OPEN,
    val overdue: Boolean = false,
    val phaseId: String? = null,
    val phaseName: String? = null,
    val assigneeName: String? = null,
    val dueDate: String? = null,
    val resolvedAt: String? = null,
    val closedAt: String? = null,
    val history: List<IssueEvent> = emptyList(),
    val photos: List<SitePhoto> = emptyList(),
    val createdBy: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    val open: Boolean get() = status == IssueStatus.OPEN || status == IssueStatus.IN_PROGRESS
}

/** An issue to log, or a change to one: only the fields sent change; [note] goes with a status change. */
@Serializable
data class SaveIssue(
    val projectId: String? = null,
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val kind: String? = null,
    val priority: String? = null,
    val phaseId: String? = null,
    val dueDate: String? = null,
    val status: String? = null,
    val note: String? = null,
)

object ChangeOrderStatus {
    const val DRAFT = "DRAFT"
    const val SENT = "SENT"
    const val APPROVED = "APPROVED"
    const val REJECTED = "REJECTED"

    val all = listOf(DRAFT, SENT, APPROVED, REJECTED)
}

@Serializable
data class ChangeOrder(
    val id: String,
    val number: String = "",
    val projectId: String = "",
    val projectNumber: String = "",
    val projectName: String = "",
    val clientName: String? = null,
    val title: String = "",
    val description: String? = null,
    /** `ADDITION` or `OMISSION`. */
    val kind: String = "",
    val items: List<LineItem> = emptyList(),
    val totalEur: Double = 0.0,
    /** Negative for an omission. */
    val signedEur: Double = 0.0,
    val daysImpact: Int = 0,
    val status: String = ChangeOrderStatus.DRAFT,
    val sentAt: String? = null,
    val decidedAt: String? = null,
    val decidedBy: String? = null,
    val decisionNote: String? = null,
    /** `TEAM` or `CLIENT_LINK`, once decided. */
    val decidedVia: String? = null,
    /** A measurement measured its lines: it can't go back to draft. */
    val measured: Boolean = false,
    val createdBy: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
)

/** `POST /building/changes/{id}/status`. A team decision names who decided, optionally. */
@Serializable
data class ChangeStatus(val status: String, val decidedBy: String? = null, val note: String? = null)

object MeasurementStatus {
    const val DRAFT = "DRAFT"
    const val APPROVED = "APPROVED"
    const val INVOICED = "INVOICED"

    val all = listOf(DRAFT, APPROVED, INVOICED)
}

@Serializable
data class MeasurementLine(
    val key: String,
    val description: String = "",
    val unit: String = "",
    val quantity: Double = 0.0,
    val unitPriceEur: Double = 0.0,
    val totalEur: Double = 0.0,
    val previousQty: Double = 0.0,
    val toDateQty: Double = 0.0,
    val periodQty: Double = 0.0,
    val previousEur: Double = 0.0,
    val toDateEur: Double = 0.0,
    val periodEur: Double = 0.0,
    val pctToDate: Double = 0.0,
)

/** An auto de medição: the work measured to a day, billed against the contract. */
@Serializable
data class Measurement(
    val id: String,
    val number: Int = 0,
    val projectId: String = "",
    val projectNumber: String = "",
    val projectName: String = "",
    val clientName: String? = null,
    val periodEnd: String = "",
    val status: String = MeasurementStatus.DRAFT,
    val retentionPct: Double = 0.0,
    val contractEur: Double = 0.0,
    val previousEur: Double = 0.0,
    val toDateEur: Double = 0.0,
    val grossEur: Double = 0.0,
    val retentionEur: Double = 0.0,
    val netEur: Double = 0.0,
    val pctToDate: Double = 0.0,
    val lines: List<MeasurementLine> = emptyList(),
    val notes: String? = null,
    val invoiceId: String? = null,
    val invoiceNumber: String? = null,
    val approvedAt: String? = null,
    val approvedBy: String? = null,
    /** Only the latest of a project can be reopened. */
    val latest: Boolean = false,
    val createdBy: String = "",
    val createdAt: String = "",
)

@Serializable
data class DueDate(val dueDate: String)

@Serializable
data class MeasurementInvoiced(val measurement: Measurement, val invoiceId: String = "", val invoiceNumber: String = "")

/** A project on its site manager's My sites: no money anywhere. */
@Serializable
data class MySite(
    val id: String,
    val number: String = "",
    val name: String,
    val address: String? = null,
    val status: String = ProjectStatus.ACTIVE,
    val progressPct: Int = 0,
    val plannedEnd: String? = null,
    /** Today's diary entry, once written. */
    val todayEntryId: String? = null,
    val openIssues: Int = 0,
) {
    val label: String get() = listOf(number, name).filter { it.isNotBlank() }.joinToString(" · ")
}

@Serializable
data class MySitePhase(val id: String, val name: String, val progressPct: Int = 0, val state: String = "")

/** `GET /portal/sites/{id}`: the diary is its last two weeks; [canDiary] and [canIssues] follow the company's modules. */
@Serializable
data class MySiteDetail(
    val site: MySite,
    val today: String = "",
    val phases: List<MySitePhase> = emptyList(),
    val diary: List<SiteLog> = emptyList(),
    val issues: List<SiteIssue> = emptyList(),
    val photos: List<SitePhoto> = emptyList(),
    val canDiary: Boolean = false,
    val canIssues: Boolean = false,
)
