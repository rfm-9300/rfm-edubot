package com.rfm.edubot.timesheets

import com.rfm.edubot.timesheets.model.Punch
import com.rfm.edubot.timesheets.model.PunchChannel
import com.rfm.edubot.timesheets.model.PunchType
import com.rfm.edubot.timesheets.model.ReviewStatus
import com.rfm.edubot.timesheets.model.Shift
import com.rfm.edubot.timesheets.model.ShiftBreak
import com.rfm.edubot.timesheets.model.ShiftFlags
import com.rfm.edubot.timesheets.model.ShiftStatus
import com.rfm.edubot.timesheets.model.ShiftTimes
import com.rfm.edubot.timesheets.model.TimesheetSettings
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ShiftRulesTest {
    private val lisbon = TimeZone.of("Europe/Lisbon")
    private val settings = TimesheetSettings.DEFAULT
    private val tenantId = ObjectId()
    private val employeeId = ObjectId()
    private val monday8 = Instant.parse("2026-10-05T07:00:00Z") // 08:00 in Lisbon (summer time)

    private fun punch(type: PunchType, at: Instant, flags: List<String> = emptyList()) = Punch(type, at, PunchChannel.APP, flags = flags)

    private fun started(at: Instant = monday8, flags: List<String> = emptyList()): Shift =
        ShiftRules.start(tenantId, employeeId, punch(PunchType.IN, at, flags), site = null, note = null, settings, lisbon, now = at)

    private fun Transition.shift(): Shift = assertIs<Transition.Ok>(this).shift

    private fun Shift.then(type: PunchType, at: Instant): Shift = ShiftRules.punch(this, punch(type, at), settings, lisbon, at).shift()

    @Test
    fun `worked time takes the breaks off, down to the minute`() {
        val shift = started()
            .then(PunchType.BREAK_START, monday8 + 4.hours)
            .then(PunchType.BREAK_END, monday8 + 4.hours + 45.minutes)
            .then(PunchType.OUT, monday8 + 9.hours + 59.minutes + 59.seconds)
        assertEquals(ShiftStatus.CLOSED, shift.status)
        assertEquals(45, shift.breakMinutes)
        assertEquals(9 * 60 + 14, shift.workedMinutes)
        assertEquals(LocalDate(2026, 10, 5), shift.day)
        assertEquals(ReviewStatus.PENDING, shift.review.status)
    }

    @Test
    fun `breaks follow the order of the day, and clocking out ends one`() {
        val open = started()
        assertEquals("not_on_break", assertIs<Transition.Refused>(ShiftRules.punch(open, punch(PunchType.BREAK_END, monday8 + 1.hours), settings, lisbon, monday8 + 1.hours)).error)
        assertEquals("already_clocked_in", assertIs<Transition.Refused>(ShiftRules.punch(open, punch(PunchType.IN, monday8 + 1.hours), settings, lisbon, monday8 + 1.hours)).error)
        val onBreak = open.then(PunchType.BREAK_START, monday8 + 3.hours)
        assertTrue(onBreak.onBreak)
        assertEquals("on_break", assertIs<Transition.Refused>(ShiftRules.punch(onBreak, punch(PunchType.BREAK_START, monday8 + 4.hours), settings, lisbon, monday8 + 4.hours)).error)
        val closed = onBreak.then(PunchType.OUT, monday8 + 5.hours)
        assertEquals(ShiftBreak(monday8 + 3.hours, monday8 + 5.hours), closed.breaks.single())
        assertEquals(180, closed.workedMinutes)
        assertEquals("not_clocked_in", assertIs<Transition.Refused>(ShiftRules.punch(closed, punch(PunchType.OUT, monday8 + 6.hours), settings, lisbon, monday8 + 6.hours)).error)
    }

    @Test
    fun `flags gather the punches' own and a shift longer than the company's limit`() {
        val shift = started(flags = listOf(ShiftFlags.UNVERIFIED, ShiftFlags.OUTSIDE_SITE)).then(PunchType.OUT, monday8 + 13.hours)
        assertEquals(listOf(ShiftFlags.LONG_SHIFT, ShiftFlags.OUTSIDE_SITE, ShiftFlags.UNVERIFIED), shift.flags)
    }

    @Test
    fun `typed times on the company's clock cross midnight and daylight saving`() {
        val night = ShiftMath.wallTimes(LocalDate(2026, 10, 5), LocalTime(22, 0), LocalTime(6, 0), listOf(LocalTime(23, 30) to LocalTime(0, 15)), lisbon)
        assertEquals(Instant.parse("2026-10-05T21:00:00Z"), night.startAt)
        assertEquals(Instant.parse("2026-10-06T05:00:00Z"), night.endAt)
        assertEquals(ShiftBreak(Instant.parse("2026-10-05T22:30:00Z"), Instant.parse("2026-10-05T23:15:00Z")), night.breaks.single())
        assertEquals(8 * 60 - 45, ShiftMath.workedMinutes(night, night.endAt!!))

        // Clocks go forward at 01:00 on 29 March 2026: 00:00–08:00 that night is seven hours.
        val spring = ShiftMath.wallTimes(LocalDate(2026, 3, 29), LocalTime(0, 0), LocalTime(8, 0), emptyList(), lisbon)
        assertEquals(7 * 60, ShiftMath.workedMinutes(spring, spring.endAt!!))
        assertEquals("08:00", ShiftMath.localTime(spring.endAt!!, lisbon))
        assertEquals(LocalTime(9, 30), ShiftMath.parseTime("09:30"))
        assertNull(ShiftMath.parseTime("9:30"))
        assertNull(ShiftMath.parseTime("25:00"))
    }

    @Test
    fun `typed times are checked before they replace a shift`() {
        val now = Instant.parse("2026-10-10T12:00:00Z")
        val day = LocalDate(2026, 10, 5)
        fun times(vararg breaks: Pair<String, String>) =
            ShiftMath.wallTimes(day, LocalTime(8, 0), LocalTime(17, 0), breaks.map { LocalTime.parse(it.first) to LocalTime.parse(it.second) }, lisbon)
        assertNull(ShiftMath.problem(times("12:00" to "13:00"), now))
        assertEquals("breaks_overlap", ShiftMath.problem(times("12:00" to "13:00", "12:30" to "12:45"), now))
        assertEquals("break_outside_shift", ShiftMath.problem(times("17:30" to "18:00"), now))
        assertEquals("break_invalid", ShiftMath.problem(times("13:00" to "12:00"), now))
        assertEquals("in_future", ShiftMath.problem(times(), Instant.parse("2026-10-05T10:00:00Z")))
        assertEquals("end_required", ShiftMath.problem(ShiftTimes(now - 3.hours, null, emptyList()), now))
    }

    @Test
    fun `a break ended within its first minute doesn't block correcting the shift`() {
        val now = Instant.parse("2026-10-10T12:00:00Z")
        val times = ShiftMath.wallTimes(
            LocalDate(2026, 10, 5), LocalTime(8, 0), LocalTime(17, 0),
            listOf(LocalTime(10, 15) to LocalTime(10, 15), LocalTime(12, 0) to LocalTime(13, 0)), lisbon,
        )
        assertNull(ShiftMath.problem(times, now), "the form sends back the 10:15–10:15 the shift shows")
        assertEquals(listOf(Instant.parse("2026-10-05T11:00:00Z")), times.breaks.map { it.startAt })
    }

    @Test
    fun `a forgotten clock-out is only for a shift past the company's limit`() {
        val open = started().then(PunchType.BREAK_START, monday8 + 4.hours)
        val early = monday8 + 6.hours
        assertEquals("not_overdue", assertIs<Transition.Refused>(ShiftRules.closeForgotten(open, monday8 + 5.hours, settings, lisbon, early)).error)
        val nextMorning = monday8 + 24.hours
        assertEquals("end_before_last_punch", assertIs<Transition.Refused>(ShiftRules.closeForgotten(open, monday8 + 3.hours, settings, lisbon, nextMorning)).error)
        assertEquals("in_future", assertIs<Transition.Refused>(ShiftRules.closeForgotten(open, nextMorning + 2.hours, settings, lisbon, nextMorning)).error)
        val closed = ShiftRules.closeForgotten(open, monday8 + 9.hours, settings, lisbon, nextMorning).shift()
        assertEquals(monday8 + 9.hours, closed.endAt)
        assertEquals(240, closed.workedMinutes, "the break nobody ended runs until the end they gave")
        assertEquals(listOf(ShiftFlags.MISSED_CLOCK_OUT), closed.flags)
        assertEquals(nextMorning, closed.punches.last().recordedAt)
    }

    @Test
    fun `the team's corrections keep what the shift was and send it back for approval`() {
        val now = monday8 + 30.hours
        val closed = started().then(PunchType.OUT, monday8 + 8.hours)
        val approved = ShiftRules.approve(closed, "ana@obras.test", now).shift()
        assertEquals(ReviewStatus.APPROVED, approved.review.status)
        assertEquals("already_approved", assertIs<Transition.Refused>(ShiftRules.approve(approved, "ana@obras.test", now)).error)
        assertEquals("shift_open", assertIs<Transition.Refused>(ShiftRules.approve(started(), "ana@obras.test", now)).error)

        val corrected = ShiftMath.wallTimes(LocalDate(2026, 10, 5), LocalTime(8, 0), LocalTime(17, 30), listOf(LocalTime(12, 0) to LocalTime(13, 0)), lisbon)
        assertEquals("reason_required", assertIs<Transition.Refused>(ShiftRules.edit(approved, corrected, "rui@obras.test", " ", settings, lisbon, now)).error)
        val edited = ShiftRules.edit(approved, corrected, "rui@obras.test", "Esqueceu-se da pausa", settings, lisbon, now).shift()
        assertEquals(ReviewStatus.PENDING, edited.review.status)
        assertEquals(8 * 60 + 30, edited.workedMinutes)
        assertEquals(listOf(ShiftFlags.EDITED), edited.flags)
        val history = edited.edits.single()
        assertEquals(closed.times, history.before)
        assertEquals(corrected, history.after)
        assertEquals(closed.punches, edited.punches, "what was punched stays as evidence")
        assertEquals("shift_open", assertIs<Transition.Refused>(ShiftRules.edit(started(), corrected, "rui", "x", settings, lisbon, now)).error)
    }

    @Test
    fun `the team closes a forgotten shift with a reason`() {
        val open = started()
        val now = monday8 + 26.hours
        val closed = ShiftRules.closeByTeam(open, monday8 + 9.hours, "rui@obras.test", "Saiu às 17h", settings, lisbon, now).shift()
        assertEquals(ShiftStatus.CLOSED, closed.status)
        assertEquals(listOf(ShiftFlags.EDITED), closed.flags)
        assertEquals(PunchChannel.TEAM, closed.punches.last().channel)
        assertNull(closed.edits.single().before?.endAt)
    }

    @Test
    fun `a shift the team adds is marked as added, with its reason`() {
        val times = ShiftMath.wallTimes(LocalDate(2026, 10, 6), LocalTime(9, 0), LocalTime(13, 0), emptyList(), lisbon)
        val shift = ShiftRules.manual(tenantId, employeeId, times, "Formação", "rui@obras.test", "Sem telemóvel", settings, lisbon, monday8 + 72.hours).shift()
        assertEquals(240, shift.workedMinutes)
        assertEquals(listOf(ShiftFlags.MANUAL), shift.flags)
        assertNull(shift.edits.single().before)
        assertEquals(LocalDate(2026, 10, 6), shift.day)
    }

    @Test
    fun `totals add up days and weeks and what went past the company's hours`() {
        val now = Instant.parse("2026-10-12T12:00:00Z")
        fun day(date: String, hours: Int): Shift {
            val start = ShiftMath.wallTimes(LocalDate.parse(date), LocalTime(8, 0), null, emptyList(), lisbon).startAt
            return started(start).then(PunchType.OUT, start + hours.hours)
        }
        val week = listOf(day("2026-10-05", 10), day("2026-10-06", 9), day("2026-10-07", 9), day("2026-10-08", 9), day("2026-10-09", 8))
        val approved = ShiftRules.approve(week.last(), "rui", now).shift()
        val totals = Timesheets.totals(week.dropLast(1) + approved, settings, now).single()
        assertEquals(45 * 60, totals.workedMinutes)
        assertEquals(5, totals.days)
        assertEquals(5 * 60, totals.overDailyMinutes)
        assertEquals(5 * 60, totals.overWeeklyMinutes)
        assertEquals(4, totals.toReview)
        assertEquals(0, totals.open)
    }
}
