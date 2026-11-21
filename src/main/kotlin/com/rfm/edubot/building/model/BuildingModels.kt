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

/**
 * The link the client follows the project from. A new [token] replaces the old one, which stops working.
 * [showBudget] puts the money on that page (the contract, the changes, what was measured and billed); off, it shows none.
 */
data class ProjectShare(
    val token: String,
    val enabled: Boolean,
    val createdAt: Instant,
    val showBudget: Boolean = false,
)

/**
 * Where an answer given from a link came from (the client approving a change, a supplier fixing an issue), kept
 * as proof beside the name they typed: the address the request came from and the browser's description of itself.
 * The team sees it; the person who answered never gets it back.
 */
data class LinkProof(val ip: String?, val userAgent: String?)

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

/**
 * One status change of an issue; [from] is null for its creation. [via] is null for the team's, and
 * [DecidedVia.SUPPLIER_LINK] for the assigned supplier's from their link, with its [proof].
 */
data class IssueEvent(
    val at: Instant,
    val by: String,
    val from: IssueStatus?,
    val to: IssueStatus,
    val note: String? = null,
    val via: DecidedVia? = null,
    val proof: LinkProof? = null,
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

/**
 * How a change order or a measurement was decided: by someone on the team, or by the client from its link. An
 * issue's history says [SUPPLIER_LINK] for what the assigned supplier did from theirs.
 */
enum class DecidedVia { TEAM, CLIENT_LINK, SUPPLIER_LINK }

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
    /** Where the client's answer from the link came from; null for the team's. */
    val decisionProof: LinkProof? = null,
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

/**
 * [DRAFT] while the team measures; a draft with an [Measurement.approval] link is waiting for the client, who
 * approves it or disputes it from there. [APPROVED] (by the client or the team) is what gets invoiced.
 */
enum class MeasurementStatus { DRAFT, APPROVED, INVOICED }

/** The link a document sent to the client opens (`/app/share/measurement/{token}`), made when it was sent. */
data class ApprovalLink(val token: String, val createdAt: Instant)

/** The client said no to a measurement from its link: what they wrote, and where it came from. */
data class MeasurementDispute(val name: String, val note: String, val at: Instant, val proof: LinkProof?)

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
    /** The team member who approved it, or the name the client typed on the link. */
    val approvedBy: String? = null,
    val approvedVia: DecidedVia? = null,
    val approvalProof: LinkProof? = null,
    val invoicedAt: Instant? = null,
    /** Sent to the client to approve: kept after their answer so the link shows it, dropped when the draft is edited. */
    val approval: ApprovalLink? = null,
    /** The client's last dispute of this draft, until it is sent again. */
    val dispute: MeasurementDispute? = null,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Sent to the client and not answered yet. */
    val awaitingClient: Boolean get() = status == MeasurementStatus.DRAFT && approval != null && dispute == null
}

/**
 * The link a supplier opens to see the issues assigned to them, across the company's projects, and say they
 * started or fixed one (`/app/share/issues/{token}`). One per supplier; a new [token] replaces the old.
 */
data class SupplierLink(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val supplierId: ObjectId,
    val token: String,
    val createdBy: String,
    val createdAt: Instant,
)
