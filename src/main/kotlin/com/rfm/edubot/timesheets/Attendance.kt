package com.rfm.edubot.timesheets

import com.rfm.edubot.timesheets.Timesheets.workedUntil
import com.rfm.edubot.timesheets.model.PlanLink
import com.rfm.edubot.timesheets.model.PlannedShift
import com.rfm.edubot.timesheets.model.SchedulePolicy
import com.rfm.edubot.timesheets.model.Shift
import com.rfm.edubot.timesheets.model.TimesheetSettings
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Planned shifts against clocked ones: which plan a clock-in belongs to, whether it may happen, and how a plan went. */
object Attendance {
    /** Unless the company is strict, a clock-in this long before a planned start still belongs to it. */
    val EARLY_WINDOW = 4.hours

    /** Under the strict rule, a planned shift starting within this long is the one a too-early clock-in is waiting for. */
    private val NEXT_WINDOW = 24.hours

    enum class State { EXCUSED, UPCOMING, DUE, MISSING, ABSENT, WORKING, ATTENDED }

    sealed interface ClockIn {
        data class Allowed(val plan: PlannedShift?) : ClockIn

        /** Clocking in opens at [opensAt], before [plan]. */
        data class TooEarly(val plan: PlannedShift, val opensAt: Instant) : ClockIn

        data object NotScheduled : ClockIn
    }

    /** How a planned shift went, from the shifts linked to it (or, without any, overlapping it). */
    data class Status(
        val state: State,
        val lateMinutes: Int = 0,
        val earlyLeaveMinutes: Int = 0,
        val late: Boolean = false,
        val leftEarly: Boolean = false,
        val workedMinutes: Int = 0,
        val shifts: List<Shift> = emptyList(),
    )

    fun opensAt(plan: PlannedShift, settings: TimesheetSettings): Instant =
        plan.startAt - if (settings.schedule == SchedulePolicy.BLOCK) settings.earlyClockInMinutes.minutes else EARLY_WINDOW

    /** The planned shift a clock-in at [at] belongs to: one whose window holds it, the nearest start first. */
    fun match(plans: List<PlannedShift>, at: Instant, settings: TimesheetSettings): PlannedShift? =
        plans.filter { it.excuse == null && at >= opensAt(it, settings) && at < it.endAt }
            .minByOrNull { (at - it.startAt).absoluteValue }

    /** Whether a clock-in at [at] may happen, and for which plan. Only the strict rule refuses. */
    fun clockIn(plans: List<PlannedShift>, at: Instant, settings: TimesheetSettings): ClockIn {
        val plan = match(plans, at, settings)
        if (plan != null || settings.schedule != SchedulePolicy.BLOCK) return ClockIn.Allowed(plan)
        val next = plans.filter { it.excuse == null && it.startAt > at && it.startAt - at <= NEXT_WINDOW }.minByOrNull { it.startAt }
        return if (next != null) ClockIn.TooEarly(next, opensAt(next, settings)) else ClockIn.NotScheduled
    }

    /** The link a shift starting for [plan] gets; [linkedBefore] says whether an earlier shift already linked to it. */
    fun link(plan: PlannedShift, linkedBefore: Boolean): PlanLink = PlanLink(plan.id, plan.startAt, plan.endAt, first = !linkedBefore)

    /** How [plan] went by [now], from the employee's [shifts] around it. */
    fun status(plan: PlannedShift, shifts: List<Shift>, settings: TimesheetSettings, now: Instant): Status {
        val linked = shifts.filter { it.planned?.id == plan.id }.ifEmpty { shifts.filter { overlaps(it, plan, now) } }.sortedBy { it.startAt }
        if (linked.isNotEmpty()) {
            val open = linked.any { it.isOpen }
            val late = (linked.first().startAt - plan.startAt).inWholeMinutes.toInt().coerceAtLeast(0)
            val end = linked.last().endAt
            val early = if (open || end == null) 0 else (plan.endAt - end).inWholeMinutes.toInt().coerceAtLeast(0)
            return Status(
                state = if (plan.excuse != null) State.EXCUSED else if (open) State.WORKING else State.ATTENDED,
                lateMinutes = late,
                earlyLeaveMinutes = early,
                late = late > settings.lateGraceMinutes,
                leftEarly = early > settings.earlyLeaveGraceMinutes,
                workedMinutes = linked.sumOf { it.workedUntil(now) },
                shifts = linked,
            )
        }
        val state = when {
            plan.excuse != null -> State.EXCUSED
            now < plan.startAt -> State.UPCOMING
            now < plan.startAt + settings.noShowMinutes.minutes -> State.DUE
            now < plan.endAt -> State.MISSING
            else -> State.ABSENT
        }
        return Status(state)
    }

    /** Planned minutes: from start to end, as planned. */
    fun minutes(plan: PlannedShift): Int = (plan.endAt - plan.startAt).inWholeMinutes.toInt()

    private fun overlaps(shift: Shift, plan: PlannedShift, now: Instant): Boolean = shift.startAt < plan.endAt && (shift.endAt ?: now) > plan.startAt
}
