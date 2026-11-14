package com.rfm.edubot.timesheets

import com.rfm.edubot.timesheets.model.Job
import com.rfm.edubot.timesheets.model.JobsChange
import com.rfm.edubot.timesheets.model.PlanLink
import com.rfm.edubot.timesheets.model.Punch
import com.rfm.edubot.timesheets.model.PunchChannel
import com.rfm.edubot.timesheets.model.PunchType
import com.rfm.edubot.timesheets.model.ReviewStatus
import com.rfm.edubot.timesheets.model.SegmentSource
import com.rfm.edubot.timesheets.model.Shift
import com.rfm.edubot.timesheets.model.ShiftBreak
import com.rfm.edubot.timesheets.model.ShiftEdit
import com.rfm.edubot.timesheets.model.ShiftFlags
import com.rfm.edubot.timesheets.model.ShiftReview
import com.rfm.edubot.timesheets.model.ShiftSegment
import com.rfm.edubot.timesheets.model.ShiftStatus
import com.rfm.edubot.timesheets.model.ShiftTimes
import com.rfm.edubot.timesheets.model.TimesheetSettings
import com.rfm.edubot.timesheets.model.WorkLogPolicy
import com.rfm.edubot.timesheets.model.WorkSite
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Hours worked, company-local days and times, and the checks on times people type. */
object ShiftMath {
    const val MAX_BREAKS = 10
    const val MAX_REASON = 500
    const val MAX_NOTE = 500
    private val MAX_SPAN = 24.hours
    /** A browser or phone clock a little ahead of the server's still counts as now. */
    private val CLOCK_TOLERANCE = 1.minutes

    /** Breaks count only inside the shift; an open break or shift runs until [until]. */
    private fun breakSeconds(times: ShiftTimes, until: Instant): Long {
        val end = times.endAt ?: until
        return times.breaks.sumOf { b ->
            val from = maxOf(b.startAt, times.startAt)
            val to = minOf(b.endAt ?: end, end)
            if (to > from) (to - from).inWholeSeconds else 0L
        }
    }

    fun breakMinutes(times: ShiftTimes, until: Instant): Int = (breakSeconds(times, until) / 60).toInt()

    /**
     * Seconds worked from the shift's start up to [upTo], no further than its end (or [now] while open), breaks
     * taken off. At the end it is exactly what [workedMinutes] counts, so pieces cut at any times add up to it.
     */
    fun workedSecondsUpTo(times: ShiftTimes, upTo: Instant, now: Instant): Long {
        val end = times.endAt ?: now
        val cut = minOf(upTo, end)
        if (cut <= times.startAt) return 0
        val breaks = times.breaks.sumOf { b ->
            val from = maxOf(b.startAt, times.startAt)
            val to = minOf(b.endAt ?: end, end, cut)
            if (to > from) (to - from).inWholeSeconds else 0L
        }
        return ((cut - times.startAt).inWholeSeconds - breaks).coerceAtLeast(0)
    }

    /** Unpaid breaks are taken off; exact minutes, never rounded up. */
    fun workedMinutes(times: ShiftTimes, until: Instant): Int {
        val end = times.endAt ?: until
        if (end <= times.startAt) return 0
        val seconds = (end - times.startAt).inWholeSeconds - breakSeconds(times, until)
        return (seconds.coerceAtLeast(0) / 60).toInt()
    }

    fun localDay(at: Instant, zone: TimeZone): LocalDate = at.toLocalDateTime(zone).date

    /** `HH:mm` in the company's timezone. */
    fun localTime(at: Instant, zone: TimeZone): String {
        val time = at.toLocalDateTime(zone).time
        return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    }

