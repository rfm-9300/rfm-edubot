package com.rfm.edubot.building.api

import com.rfm.edubot.crm.api.LineItemDto
import kotlinx.serialization.Serializable

// Responses carry no default values: the JSON config leaves out properties equal to their default, and the
// dashboard reads every one of these.

@Serializable
internal data class PhaseDto(
    val id: String,
    val name: String,
    val plannedStart: String?,
    val plannedEnd: String?,
    val actualStart: String?,
    val actualEnd: String?,
    val progressPct: Int,
    val weight: Int?,
    /** What the plan expects done by the end of yesterday. */
    val plannedPct: Int?,
    val state: String,
)

@Serializable
internal data class ContractLineDto(
    val id: String,
    val description: String,
    val quantity: Double,
    val unit: String,
    val unitPriceEur: Double,
    val totalEur: Double,
    /** Measured to date by the latest approved measurement. */
    val measuredQty: Double,
    val measuredPct: Int,
)

@Serializable
internal data class ProjectDto(
    val id: String,
    val number: String,
    val name: String,
    val status: String,
    val health: String,
    val clientId: String?,
    val clientName: String?,
    val clientPhone: String?,
    val quoteId: String?,
    val quoteNumber: String?,
    val managerId: String?,
    val managerName: String?,
    val address: String?,
    val description: String?,
    /** The dates typed on the project itself (the ones agreed with the client), which [plannedEnd] prefers to the phases'. */
    val projectStart: String?,
    val projectEnd: String?,
    val plannedStart: String?,
    /** The project's own planned end, or its last phase's. */
    val plannedEnd: String?,
    /** [plannedEnd] moved by the days approved changes add. */
    val revisedEnd: String?,
    val actualStart: String?,
    val actualEnd: String?,
    val progressPct: Int,
    val plannedPct: Int?,
    /** Ahead (positive) or behind (negative) the plan, in days. */
    val scheduleDays: Int?,
    val manualProgressPct: Int,
    val retentionPct: Double,
    val contractEur: Double,
    /** What approved changes add (negative when omissions win). */
    val changesEur: Double,
    val changeDays: Int,
    val currentContractEur: Double,
    val measuredEur: Double,
    /** Approved measurements still to invoice, net of retention. */
    val toInvoiceEur: Double,
    val retainedEur: Double,
    /** What the invoiced measurements kept back and wasn't given back yet: invoiced to the client once the project is finished. */
    val retentionHeldEur: Double,
    val retentionReleasedEur: Double,
    /** Only on one project's record. */
    val retentionReleases: List<RetentionReleaseDto>,
    val costBudgetEur: Double?,
    val laborRateEur: Double?,
    /** The time clock's work sites of the project. */
    val siteIds: List<String>,
    val phaseCount: Int,
    val latePhases: Int,
    val openIssues: Int,
    val overdueIssues: Int,
    val pendingChanges: Int,
    val photoCount: Int,
    val lastDiaryDay: String?,
    val contractLocked: Boolean,
    val shareEnabled: Boolean,
    /** The client's page shows the money. */
    val shareBudget: Boolean,
    /** Only on one project's record. */
    val shareToken: String?,
    val phases: List<PhaseDto>,
    val contract: List<ContractLineDto>,
    val createdAt: String,
    val updatedAt: String,
)

/** A project's costs tab: [bills] without payments and [labour] without the time clock are null. */
@Serializable
internal data class ProjectCostsDto(
    val currentContractEur: Double,
    val measuredEur: Double,
    val invoicedEur: Double,
    val receivedEur: Double,
    val bills: CostBillsDto?,
    val labour: CostLabourDto?,
    val costBudgetEur: Double?,
    val totalCostEur: Double,
    /** The costs as a share of the budget, past 100 when over it. */
    val budgetUsedPct: Double?,
    val expectedMarginEur: Double,
    val expectedMarginPct: Double?,
    val marginToDateEur: Double,
    val marginToDatePct: Double?,
)

@Serializable
internal data class CostBillsDto(val totalEur: Double, val paidEur: Double, val toPayEur: Double, val byPayee: List<CostPayeeDto>, val payments: List<CostPaymentDto>)

/** [kind] is `supplier` or `employee`. */
@Serializable
internal data class CostPayeeDto(val kind: String, val id: String?, val name: String, val totalEur: Double, val paidEur: Double, val count: Int)

@Serializable
internal data class CostPaymentDto(val id: String, val number: String, val payeeName: String, val dueDate: String, val status: String, val totalEur: Double)

