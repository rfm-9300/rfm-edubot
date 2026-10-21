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
    val phaseCount: Int,
    val latePhases: Int,
    val openIssues: Int,
    val overdueIssues: Int,
    val pendingChanges: Int,
    val photoCount: Int,
    val lastDiaryDay: String?,
    val contractLocked: Boolean,
    val shareEnabled: Boolean,
    /** Only on one project's record. */
    val shareToken: String?,
    val phases: List<PhaseDto>,
    val contract: List<ContractLineDto>,
    val createdAt: String,
    val updatedAt: String,
)

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

@Serializable
internal data class IssueEventDto(val at: String, val by: String, val from: String?, val to: String, val note: String?)

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
    /** The client's approval link (`/app/share/change/{token}`), from when it was sent until it goes back to draft. */
    val approvalToken: String?,
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
    val invoicedAt: String?,
    /** Only the latest measurement of a project can be reopened or deleted. */
    val latest: Boolean,
    val createdBy: String,
    val createdAt: String,
    val updatedAt: String,
)

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
    val updates: List<SharedUpdateDto>,
    val photos: List<SharedPhotoDto>,
    val updatedAt: String,
)

@Serializable
internal data class SharedPhaseDto(val name: String, val plannedStart: String?, val plannedEnd: String?, val progressPct: Int, val state: String)

@Serializable
internal data class SharedUpdateDto(val day: String, val weather: String?, val workDone: String, val photos: List<SharedPhotoDto>)

@Serializable
internal data class SharedPhotoDto(val id: String, val caption: String?, val day: String)

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
)
