package com.rfm.edubot.building

import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.ProjectShare
import com.rfm.edubot.building.model.ProjectStatus
import com.rfm.edubot.crm.QuoteServicing
import com.rfm.edubot.crm.model.QuoteStatus
import org.bson.types.ObjectId
import kotlin.math.roundToLong

/**
 * Writes to a company's projects: the record, its phases, the link its client follows it from, and removing it
 * with its diary, issues, changes, client updates and photos. A project made from a quote takes its client, name
 * and lines as its contract, and the quote turns accepted.
 */
class ProjectService(private val records: BuildingRecords, private val store: PhotoStore) {
    suspend fun create(draft: ProjectDraft, quoteId: ObjectId?, stamp: Stamp): Outcome<Project> {
        val quote = quoteId?.let { records.quotes.findById(it) ?: return invalid("quote_not_found") }
        if (quote != null && records.projects.forQuote(quote.id).isNotEmpty()) return conflict("quote_in_project")
        val base = Project(
            tenantId = records.tenantId,
            number = "",
            name = quote?.let { QuoteServicing.nameFrom(it.items).ifEmpty { it.number } }.orEmpty(),
            clientId = quote?.clientId,
            quoteId = quote?.id,
            contract = quote?.items.orEmpty().map { it.toContractLine(newLineId()) },
            createdAt = stamp.now,
            updatedAt = stamp.now,
        )
        val project = when (val made = projectFrom(draft, base, stamp, locked = false)) {
            is ProjectChange.Refused -> return invalid(made.error)
            is ProjectChange.Ok -> made.project
        }
        if (project.name.isBlank()) return invalid("name_required")
        val stored = project.copy(number = records.projects.nextNumber())
        records.projects.insert(stored)
        if (quote != null && quote.status != QuoteStatus.ACEITO) records.quoteService.setStatus(quote.id, QuoteStatus.ACEITO)
        records.events.projectCreated(stored)
        return Outcome.Done(stored)
    }

    /** The contract can't change once a measurement exists, except its lines' descriptions. */
    suspend fun update(id: ObjectId, draft: ProjectDraft, stamp: Stamp): Outcome<Project> {
        val locked = records.measurements.count(id) > 0
        var before: ProjectStatus? = null
        val outcome = records.projects.update(id) { current ->
            before = current.status
            projectFrom(draft, current.copy(updatedAt = stamp.now), stamp, locked)
        }
        if (outcome is ProjectChange.Ok) before?.takeIf { it != outcome.project.status }?.let { records.events.projectStatusChanged(outcome.project, it) }
        return outcome.outcome()
    }

    suspend fun setPhases(id: ObjectId, phases: List<PhaseDraft>, stamp: Stamp): Outcome<Project> =
        records.projects.update(id) { current ->
            when (val parsed = phasesFrom(phases, current.phases, stamp.today)) {
                is Parsed.Bad -> ProjectChange.Refused(parsed.error)
                is Parsed.Good -> ProjectChange.Ok(current.copy(phases = parsed.value, updatedAt = stamp.now))
            }
        }.outcome()

    suspend fun setPhaseProgress(id: ObjectId, phaseId: String, progress: Int, stamp: Stamp): Outcome<Project> {
        if (progress !in 0..100) return invalid("invalid_progress")
        return records.projects.update(id) { current ->
            if (current.phases.none { it.id == phaseId }) return@update ProjectChange.Refused("phase_not_found")
            ProjectChange.Ok(current.copy(phases = current.phases.map { if (it.id == phaseId) it.withProgress(progress, stamp.today) else it }, updatedAt = stamp.now))
        }.outcome()
    }

    /** Turns the client's link on, with the token it had before, or a new one. */
    suspend fun share(id: ObjectId, stamp: Stamp): Outcome<Project> =
        records.projects.update(id) { current ->
            ProjectChange.Ok(current.copy(share = current.share?.copy(enabled = true) ?: ProjectShare(secretToken(), true, stamp.now), updatedAt = stamp.now))
        }.outcome()

    /** A new link; the old one stops working. */
    suspend fun rotateShare(id: ObjectId, stamp: Stamp): Outcome<Project> =
        records.projects.update(id) { current -> ProjectChange.Ok(current.copy(share = ProjectShare(secretToken(), true, stamp.now), updatedAt = stamp.now)) }.outcome()

    suspend fun stopSharing(id: ObjectId, stamp: Stamp): Outcome<Project> =
        records.projects.update(id) { current -> ProjectChange.Ok(current.copy(share = current.share?.copy(enabled = false), updatedAt = stamp.now)) }.outcome()

    /** Refused while a measurement bills the project: its invoices point at it. */
    suspend fun delete(id: ObjectId, companySlug: String): Outcome<Unit> {
        val project = records.projects.findById(id) ?: return Outcome.NotFound
        if (records.measurements.count(project.id) > 0) return conflict("in_use")
        records.logs.deleteForProject(project.id)
        records.issues.deleteForProject(project.id)
        records.changes.deleteForProject(project.id)
        records.reports.deleteForProject(project.id)
        records.photos.deleteForProject(project.id)
        store.deleteProject(companySlug, project.id)
        records.payments.clearProject(project.id)
        records.projects.delete(project.id)
        return Outcome.Done(Unit)
    }

