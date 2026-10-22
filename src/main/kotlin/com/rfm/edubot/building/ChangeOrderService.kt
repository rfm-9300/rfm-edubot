package com.rfm.edubot.building

import com.rfm.edubot.building.model.ChangeApproval
import com.rfm.edubot.building.model.ChangeOrder
import com.rfm.edubot.building.model.ChangeOrderStatus
import com.rfm.edubot.building.model.DecidedVia
import com.rfm.edubot.building.model.Measurement
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.notifications.NotificationAudience
import com.rfm.edubot.notifications.NotificationKinds
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

    /** The client approves what they read: editing a sent change takes it back to draft and ends its link. */
    suspend fun update(id: ObjectId, draft: ChangeOrderDraft, stamp: Stamp): Outcome<ChangeOrder> {
        val change = records.changes.findById(id) ?: return Outcome.NotFound
        if (change.status != ChangeOrderStatus.DRAFT && change.status != ChangeOrderStatus.SENT) return conflict("change_locked")
        val edited = changeFrom(draft, change.copy(updatedAt = stamp.now)).orElse { return it.refused() }
        if (edited.title.isBlank()) return invalid("title_required")
        if (edited.items.isEmpty()) return invalid("items_required")
        val next = if (change.status == ChangeOrderStatus.SENT) edited.copy(status = ChangeOrderStatus.DRAFT, approval = null) else edited
        return records.changes.replace(next)?.let { Outcome.Done(it) } ?: Outcome.NotFound
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
            ChangeOrderStatus.SENT -> change.copy(status = target, sentAt = stamp.now, approval = ChangeApproval(secretToken(), stamp.now))
            ChangeOrderStatus.APPROVED, ChangeOrderStatus.REJECTED -> change.copy(
                status = target,
                decidedAt = stamp.now,
                decidedBy = decidedBy?.trim()?.take(BuildingLimits.NAME)?.ifEmpty { null } ?: stamp.who,
                decisionNote = note?.trim()?.take(BuildingLimits.NOTE)?.ifEmpty { null },
                decidedVia = DecidedVia.TEAM,
            )
            ChangeOrderStatus.DRAFT -> change.copy(status = target, decidedAt = null, decidedBy = null, decisionNote = null, decidedVia = null, approval = null)
        }.copy(updatedAt = stamp.now)
        val stored = records.changes.replace(next) ?: return Outcome.NotFound
        records.projects.findById(stored.projectId)?.let { project ->
            when (stored.status) {
                ChangeOrderStatus.SENT -> records.events.changeSent(stored, project)
                ChangeOrderStatus.APPROVED, ChangeOrderStatus.REJECTED -> records.events.changeDecided(stored, project, DecidedVia.TEAM)
                ChangeOrderStatus.DRAFT -> Unit
            }
        }
        return Outcome.Done(stored)
    }

    /**
     * The client's answer from the change's link ([token]): [decision] is `APPROVED` or `REJECTED`, [name] the name
     * they typed. Once, while the change is sent; a second answer, or one racing the team's, is `already_decided`.
     * The team hears it on the bell, and agents from `change_order.decided`.
     */
    suspend fun decideByLink(id: ObjectId, token: String, decision: String, name: String, note: String?, stamp: Stamp): Outcome<ChangeOrder> {
        val change = records.changes.findById(id)?.takeIf { it.approval?.token == token } ?: return Outcome.NotFound
        if (change.status != ChangeOrderStatus.SENT) return conflict("already_decided")
        val target = enumOf<ChangeOrderStatus>(decision)?.takeIf { it == ChangeOrderStatus.APPROVED || it == ChangeOrderStatus.REJECTED }
            ?: return invalid("invalid_decision", "decision")
        val who = name.trim()
        if (who.length < MIN_NAME) return invalid("name_required", "name")
        if (who.length > BuildingLimits.NAME) return invalid("name_too_long", "name")
        val said = note?.trim()?.ifEmpty { null }
        if (said != null && said.length > BuildingLimits.NOTE) return invalid("note_too_long", "note")
        val next = change.copy(status = target, decidedAt = stamp.now, decidedBy = who, decisionNote = said, decidedVia = DecidedVia.CLIENT_LINK, updatedAt = stamp.now)
        val stored = records.changes.replace(change, next) ?: return conflict("already_decided")
        val project = records.projects.findById(stored.projectId)
        if (project != null) records.events.changeDecided(stored, project, DecidedVia.CLIENT_LINK)
        runCatching {
            records.notifications.notify(
                tenantId = records.tenantId,
                kind = NotificationKinds.CHANGE_ORDER_DECIDED,
                audience = NotificationAudience.ALL,
                params = mapOf("name" to who, "number" to stored.number, "title" to stored.title, "project" to project?.name.orEmpty(), "decision" to target.name),
                link = DashboardModules.CHANGE_ORDERS,
                subject = SubjectRef.of(SubjectTypes.CHANGE_ORDER, stored.id),
            )
        }
        return Outcome.Done(stored)
    }

    /** An approved change is part of the contract: take it back to draft first. */
    suspend fun delete(id: ObjectId): Outcome<Unit> {
        val change = records.changes.findById(id) ?: return Outcome.NotFound
        if (change.status == ChangeOrderStatus.APPROVED) return conflict("change_approved")
        records.changes.delete(change.id)
        return Outcome.Done(Unit)
    }
}

/** A name shorter than this is no name: the client must say who they are. */
private const val MIN_NAME = 2

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