    fun weekStart(day: LocalDate): LocalDate = day.minus(day.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

    fun parseTime(value: String?): LocalTime? =
        value?.trim()?.takeIf { Regex("""\d{2}:\d{2}""").matches(it) }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

    /**
     * Wall-clock times typed for a shift that starts on [day]: a time earlier than the start is on the next
     * day, so `22:00`–`06:00` is a night shift. The timezone's daylight-saving rules apply. A break typed
     * with the same start and end is left out: it's what a break ended within its first minute shows as,
     * and refusing it would block every correction of that shift.
     */
    fun wallTimes(day: LocalDate, start: LocalTime, end: LocalTime?, breaks: List<Pair<LocalTime, LocalTime?>>, zone: TimeZone): ShiftTimes {
        val startAt = LocalDateTime(day, start).toInstant(zone)
        fun after(time: LocalTime, strictly: Boolean): Instant {
            val same = LocalDateTime(day, time).toInstant(zone)
            val beforeStart = if (strictly) same <= startAt else same < startAt
            return if (beforeStart) LocalDateTime(day.plus(1, DateTimeUnit.DAY), time).toInstant(zone) else same
        }
        return ShiftTimes(
            startAt = startAt,
            endAt = end?.let { after(it, strictly = true) },
            breaks = breaks.filterNot { (from, to) -> from == to }
                .map { (from, to) -> ShiftBreak(after(from, strictly = false), to?.let { after(it, strictly = false) }) },
        )
    }

    /** A wall-clock end for a shift that started at [startAt]: the first such time after the start. */
    fun endAfter(startAt: Instant, end: LocalTime, zone: TimeZone): Instant =
        wallTimes(localDay(startAt, zone), startAt.toLocalDateTime(zone).time, end, emptyList(), zone).endAt!!

    /** The first problem with times the team typed for a closed shift, as a stable error code. */
    fun problem(times: ShiftTimes, now: Instant): String? {
        val end = times.endAt ?: return "end_required"
        if (end <= times.startAt) return "end_before_start"
        if (times.startAt > now + CLOCK_TOLERANCE || end > now + CLOCK_TOLERANCE) return "in_future"
        if (end - times.startAt > MAX_SPAN) return "shift_too_long"
        if (times.breaks.size > MAX_BREAKS) return "too_many_breaks"
        val sorted = times.breaks.sortedBy { it.startAt }
        sorted.forEach { b ->
            val bEnd = b.endAt ?: return "break_invalid"
            if (bEnd <= b.startAt) return "break_invalid"
            if (b.startAt < times.startAt || bEnd > end) return "break_outside_shift"
        }
        if (sorted.zipWithNext().any { (a, b) -> b.startAt < a.endAt!! }) return "breaks_overlap"
        return null
    }
}

sealed interface Transition {
    data class Ok(val shift: Shift) : Transition
    data class Refused(val error: String) : Transition
}

/** How a shift changes. Pure: the repository stores what these return. */
object ShiftRules {
    fun isOverdue(shift: Shift, settings: TimesheetSettings, now: Instant): Boolean =
        shift.isOpen && now - shift.startAt > settings.maxShiftHours.hours

    /**
     * A new shift from a clock-in; [site] is the site the punch was inside, if any, and [plan] the planned shift it
     * belongs to. A company that logs jobs gets the first segment, on the job the clock-in picked.
     */
    fun start(
        tenantId: ObjectId,
        employeeId: ObjectId,
        punch: Punch,
        site: WorkSite?,
        note: String?,
        settings: TimesheetSettings,
        zone: TimeZone,
        now: Instant,
        plan: PlanLink? = null,
    ): Shift = Shift(
        tenantId = tenantId,
        employeeId = employeeId,
        status = ShiftStatus.OPEN,
        startAt = punch.at,
        day = ShiftMath.localDay(punch.at, zone),
        siteId = site?.id,
        siteName = site?.name,
        clientId = site?.clientId,
        note = note.cleanNote(),
        punches = listOf(punch),
        segments = if (settings.logsJobs) listOf(ShiftSegment(punch.at, punch.job, SegmentSource.CLOCK, site?.name)) else emptyList(),
        planned = plan,
        createdAt = now,
        updatedAt = now,
    ).recomputed(settings, zone, now)

    /**
     * A break, clock-out or switch on the open [shift]; clocking out during a break ends the break. A switch moves
     * the work to the punch's job from the punch's time on, never during a break.
     */
    fun punch(shift: Shift, punch: Punch, settings: TimesheetSettings, zone: TimeZone, now: Instant): Transition {
        if (!shift.isOpen) return Transition.Refused("not_clocked_in")
        val at = maxOf(punch.at, shift.lastPunchAt)
        val next = when (punch.type) {
            PunchType.IN -> return Transition.Refused("already_clocked_in")
            PunchType.BREAK_START -> {
                if (shift.onBreak) return Transition.Refused("on_break")
                shift.copy(breaks = shift.breaks + ShiftBreak(at))
            }
            PunchType.BREAK_END -> {
                if (!shift.onBreak) return Transition.Refused("not_on_break")
                shift.copy(breaks = shift.breaks.endOpenBreak(at))
            }
            PunchType.OUT -> shift.closedAt(at)
            PunchType.SWITCH -> {
                if (!settings.logsJobs) return Transition.Refused("work_log_off")
                if (shift.onBreak) return Transition.Refused("on_break")
                if (WorkLog.isCurrent(shift, punch.job)) return Transition.Refused("same_job")
                shift.copy(segments = WorkLog.switched(shift, punch.job, at, punch.site?.takeIf { it.inside }?.siteName))
            }
        }
        return Transition.Ok(next.copy(punches = shift.punches + punch.copy(at = at), updatedAt = now).recomputed(settings, zone, now))
    }

