package com.rfm.edubot.building.api

import com.rfm.edubot.building.BuildingRecords
import com.rfm.edubot.building.MeasurementMath
import com.rfm.edubot.building.PhaseState
import com.rfm.edubot.building.ProjectProgress
import com.rfm.edubot.building.approvedEffect
import com.rfm.edubot.building.measuredLines
import com.rfm.edubot.building.model.ChangeOrder
import com.rfm.edubot.building.model.ChangeOrderStatus
import com.rfm.edubot.building.model.IssueStatus
import com.rfm.edubot.building.model.Measurement
import com.rfm.edubot.building.model.MeasurementStatus
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.SiteIssue
import com.rfm.edubot.building.model.SiteLogEntry
import com.rfm.edubot.building.model.SitePhoto
import com.rfm.edubot.crm.api.LineItemDto
import com.rfm.edubot.tenant.model.Tenant
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

// These records as the dashboard shows them. A list reads the names and figures it shows with one query per kind.

private fun eur(cents: Long): Double = cents / 100.0

private fun pct(value: Double?): Int? = value?.roundToInt()?.coerceIn(0, 100)

/** [detail] adds the contract and the client's link, which only a project's own record shows. */
internal suspend fun BuildingRecords.projectDtos(list: List<Project>, today: LocalDate, detail: Boolean = false): List<ProjectDto> {
    if (list.isEmpty()) return emptyList()
    val ids = list.map { it.id }
    val clientsById = clients.findByIds(list.mapNotNull { it.clientId })
    val quoteNumbers = quotes.findByIds(list.mapNotNull { it.quoteId }).mapValues { it.value.number }
    val managers = employees.findByIds(list.mapNotNull { it.managerId }).mapValues { it.value.name }
    val changesByProject = changes.forProjects(ids).groupBy { it.projectId }
    val openIssues = issues.forProjects(ids, listOf(IssueStatus.OPEN, IssueStatus.IN_PROGRESS)).groupBy { it.projectId }
    val measurementsByProject = measurements.forProjects(ids).groupBy { it.projectId }
    val lastDays = logs.lastDays(ids)
    val photoCounts = photos.counts(ids)
    return list.map { project ->
        val projectChanges = changesByProject[project.id].orEmpty()
        val effect = approvedEffect(projectChanges)
        val projectMeasurements = measurementsByProject[project.id].orEmpty()
        val billed = projectMeasurements.filter { it.status != MeasurementStatus.DRAFT }
        val latestBilled = billed.maxByOrNull { it.number }
        val issuesOpen = openIssues[project.id].orEmpty()
        val client = project.clientId?.let { clientsById[it] }
        val phaseStates = project.phases.map { it to ProjectProgress.phaseState(it, today) }
        ProjectDto(
            id = project.id.toHexString(),
            number = project.number,
            name = project.name,
            status = project.status.name,
            health = ProjectProgress.health(project, today, effect.days).name,
            clientId = project.clientId?.toHexString(),
            clientName = client?.name,
            clientPhone = client?.phone,
            quoteId = project.quoteId?.toHexString(),
            quoteNumber = project.quoteId?.let { quoteNumbers[it] },
            managerId = project.managerId?.toHexString(),
            managerName = project.managerId?.let { managers[it] },
            address = project.address,
            description = project.description,
            projectStart = project.plannedStart?.toString(),
            projectEnd = project.plannedEnd?.toString(),
            plannedStart = (project.plannedStart ?: ProjectProgress.plannedWindow(project)?.first)?.toString(),
            plannedEnd = ProjectProgress.plannedEnd(project)?.toString(),
            revisedEnd = ProjectProgress.revisedEnd(project, effect.days)?.toString(),
            actualStart = project.actualStart?.toString(),
            actualEnd = project.actualEnd?.toString(),
            progressPct = pct(ProjectProgress.actualPct(project)) ?: 0,
            plannedPct = pct(ProjectProgress.plannedPct(project, ProjectProgress.asOf(today))),
            scheduleDays = ProjectProgress.scheduleDays(project, today),
            manualProgressPct = project.manualProgressPct,
            retentionPct = project.retentionPct,
            contractEur = eur(project.contractCents),
            changesEur = eur(effect.cents),
            changeDays = effect.days,
            currentContractEur = eur(project.contractCents + effect.cents),
            measuredEur = eur(latestBilled?.toDateCents ?: 0),
            toInvoiceEur = eur(projectMeasurements.filter { it.status == MeasurementStatus.APPROVED }.sumOf { it.netCents }),
            retainedEur = eur(billed.sumOf { it.retentionCents }),
            phaseCount = project.phases.size,
            latePhases = phaseStates.count { it.second == PhaseState.LATE },
            openIssues = issuesOpen.size,
            overdueIssues = issuesOpen.count { it.isOverdue(today) },
            pendingChanges = projectChanges.count { it.status == ChangeOrderStatus.SENT },
            photoCount = photoCounts[project.id] ?: 0,
            lastDiaryDay = lastDays[project.id]?.toString(),
            contractLocked = projectMeasurements.isNotEmpty(),
            shareEnabled = project.share?.enabled == true,
            shareToken = if (detail) project.share?.takeIf { it.enabled }?.token else null,
            phases = phaseStates.map { (phase, state) ->
                PhaseDto(
                    id = phase.id,
                    name = phase.name,
                    plannedStart = phase.plannedStart?.toString(),
                    plannedEnd = phase.plannedEnd?.toString(),
                    actualStart = phase.actualStart?.toString(),
                    actualEnd = phase.actualEnd?.toString(),
                    progressPct = phase.progressPct,
                    weight = phase.weight,
                    plannedPct = pct(ProjectProgress.plannedPct(phase, ProjectProgress.asOf(today))),
                    state = state.name,
                )
            },
            contract = if (!detail) emptyList() else project.contract.map { line ->
                val measured = latestBilled?.lines?.firstOrNull { it.key == line.id }?.toDateQty ?: 0.0
                ContractLineDto(
                    id = line.id,
                    description = line.description,
                    quantity = line.quantity,
                    unit = line.unit,
                    unitPriceEur = eur(line.unitPriceCents),
                    totalEur = eur(line.totalCents),
                    measuredQty = measured,
                    measuredPct = if (line.quantity > 0) (measured / line.quantity * 100).roundToInt().coerceIn(0, 100) else 0,
                )
            },
            createdAt = project.createdAt.toString(),
            updatedAt = project.updatedAt.toString(),
        )
    }
}

