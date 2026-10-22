package com.rfm.edubot.building

import com.rfm.edubot.building.model.IssueEvent
import com.rfm.edubot.building.model.IssueKind
import com.rfm.edubot.building.model.IssuePriority
import com.rfm.edubot.building.model.IssueStatus
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.SiteIssue
import org.bson.types.ObjectId

/** Issues on site (punch items, defects, safety), numbered per company, with the history of their status. */
class SiteIssueService(private val records: BuildingRecords) {
    suspend fun create(projectId: ObjectId?, draft: IssueDraft, stamp: Stamp): Outcome<SiteIssue> {
        val project = projectId?.let { records.projects.findById(it) } ?: return invalid("project_not_found")
        val base = SiteIssue(
            tenantId = records.tenantId, projectId = project.id, number = "", title = "",
            createdBy = stamp.who, createdAt = stamp.now, updatedAt = stamp.now,
        )
        val issue = issueFrom(draft, base, project).orElse { return it.refused() }
        if (issue.title.isBlank()) return invalid("title_required")
        val stored = issue.copy(
            number = records.issues.nextNumber(),
            history = listOf(IssueEvent(stamp.now, stamp.who, null, issue.status, note(draft))),
        ).stamped(issue.status, stamp.now)
        records.issues.insert(stored)
        return Outcome.Done(stored)
    }

    /** A status change goes into the history, with the draft's note. */
    suspend fun update(id: ObjectId, draft: IssueDraft, stamp: Stamp): Outcome<SiteIssue> {
        val issue = records.issues.findById(id) ?: return Outcome.NotFound
        val project = records.projects.findById(issue.projectId) ?: return Outcome.NotFound
        val edited = issueFrom(draft, issue.copy(updatedAt = stamp.now), project).orElse { return it.refused() }
        val next = if (edited.status != issue.status) {
            edited.copy(history = issue.history + IssueEvent(stamp.now, stamp.who, issue.status, edited.status, note(draft))).stamped(edited.status, stamp.now)
        } else {
            edited
        }
        return records.issues.replace(next)?.let { Outcome.Done(it) } ?: Outcome.NotFound
    }

    /** The issue's photos stay in the project's gallery. */
    suspend fun delete(id: ObjectId): Boolean {
        if (!records.issues.delete(id)) return false
        records.photos.detach("issueId", id)
        return true
    }

    private fun note(draft: IssueDraft) = draft.note?.trim()?.take(BuildingLimits.NOTE)?.ifEmpty { null }

    private suspend fun issueFrom(draft: IssueDraft, base: SiteIssue, project: Project): Parsed<SiteIssue> {
        val title = draft.title?.trim() ?: base.title
        if (draft.title != null && title.isEmpty()) return Parsed.Bad("title_required")
        if (title.length > BuildingLimits.NAME) return Parsed.Bad("title_too_long")
        val description = text(draft.description, base.description, BuildingLimits.TEXT, "description_too_long").orElse { return it }
        val location = text(draft.location, base.location, BuildingLimits.NAME, "location_too_long").orElse { return it }
        val kind = draft.kind?.let { enumOf<IssueKind>(it) ?: return Parsed.Bad("invalid_kind") } ?: base.kind
        val priority = draft.priority?.let { enumOf<IssuePriority>(it) ?: return Parsed.Bad("invalid_priority") } ?: base.priority
        val status = draft.status?.let { enumOf<IssueStatus>(it) ?: return Parsed.Bad("invalid_status") } ?: base.status
        val phaseId = when (val raw = draft.phaseId?.trim()) {
            null -> base.phaseId
            "" -> null
            else -> raw.takeIf { id -> project.phases.any { it.id == id } } ?: return Parsed.Bad("phase_not_found")
        }
        val employeeId = when (val raw = draft.employeeId?.trim()) {
            null -> base.employeeId
            "" -> null
            else -> objectIdOrNull(raw)?.takeIf { records.employees.findById(it) != null } ?: return Parsed.Bad("employee_not_found")
        }
        val supplierId = when (val raw = draft.supplierId?.trim()) {
            null -> base.supplierId
            "" -> null
            else -> objectIdOrNull(raw)?.takeIf { records.suppliers.findById(it) != null } ?: return Parsed.Bad("supplier_not_found")
        }
        val (employee, supplier) = when {
            draft.employeeId?.isNotBlank() == true -> employeeId to null
            draft.supplierId?.isNotBlank() == true -> null to supplierId
            else -> employeeId to supplierId
        }
        val dueDate = date(draft.dueDate, base.dueDate, "dueDate").orElse { return it }
        return Parsed.Good(
            base.copy(
                title = title,
                description = description,
                location = location,
                kind = kind,
                priority = priority,
                status = status,
                phaseId = phaseId,
                employeeId = employee,
                supplierId = supplier,
                dueDate = dueDate,
            ),
        )
    }
}
