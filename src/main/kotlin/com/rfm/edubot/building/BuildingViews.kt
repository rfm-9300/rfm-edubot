package com.rfm.edubot.building

import com.rfm.edubot.admin.LineItemDto
import com.rfm.edubot.building.model.ChangeOrder
import com.rfm.edubot.building.model.ChangeOrderStatus
import com.rfm.edubot.building.model.IssueStatus
import com.rfm.edubot.building.model.Measurement
import com.rfm.edubot.building.model.MeasurementStatus
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.SiteIssue
import com.rfm.edubot.building.model.SiteLogEntry
import com.rfm.edubot.building.model.SitePhoto
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.SupplierRepository
import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.requireModule
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.timesheets.zone
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId
import kotlin.math.roundToInt

/** One request's view of a company's Building data: its repositories, today in its timezone, and who is asking. */
internal class Building(val mongo: MongoModule, val ctx: DashboardContext, val now: Instant) {
    val tenantId: ObjectId = ctx.tenant.id
    val zone: TimeZone = ctx.tenant.zone()
    val today: LocalDate = now.toLocalDateTime(zone).date
    val projects = ProjectRepository(mongo, tenantId)
    val logs = SiteLogRepository(mongo, tenantId)
    val photos = SitePhotoRepository(mongo, tenantId)
    val issues = SiteIssueRepository(mongo, tenantId)
    val changes = ChangeOrderRepository(mongo, tenantId)
    val measurements = MeasurementRepository(mongo, tenantId)
    val clients = ClientRepository(mongo, tenantId)
    val quotes = QuoteRepository(mongo, tenantId)
    val invoices = InvoiceRepository(mongo, tenantId)
    val employees = EmployeeRepository(mongo, tenantId)
    val suppliers = SupplierRepository(mongo, tenantId)

    val who: String get() = ctx.user?.email ?: "operator"

    fun has(module: String): Boolean = ctx.requireModule(module)

    fun localDay(at: Instant): LocalDate = at.toLocalDateTime(zone).date
}

/** What approved changes do to a project: value (signed) and days. */
internal data class ChangeEffect(val cents: Long, val days: Int)

internal fun approvedEffect(changes: List<ChangeOrder>): ChangeEffect {
    val approved = changes.filter { it.status == ChangeOrderStatus.APPROVED }
    return ChangeEffect(approved.sumOf { it.signedCents }, approved.sumOf { it.daysImpact })
}

private fun eur(cents: Long): Double = cents / 100.0

private fun pct(value: Double?): Int? = value?.roundToInt()?.coerceIn(0, 100)

internal suspend fun Building.projectDtos(list: List<Project>, detail: Boolean = false): List<ProjectDto> {
    if (list.isEmpty()) return emptyList()
    val ids = list.map { it.id }.toSet()
    val clientsById = list.mapNotNull { it.clientId }.distinct().mapNotNull { clients.findById(it) }.associateBy { it.id }
    val quoteNumbers = list.mapNotNull { it.quoteId }.distinct().mapNotNull { quotes.findById(it) }.associate { it.id to it.number }
    val managers = list.mapNotNull { it.managerId }.distinct().mapNotNull { employees.findById(it) }.associate { it.id to it.name }
    val changesByProject = changes.list().filter { it.projectId in ids }.groupBy { it.projectId }
    val openIssues = issues.list(statuses = listOf(IssueStatus.OPEN, IssueStatus.IN_PROGRESS)).filter { it.projectId in ids }.groupBy { it.projectId }
    val measurementsByProject = measurements.list().filter { it.projectId in ids }.groupBy { it.projectId }
    val lastDays = logs.lastDays()
    val photoCounts = photos.counts()
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

internal fun SiteIssue.isOverdue(today: LocalDate): Boolean = isOpen && dueDate != null && dueDate < today

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

internal suspend fun Building.logDtos(entries: List<SiteLogEntry>): List<SiteLogDto> {
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

internal suspend fun Building.issueDtos(list: List<SiteIssue>, withPhotos: Boolean = false): List<IssueDto> {
    if (list.isEmpty()) return emptyList()
    val projectsById = projects.byIds(list.map { it.projectId }).associateBy { it.id }
    val staff = list.mapNotNull { it.employeeId }.distinct().mapNotNull { employees.findById(it) }.associate { it.id to it.name }
    val vendors = list.mapNotNull { it.supplierId }.distinct().mapNotNull { suppliers.findById(it) }.associate { it.id to it.name }
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

/** Whether a measurement of the project measured any of [change]'s lines. */
internal fun measuredLines(change: ChangeOrder, measurements: List<Measurement>): Boolean {
    val prefix = "co:${change.id.toHexString()}:"
    return measurements.any { m -> m.lines.any { it.key.startsWith(prefix) && it.toDateQty > 0 } }
}

internal suspend fun Building.changeDtos(list: List<ChangeOrder>): List<ChangeOrderDto> {
    if (list.isEmpty()) return emptyList()
    val projectsById = projects.byIds(list.map { it.projectId }).associateBy { it.id }
    val clientsById = projectsById.values.mapNotNull { it.clientId }.distinct().mapNotNull { clients.findById(it) }.associateBy { it.id }
    val measured = measurements.list().groupBy { it.projectId }
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

internal suspend fun Building.measurementDtos(list: List<Measurement>): List<MeasurementDto> {
    if (list.isEmpty()) return emptyList()
    val projectsById = projects.byIds(list.map { it.projectId }).associateBy { it.id }
    val clientsById = projectsById.values.mapNotNull { it.clientId }.distinct().mapNotNull { clients.findById(it) }.associateBy { it.id }
    val latestNumber = list.map { it.projectId }.distinct().associateWith { measurements.latest(it)?.number }
    val invoicesById = list.mapNotNull { it.invoiceId }.distinct().mapNotNull { invoices.findById(it) }.associateBy { it.id }
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