    /**
     * The employee forgot to clock out and says when they stopped. Only for a shift open longer than the
     * company's limit, so it can't stand in for a clock-out (with its location and fingerprint) any other day.
     */
    fun closeForgotten(shift: Shift, endAt: Instant, settings: TimesheetSettings, zone: TimeZone, now: Instant): Transition {
        if (!shift.isOpen) return Transition.Refused("not_clocked_in")
        if (!isOverdue(shift, settings, now)) return Transition.Refused("not_overdue")
        endProblem(shift, endAt, now)?.let { return Transition.Refused(it) }
        val punch = Punch(PunchType.OUT, endAt, PunchChannel.CORRECTION, recordedAt = now, flags = listOf(ShiftFlags.MISSED_CLOCK_OUT))
        return Transition.Ok(shift.closedAt(endAt).copy(punches = shift.punches + punch, updatedAt = now).recomputed(settings, zone, now))
    }

    /** The team closes someone's open shift; it is kept as a correction with its reason. */
    fun closeByTeam(shift: Shift, endAt: Instant, by: String, reason: String, settings: TimesheetSettings, zone: TimeZone, now: Instant): Transition {
        if (!shift.isOpen) return Transition.Refused("not_clocked_in")
        reasonProblem(reason)?.let { return Transition.Refused(it) }
        endProblem(shift, endAt, now)?.let { return Transition.Refused(it) }
        val closed = shift.closedAt(endAt)
        val edit = ShiftEdit(now, by, reason.trim(), before = shift.times, after = closed.times)
        val punch = Punch(PunchType.OUT, endAt, PunchChannel.TEAM, by = by, recordedAt = now)
        return Transition.Ok(closed.copy(punches = shift.punches + punch, edits = shift.edits + edit, updatedAt = now).recomputed(settings, zone, now))
    }

    /** The team corrects a closed shift's times. The punches stay as they were; approval starts over. */
    fun edit(shift: Shift, times: ShiftTimes, by: String, reason: String, settings: TimesheetSettings, zone: TimeZone, now: Instant): Transition {
        if (shift.isOpen) return Transition.Refused("shift_open")
        reasonProblem(reason)?.let { return Transition.Refused(it) }
        val clean = times.copy(breaks = times.breaks.sortedBy { it.startAt })
        ShiftMath.problem(clean, now)?.let { return Transition.Refused(it) }
        if (clean == shift.times) return Transition.Ok(shift)
        val edit = ShiftEdit(now, by, reason.trim(), before = shift.times, after = clean)
        return Transition.Ok(
            shift.copy(
                startAt = clean.startAt,
                endAt = clean.endAt,
                breaks = clean.breaks,
                edits = shift.edits + edit,
                review = ShiftReview(),
                updatedAt = now,
            ).recomputed(settings, zone, now),
        )
    }

    /** A shift nobody clocked, added by the team with its reason, on [job] when the company logs jobs. */
    fun manual(
        tenantId: ObjectId,
        employeeId: ObjectId,
        times: ShiftTimes,
        note: String?,
        by: String,
        reason: String,
        settings: TimesheetSettings,
        zone: TimeZone,
        now: Instant,
        job: Job? = null,
        plan: PlanLink? = null,
    ): Transition {
        reasonProblem(reason)?.let { return Transition.Refused(it) }
        val clean = times.copy(breaks = times.breaks.sortedBy { it.startAt })
        ShiftMath.problem(clean, now)?.let { return Transition.Refused(it) }
        return Transition.Ok(
            Shift(
                tenantId = tenantId,
                employeeId = employeeId,
                status = ShiftStatus.CLOSED,
                startAt = clean.startAt,
                endAt = clean.endAt,
                breaks = clean.breaks,
                day = ShiftMath.localDay(clean.startAt, zone),
                note = note.cleanNote(),
                edits = listOf(ShiftEdit(now, by, reason.trim(), before = null, after = clean)),
                manual = true,
                segments = if (settings.logsJobs) listOf(ShiftSegment(clean.startAt, job, SegmentSource.TEAM)) else emptyList(),
                planned = plan,
                createdAt = now,
                updatedAt = now,
            ).recomputed(settings, zone, now),
        )
    }

