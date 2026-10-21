package com.rfm.edubot.building

import com.rfm.edubot.building.model.ProjectStatus
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.shared.jobs.PeriodicJob
import com.rfm.edubot.shared.jobs.SchedulerLease
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import com.rfm.edubot.tenant.model.TenantTimeZones
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours

/**
 * A project's health follows the calendar as much as its writes: it turns delayed the day its end passes
 * with nobody touching it. Every hour this looks at each running project of each company with Building,
 * stores the health it sees, and appends `project.at_risk` or `project.delayed` when it turned so since the
 * last look. The first look only stores it, so turning the watch on doesn't raise an event per project.
 */
class ProjectHealthWatch(private val mongo: MongoModule, private val tenants: TenantRepository, private val clock: () -> Instant = SystemClock::now) {
    suspend fun check() {
        tenants.findAll()
            .filter { it.status == TenantStatus.ACTIVE && DashboardModules.PROJECTS in DashboardModules.effectiveFor(it) }
            .forEach { check(it) }
    }

    /** How many projects of [tenant] it announced as fallen behind. */
    suspend fun check(tenant: Tenant): Int {
        val records = BuildingRecords(mongo, tenant.id)
        val today = clock().toLocalDateTime(TimeZone.of(TenantTimeZones.normalize(tenant.timezone))).date
        val running = records.projects.list(listOf(ProjectStatus.ACTIVE))
        val days = records.changes.forProjects(running.map { it.id }).groupBy { it.projectId }.mapValues { approvedEffect(it.value).days }
        var moved = 0
        for (project in running) {
            val health = ProjectProgress.health(project, today, days[project.id] ?: 0)
            if (project.watchedHealth == health.name) continue
            records.projects.setWatchedHealth(project.id, health.name)
            val before = project.watchedHealth?.let { name -> ProjectHealth.entries.firstOrNull { it.name == name } } ?: continue
            if (records.events.projectFellBehind(project, before, health)) moved++
        }
        return moved
    }

    fun job(lease: SchedulerLease?): PeriodicJob = PeriodicJob("building-health-watch", INTERVAL, lease) { check() }

    private companion object {
        val INTERVAL = 1.hours
    }
}
