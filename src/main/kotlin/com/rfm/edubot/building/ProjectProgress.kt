package com.rfm.edubot.building

import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.ProjectPhase
import com.rfm.edubot.building.model.ProjectStatus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.max
import kotlin.math.roundToInt

enum class PhaseState { NOT_STARTED, IN_PROGRESS, DONE, LATE }

enum class ProjectHealth { PLANNING, ON_TRACK, AT_RISK, DELAYED, ON_HOLD, DONE, CANCELLED }

/**
 * How far along a project is, and against what. A phase is planned to go from 0% to 100% in a straight line
 * between its planned start and end; the plan is compared as of the end of yesterday, since progress is
 * reported at the end of a working day (the diary), so a phase starting today isn't behind yet.
 */
object ProjectProgress {
    /** Points a phase may trail its plan by before it counts as late. */
    const val LATE_GAP = 20
    private const val DELAY_MIN_DAYS = 7
    private const val DELAY_SHARE_PCT = 10
    private const val RISK_MIN_DAYS = 2
    private const val RISK_SHARE_PCT = 3
    private const val EPS = 1e-9

    fun asOf(today: LocalDate): LocalDate = today.minus(1, DateTimeUnit.DAY)

    fun plannedDays(start: LocalDate?, end: LocalDate?): Int? =
        if (start != null && end != null && end >= start) start.daysUntil(end) + 1 else null

    /** The weight typed on the phase, else its planned days, else one. */
    fun weight(phase: ProjectPhase): Double = (phase.weight ?: plannedDays(phase.plannedStart, phase.plannedEnd) ?: 1).toDouble()

    /** 0–100: the phases' progress weighted by [weight]; a project without phases keeps the one typed on it. */
    fun actualPct(project: Project): Double =
        if (project.phases.isEmpty()) project.manualProgressPct.coerceIn(0, 100).toDouble() else weighted(project.phases)

    /** Where the plan is at the end of [day], from 0% the day before [start] to 100% at the end of [end]. */
    fun linearPct(start: LocalDate, end: LocalDate, day: LocalDate): Double {
        val days = plannedDays(start, end) ?: return 0.0
        return ((start.daysUntil(day) + 1).toDouble() / days).coerceIn(0.0, 1.0) * 100
    }

    fun plannedPct(phase: ProjectPhase, day: LocalDate): Double? {
        if (plannedDays(phase.plannedStart, phase.plannedEnd) == null) return null
        return linearPct(phase.plannedStart!!, phase.plannedEnd!!, day)
    }

    /** The plan's progress at the end of [day]: the phases that have dates, or else the project's own dates. */
    fun plannedPct(project: Project, day: LocalDate): Double? {
        val dated = scheduled(project)
        if (dated.isNotEmpty()) return dated.sumOf { weight(it) * plannedPct(it, day)!! } / dated.sumOf { weight(it) }
        val start = project.plannedStart ?: return null
        val end = project.plannedEnd ?: return null
        return if (end >= start) linearPct(start, end, day) else null
    }

    /** First and last planned day: of the phases with dates, else the project's own. */
    fun plannedWindow(project: Project): Pair<LocalDate, LocalDate>? {
        val dated = scheduled(project)
        if (dated.isNotEmpty()) return dated.minOf { it.plannedStart!! } to dated.maxOf { it.plannedEnd!! }
        val start = project.plannedStart ?: return null
        val end = project.plannedEnd ?: return null
        return if (end >= start) start to end else null
    }

    /** The end the project was planned for: its own, else its last phase's. */
    fun plannedEnd(project: Project): LocalDate? = project.plannedEnd ?: plannedWindow(project)?.second

    /** [plannedEnd] moved by the days approved changes added. */
    fun revisedEnd(project: Project, approvedDays: Int): LocalDate? = plannedEnd(project)?.plus(approvedDays, DateTimeUnit.DAY)

