package com.rfm.edubot.timesheets

import com.rfm.edubot.timesheets.model.Job
import com.rfm.edubot.timesheets.model.JobNeeds
import com.rfm.edubot.timesheets.model.Punch
import com.rfm.edubot.timesheets.model.PunchChannel
import com.rfm.edubot.timesheets.model.PunchType
import com.rfm.edubot.timesheets.model.ReviewStatus
import com.rfm.edubot.timesheets.model.SegmentSource
import com.rfm.edubot.timesheets.model.Shift
import com.rfm.edubot.timesheets.model.ShiftFlags
import com.rfm.edubot.timesheets.model.ShiftSegment
import com.rfm.edubot.timesheets.model.SiteVerdict
import com.rfm.edubot.timesheets.model.TimesheetSettings
import com.rfm.edubot.timesheets.model.WorkLogPolicy
import kotlinx.datetime.Instant
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

/** How a shift's time splits between jobs: segments, their minutes, switching, fitting and the strict work log. */
class WorkLogTest {
    private val lisbon = TimeZone.of("Europe/Lisbon")
    private val tenantId = ObjectId()
    private val employeeId = ObjectId()
    private val monday8 = Instant.parse("2026-11-16T08:00:00Z")
    private val required = TimesheetSettings(workLog = WorkLogPolicy.REQUIRED, jobNeeds = JobNeeds(activity = true))
    private val optional = TimesheetSettings(workLog = WorkLogPolicy.OPTIONAL)

    private val cleaning = Job(clientId = ObjectId(), clientName = "Casa Silva", activityId = ObjectId(), activityName = "Limpeza", billable = true)
    private val travel = Job(activityId = ObjectId(), activityName = "Deslocação", billable = false)

    private fun Transition.shift(): Shift = assertIs<Transition.Ok>(this).shift

    private fun clockIn(job: Job?, settings: TimesheetSettings = required, at: Instant = monday8): Shift =
        ShiftRules.start(tenantId, employeeId, Punch(PunchType.IN, at, PunchChannel.WEB, job = job), null, null, settings, lisbon, at)

    private fun Shift.punch(type: PunchType, at: Instant, job: Job? = null, settings: TimesheetSettings = required, site: SiteVerdict? = null): Transition =
        ShiftRules.punch(this, Punch(type, at, PunchChannel.WEB, job = job, site = site), settings, lisbon, at)

    @Test
    fun `a switch cuts the shift, breaks count nowhere, and the parts add up to the shift`() {
        val site = SiteVerdict(ObjectId(), "Rua do Sol", 12, inside = true)
        val open = clockIn(cleaning)
        assertEquals(listOf(cleaning), open.segments.map { it.job })
        val switched = open.punch(PunchType.SWITCH, monday8 + 2.hours + 30.seconds, travel, site = site).shift()
        assertEquals("Rua do Sol", switched.segments.last().siteName, "the site the switch's punch was inside")
        val closed = switched
            .punch(PunchType.BREAK_START, monday8 + 4.hours).shift()
            .punch(PunchType.BREAK_END, monday8 + 5.hours).shift()
            .punch(PunchType.OUT, monday8 + 9.hours).shift()
        val spans = WorkLog.spans(closed, monday8 + 10.hours)
        assertEquals(listOf(120, 360), spans.map { it.minutes }, "the 30 seconds land in the second part, and the hour's break in neither")
        assertEquals(closed.workedMinutes, spans.sumOf { it.minutes })
        assertEquals(listOf(monday8 + 2.hours + 30.seconds, monday8 + 9.hours), spans.map { it.endAt })
        assertEquals(PunchType.SWITCH, closed.punches[1].type)
        assertEquals(travel, closed.punches[1].job, "the switch keeps the job it picked as evidence")
    }

    @Test
    fun `switching refuses what changes nothing, during a break, or without a work log`() {
        val open = clockIn(cleaning)
        assertEquals("same_job", assertIs<Transition.Refused>(open.punch(PunchType.SWITCH, monday8 + 1.hours, cleaning.copy(clientName = "Renamed"))).error)
        val onBreak = open.punch(PunchType.BREAK_START, monday8 + 1.hours).shift()
        assertEquals("on_break", assertIs<Transition.Refused>(onBreak.punch(PunchType.SWITCH, monday8 + 2.hours, travel)).error)
        val plain = clockIn(null, TimesheetSettings.DEFAULT)
        assertTrue(plain.segments.isEmpty(), "a company that doesn't log jobs gets no segments")
        assertEquals("work_log_off", assertIs<Transition.Refused>(plain.punch(PunchType.SWITCH, monday8 + 1.hours, travel, TimesheetSettings.DEFAULT)).error)
    }

    @Test
    fun `a switch within a minute fixes the job just picked instead of cutting`() {
        val fixed = clockIn(travel).punch(PunchType.SWITCH, monday8 + 40.seconds, cleaning).shift()
        assertEquals(listOf(cleaning), fixed.segments.map { it.job })
        assertEquals(monday8, fixed.segments.single().startAt)
    }