internal fun SitePhoto.dto() = PhotoDto(
    id = id.toHexString(),
    projectId = projectId.toHexString(),
    logId = logId?.toHexString(),
    issueId = issueId?.toHexString(),
    phaseId = phaseId,
    caption = caption,
    day = day.toString(),
    takenAt = takenAt?.toString(),
    shared = shared,
    uploadedBy = uploadedBy,
    createdAt = createdAt.toString(),
)

internal suspend fun BuildingRecords.logDtos(entries: List<SiteLogEntry>): List<SiteLogDto> {
    if (entries.isEmpty()) return emptyList()
    val projectsById = projects.byIds(entries.map { it.projectId }).associateBy { it.id }
    val photosByLog = photos.forLogs(entries.map { it.id }).groupBy { it.logId }
    return entries.map { entry ->
        val project = projectsById[entry.projectId]
        SiteLogDto(
            id = entry.id.toHexString(),
            projectId = entry.projectId.toHexString(),
            projectNumber = project?.number.orEmpty(),
            projectName = project?.name.orEmpty(),
            day = entry.day.toString(),
            weather = entry.weather?.name,
            temperatureC = entry.temperatureC,
            workers = entry.workers,
            workDone = entry.workDone,
            equipment = entry.equipment,
            materials = entry.materials,
            visitors = entry.visitors,
            incidents = entry.incidents,
            lostHours = entry.lostHours,
            shared = entry.shared,
            progress = entry.progress.map { PhaseProgressChangeDto(it.phaseId, it.phaseName, it.fromPct, it.toPct) },
            photos = photosByLog[entry.id].orEmpty().map { it.dto() },
            createdBy = entry.createdBy,
            updatedBy = entry.updatedBy,
            createdAt = entry.createdAt.toString(),
            updatedAt = entry.updatedAt.toString(),
        )
    }
}