    /** Under the strict work log, a shift with time on no complete job can't be approved: the hours aren't accounted for. */
    fun approve(shift: Shift, by: String, settings: TimesheetSettings, now: Instant): Transition {
        if (shift.isOpen) return Transition.Refused("shift_open")
        if (shift.review.status == ReviewStatus.APPROVED) return Transition.Refused("already_approved")
        if (settings.workLog == WorkLogPolicy.REQUIRED && WorkLog.unallocatedMinutes(shift, settings.jobNeeds, now) > 0) {
            return Transition.Refused("unallocated")
        }
        return Transition.Ok(shift.copy(review = ShiftReview(ReviewStatus.APPROVED, by, now), updatedAt = now))
    }

    /** The employee splits their own shift's time between jobs, until it is approved. */
    fun employeeJobs(shift: Shift, drafts: List<SegmentDraft>, settings: TimesheetSettings, zone: TimeZone, now: Instant): Transition {
        if (!settings.logsJobs) return Transition.Refused("work_log_off")
        if (shift.review.status == ReviewStatus.APPROVED) return Transition.Refused("already_approved")
        WorkLog.problem(shift, drafts, now)?.let { return Transition.Refused(it) }
        val segments = WorkLog.replaced(shift, drafts, SegmentSource.EMPLOYEE)
        if (segments == shift.segments) return Transition.Ok(shift)
        return Transition.Ok(shift.copy(segments = segments, updatedAt = now).recomputed(settings, zone, now))
    }

    /**
     * The team changes how a shift's time splits between jobs, with a reason kept in its history; an approved shift
     * goes back for approval.
     */
    fun teamJobs(shift: Shift, drafts: List<SegmentDraft>, by: String, reason: String, settings: TimesheetSettings, zone: TimeZone, now: Instant): Transition {
        if (!settings.logsJobs) return Transition.Refused("work_log_off")
        reasonProblem(reason)?.let { return Transition.Refused(it) }
        WorkLog.problem(shift, drafts, now)?.let { return Transition.Refused(it) }
        val segments = WorkLog.replaced(shift, drafts, SegmentSource.TEAM)
        if (segments == shift.segments) return Transition.Ok(shift)
        val edit = ShiftEdit(now, by, reason.trim(), before = shift.times, after = shift.times, jobs = JobsChange(shift.segments, segments))
        return Transition.Ok(
            shift.copy(segments = segments, edits = shift.edits + edit, review = ShiftReview(), updatedAt = now).recomputed(settings, zone, now),
        )
    }

    /** The shift's planned times changed (or its plan was linked again): lateness and early leave follow. */
    fun replan(shift: Shift, plan: PlanLink?, settings: TimesheetSettings, zone: TimeZone, now: Instant): Transition {
        if (plan == shift.planned) return Transition.Ok(shift)
        return Transition.Ok(shift.copy(planned = plan, updatedAt = now).recomputed(settings, zone, now))
    }

    /** The employee's own note, while nobody approved the shift. */
    fun note(shift: Shift, note: String?, now: Instant): Transition {
        if (shift.review.status == ReviewStatus.APPROVED) return Transition.Refused("already_approved")
        if ((note?.trim()?.length ?: 0) > ShiftMath.MAX_NOTE) return Transition.Refused("note_too_long")
        return Transition.Ok(shift.copy(note = note.cleanNote(), updatedAt = now))
    }

