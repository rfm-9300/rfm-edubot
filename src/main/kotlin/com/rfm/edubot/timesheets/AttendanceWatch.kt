package com.rfm.edubot.timesheets

import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.notifications.NotificationAudience
import com.rfm.edubot.notifications.NotificationKinds
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.shared.jobs.PeriodicJob
import com.rfm.edubot.shared.jobs.SchedulerLease
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.TenantStatus
import com.rfm.edubot.timesheets.model.TimesheetSettings
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Tells a company's team when someone hasn't clocked in for a planned shift: every few minutes it looks at the
 * planned shifts that started up to a day ago with nobody matched, no alert sent and no excuse, in the companies
 * that check schedules, and once a shift is `noShowMinutes` past its start with nobody clocked in, it rings the bell
 * once. A plan found attended (a late clock-in, or a shift that ran over it) is marked so it isn't looked at again.
 */
class AttendanceWatch(
    private val mongo: MongoModule,
    private val tenants: TenantRepository,
    private val notifications: NotificationRepository,
    private val clock: () -> Instant = SystemClock::now,
) {
    private val log = LoggerFactory.getLogger(AttendanceWatch::class.java)

    /** How many no-shows it told teams about. */
    suspend fun check(): Int {
        val now = clock()
        val due = PlannedShiftRepository.unattended(mongo, from = now - LOOKBACK, to = now - MIN_NO_SHOW)
        var told = 0
        for ((tenantId, plans) in due.groupBy { it.tenantId }) {
            val tenant = tenants.findById(tenantId) ?: continue
            if (tenant.status != TenantStatus.ACTIVE || DashboardModules.TIMESHEETS !in DashboardModules.effectiveFor(tenant)) continue
            val rules = TimesheetSettingsRepository(mongo).get(tenantId)
            if (!rules.checksSchedule) continue
            val zone = tenant.zone()
            val repository = PlannedShiftRepository(mongo, tenantId)
            val shifts = ShiftRepository(mongo, tenantId)
            val employees = EmployeeRepository(mongo, tenantId).findByIds(plans.map { it.employeeId })
            val around = shifts.list(plans.minOf { it.day }.minus(1, DateTimeUnit.DAY), plans.maxOf { it.day }.plus(1, DateTimeUnit.DAY))
            val linked = shifts.linkedTo(plans.map { it.id })
            val byEmployee = (around + linked).distinctBy { it.id }.groupBy { it.employeeId }
            for (plan in plans) {
                if (now < plan.startAt + rules.noShowMinutes.minutes) continue
                val employee = employees[plan.employeeId]?.takeIf { it.archivedAt == null } ?: continue
                val status = Attendance.status(plan, byEmployee[plan.employeeId].orEmpty(), rules, now)
                val attended = status.shifts.firstOrNull()
                if (attended != null) {
                    repository.setMatched(plan.id, attended.id)
                    continue
                }
                if (status.state != Attendance.State.MISSING && status.state != Attendance.State.ABSENT) continue
                if (!repository.claimAlert(plan.id, now)) continue
                runCatching {
                    notifications.notify(
                        tenantId = tenantId,
                        kind = NotificationKinds.TIME_NO_SHOW,
                        audience = NotificationAudience.ALL,
                        params = mapOf("employee" to employee.name, "day" to plan.day.toString(), "start" to ShiftMath.localTime(plan.startAt, zone)),
                        link = DashboardModules.TIMESHEETS,
                        subject = SubjectRef.of(SubjectTypes.EMPLOYEE, employee.id),
                        ref = "planned:${plan.id.toHexString()}",
                    )
                    told++
                }.onFailure { log.warn("Could not tell the team about planned shift {}: {}", plan.id, it.message) }
            }
        }
        if (told > 0) log.info("Time clock: told teams about {} planned shifts nobody clocked in for", told)
        return told
    }

    fun job(lease: SchedulerLease?): PeriodicJob = PeriodicJob("timesheet-attendance-watch", INTERVAL, lease) { check() }

    companion object {
        val INTERVAL: Duration = 5.minutes

        /** Older planned shifts are left alone: after a long outage, a day-old no-show is no longer news. */
        val LOOKBACK: Duration = 24.hours

        /** The shortest no-show time a company can set: nothing younger can be one yet. */
        private val MIN_NO_SHOW: Duration = TimesheetSettings.NO_SHOW_MINUTES.first.minutes
    }
}
