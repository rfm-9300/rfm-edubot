package com.rfm.edubot.timesheets

import com.rfm.edubot.timesheets.model.Excuse
import com.rfm.edubot.timesheets.model.PlannedShift
import com.rfm.edubot.timesheets.model.Punch
import com.rfm.edubot.timesheets.model.PunchChannel
import com.rfm.edubot.timesheets.model.PunchType
import com.rfm.edubot.timesheets.model.SchedulePolicy
import com.rfm.edubot.timesheets.model.Shift
import com.rfm.edubot.timesheets.model.ShiftFlags
import com.rfm.edubot.timesheets.model.TimesheetSettings
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Planned shifts against clocked ones: matching, the strict clock-in window, lateness, early leave and absences. */
class AttendanceTest {
    private val lisbon = TimeZone.of("Europe/Lisbon")
    private val tenantId = ObjectId()
    private val employeeId = ObjectId()
    private val monday8 = Instant.parse("2026-11-16T08:00:00Z")
    private val flag = TimesheetSettings(schedule = SchedulePolicy.FLAG)
    private val block = TimesheetSettings(schedule = SchedulePolicy.BLOCK, earlyClockInMinutes = 15)

    private fun plan(start: Instant, hours: Int = 8, excused: Boolean = false) = PlannedShift(
        tenantId = tenantId, employeeId = employeeId, day = LocalDate(2026, 11, 16), startAt = start, endAt = start + hours.hours,
        excuse = if (excused) Excuse("Consulta médica", "rui", start) else null, createdBy = "rui", createdAt = start, updatedAt = start,
    )

    private fun Transition.shift(): Shift = assertIs<Transition.Ok>(this).shift

    private fun clocked(plan: PlannedShift, start: Instant, end: Instant?, settings: TimesheetSettings = flag, first: Boolean = true): Shift {
        val open = ShiftRules.start(tenantId, employeeId, Punch(PunchType.IN, start, PunchChannel.WEB), null, null, settings, lisbon, start, Attendance.link(plan, !first))
        return if (end == null) open else ShiftRules.punch(open, Punch(PunchType.OUT, end, PunchChannel.WEB), settings, lisbon, end).shift()
    }

    @Test
    fun `a clock-in belongs to the planned shift whose window holds it`() {
        val morning = plan(monday8, hours = 4)
        val afternoon = plan(monday8 + 5.hours, hours = 4)
        assertEquals(morning, Attendance.match(listOf(morning, afternoon), monday8 - 3.hours, flag), "up to 4 hours early, unless strict")
        assertNull(Attendance.match(listOf(morning), monday8 - 5.hours, flag))
        assertEquals(afternoon, Attendance.match(listOf(morning, afternoon), monday8 + 4.hours + 40.minutes, flag), "the nearest start")
        assertNull(Attendance.match(listOf(plan(monday8, excused = true)), monday8, flag), "an excused plan expects nobody")
        assertNull(Attendance.match(listOf(morning), monday8 + 4.hours, flag), "a plan that ended takes nobody")
    }

    @Test
    fun `the strict rule opens clocking in shortly before a planned shift, and only for one`() {
        val morning = plan(monday8)
        val early = assertIs<Attendance.ClockIn.TooEarly>(Attendance.clockIn(listOf(morning), monday8 - 30.minutes, block))
        assertEquals(monday8 - 15.minutes, early.opensAt)
        assertEquals(morning, assertIs<Attendance.ClockIn.Allowed>(Attendance.clockIn(listOf(morning), monday8 - 10.minutes, block)).plan)
        assertEquals(Attendance.ClockIn.NotScheduled, Attendance.clockIn(emptyList(), monday8, block))
        assertEquals(Attendance.ClockIn.NotScheduled, Attendance.clockIn(listOf(morning), monday8 + 9.hours, block), "after it ended")
        assertNull(assertIs<Attendance.ClockIn.Allowed>(Attendance.clockIn(emptyList(), monday8, flag)).plan, "flagging never refuses")
    }

    @Test
    fun `lateness and an early leave are flagged past the grace, only while schedules are checked`() {
        val planned = plan(monday8)
        val late = clocked(planned, monday8 + 12.minutes, monday8 + 7.hours)
        assertEquals(12, late.lateMinutes)
        assertEquals(60, late.earlyLeaveMinutes)
        assertTrue(ShiftFlags.LATE in late.flags && ShiftFlags.LEFT_EARLY in late.flags)
        val onTime = clocked(planned, monday8 + 4.minutes, monday8 + 8.hours - 3.minutes)
        assertTrue(ShiftFlags.LATE !in onTime.flags && ShiftFlags.LEFT_EARLY !in onTime.flags, "within the 5-minute grace")
        val unchecked = clocked(planned, monday8 + 30.minutes, monday8 + 6.hours, TimesheetSettings.DEFAULT)
        assertEquals(30, unchecked.lateMinutes, "the minutes are kept")
        assertTrue(ShiftFlags.LATE !in unchecked.flags, "without schedule checks, no flags")
        val unplanned = ShiftRules.start(tenantId, employeeId, Punch(PunchType.IN, monday8, PunchChannel.WEB), null, null, flag, lisbon, monday8)
        assertTrue(ShiftFlags.UNSCHEDULED in unplanned.flags)
    }

    @Test
    fun `clocking out for lunch and back in is one attendance, not an early leave and a late arrival`() {
        val planned = plan(monday8)
        val morning = clocked(planned, monday8, monday8 + 4.hours)
        assertTrue(ShiftFlags.LEFT_EARLY in morning.flags)
        val continued = ShiftRules.replan(morning, morning.planned!!.copy(continued = true), flag, lisbon, monday8 + 5.hours).shift()
        assertTrue(ShiftFlags.LEFT_EARLY !in continued.flags)
        val afternoon = clocked(planned, monday8 + 5.hours, monday8 + 8.hours, first = false)
        assertTrue(ShiftFlags.LATE !in afternoon.flags, "only the first shift of a plan is checked for lateness")
        val status = Attendance.status(planned, listOf(continued, afternoon), flag, monday8 + 9.hours)
        assertEquals(Attendance.State.ATTENDED, status.state)
        assertEquals(0 to 0, status.lateMinutes to status.earlyLeaveMinutes)
        assertEquals(7 * 60, status.workedMinutes)
    }

    @Test
    fun `a planned shift nobody clocked in for is due, then missing, then absent`() {
        val planned = plan(monday8)
        val states = listOf(monday8 - 1.hours, monday8 + 10.minutes, monday8 + 40.minutes, monday8 + 9.hours)
            .map { Attendance.status(planned, emptyList(), flag, it).state }
        assertEquals(listOf(Attendance.State.UPCOMING, Attendance.State.DUE, Attendance.State.MISSING, Attendance.State.ABSENT), states)
        assertEquals(Attendance.State.EXCUSED, Attendance.status(plan(monday8, excused = true), emptyList(), flag, monday8 + 9.hours).state)
        val other = plan(monday8 - 4.hours, hours = 3)
        val overlapping = clocked(other, monday8 - 4.hours, monday8 + 2.hours)
        val covered = Attendance.status(planned, listOf(overlapping), flag, monday8 + 9.hours)
        assertEquals(Attendance.State.ATTENDED, covered.state, "a shift that ran over the plan counts as being there")
        val working = Attendance.status(planned, listOf(clocked(planned, monday8 + 20.minutes, null)), flag, monday8 + 1.hours)
        assertEquals(Attendance.State.WORKING, working.state)
        assertTrue(working.late)
    }
}
