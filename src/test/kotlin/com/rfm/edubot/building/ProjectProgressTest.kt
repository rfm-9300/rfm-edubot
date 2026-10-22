package com.rfm.edubot.building

import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.ProjectPhase
import com.rfm.edubot.building.model.ProjectStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectProgressTest {
    private val epoch = Instant.fromEpochMilliseconds(0)

    private fun project(vararg phases: ProjectPhase, status: ProjectStatus = ProjectStatus.ACTIVE, start: String? = null, end: String? = null, manual: Int = 0) = Project(
        tenantId = ObjectId(),
        number = "OB-001",
        name = "Moradia",
        status = status,
        plannedStart = start?.let(LocalDate::parse),
        plannedEnd = end?.let(LocalDate::parse),
        manualProgressPct = manual,
        phases = phases.toList(),
        createdAt = epoch,
        updatedAt = epoch,
    )

    private fun phase(name: String, start: String?, end: String?, progress: Int = 0, weight: Int? = null) =
        ProjectPhase(id = name, name = name, plannedStart = start?.let(LocalDate::parse), plannedEnd = end?.let(LocalDate::parse), progressPct = progress, weight = weight)

    private fun day(value: String) = LocalDate.parse(value)

    @Test
    fun `progress weighs each phase by its planned days unless it has a weight`() {
        // 10 days at 50% and 30 days at 0%: an eighth of the work is done.
        val p = project(phase("Fundações", "2026-10-01", "2026-10-10", 50), phase("Estrutura", "2026-10-11", "2026-11-09", 0))
        assertEquals(12.5, ProjectProgress.actualPct(p))
        val weighted = project(phase("A", "2026-10-01", "2026-10-10", 50, weight = 1), phase("B", "2026-10-11", "2026-11-09", 0, weight = 1))
        assertEquals(25.0, ProjectProgress.actualPct(weighted))
        assertEquals(40.0, ProjectProgress.actualPct(project(manual = 40)), "a project without phases keeps its own")
    }

    @Test
    fun `the plan goes from nothing the day before a phase starts to all of it at the end of its last day`() {
        val p = phase("A", "2026-10-01", "2026-10-10")
        assertEquals(0.0, ProjectProgress.plannedPct(p, day("2026-09-30")))
        assertEquals(10.0, ProjectProgress.plannedPct(p, day("2026-10-01")))
        assertEquals(50.0, ProjectProgress.plannedPct(p, day("2026-10-05")))
        assertEquals(100.0, ProjectProgress.plannedPct(p, day("2026-10-10")))
        assertEquals(100.0, ProjectProgress.plannedPct(p, day("2026-12-01")))
        assertNull(ProjectProgress.plannedPct(phase("B", null, "2026-10-10"), day("2026-10-05")))
    }

    @Test
    fun `days behind are when the plan reached today's progress, against the end of yesterday`() {
        // Ten days, planned 10% a day. On Oct 7 the plan expects 60% (end of Oct 6).
        val behind = project(phase("A", "2026-10-01", "2026-10-10", 30))
        assertEquals(-3, ProjectProgress.scheduleDays(behind, day("2026-10-07")), "30% was the plan for the end of Oct 3")
        val ahead = project(phase("A", "2026-10-01", "2026-10-10", 80))
        assertEquals(2, ProjectProgress.scheduleDays(ahead, day("2026-10-07")))
        val onPlan = project(phase("A", "2026-10-01", "2026-10-10", 60))
        assertEquals(0, ProjectProgress.scheduleDays(onPlan, day("2026-10-07")))
    }

    @Test
    fun `a project isn't ahead before it starts, nor behind on its first day`() {
        val waiting = project(phase("A", "2026-10-10", "2026-10-19", 0))
        assertEquals(0, ProjectProgress.scheduleDays(waiting, day("2026-10-01")))
        assertEquals(0, ProjectProgress.scheduleDays(waiting, day("2026-10-10")))
        assertEquals(PhaseState.NOT_STARTED, ProjectProgress.phaseState(waiting.phases.single(), day("2026-10-10")))
        assertEquals(-1, ProjectProgress.scheduleDays(waiting, day("2026-10-11")))
    }

    @Test
    fun `past its end and unfinished, a project is behind by the days since the plan reached its progress`() {
        val late = project(phase("A", "2026-10-01", "2026-10-10", 50))
        assertEquals(-15, ProjectProgress.scheduleDays(late, day("2026-10-21")), "50% was planned for the end of Oct 5")
    }

    @Test
    fun `between phases the plan stays flat, and the nearest day counts`() {
        val p = project(phase("A", "2026-10-01", "2026-10-10", 100), phase("B", "2026-10-21", "2026-10-30", 0))
        assertEquals(0, ProjectProgress.scheduleDays(p, day("2026-10-15")))
    }

    @Test
    fun `without dates there is no plan to compare with`() {
        assertNull(ProjectProgress.scheduleDays(project(phase("A", null, null, 20)), day("2026-10-15")))
        assertEquals(0, ProjectProgress.scheduleDays(project(start = "2026-10-01", end = "2026-10-10", manual = 60), day("2026-10-07")), "the project's own dates")
    }

    @Test
    fun `a phase is late past its end or twenty points behind its plan`() {
        val today = day("2026-10-07")
        assertEquals(PhaseState.LATE, ProjectProgress.phaseState(phase("A", "2026-09-20", "2026-10-06", 90), today))
        assertEquals(PhaseState.LATE, ProjectProgress.phaseState(phase("B", "2026-10-01", "2026-10-10", 40), today), "60% planned, 40% done")
        assertEquals(PhaseState.IN_PROGRESS, ProjectProgress.phaseState(phase("C", "2026-10-01", "2026-10-10", 45), today))
        assertEquals(PhaseState.DONE, ProjectProgress.phaseState(phase("D", "2026-09-01", "2026-09-05", 100), today))
        assertEquals(PhaseState.NOT_STARTED, ProjectProgress.phaseState(phase("E", null, null, 0), today))
    }

    @Test
    fun `health follows status, the revised end, and how far behind against the duration`() {
        val today = day("2026-10-21")
        assertEquals(ProjectHealth.PLANNING, ProjectProgress.health(project(status = ProjectStatus.PLANNING), today))
        assertEquals(ProjectHealth.DONE, ProjectProgress.health(project(status = ProjectStatus.COMPLETED), today))
        assertEquals(ProjectHealth.ON_HOLD, ProjectProgress.health(project(status = ProjectStatus.ON_HOLD), today))

        val pastEnd = project(phase("A", "2026-10-01", "2026-10-18", 95))
        assertEquals(ProjectHealth.DELAYED, ProjectProgress.health(pastEnd, today))
        assertEquals(ProjectHealth.AT_RISK, ProjectProgress.health(pastEnd, today, approvedDays = 15), "an approved extension moves the end; 3 days behind the phases")

        // 100 days from Sep 1: 10% behind is 10 days, 3% is 3 days.
        val long = { progress: Int -> project(phase("A", "2026-09-01", "2026-12-09", progress)) }
        assertEquals(ProjectHealth.ON_TRACK, ProjectProgress.health(long(50), today), "on Oct 21 the plan expects 50%")
        assertEquals(ProjectHealth.AT_RISK, ProjectProgress.health(long(46), today), "4 days behind")
        assertEquals(ProjectHealth.DELAYED, ProjectProgress.health(long(39), today), "11 days behind")
        assertEquals(ProjectHealth.ON_TRACK, ProjectProgress.health(long(100), today))
    }

    @Test
    fun `the revised end moves the planned end by the approved days`() {
        val p = project(phase("A", "2026-10-01", "2026-10-10"), phase("B", "2026-10-11", "2026-10-31"))
        assertEquals(day("2026-10-31"), ProjectProgress.plannedEnd(p))
        assertEquals(day("2026-11-07"), ProjectProgress.revisedEnd(p, 7))
        assertEquals(day("2026-12-24"), ProjectProgress.plannedEnd(p.copy(plannedEnd = day("2026-12-24"))), "the project's own end wins")
    }
}
