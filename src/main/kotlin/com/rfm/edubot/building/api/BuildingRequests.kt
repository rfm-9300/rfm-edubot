package com.rfm.edubot.building.api

import com.rfm.edubot.building.ChangeOrderDraft
import com.rfm.edubot.building.ContractLineDraft
import com.rfm.edubot.building.IssueDraft
import com.rfm.edubot.building.LineDraft
import com.rfm.edubot.building.PhaseDraft
import com.rfm.edubot.building.PhaseProgressDraft
import com.rfm.edubot.building.ProjectDraft
import com.rfm.edubot.building.SiteLogDraft
import com.rfm.edubot.crm.api.CreateLineItemRequest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

// Create and edit bodies: on an edit an omitted field keeps what is stored, and an empty string clears it.

@Serializable
internal data class ContractLineRequest(
    val id: String? = null,
    val description: String = "",
    val quantity: Double = 1.0,
    val unit: String = "",
    val unitPriceEur: Double = 0.0,
)

@Serializable
internal data class ProjectRequest(
    val name: String? = null,
    val status: String? = null,
    val clientId: String? = null,
    /** Only on create: the quote the project's contract and client come from. */
    val quoteId: String? = null,
    val managerId: String? = null,
    val address: String? = null,
    val description: String? = null,
    val plannedStart: String? = null,
    val plannedEnd: String? = null,
    val actualStart: String? = null,
    val actualEnd: String? = null,
    val retentionPct: Double? = null,
    val manualProgressPct: Int? = null,
    val contract: List<ContractLineRequest>? = null,
    val costBudgetEur: Double? = null,
    val laborRateEur: Double? = null,
    val siteIds: List<String>? = null,
) {
    fun toDraft() = ProjectDraft(
        name = name,
        status = status,
        clientId = clientId,
        managerId = managerId,
        address = address,
        description = description,
        plannedStart = plannedStart,
        plannedEnd = plannedEnd,
        actualStart = actualStart,
        actualEnd = actualEnd,
        retentionPct = retentionPct,
        manualProgressPct = manualProgressPct,
        contract = contract?.map { ContractLineDraft(it.id, it.description, it.quantity, it.unit, it.unitPriceEur) },
        costBudgetEur = costBudgetEur,
        laborRateEur = laborRateEur,
        siteIds = siteIds,
    )
}

@Serializable
internal data class PhaseRequest(
    val id: String? = null,
    val name: String = "",
    val plannedStart: String? = null,
    val plannedEnd: String? = null,
    val progressPct: Int = 0,
    val weight: Int? = null,
)

/** Every phase of the project, in order: one missing from the list is removed. */
@Serializable
internal data class PhasesRequest(val phases: List<PhaseRequest> = emptyList()) {
    fun toDrafts() = phases.map { PhaseDraft(it.id, it.name, it.plannedStart, it.plannedEnd, it.progressPct, it.weight) }
}

@Serializable
internal data class PhaseProgressRequest(val progressPct: Int? = null)

@Serializable
internal data class PhaseProgressEntry(val phaseId: String = "", val progressPct: Int = 0)

@Serializable
internal data class SiteLogRequest(
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
    /** Clears [temperatureC], [workers] or [lostHours] on an edit: a JSON null can't say it. */
    val clear: List<String> = emptyList(),
) {
    /** [day] as the route read it. */
    fun toDraft(day: LocalDate?) = SiteLogDraft(
        day = day,
        weather = weather,
        temperatureC = temperatureC,
        workers = workers,
        workDone = workDone,
        equipment = equipment,
        materials = materials,
        visitors = visitors,
        incidents = incidents,
        lostHours = lostHours,
        shared = shared,
        phaseProgress = phaseProgress?.map { PhaseProgressDraft(it.phaseId, it.progressPct) },
        clear = clear.toSet(),
    )
}

@Serializable
internal data class PhotoUpdateRequest(val caption: String? = null, val shared: Boolean? = null)

@Serializable
internal data class IssueRequest(
    val projectId: String? = null,
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val kind: String? = null,
    val priority: String? = null,
    val phaseId: String? = null,
    val employeeId: String? = null,
    val supplierId: String? = null,
    val dueDate: String? = null,
    val status: String? = null,
    /** Kept with a status change. */
    val note: String? = null,
) {
    fun toDraft() = IssueDraft(title, description, location, kind, priority, status, phaseId, employeeId, supplierId, dueDate, note)
}

@Serializable
internal data class ChangeOrderRequest(
    val projectId: String? = null,
    val title: String? = null,
    val description: String? = null,
    val kind: String? = null,
    val items: List<CreateLineItemRequest>? = null,
    val daysImpact: Int? = null,
) {
    fun toDraft() = ChangeOrderDraft(
        title = title,
        description = description,
        kind = kind,
        items = items?.map { LineDraft(it.description, it.quantity, it.unit, it.unitPriceEur) },
        daysImpact = daysImpact,
    )
}

@Serializable
internal data class ChangeStatusRequest(val status: String = "", val decidedBy: String? = null, val note: String? = null)

/** The client's answer on the change's link: `APPROVED` or `REJECTED`, in their own name. */
@Serializable
internal data class ChangeDecisionRequest(val decision: String = "", val name: String = "", val note: String? = null)

/** A client update's period, `yyyy-MM-dd`; the 7 days to today when both are left out. */
@Serializable
internal data class ReportDraftRequest(val from: String? = null, val to: String? = null)

@Serializable
internal data class ReportRequest(val from: String? = null, val to: String? = null, val text: String? = null)

@Serializable
internal data class MeasurementRequest(val projectId: String = "", val periodEnd: String? = null, val notes: String? = null)

@Serializable
internal data class MeasuredLineRequest(val key: String = "", val toDateQty: Double = 0.0)

@Serializable
internal data class MeasurementUpdateRequest(val periodEnd: String? = null, val notes: String? = null, val lines: List<MeasuredLineRequest>? = null)

@Serializable
internal data class MeasurementInvoiceRequest(val dueDate: String = "")
