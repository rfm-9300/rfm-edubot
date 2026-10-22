package com.rfm.edubot.building.model

import com.rfm.edubot.crm.model.LineItem
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId

enum class ProjectStatus { PLANNING, ACTIVE, ON_HOLD, COMPLETED, CANCELLED }

/**
 * One line of the contract a project is billed against: a line of the quote it came from, or one typed in.
 * Measurements refer to it by [id], which stays when the line is edited.
 */
data class ContractLine(
    val id: String,
    val description: String,
    val quantity: Double,
    val unit: String,
    val unitPriceCents: Long,
    val totalCents: Long,
)

/** A stage of the work with its own planned dates and progress (0–100). */
data class ProjectPhase(
    val id: String,
    val name: String,
    val plannedStart: LocalDate? = null,
    val plannedEnd: LocalDate? = null,
    val actualStart: LocalDate? = null,
    val actualEnd: LocalDate? = null,
    val progressPct: Int = 0,
    /** How much of the project this phase is, against the others; without one it weighs its planned days. */
    val weight: Int? = null,
)

/**
 * Retention given back at the end of the job: one invoice to the client of what was still held. [invoiceId] is null
 * only while that invoice is being written; cancelling or deleting it removes the release, and the amount is held again.
 */
data class RetentionRelease(
    val id: String,
    val invoiceId: ObjectId?,
    val amountCents: Long,
    val releasedBy: String,
    val releasedAt: Instant,
)

/** The link the client follows the project from. A new [token] replaces the old one, which stops working. */
data class ProjectShare(
    val token: String,
    val enabled: Boolean,
    val createdAt: Instant,
)

/**
 * A construction or renovation job. [contract] is what measurements bill (locked once one exists);
 * [manualProgressPct] is its progress while it has no [phases]. [version] is bumped on every write, and a
 * write only lands on the version it was computed from.
 */
data class Project(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val number: String,
    val name: String,
    val status: ProjectStatus = ProjectStatus.PLANNING,
    val clientId: ObjectId? = null,
    val quoteId: ObjectId? = null,
    /** The employee who runs the site. */
    val managerId: ObjectId? = null,
    val address: String? = null,
    val description: String? = null,
    val plannedStart: LocalDate? = null,
    val plannedEnd: LocalDate? = null,
    val actualStart: LocalDate? = null,
    val actualEnd: LocalDate? = null,
    val contract: List<ContractLine> = emptyList(),
    /** Held back from each measurement until the end of the job (5 = 5%). */
    val retentionPct: Double = 0.0,
    val retentionReleases: List<RetentionRelease> = emptyList(),
    /** What the job is expected to cost the company, which its costs are measured against. */
    val costBudgetCents: Long? = null,
    /** What an hour of the team's work costs on this job; hours clocked at [siteIds] cost this much each. */
    val laborRateCents: Long? = null,
    /** The time clock's work sites of this job: shifts clocked at them are its hours. A site is one project's. */
    val siteIds: List<ObjectId> = emptyList(),
    val manualProgressPct: Int = 0,
    val phases: List<ProjectPhase> = emptyList(),
    val share: ProjectShare? = null,
    /** The health (a `ProjectHealth` name) the hourly watch last saw while the project ran, to tell when it moves. */
    val watchedHealth: String? = null,
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val contractCents: Long get() = contract.sumOf { it.totalCents }
}

enum class Weather { CLEAR, CLOUDY, RAIN, WIND, STORM, FOG }

/** A phase's progress as a diary entry moved it. */
data class PhaseProgressChange(
    val phaseId: String,
    val phaseName: String,
    val fromPct: Int,
    val toPct: Int,
)