/** [costEur] is null without a labour rate. */
@Serializable
internal data class CostLabourDto(val hours: Double, val rateEur: Double?, val costEur: Double?, val byEmployee: List<CostHoursDto>, val sites: List<CostSiteDto>)

@Serializable
internal data class CostHoursDto(val employeeId: String, val name: String, val hours: Double, val costEur: Double?)

@Serializable
internal data class CostSiteDto(val id: String, val name: String)

/** A project on its site manager's My sites page: no money anywhere. */
@Serializable
internal data class MySiteDto(
    val id: String,
    val number: String,
    val name: String,
    val address: String?,
    val status: String,
    val progressPct: Int,
    val plannedEnd: String?,
    /** Today's diary entry, once written. */
    val todayEntryId: String?,
    val openIssues: Int,
)

@Serializable
internal data class MySitePhaseDto(val id: String, val name: String, val progressPct: Int, val state: String)

/** [canDiary] and [canIssues] follow the company's modules; the diary is its last two weeks. */
@Serializable
internal data class MySiteDetailDto(
    val site: MySiteDto,
    val today: String,
    val phases: List<MySitePhaseDto>,
    val diary: List<SiteLogDto>,
    val issues: List<IssueDto>,
    val photos: List<PhotoDto>,
    val canDiary: Boolean,
    val canIssues: Boolean,
)

@Serializable
internal data class ReportDraftDto(val from: String, val to: String, val text: String, val sharedDays: Int)

@Serializable
internal data class ReportDto(val id: String, val projectId: String, val from: String, val to: String, val text: String, val postedBy: String, val postedAt: String, val updatedAt: String)

@Serializable
internal data class SharedReportDto(val from: String, val to: String, val text: String, val postedAt: String)

@Serializable
internal data class RetentionReleaseDto(val invoiceId: String?, val invoiceNumber: String?, val amountEur: Double, val releasedBy: String, val releasedAt: String)

@Serializable
internal data class PhotoDto(
    val id: String,
    val projectId: String,
    val logId: String?,
    val issueId: String?,
    val phaseId: String?,
    val caption: String?,
    val day: String,
    val takenAt: String?,
    val shared: Boolean,
    val uploadedBy: String,
    val createdAt: String,
)

@Serializable
internal data class PhaseProgressChangeDto(val phaseId: String, val phaseName: String, val fromPct: Int, val toPct: Int)

@Serializable
internal data class SiteLogDto(
    val id: String,
    val projectId: String,
    val projectNumber: String,
    val projectName: String,
    val day: String,
    val weather: String?,
    val temperatureC: Int?,
    val workers: Int?,
    val workDone: String,
    val equipment: String?,
    val materials: String?,
    val visitors: String?,
    val incidents: String?,
    val lostHours: Double?,
    val shared: Boolean,
    val progress: List<PhaseProgressChangeDto>,
    val photos: List<PhotoDto>,
    val createdBy: String,
    val updatedBy: String,
    val createdAt: String,
    val updatedAt: String,
)

/** [via] is `SUPPLIER_LINK` for what the assigned supplier did from their link, with where it came from. */
@Serializable
internal data class IssueEventDto(val at: String, val by: String, val from: String?, val to: String, val note: String?, val via: String?, val ip: String?, val device: String?)