    @Test
    fun `the strict work log flags untagged time, refuses approval, and lets the team fix it with a reason`() {
        val closed = clockIn(cleaning).punch(PunchType.SWITCH, monday8 + 2.hours, null).shift().punch(PunchType.OUT, monday8 + 8.hours).shift()
        assertTrue(ShiftFlags.UNALLOCATED in closed.flags)
        assertEquals(360, WorkLog.unallocatedMinutes(closed, required.jobNeeds, monday8 + 9.hours))
        assertEquals("unallocated", assertIs<Transition.Refused>(ShiftRules.approve(closed, "rui", required, monday8 + 9.hours)).error)
        assertIs<Transition.Ok>(ShiftRules.approve(closed, "rui", optional, monday8 + 9.hours), "only the strict rule blocks approval")

        val drafts = listOf(SegmentDraft(monday8, cleaning), SegmentDraft(monday8 + 2.hours, travel))
        assertEquals("reason_required", assertIs<Transition.Refused>(ShiftRules.teamJobs(closed, drafts, "rui", " ", required, lisbon, monday8 + 9.hours)).error)
        val fixed = ShiftRules.teamJobs(closed, drafts, "rui", "Deslocação ao cliente", required, lisbon, monday8 + 9.hours).shift()
        assertTrue(ShiftFlags.UNALLOCATED !in fixed.flags)
        assertEquals(listOf(SegmentSource.CLOCK, SegmentSource.TEAM), fixed.segments.map { it.source }, "the untouched part keeps how it was set")
        val edit = fixed.edits.single()
        assertEquals(closed.times, edit.before, "a change to the jobs keeps the times on both sides")
        assertEquals(listOf(cleaning, null), edit.jobs!!.before.map { it.job })
        assertTrue(ShiftFlags.EDITED in fixed.flags)
        val approved = ShiftRules.approve(fixed, "rui", required, monday8 + 9.hours).shift()
        assertEquals(ReviewStatus.APPROVED, approved.review.status)
        assertEquals("already_approved", assertIs<Transition.Refused>(ShiftRules.employeeJobs(approved, drafts, required, lisbon, monday8 + 9.hours)).error)
    }

    @Test
    fun `typed splits must start inside the shift at different times, go in time order, and neighbours on the same work merge`() {
        val closed = clockIn(cleaning).punch(PunchType.OUT, monday8 + 8.hours).shift()
        val now = monday8 + 9.hours
        assertEquals("segments_required", WorkLog.problem(closed, emptyList(), now))
        assertEquals("segment_outside_shift", WorkLog.problem(closed, listOf(SegmentDraft(monday8, cleaning), SegmentDraft(monday8 + 8.hours, travel)), now))
        assertEquals(
            "segments_same_start",
            WorkLog.problem(closed, listOf(SegmentDraft(monday8, cleaning), SegmentDraft(monday8 + 2.hours, travel), SegmentDraft(monday8 + 2.hours, cleaning)), now),
        )
        val added = ShiftRules.employeeJobs(
            closed,
            listOf(SegmentDraft(monday8, cleaning), SegmentDraft(monday8 + 3.hours, cleaning), SegmentDraft(monday8 + 2.hours, travel)),
            required, lisbon, now,
        ).shift()
        assertEquals(listOf(monday8, monday8 + 2.hours, monday8 + 3.hours), added.segments.map { it.startAt }, "a part added last for 10:00 goes before the one at 11:00")
        assertEquals(listOf(cleaning, travel, cleaning), added.segments.map { it.job })
        val merged = ShiftRules.employeeJobs(
            closed,
            listOf(SegmentDraft(monday8, cleaning), SegmentDraft(monday8 + 2.hours, cleaning), SegmentDraft(monday8 + 3.hours, travel)),
            required, lisbon, now,
        ).shift()
        assertEquals(listOf(monday8, monday8 + 3.hours), merged.segments.map { it.startAt })
        assertEquals(listOf(SegmentSource.CLOCK, SegmentSource.EMPLOYEE), merged.segments.map { it.source })
        val open = clockIn(cleaning)
        assertEquals("segment_outside_shift", WorkLog.problem(open, listOf(SegmentDraft(monday8, cleaning), SegmentDraft(monday8 + 3.hours, travel)), monday8 + 2.hours), "not after now")
    }

    @Test
    fun `correcting a shift's times fits its segments to them`() {
        val segments = listOf(ShiftSegment(monday8, cleaning), ShiftSegment(monday8 + 2.hours, travel), ShiftSegment(monday8 + 6.hours, cleaning))
        assertEquals(listOf(monday8 + 3.hours, monday8 + 6.hours), WorkLog.fit(segments, monday8 + 3.hours, monday8 + 8.hours).map { it.startAt }, "a later start trims")
        assertEquals(listOf(travel, cleaning), WorkLog.fit(segments, monday8 + 3.hours, monday8 + 8.hours).map { it.job })
        assertEquals(listOf(monday8 - 1.hours, monday8 + 2.hours), WorkLog.fit(segments, monday8 - 1.hours, monday8 + 5.hours).map { it.startAt }, "an earlier start stretches, an earlier end drops")
        assertTrue(WorkLog.fit(emptyList(), monday8, null).isEmpty())
    }

    @Test
    fun `a complete job has what the company asks for`() {
        assertEquals("job", WorkLog.missing(null, JobNeeds()))
        assertEquals("job", WorkLog.missing(Job(note = "só nota"), JobNeeds()), "a note alone can't be counted")
        assertNull(WorkLog.missing(Job(note = "só nota"), JobNeeds(note = true)))
        assertNull(WorkLog.missing(Job(activityId = ObjectId()), JobNeeds()))
        assertEquals("client", WorkLog.missing(Job(activityId = ObjectId()), JobNeeds(client = true, activity = true)))
        assertEquals("note", WorkLog.missing(cleaning, JobNeeds(note = true)))
        assertEquals("project", WorkLog.missing(cleaning, JobNeeds(project = true)))
    }
}