/** The site diary (diário de obra) of one project for one company-local [day]: one entry per day. */
data class SiteLogEntry(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val projectId: ObjectId,
    val day: LocalDate,
    val weather: Weather? = null,
    val temperatureC: Int? = null,
    val workers: Int? = null,
    val workDone: String,
    val equipment: String? = null,
    val materials: String? = null,
    val visitors: String? = null,
    val incidents: String? = null,
    /** Work hours lost to weather, a missing delivery, an inspection… */
    val lostHours: Double? = null,
    /** On the client's page, with its shared photos. */
    val shared: Boolean = false,
    val progress: List<PhaseProgressChange> = emptyList(),
    val createdBy: String,
    val updatedBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** A photo of a project, on disk beside the company's logo; optionally of a diary day, an issue or a phase. */
data class SitePhoto(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val projectId: ObjectId,
    val logId: ObjectId? = null,
    val issueId: ObjectId? = null,
    val phaseId: String? = null,
    val caption: String? = null,
    /** Company-local day it was taken (the upload's, when the browser didn't say). */
    val day: LocalDate,
    val takenAt: Instant? = null,
    val shared: Boolean,
    val path: String,
    val thumbPath: String,
    val bytes: Long,
    val uploadedBy: String,
    val createdAt: Instant,
)

enum class IssueKind { PUNCH, DEFECT, SAFETY, OTHER }

enum class IssuePriority { LOW, MEDIUM, HIGH }

enum class IssueStatus { OPEN, IN_PROGRESS, RESOLVED, CLOSED }

/** One status change of an issue; [from] is null for its creation. */
data class IssueEvent(
    val at: Instant,
    val by: String,
    val from: IssueStatus?,
    val to: IssueStatus,
    val note: String? = null,
)

/** Something on site to fix or finish (a punch item, a defect, a safety problem), assigned to an employee or a supplier. */
data class SiteIssue(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val projectId: ObjectId,
    val number: String,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val kind: IssueKind = IssueKind.PUNCH,
    val priority: IssuePriority = IssuePriority.MEDIUM,
    val status: IssueStatus = IssueStatus.OPEN,
    val phaseId: String? = null,
    val employeeId: ObjectId? = null,
    val supplierId: ObjectId? = null,
    val dueDate: LocalDate? = null,
    val resolvedAt: Instant? = null,
    val closedAt: Instant? = null,
    val history: List<IssueEvent> = emptyList(),
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isOpen: Boolean get() = status == IssueStatus.OPEN || status == IssueStatus.IN_PROGRESS

    fun isOverdue(today: LocalDate): Boolean = isOpen && dueDate != null && dueDate < today
}

/** Work added to the contract (trabalhos a mais) or taken out of it (trabalhos a menos). */
enum class ChangeOrderKind { ADDITION, OMISSION }

enum class ChangeOrderStatus { DRAFT, SENT, APPROVED, REJECTED }

/** How a change order was decided: by someone on the team, or by the client from the change's link. */
enum class DecidedVia { TEAM, CLIENT_LINK }

/**
 * The link a sent change gives its client to approve or reject it (`/app/share/change/{token}`). It keeps
 * showing the decision afterwards, and ends when the change goes back to draft, edited or not.
 */
data class ChangeApproval(val token: String, val createdAt: Instant)

/**
 * A change to a project's contract. Approved, an addition's lines are measured and billed like the contract's,
 * an omission lowers the contract's value, and [daysImpact] moves the planned end.
 */
data class ChangeOrder(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val projectId: ObjectId,
    val number: String,
    val title: String,
    val description: String? = null,
    val kind: ChangeOrderKind = ChangeOrderKind.ADDITION,
    val items: List<LineItem>,
    val totalCents: Long,
    val daysImpact: Int = 0,
    val status: ChangeOrderStatus = ChangeOrderStatus.DRAFT,
    val sentAt: Instant? = null,
    val decidedAt: Instant? = null,
    /** Who said yes or no: the client's name, or the team member who recorded it. */
    val decidedBy: String? = null,
    val decisionNote: String? = null,
    val decidedVia: DecidedVia? = null,
    val approval: ChangeApproval? = null,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** What it does to the contract's value. */
    val signedCents: Long get() = if (kind == ChangeOrderKind.OMISSION) -totalCents else totalCents
}

/**
 * A short update for the client about the work from [from] to [to], written by the team, the AI's draft or their
 * own; the client's page shows it once posted.
 */
data class ProjectReport(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val projectId: ObjectId,
    val from: LocalDate,
    val to: LocalDate,
    val text: String,
    val postedBy: String,
    val postedAt: Instant,
    val updatedAt: Instant,
)

enum class MeasurementStatus { DRAFT, APPROVED, INVOICED }

/**
 * A billable line as one measurement measured it: the contracted [quantity] and value, and how much of it was
 * done by the previous measurement ([previousQty]) and by this one ([toDateQty]), both to date.
 */
data class MeasurementLine(
    /** A contract line's id, or `co:<change order id>:<line index>`. */
    val key: String,
    val description: String,
    val unit: String,
    val quantity: Double,
    val unitPriceCents: Long,
    val totalCents: Long,
    val previousQty: Double,
    val toDateQty: Double,
)

/**
 * A measurement of the work done (auto de medição): numbered per project, measured up to [periodEnd]. The
 * totals are kept as they were computed on its last save ([grossCents] billed this period).
 */
data class Measurement(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val projectId: ObjectId,
    val number: Int,
    val periodEnd: LocalDate,
    val status: MeasurementStatus = MeasurementStatus.DRAFT,
    val lines: List<MeasurementLine>,
    val retentionPct: Double,
    val grossCents: Long,
    val retentionCents: Long,
    val netCents: Long,
    val toDateCents: Long,
    val notes: String? = null,
    val invoiceId: ObjectId? = null,
    val approvedAt: Instant? = null,
    val approvedBy: String? = null,
    val invoicedAt: Instant? = null,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
