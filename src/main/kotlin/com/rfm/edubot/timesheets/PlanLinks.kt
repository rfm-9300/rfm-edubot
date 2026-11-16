package com.rfm.edubot.timesheets

import com.rfm.edubot.timesheets.model.PlannedShift
import com.rfm.edubot.timesheets.model.TimesheetSettings
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.bson.types.ObjectId

/** Keeps the shifts linked to a planned shift in step with it and with each other. */
internal object PlanLinks {
    /**
     * Puts `first` and `continued` right on every shift linked to [planId], whatever order they were added in: the
     * earliest is the one checked for lateness, and every one but the latest was continued.
     */
    suspend fun relink(shifts: ShiftRepository, planId: ObjectId, rules: TimesheetSettings, zone: TimeZone, now: Instant) {
        val linked = shifts.linkedTo(listOf(planId)).sortedBy { it.startAt }
        linked.forEachIndexed { i, shift ->
            val first = i == 0
            val continued = i < linked.lastIndex
            val link = shift.planned ?: return@forEachIndexed
            if (link.first != first || link.continued != continued) {
                shifts.update(shift.id) { current -> ShiftRules.replan(current, current.planned?.copy(first = first, continued = continued), rules, zone, now) }
            }
        }
    }

    /** The plan's times changed: every shift linked to it measures lateness and early leave against the new ones. */
    suspend fun retime(shifts: ShiftRepository, plan: PlannedShift, rules: TimesheetSettings, zone: TimeZone, now: Instant) {
        shifts.linkedTo(listOf(plan.id)).forEach { shift ->
            shifts.update(shift.id) { current -> ShiftRules.replan(current, current.planned?.copy(startAt = plan.startAt, endAt = plan.endAt), rules, zone, now) }
        }
    }
}