    /**
     * Minutes, the local day, the segments fitted to the times, lateness against the plan and the flags, from the
     * shift's times, punches, plan and history.
     */
    fun Shift.recomputed(settings: TimesheetSettings, zone: TimeZone, now: Instant): Shift {
        val end = endAt
        val fitted = copy(segments = WorkLog.fit(segments, startAt, end))
        val plan = planned
        val late = plan?.takeIf { it.first }?.let { (startAt - it.startAt).inWholeMinutes.toInt().coerceAtLeast(0) } ?: 0
        val early = if (plan == null || plan.continued || end == null) 0 else (plan.endAt - end).inWholeMinutes.toInt().coerceAtLeast(0)
        val checks = settings.checksSchedule
        val shiftFlags = listOfNotNull(
            ShiftFlags.LONG_SHIFT.takeIf { end != null && end - startAt > settings.maxShiftHours.hours },
            ShiftFlags.MISSED_CLOCK_OUT.takeIf { punches.any { it.channel == PunchChannel.CORRECTION } },
            ShiftFlags.EDITED.takeIf { edits.any { it.before != null } },
            ShiftFlags.MANUAL.takeIf { manual },
            ShiftFlags.UNALLOCATED.takeIf {
                settings.workLog == WorkLogPolicy.REQUIRED && WorkLog.unallocatedMinutes(fitted, settings.jobNeeds, now) > 0
            },
            ShiftFlags.LATE.takeIf { checks && late > settings.lateGraceMinutes },
            ShiftFlags.LEFT_EARLY.takeIf { checks && early > settings.earlyLeaveGraceMinutes },
            ShiftFlags.UNSCHEDULED.takeIf { checks && plan == null && !manual },
        )
        return fitted.copy(
            day = ShiftMath.localDay(startAt, zone),
            workedMinutes = ShiftMath.workedMinutes(times, now),
            breakMinutes = ShiftMath.breakMinutes(times, now),
            lateMinutes = late,
            earlyLeaveMinutes = early,
            flags = (punches.flatMap { it.flags } + shiftFlags).distinct().sorted(),
        )
    }

    private fun Shift.closedAt(at: Instant): Shift =
        copy(status = ShiftStatus.CLOSED, endAt = at, breaks = breaks.endOpenBreak(at), review = ShiftReview())

    private fun List<ShiftBreak>.endOpenBreak(at: Instant): List<ShiftBreak> =
        map { if (it.endAt == null) it.copy(endAt = maxOf(at, it.startAt)) else it }

    private fun endProblem(shift: Shift, endAt: Instant, now: Instant): String? = when {
        endAt < shift.lastPunchAt -> "end_before_last_punch"
        endAt > now + 1.minutes -> "in_future"
        else -> null
    }

    private fun reasonProblem(reason: String): String? = when {
        reason.isBlank() -> "reason_required"
        reason.trim().length > ShiftMath.MAX_REASON -> "reason_too_long"
        else -> null
    }

    private fun String?.cleanNote(): String? = this?.trim()?.takeIf { it.isNotEmpty() }?.take(ShiftMath.MAX_NOTE)
}

/** Hours per employee over a period, and how far they went past the company's daily and weekly hours. */
data class EmployeeTotals(
    val employeeId: ObjectId,
    val workedMinutes: Int,
    val days: Int,
    val overDailyMinutes: Int,
    val overWeeklyMinutes: Int,
    val toReview: Int,
    val flagged: Int,
    val open: Int,
)

object Timesheets {
    /** Open shifts count up to [now]. Weeks run Monday to Sunday, so pass whole weeks for the weekly figure. */
    fun totals(shifts: List<Shift>, settings: TimesheetSettings, now: Instant): List<EmployeeTotals> =
        shifts.groupBy { it.employeeId }.map { (employeeId, own) ->
            val perDay = own.groupBy { it.day }.mapValues { (_, list) -> list.sumOf { it.workedUntil(now) } }
            val perWeek = perDay.entries.groupBy({ ShiftMath.weekStart(it.key) }, { it.value }).mapValues { (_, minutes) -> minutes.sum() }
            EmployeeTotals(
                employeeId = employeeId,
                workedMinutes = perDay.values.sum(),
                days = perDay.size,
                overDailyMinutes = perDay.values.sumOf { (it - settings.dailyHours * 60).coerceAtLeast(0) },
                overWeeklyMinutes = perWeek.values.sumOf { (it - settings.weeklyHours * 60).coerceAtLeast(0) },
                toReview = own.count { !it.isOpen && it.review.status == ReviewStatus.PENDING },
                flagged = own.count { it.flags.isNotEmpty() },
                open = own.count { it.isOpen },
            )
        }

    fun Shift.workedUntil(now: Instant): Int = if (isOpen) ShiftMath.workedMinutes(times, now) else workedMinutes
}