@Serializable
internal data class IssueDto(
    val id: String,
    val number: String,
    val projectId: String,
    val projectNumber: String,
    val projectName: String,
    val title: String,
    val description: String?,
    val location: String?,
    val kind: String,
    val priority: String,
    val status: String,
    val overdue: Boolean,
    val phaseId: String?,
    val phaseName: String?,
    val employeeId: String?,
    val supplierId: String?,
    val assigneeName: String?,
    /** The assigned supplier's phone and their issues link, on one issue's record only. */
    val supplierPhone: String?,
    val supplierLinkToken: String?,
    val dueDate: String?,
    val resolvedAt: String?,
    val closedAt: String?,
    val history: List<IssueEventDto>,
    val photos: List<PhotoDto>,
    val createdBy: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
internal data class ChangeOrderDto(
    val id: String,
    val number: String,
    val projectId: String,
    val projectNumber: String,
    val projectName: String,
    val clientName: String?,
    val clientPhone: String?,
    val title: String,
    val description: String?,
    val kind: String,
    val items: List<LineItemDto>,
    val totalEur: Double,
    val signedEur: Double,
    val daysImpact: Int,
    val status: String,
    val sentAt: String?,
    val decidedAt: String?,
    val decidedBy: String?,
    val decisionNote: String?,
    /** `TEAM` or `CLIENT_LINK`, once decided. */
    val decidedVia: String?,
    /** Where the client's answer from the link came from: the address and the browser. */
    val decisionIp: String?,
    val decisionDevice: String?,
    /** The client's approval link (`/app/share/change/{token}`), from when it was sent until it goes back to draft. */
    val approvalToken: String?,
    /** The project's contract and end without this change (with the other approved ones) and with it. */
    val contractBeforeEur: Double,
    val contractAfterEur: Double,
    val endBefore: String?,
    val endAfter: String?,
    /** A measurement measured some of its lines: it can't go back to draft. */
    val measured: Boolean,
    val createdBy: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
internal data class MeasurementLineDto(
    val key: String,
    val description: String,
    val unit: String,
    val quantity: Double,
    val unitPriceEur: Double,
    val totalEur: Double,
    val previousQty: Double,
    val toDateQty: Double,
    val periodQty: Double,
    val previousEur: Double,
    val toDateEur: Double,
    val periodEur: Double,
    val pctToDate: Double,
)

@Serializable
internal data class MeasurementDto(
    val id: String,
    val number: Int,
    val projectId: String,
    val projectNumber: String,
    val projectName: String,
    val clientId: String?,
    val clientName: String?,
    val clientPhone: String?,
    val periodEnd: String,
    val status: String,
    val retentionPct: Double,
    val contractEur: Double,
    val previousEur: Double,
    val toDateEur: Double,
    val grossEur: Double,
    val retentionEur: Double,
    val netEur: Double,
    val pctToDate: Double,
    val lines: List<MeasurementLineDto>,
    val notes: String?,
    val invoiceId: String?,
    val invoiceNumber: String?,
    val invoiceStatus: String?,
    val approvedAt: String?,
    val approvedBy: String?,
    /** `TEAM` or `CLIENT_LINK`, once approved; from the link, where the answer came from. */
    val approvedVia: String?,
    val approvalIp: String?,
    val approvalDevice: String?,
    val invoicedAt: String?,
    /** Its client's approval link (`/app/share/measurement/{token}`) once sent, until the draft is edited or reopened. */
    val approvalToken: String?,
    val sentAt: String?,
    /** Sent and not answered yet. */
    val awaitingClient: Boolean,
    /** The client's dispute of this draft, until it is sent again. */
    val dispute: MeasurementDisputeDto?,
    /** Only the latest measurement of a project can be reopened or deleted. */
    val latest: Boolean,
    val createdBy: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
internal data class MeasurementDisputeDto(val name: String, val note: String, val at: String, val ip: String?, val device: String?)

@Serializable
internal data class MeasurementInvoicedDto(val measurement: MeasurementDto, val invoiceId: String, val invoiceNumber: String)

@Serializable
internal data class RetentionInvoicedDto(val project: ProjectDto, val invoiceId: String, val invoiceNumber: String)

/** What the client's page shows: progress, the plan and what the team chose to share. No money, issues or people. */
@Serializable
internal data class SharedProjectDto(
    val company: String,
    val hasLogo: Boolean,
    val locale: String,
    val number: String,
    val name: String,
    val status: String,
    val address: String?,
    val progressPct: Int,
    val plannedStart: String?,
    val plannedEnd: String?,
    val actualStart: String?,
    val actualEnd: String?,
    val phases: List<SharedPhaseDto>,
    /** The updates the team posted, newest first. */
    val reports: List<SharedReportDto>,
    val updates: List<SharedUpdateDto>,
    val photos: List<SharedPhotoDto>,
    /** What waits for the client's answer (changes and measurements sent), each with its own page. */
    val decisions: List<SharedDecisionDto>,
    /** The changes they or the team decided, newest first. */
    val changes: List<SharedDecidedChangeDto>,
    /** The money, only when the team chose to show it. */
    val budget: SharedBudgetDto?,
    val updatedAt: String,
)

/** [kind] is `change` or `measurement`; [path] its page. Amounts only when the page shows the money. */
@Serializable
internal data class SharedDecisionDto(
    val kind: String,
    val number: String,
    val title: String,
    val sentAt: String?,
    val path: String,
    val amountEur: Double?,
    val daysImpact: Int?,
)

@Serializable
internal data class SharedDecidedChangeDto(
    val number: String,
    val title: String,
    val kind: String,
    val status: String,
    val decidedAt: String?,
    val decidedBy: String?,
    val daysImpact: Int,
    /** Signed; only when the page shows the money. */
    val amountEur: Double?,
    /** Its page, which still shows the decision. */
    val path: String?,
)

/** The contract as it stands: the original, what approved changes did, what waits for an answer, and what was done and billed. */
@Serializable
internal data class SharedBudgetDto(
    val contractEur: Double,
    val changesEur: Double,
    val currentEur: Double,
    val pendingEur: Double,
    val measuredEur: Double,
    val measuredPct: Int,
    val invoicedEur: Double,
    val paidEur: Double,
)

@Serializable
internal data class SharedPhaseDto(val name: String, val plannedStart: String?, val plannedEnd: String?, val progressPct: Int, val state: String)

@Serializable
internal data class SharedUpdateDto(val day: String, val weather: String?, val workDone: String, val photos: List<SharedPhotoDto>)

@Serializable
internal data class SharedPhotoDto(val id: String, val caption: String?, val day: String)

/**
 * What a measurement's approval page shows its client: the work measured, what it bills, and their answer once they
 * gave it. Never the team's names, notes on the client or costs.
 */
@Serializable
internal data class SharedMeasurementDto(
    val company: String,
    val hasLogo: Boolean,
    val locale: String,
    val projectNumber: String,
    val projectName: String,
    val address: String?,
    val number: Int,
    val periodEnd: String,
    val status: String,
    /** Waiting for their answer. */
    val awaiting: Boolean,
    val sentAt: String?,
    val retentionPct: Double,
    val contractEur: Double,
    val previousEur: Double,
    val toDateEur: Double,
    val grossEur: Double,
    val retentionEur: Double,
    val netEur: Double,
    val pctToDate: Double,
    /** The lines measured so far; [notStarted] more have nothing yet. */
    val lines: List<SharedMeasurementLineDto>,
    val notStarted: Int,
    val notes: String?,
    val approvedAt: String?,
    val approvedBy: String?,
    val approvedVia: String?,
    val dispute: SharedDisputeDto?,
)

@Serializable
internal data class SharedMeasurementLineDto(
    val description: String,
    val unit: String,
    val quantity: Double,
    val unitPriceEur: Double,
    val previousQty: Double,
    val periodQty: Double,
    val toDateQty: Double,
    val periodEur: Double,
    val toDateEur: Double,
    val pctToDate: Double,
)

@Serializable
internal data class SharedDisputeDto(val name: String, val note: String, val at: String)

/**
 * What a supplier's link shows: the issues assigned to them across the company's projects, open or fixed lately, with
 * where they are and the photos the team took. Never money, the client, other issues or the team's notes.
 */
@Serializable
internal data class SupplierIssuesDto(
    val company: String,
    val hasLogo: Boolean,
    val locale: String,
    val supplier: String,
    val today: String,
    val issues: List<SupplierIssueDto>,
)

@Serializable
internal data class SupplierIssueDto(
    val id: String,
    val number: String,
    val title: String,
    val description: String?,
    val location: String?,
    val kind: String,
    val priority: String,
    val status: String,
    val dueDate: String?,
    val overdue: Boolean,
    val projectNumber: String,
    val projectName: String,
    val address: String?,
    val phaseName: String?,
    val photos: List<SharedPhotoDto>,
    /** What was said from this link: who, when, to which status and the note. */
    val answers: List<SupplierAnswerDto>,
)

@Serializable
internal data class SupplierAnswerDto(val at: String, val by: String, val to: String, val note: String?)

/** A supplier's issues link: [token] is null while it is off. */
@Serializable
internal data class SupplierLinkDto(val supplierId: String, val token: String?, val createdAt: String?)

/** What the client's approval page shows: the change as sent, and the decision once there is one. */
@Serializable
internal data class SharedChangeDto(
    val company: String,
    val hasLogo: Boolean,
    val locale: String,
    val projectNumber: String,
    val projectName: String,
    val address: String?,
    val number: String,
    val title: String,
    val description: String?,
    val kind: String,
    val items: List<LineItemDto>,
    /** Negative for work taken out. */
    val totalEur: Double,
    val daysImpact: Int,
    val status: String,
    val sentAt: String?,
    val decidedAt: String?,
    val decidedBy: String?,
    val decidedVia: String?,
    val decisionNote: String?,
    /** What it does to the project: the contract and the planned end before it and after it. */
    val contractBeforeEur: Double,
    val contractAfterEur: Double,
    val endBefore: String?,
    val endAfter: String?,
)
