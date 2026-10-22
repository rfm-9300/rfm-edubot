package com.rfm.edubot.building

import com.rfm.edubot.building.model.ChangeOrder
import com.rfm.edubot.building.model.ChangeOrderStatus
import com.rfm.edubot.building.model.Measurement
import org.bson.types.ObjectId

/**
 * Change orders: work added to a project's contract or taken out of it, sent to the client and approved or
 * rejected. Only a draft or a sent one can be edited; an approved one is measured like the contract.
 */
class ChangeOrderService(private val records: BuildingRecords) {
    suspend fun create(projectId: ObjectId?, draft: ChangeOrderDraft, stamp: Stamp): Outcome<ChangeOrder> {
        val project = projectId?.let { records.projects.findById(it) } ?: return invalid("project_not_found")
        val base = ChangeOrder(
            tenantId = records.tenantId, projectId = project.id, number = "", title = "", items = emptyList(), totalCents = 0,
            createdBy = stamp.who, createdAt = stamp.now, updatedAt = stamp.now,
        )
        val change = changeFrom(draft, base).orElse { return it.refused() }
        if (change.title.isBlank()) return invalid("title_required")
        if (change.items.isEmpty()) return invalid("items_required")
        val stored = change.copy(number = records.changes.nextNumber())
        records.changes.insert(stored)
        return Outcome.Done(stored)
    }

    suspend fun update(id: ObjectId, draft: ChangeOrderDraft, stamp: Stamp): Outcome<ChangeOrder> {
        val change = records.changes.findById(id) ?: return Outcome.NotFound
        if (change.status != ChangeOrderStatus.DRAFT && change.status != ChangeOrderStatus.SENT) return conflict("change_locked")
        val edited = changeFrom(draft, change.copy(updatedAt = stamp.now)).orElse { return it.refused() }
        if (edited.title.isBlank()) return invalid("title_required")
        if (edited.items.isEmpty()) return invalid("items_required")
        return records.changes.replace(edited)?.let { Outcome.Done(it) } ?: Outcome.NotFound
    }

    /**
     * Sends, approves or rejects an open change, or takes a decided one back to draft. [decidedBy] is who decided,
     * the person recording it when blank; an approved change a measurement measured can't go back.
     */
    suspend fun setStatus(id: ObjectId, target: ChangeOrderStatus, decidedBy: String?, note: String?, stamp: Stamp): Outcome<ChangeOrder> {
        val change = records.changes.findById(id) ?: return Outcome.NotFound
        val open = change.status == ChangeOrderStatus.DRAFT || change.status == ChangeOrderStatus.SENT
        val allowed = when (target) {
            ChangeOrderStatus.SENT, ChangeOrderStatus.APPROVED, ChangeOrderStatus.REJECTED -> open
            ChangeOrderStatus.DRAFT -> change.status != ChangeOrderStatus.DRAFT
        }
        if (!allowed) return conflict("invalid_transition")
        if (target == ChangeOrderStatus.DRAFT && change.status == ChangeOrderStatus.APPROVED && measuredLines(change, records.measurements.list(change.projectId))) {
            return conflict("change_measured")
        }
        val next = when (target) {
            ChangeOrderStatus.SENT -> change.copy(status = target, sentAt = stamp.now)
            ChangeOrderStatus.APPROVED, ChangeOrderStatus.REJECTED -> change.copy(
                status = target,
                decidedAt = stamp.now,
                decidedBy = decidedBy?.trim()?.take(BuildingLimits.NAME)?.ifEmpty { null } ?: stamp.who,
                decisionNote = note?.trim()?.take(BuildingLimits.NOTE)?.ifEmpty { null },
            )
            ChangeOrderStatus.DRAFT -> change.copy(status = target, decidedAt = null, decidedBy = null, decisionNote = null)
        }.copy(updatedAt = stamp.now)
        return records.changes.replace(next)?.let { Outcome.Done(it) } ?: Outcome.NotFound
    }

    /** An approved change is part of the contract: take it back to draft first. */
    suspend fun delete(id: ObjectId): Outcome<Unit> {
        val change = records.changes.findById(id) ?: return Outcome.NotFound
        if (change.status == ChangeOrderStatus.APPROVED) return conflict("change_approved")
        records.changes.delete(change.id)
        return Outcome.Done(Unit)
    }
}

/** What approved changes do to a project: value (signed) and days. */
data class ChangeEffect(val cents: Long, val days: Int)

fun approvedEffect(changes: List<ChangeOrder>): ChangeEffect {
    val approved = changes.filter { it.status == ChangeOrderStatus.APPROVED }
    return ChangeEffect(approved.sumOf { it.signedCents }, approved.sumOf { it.daysImpact })
}

/** Whether a measurement of the project measured any of [change]'s lines. */
fun measuredLines(change: ChangeOrder, measurements: List<Measurement>): Boolean {
    val prefix = "co:${change.id.toHexString()}:"
    return measurements.any { m -> m.lines.any { it.key.startsWith(prefix) && it.toDateQty > 0 } }
}