    private suspend fun projectFrom(draft: ProjectDraft, base: Project, stamp: Stamp, locked: Boolean): ProjectChange {
        fun bad(error: String) = ProjectChange.Refused(error)
        val name = draft.name?.trim() ?: base.name
        if (draft.name != null && name.isEmpty()) return bad("name_required")
        if (name.length > BuildingLimits.NAME) return bad("name_too_long")
        val status = draft.status?.let { enumOf<ProjectStatus>(it) ?: return bad("invalid_status") } ?: base.status
        val clientId = when (val raw = draft.clientId?.trim()) {
            null -> base.clientId
            "" -> null
            else -> objectIdOrNull(raw)?.takeIf { records.clients.findById(it) != null } ?: return bad("client_not_found")
        }
        val managerId = when (val raw = draft.managerId?.trim()) {
            null -> base.managerId
            "" -> null
            else -> objectIdOrNull(raw)?.takeIf { records.employees.findById(it) != null } ?: return bad("employee_not_found")
        }
        val address = text(draft.address, base.address, BuildingLimits.ADDRESS, "address_too_long").orElse { return bad(it.error) }
        val description = text(draft.description, base.description, BuildingLimits.TEXT, "description_too_long").orElse { return bad(it.error) }
        val plannedStart = date(draft.plannedStart, base.plannedStart, "plannedStart").orElse { return bad(it.error) }
        val plannedEnd = date(draft.plannedEnd, base.plannedEnd, "plannedEnd").orElse { return bad(it.error) }
        var actualStart = date(draft.actualStart, base.actualStart, "actualStart").orElse { return bad(it.error) }
        var actualEnd = date(draft.actualEnd, base.actualEnd, "actualEnd").orElse { return bad(it.error) }
        if (plannedStart != null && plannedEnd != null && plannedEnd < plannedStart) return bad("invalid_dates")
        val retention = draft.retentionPct ?: base.retentionPct
        if (!retention.isFinite() || retention < 0 || retention > BuildingLimits.RETENTION_PCT) return bad("invalid_retention")
        val manual = draft.manualProgressPct ?: base.manualProgressPct
        if (manual !in 0..100) return bad("invalid_progress")
        val budget = optionalCents(draft.costBudgetEur, base.costBudgetCents, BuildingLimits.PRICE_EUR, "invalid_budget").orElse { return bad(it.error) }
        val rate = optionalCents(draft.laborRateEur, base.laborRateCents, BuildingLimits.LABOR_RATE_EUR, "invalid_rate").orElse { return bad(it.error) }
        val siteIds = draft.siteIds?.let { raw -> sitesFrom(raw, base.id).orElse { return bad(it.error) } } ?: base.siteIds
        val contract = draft.contract?.let { lines -> contractFrom(lines, base.contract).orElse { return bad(it.error) } } ?: base.contract
        if (locked && contract.map { it.copy(description = "") } != base.contract.map { it.copy(description = "") }) return bad("contract_locked")
        if (status != base.status) {
            if (status == ProjectStatus.ACTIVE) actualStart = actualStart ?: stamp.today
            if (status == ProjectStatus.COMPLETED) actualEnd = actualEnd ?: stamp.today
        }
        if (actualStart != null && actualEnd != null && actualEnd < actualStart) return bad("invalid_dates")
        return ProjectChange.Ok(
            base.copy(
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
                retentionPct = retention,
                manualProgressPct = manual,
                contract = contract,
                costBudgetCents = budget,
                laborRateCents = rate,
                siteIds = siteIds,
            ),
        )
    }

    /** The time clock's sites [raw] names, each a site of the company that no other project has. */
    private suspend fun sitesFrom(raw: List<String>, projectId: ObjectId): Parsed<List<ObjectId>> {
        val ids = raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { objectIdOrNull(it) ?: return Parsed.Bad("site_not_found") }
        if (ids.size > BuildingLimits.SITES) return Parsed.Bad("too_many_sites")
        if (ids.isEmpty()) return Parsed.Good(ids)
        val known = records.sites.list().map { it.id }.toSet()
        if (ids.any { it !in known }) return Parsed.Bad("site_not_found")
        if (records.projects.forSites(ids).any { it.id != projectId }) return Parsed.Bad("site_in_use")
        return Parsed.Good(ids)
    }
}

/** [value] euros as cents: null keeps [current], zero clears it, and a negative, non-finite or above-[max] value is [error]. */
private fun optionalCents(value: Double?, current: Long?, max: Double, error: String): Parsed<Long?> = when {
    value == null -> Parsed.Good(current)
    !value.isFinite() || value < 0 || value > max -> Parsed.Bad(error)
    value == 0.0 -> Parsed.Good(null)
    else -> Parsed.Good((value * 100).roundToLong())
}

/** A project write as a service answers it: a conflict when someone else wrote first or the contract is locked. */
internal fun ProjectChange?.outcome(): Outcome<Project> = when (this) {
    null -> Outcome.NotFound
    is ProjectChange.Refused -> Outcome.Refused(error, conflict = error == "conflict" || error == "contract_locked")
    is ProjectChange.Ok -> Outcome.Done(project)
}
