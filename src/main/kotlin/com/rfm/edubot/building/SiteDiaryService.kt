package com.rfm.edubot.building

import com.rfm.edubot.building.model.PhaseProgressChange
import com.rfm.edubot.building.model.SiteLogEntry
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId

/**
 * The site diary: one entry per project and day, never for a day still to come. An entry can move the project's
 * phases, and keeps what each one went from and to.
 */
class SiteDiaryService(private val records: BuildingRecords) {
    suspend fun create(projectId: ObjectId?, draft: SiteLogDraft, stamp: Stamp): Outcome<SiteLogEntry> {
        val project = projectId?.let { records.projects.findById(it) } ?: return invalid("project_not_found")
        val day = draft.day ?: stamp.today
        if (day > stamp.today) return invalid("future_day")
        records.logs.findByDay(project.id, day)?.let { return conflict("day_taken", it.id.toHexString()) }
        val base = SiteLogEntry(
            tenantId = records.tenantId, projectId = project.id, day = day, workDone = "",
            createdBy = stamp.who, updatedBy = stamp.who, createdAt = stamp.now, updatedAt = stamp.now,
        )
        val entry = logFrom(draft, base).orElse { return it.refused() }
        val progress = moveProgress(project.id, draft.phaseProgress, day, emptyList(), stamp).orElse { return it.refused() }
        val stored = entry.copy(progress = progress)
        if (!records.logs.insert(stored)) return conflict("day_taken")
        return Outcome.Done(stored)
    }

    suspend fun update(id: ObjectId, draft: SiteLogDraft, stamp: Stamp): Outcome<SiteLogEntry> {
        val entry = records.logs.findById(id) ?: return Outcome.NotFound
        val day = draft.day ?: entry.day
        if (day > stamp.today) return invalid("future_day")
        val edited = logFrom(draft, entry.copy(day = day, updatedBy = stamp.who, updatedAt = stamp.now)).orElse { return it.refused() }
        val progress = moveProgress(entry.projectId, draft.phaseProgress, day, entry.progress, stamp).orElse { return it.refused() }
        val stored = edited.copy(progress = progress)
        if (!records.logs.replace(stored)) return conflict("day_taken")
        return Outcome.Done(stored)
    }

    /** The day's photos stay in the project's gallery. */
    suspend fun delete(id: ObjectId): Boolean {
        if (!records.logs.delete(id)) return false
        records.photos.detach("logId", id)
        return true
    }

    /**
     * Moves the project's phases to the progress the entry typed and returns the entry's record of it: what each
     * phase went from and to, keeping the first "from" of a phase the entry had already moved ([recorded]).
     */
    private suspend fun moveProgress(
        projectId: ObjectId,
        typed: List<PhaseProgressDraft>?,
        day: LocalDate,
        recorded: List<PhaseProgressChange>,
        stamp: Stamp,
    ): Parsed<List<PhaseProgressChange>> {
        if (typed == null) return Parsed.Good(recorded)
        if (typed.any { it.progressPct !in 0..100 }) return Parsed.Bad("invalid_progress")
        var changes = recorded
        val outcome = records.projects.update(projectId) { current ->
            val byId = current.phases.associateBy { it.id }
            if (typed.any { it.phaseId !in byId }) return@update ProjectChange.Refused("phase_not_found")
            val wanted = typed.associate { it.phaseId to it.progressPct }
            val firstFrom = recorded.associate { it.phaseId to it.fromPct }
            val moved = wanted.map { (id, to) -> PhaseProgressChange(id, byId.getValue(id).name, firstFrom[id] ?: byId.getValue(id).progressPct, to) }
            changes = (recorded.filter { it.phaseId !in wanted } + moved).filter { it.fromPct != it.toPct }
            ProjectChange.Ok(current.copy(phases = current.phases.map { phase -> wanted[phase.id]?.let { phase.withProgress(it, day) } ?: phase }, updatedAt = stamp.now))
        }
        return when (outcome) {
            null -> Parsed.Bad("project_not_found")
            is ProjectChange.Refused -> Parsed.Bad(outcome.error)
            is ProjectChange.Ok -> Parsed.Good(changes)
        }
    }
}