/** [withPhotos] adds the issue's photos, which only its own record shows. */
internal suspend fun BuildingRecords.issueDtos(list: List<SiteIssue>, today: LocalDate, withPhotos: Boolean = false): List<IssueDto> {
    if (list.isEmpty()) return emptyList()
    val projectsById = projects.byIds(list.map { it.projectId }).associateBy { it.id }
    val staff = employees.findByIds(list.mapNotNull { it.employeeId }).mapValues { it.value.name }
    val vendors = suppliers.findByIds(list.mapNotNull { it.supplierId }).mapValues { it.value.name }
    return list.map { issue ->
        val project = projectsById[issue.projectId]
        IssueDto(
            id = issue.id.toHexString(),
            number = issue.number,
            projectId = issue.projectId.toHexString(),
            projectNumber = project?.number.orEmpty(),
            projectName = project?.name.orEmpty(),
            title = issue.title,
            description = issue.description,
            location = issue.location,
            kind = issue.kind.name,
            priority = issue.priority.name,
            status = issue.status.name,
            overdue = issue.isOverdue(today),
            phaseId = issue.phaseId,
            phaseName = issue.phaseId?.let { id -> project?.phases?.firstOrNull { it.id == id }?.name },
            employeeId = issue.employeeId?.toHexString(),
            supplierId = issue.supplierId?.toHexString(),
            assigneeName = issue.employeeId?.let { staff[it] } ?: issue.supplierId?.let { vendors[it] },
            dueDate = issue.dueDate?.toString(),
            resolvedAt = issue.resolvedAt?.toString(),
            closedAt = issue.closedAt?.toString(),
            history = issue.history.map { IssueEventDto(it.at.toString(), it.by, it.from?.name, it.to.name, it.note) },
            photos = if (withPhotos) photos.forIssue(issue.id).map { it.dto() } else emptyList(),
            createdBy = issue.createdBy,
            createdAt = issue.createdAt.toString(),
            updatedAt = issue.updatedAt.toString(),
        )
    }
}

internal suspend fun BuildingRecords.changeDtos(list: List<ChangeOrder>): List<ChangeOrderDto> {
    if (list.isEmpty()) return emptyList()
    val projectsById = projects.byIds(list.map { it.projectId }).associateBy { it.id }
    val clientsById = clients.findByIds(projectsById.values.mapNotNull { it.clientId })
    val measured = measurements.forProjects(projectsById.keys).groupBy { it.projectId }
    return list.map { change ->
        val project = projectsById[change.projectId]
        val client = project?.clientId?.let { clientsById[it] }
        ChangeOrderDto(
            id = change.id.toHexString(),
            number = change.number,
            projectId = change.projectId.toHexString(),
            projectNumber = project?.number.orEmpty(),
            projectName = project?.name.orEmpty(),
            clientName = client?.name,
            clientPhone = client?.phone,
            title = change.title,
            description = change.description,
            kind = change.kind.name,
            items = change.items.map { LineItemDto(it.description, it.quantity, it.unit, it.unitPriceCents / 100.0) },
            totalEur = eur(change.totalCents),
            signedEur = eur(change.signedCents),
            daysImpact = change.daysImpact,
            status = change.status.name,
            sentAt = change.sentAt?.toString(),
            decidedAt = change.decidedAt?.toString(),
            decidedBy = change.decidedBy,
            decisionNote = change.decisionNote,
            measured = measuredLines(change, measured[change.projectId].orEmpty()),
            createdBy = change.createdBy,
            createdAt = change.createdAt.toString(),
            updatedAt = change.updatedAt.toString(),
        )
    }
}