    /**
     * Days ahead (positive) or behind (negative) the plan, by earned schedule: the day the plan reaches the
     * progress made so far, less the end of yesterday. Where the plan stays at that progress for a while (before
     * it starts, after it ends, between phases) the day nearest counts, so a project that hasn't started before
     * its start isn't "ahead". Null without planned dates.
     */
    fun scheduleDays(project: Project, today: LocalDate): Int? {
        val (start, end) = plannedWindow(project) ?: return null
        val reference = asOf(today)
        val actual = scheduledActualPct(project)
        val from = minOf(start.minus(1, DateTimeUnit.DAY), reference)
        val span = from.daysUntil(maxOf(end, reference))
        val plan = DoubleArray(span + 1) { plannedPct(project, from.plus(it, DateTimeUnit.DAY)) ?: 0.0 }
        val reached = plan.indexOfFirst { it >= actual - EPS }
        val notPast = plan.indexOfLast { it <= actual + EPS }
        val at = from.daysUntil(reference)
        val earned = when {
            reached < 0 -> span.toDouble()
            notPast < 0 -> 0.0
            reached <= notPast -> at.coerceIn(reached, notPast).toDouble()
            else -> notPast + (actual - plan[notPast]) / (plan[reached] - plan[notPast])
        }
        return (earned - at).roundToInt()
    }

    fun phaseState(phase: ProjectPhase, today: LocalDate): PhaseState {
        val progress = phase.progressPct.coerceIn(0, 100)
        if (progress >= 100) return PhaseState.DONE
        val pastEnd = phase.plannedEnd?.let { today > it } == true
        val planned = plannedPct(phase, asOf(today))
        if (pastEnd || (planned != null && planned - progress >= LATE_GAP)) return PhaseState.LATE
        return if (progress > 0) PhaseState.IN_PROGRESS else PhaseState.NOT_STARTED
    }

    /**
     * An active project is delayed once its revised end has passed, or when it is more than the larger of 7 days
     * and 10% of its planned duration behind; at risk when more than the larger of 2 days and 3% behind, or when a
     * phase is late.
     */
    fun health(project: Project, today: LocalDate, approvedDays: Int = 0): ProjectHealth = when (project.status) {
        ProjectStatus.PLANNING -> ProjectHealth.PLANNING
        ProjectStatus.ON_HOLD -> ProjectHealth.ON_HOLD
        ProjectStatus.COMPLETED -> ProjectHealth.DONE
        ProjectStatus.CANCELLED -> ProjectHealth.CANCELLED
        ProjectStatus.ACTIVE -> activeHealth(project, today, approvedDays)
    }

    private fun activeHealth(project: Project, today: LocalDate, approvedDays: Int): ProjectHealth {
        if (actualPct(project) >= 100 - EPS) return ProjectHealth.ON_TRACK
        val end = revisedEnd(project, approvedDays)
        if (end != null && today > end) return ProjectHealth.DELAYED
        val behind = -(scheduleDays(project, today) ?: 0)
        val duration = plannedWindow(project)?.let { plannedDays(it.first, it.second) } ?: 0
        if (behind > max(DELAY_MIN_DAYS, duration * DELAY_SHARE_PCT / 100)) return ProjectHealth.DELAYED
        if (behind > max(RISK_MIN_DAYS, duration * RISK_SHARE_PCT / 100)) return ProjectHealth.AT_RISK
        if (project.phases.any { phaseState(it, today) == PhaseState.LATE }) return ProjectHealth.AT_RISK
        return ProjectHealth.ON_TRACK
    }

    private fun scheduled(project: Project): List<ProjectPhase> =
        project.phases.filter { plannedDays(it.plannedStart, it.plannedEnd) != null }

    /** The progress of the work the plan has dates for, so it compares with [plannedPct]. */
    private fun scheduledActualPct(project: Project): Double {
        val dated = scheduled(project)
        return if (dated.isEmpty()) actualPct(project) else weighted(dated)
    }

    private fun weighted(phases: List<ProjectPhase>): Double {
        val total = phases.sumOf { weight(it) }
        if (total <= 0) return 0.0
        return phases.sumOf { weight(it) * it.progressPct.coerceIn(0, 100) } / total
    }
}