internal suspend fun BuildingRecords.measurementDtos(list: List<Measurement>): List<MeasurementDto> {
    if (list.isEmpty()) return emptyList()
    val projectsById = projects.byIds(list.map { it.projectId }).associateBy { it.id }
    val clientsById = clients.findByIds(projectsById.values.mapNotNull { it.clientId })
    val latestNumber = measurements.latestNumbers(list.map { it.projectId })
    val invoicesById = invoices.findByIds(list.mapNotNull { it.invoiceId })
    return list.map { m ->
        val project = projectsById[m.projectId]
        val totals = MeasurementMath.totals(m.lines, m.retentionPct)
        val invoice = m.invoiceId?.let { invoicesById[it] }
        MeasurementDto(
            id = m.id.toHexString(),
            number = m.number,
            projectId = m.projectId.toHexString(),
            projectNumber = project?.number.orEmpty(),
            projectName = project?.name.orEmpty(),
            clientId = project?.clientId?.toHexString(),
            clientName = project?.clientId?.let { clientsById[it]?.name },
            periodEnd = m.periodEnd.toString(),
            status = m.status.name,
            retentionPct = m.retentionPct,
            contractEur = eur(totals.contractCents),
            previousEur = eur(totals.previousCents),
            toDateEur = eur(totals.toDateCents),
            grossEur = eur(totals.grossCents),
            retentionEur = eur(totals.retentionCents),
            netEur = eur(totals.netCents),
            pctToDate = if (totals.contractCents != 0L) (totals.toDateCents.toDouble() / totals.contractCents * 100).coerceIn(0.0, 100.0) else 0.0,
            lines = m.lines.map { line ->
                MeasurementLineDto(
                    key = line.key,
                    description = line.description,
                    unit = line.unit,
                    quantity = line.quantity,
                    unitPriceEur = eur(line.unitPriceCents),
                    totalEur = eur(line.totalCents),
                    previousQty = line.previousQty,
                    toDateQty = line.toDateQty,
                    periodQty = MeasurementMath.periodQty(line),
                    previousEur = eur(MeasurementMath.previousCents(line)),
                    toDateEur = eur(MeasurementMath.toDateCents(line)),
                    periodEur = eur(MeasurementMath.periodCents(line)),
                    pctToDate = MeasurementMath.pctToDate(line),
                )
            },
            notes = m.notes,
            invoiceId = m.invoiceId?.toHexString(),
            invoiceNumber = invoice?.number,
            invoiceStatus = invoice?.status?.name,
            approvedAt = m.approvedAt?.toString(),
            approvedBy = m.approvedBy,
            invoicedAt = m.invoicedAt?.toString(),
            latest = latestNumber[m.projectId] == m.number,
            createdBy = m.createdBy,
            createdAt = m.createdAt.toString(),
            updatedAt = m.updatedAt.toString(),
        )
    }
}

/**
 * The project as its client's page shows it: progress, the plan (moved by approved changes) and the diary days and
 * photos the team shared. No money, issues, notes or people. A phase is late only on the team's side.
 */
internal suspend fun BuildingRecords.sharedProjectDto(project: Project, tenant: Tenant, today: LocalDate, hasLogo: Boolean): SharedProjectDto {
    val shared = photos.list(project.id, sharedOnly = true, limit = SHARED_PHOTOS)
    val updates = logs.list(project.id, null, null, limit = SHARED_UPDATES * 3).filter { it.shared }.take(SHARED_UPDATES)
    val byLog = shared.filter { it.logId != null }.groupBy { it.logId }
    val days = approvedEffect(changes.list(project.id)).days
    fun SitePhoto.shown() = SharedPhotoDto(id.toHexString(), caption, day.toString())
    return SharedProjectDto(
        company = tenant.documentTemplate.companyName.ifBlank { tenant.name },
        hasLogo = hasLogo,
        locale = tenant.locale,
        number = project.number,
        name = project.name,
        status = project.status.name,
        address = project.address,
        progressPct = ProjectProgress.actualPct(project).roundToInt().coerceIn(0, 100),
        plannedStart = (project.plannedStart ?: ProjectProgress.plannedWindow(project)?.first)?.toString(),
        plannedEnd = ProjectProgress.revisedEnd(project, days)?.toString(),
        actualStart = project.actualStart?.toString(),
        actualEnd = project.actualEnd?.toString(),
        phases = project.phases.map { phase ->
            val state = when (ProjectProgress.phaseState(phase, today)) {
                PhaseState.DONE -> PhaseState.DONE
                PhaseState.NOT_STARTED -> PhaseState.NOT_STARTED
                else -> if (phase.progressPct > 0) PhaseState.IN_PROGRESS else PhaseState.NOT_STARTED
            }
            SharedPhaseDto(phase.name, phase.plannedStart?.toString(), phase.plannedEnd?.toString(), phase.progressPct.coerceIn(0, 100), state.name)
        },
        updates = updates.map { entry -> SharedUpdateDto(entry.day.toString(), entry.weather?.name, entry.workDone, byLog[entry.id].orEmpty().map { it.shown() }) },
        photos = shared.map { it.shown() },
        updatedAt = (listOf(project.updatedAt) + updates.map { it.updatedAt } + shared.map { it.createdAt }).max().toString(),
    )
}

private const val SHARED_PHOTOS = 60
private const val SHARED_UPDATES = 30
