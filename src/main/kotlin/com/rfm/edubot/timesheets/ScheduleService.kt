package com.rfm.edubot.timesheets

import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.dashboard.toObjectIdOrNull
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.timesheets.model.Excuse
import com.rfm.edubot.timesheets.model.Job
import com.rfm.edubot.timesheets.model.PlannedShift
import com.rfm.edubot.timesheets.model.TimesheetSettings
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

/**
 * A planned shift as the team sends it. New ones take [employeeId] and [days] (one shift per day); a change takes
 * any of the rest, a field left out keeping its value and an empty site, note or job clearing it.
 */
@Serializable
internal data class PlanRequest(
    val employeeId: String? = null,
    val days: List<String> = emptyList(),
    val day: String? = null,
    /** `HH:mm` on the company's clock; an end before the start is the next day. */
    val start: String? = null,
    val end: String? = null,
    val siteId: String? = null,
    val job: JobRequest? = null,
    val note: String? = null,
)

/** [from] and [to] are the Mondays of the weeks. */
@Serializable internal data class CopyWeekRequest(val from: String = "", val to: String = "")

@Serializable internal data class ExcuseRequest(val reason: String = "")

/** The team's planner: planned shifts, how each went, and the changes people make to them. */
internal class ScheduleService(
    private val mongo: MongoModule,
    private val projects: JobProjects = JobProjects.None,
    private val clock: () -> Instant = SystemClock::now,
) {
    private val settings = TimesheetSettingsRepository(mongo)

    suspend fun rules(tenant: Tenant): TimesheetSettings = settings.get(tenant.id).forCompany(tenant)

    suspend fun view(tenant: Tenant, from: LocalDate, to: LocalDate, employeeId: ObjectId?, canEdit: Boolean): ScheduleDto {
        val plans = PlannedShiftRepository(mongo, tenant.id).list(from, to, employeeId)
        val rules = rules(tenant)
        return ScheduleDto(from.toString(), to.toString(), dtos(tenant, plans, rules, clock()), rules.dto(canEdit))
    }

    /** [plans] with how each went by [now] and their employees' names. */
    suspend fun dtos(tenant: Tenant, plans: List<PlannedShift>, rules: TimesheetSettings, now: Instant): List<PlannedShiftDto> {
        if (plans.isEmpty()) return emptyList()
        val shifts = ShiftRepository(mongo, tenant.id)
        val people = plans.map { it.employeeId }.toSet()
        val listed = shifts.list(plans.minOf { it.day }.minus(1, DateTimeUnit.DAY), plans.maxOf { it.day }).filter { it.employeeId in people }
        val linked = shifts.linkedTo(plans.map { it.id }).filter { l -> listed.none { it.id == l.id } }
        val employees = ShiftView.employeesById(mongo, tenant.id, people)
        val view = PlanView(tenant.zone(), rules, now, listed + linked, employees)
        return plans.map(view::dto)
    }

    suspend fun find(tenant: Tenant, id: ObjectId): PlannedShift? = PlannedShiftRepository(mongo, tenant.id).findById(id)

    /** One planned shift per day in [PlanRequest.days], all or none: none may overlap the employee's others. */
    suspend fun create(tenant: Tenant, request: PlanRequest, by: String): Outcome<List<PlannedShift>> {
        val employeeId = request.employeeId?.toObjectIdOrNull() ?: return refused("employee_not_found")
        EmployeeRepository(mongo, tenant.id).findById(employeeId)?.takeIf { it.archivedAt == null } ?: return refused("employee_not_found")
        val days = request.days.map { day(it) ?: return refused("invalid_day") }.distinct().sorted()
        if (days.isEmpty()) return refused("invalid_day")
        if (days.size > MAX_DAYS) return refused("too_many_days")
        val start = ShiftMath.parseTime(request.start) ?: return refused("invalid_time")
        val end = ShiftMath.parseTime(request.end) ?: return refused("invalid_time")
        if (start == end) return refused("end_before_start")
        val details = when (val d = details(tenant, request, base = null)) {
            is Outcome.Refused -> return d
            is Outcome.Ok -> d.value
        }
        val zone = tenant.zone()
        val now = clock()
        val plans = PlannedShiftRepository(mongo, tenant.id)
        val drafts = days.map { day ->
            val times = ShiftMath.wallTimes(day, start, end, emptyList(), zone)
            PlannedShift(
                tenantId = tenant.id,
                employeeId = employeeId,
                day = day,
                startAt = times.startAt,
                endAt = times.endAt!!,
                siteId = details.siteId,
                siteName = details.siteName,
                job = details.job,
                note = details.note,
                createdBy = by,
                createdAt = now,
                updatedAt = now,
            )
        }
        drafts.forEachIndexed { i, plan ->
            val clash = plans.overlapping(employeeId, plan.startAt, plan.endAt).isNotEmpty() ||
                drafts.take(i).any { it.startAt < plan.endAt && it.endAt > plan.startAt }
            if (clash) return Outcome.Refused("planned_overlap", 409)
        }
        drafts.forEach { plans.insert(it) }
        return Outcome.Ok(drafts)
    }

    /**
     * Changes a planned shift. Once someone clocked in for it, it stays on its day; new times move the lateness and
     * early leave of the shifts linked to it.
     */
    suspend fun update(tenant: Tenant, id: ObjectId, request: PlanRequest): Outcome<PlannedShift> {
        val plans = PlannedShiftRepository(mongo, tenant.id)
        val plan = plans.findById(id) ?: return Outcome.Refused("not_found", 404)
        val zone = tenant.zone()
        val shifts = ShiftRepository(mongo, tenant.id)
        val attended = shifts.linkedTo(listOf(plan.id)).isNotEmpty()
        val day = request.day?.let { day(it) ?: return refused("invalid_day") } ?: plan.day
        if (day != plan.day && attended) return Outcome.Refused("planned_attended", 409)
        val start = request.start?.let { ShiftMath.parseTime(it) ?: return refused("invalid_time") } ?: plan.startAt.local(zone)
        val end = request.end?.let { ShiftMath.parseTime(it) ?: return refused("invalid_time") } ?: plan.endAt.local(zone)
        if (start == end) return refused("end_before_start")
        val details = when (val d = details(tenant, request, base = plan)) {
            is Outcome.Refused -> return d
            is Outcome.Ok -> d.value
        }
        val now = clock()
        val times = ShiftMath.wallTimes(day, start, end, emptyList(), zone)
        val next = plan.copy(
            day = day,
            startAt = times.startAt,
            endAt = times.endAt!!,
            siteId = details.siteId,
            siteName = details.siteName,
            job = details.job,
            note = details.note,
            updatedAt = now,
        )
        if (plans.overlapping(plan.employeeId, next.startAt, next.endAt, except = plan.id).isNotEmpty()) return Outcome.Refused("planned_overlap", 409)
        val saved = plans.replace(next) ?: return Outcome.Refused("not_found", 404)
        if (attended && (saved.startAt != plan.startAt || saved.endAt != plan.endAt)) PlanLinks.retime(shifts, saved, rules(tenant), zone, now)
        return Outcome.Ok(saved)
    }

    /** Removes a planned shift nobody clocked in for; one that was attended stays, as the record of what was planned. */
    suspend fun delete(tenant: Tenant, id: ObjectId): Outcome<Unit> {
        val plans = PlannedShiftRepository(mongo, tenant.id)
        plans.findById(id) ?: return Outcome.Refused("not_found", 404)
        if (ShiftRepository(mongo, tenant.id).linkedTo(listOf(id)).isNotEmpty()) return Outcome.Refused("planned_attended", 409)
        plans.delete(id)
        return Outcome.Ok(Unit)
    }

    /**
     * Copies the week of Monday [CopyWeekRequest.from] onto the week of Monday [CopyWeekRequest.to], at the same
     * wall-clock times. A planned shift that would overlap one already there, or whose employee left, is skipped.
     */
    suspend fun copyWeek(tenant: Tenant, request: CopyWeekRequest, by: String): Outcome<CopiedDto> {
        val from = day(request.from)?.takeIf { it.dayOfWeek == DayOfWeek.MONDAY } ?: return refused("invalid_week")
        val to = day(request.to)?.takeIf { it.dayOfWeek == DayOfWeek.MONDAY && it != from } ?: return refused("invalid_week")
        val zone = tenant.zone()
        val now = clock()
        val plans = PlannedShiftRepository(mongo, tenant.id)
        val source = plans.list(from, from.plus(6, DateTimeUnit.DAY))
        val employees = EmployeeRepository(mongo, tenant.id).findByIds(source.map { it.employeeId })
        val offset = from.daysUntil(to)
        var created = 0
        var skipped = 0
        for (plan in source) {
            if (employees[plan.employeeId]?.archivedAt != null || plan.employeeId !in employees) {
                skipped++
                continue
            }
            val day = plan.day.plus(offset, DateTimeUnit.DAY)
            val times = ShiftMath.wallTimes(day, plan.startAt.local(zone), plan.endAt.local(zone), emptyList(), zone)
            val copy = PlannedShift(
                tenantId = tenant.id,
                employeeId = plan.employeeId,
                day = day,
                startAt = times.startAt,
                endAt = times.endAt!!,
                siteId = plan.siteId,
                siteName = plan.siteName,
                job = plan.job,
                note = plan.note,
                createdBy = by,
                createdAt = now,
                updatedAt = now,
            )
            if (plans.overlapping(copy.employeeId, copy.startAt, copy.endAt).isNotEmpty()) {
                skipped++
                continue
            }
            plans.insert(copy)
            created++
        }
        return Outcome.Ok(CopiedDto(created, skipped))
    }

    /** The team says why nobody needs to work a planned shift (or did not): it stops counting as an absence. */
    suspend fun excuse(tenant: Tenant, id: ObjectId, reason: String, by: String): Outcome<PlannedShift> {
        if (reason.isBlank()) return refused("reason_required")
        if (reason.trim().length > ShiftMath.MAX_REASON) return refused("reason_too_long")
        val plans = PlannedShiftRepository(mongo, tenant.id)
        val plan = plans.findById(id) ?: return Outcome.Refused("not_found", 404)
        val saved = plans.replace(plan.copy(excuse = Excuse(reason.trim(), by, clock()), updatedAt = clock())) ?: return Outcome.Refused("not_found", 404)
        return Outcome.Ok(saved)
    }

    suspend fun unexcuse(tenant: Tenant, id: ObjectId): Outcome<PlannedShift> {
        val plans = PlannedShiftRepository(mongo, tenant.id)
        val plan = plans.findById(id) ?: return Outcome.Refused("not_found", 404)
        val saved = plans.replace(plan.copy(excuse = null, updatedAt = clock())) ?: return Outcome.Refused("not_found", 404)
        return Outcome.Ok(saved)
    }

    private data class Details(val siteId: ObjectId?, val siteName: String?, val job: Job?, val note: String?)

    /** The site, job and note of a request over [base]'s. */
    private suspend fun details(tenant: Tenant, request: PlanRequest, base: PlannedShift?): Outcome<Details> {
        val site: Pair<ObjectId, String>? = when (val raw = request.siteId?.trim()) {
            null -> base?.siteId?.let { it to base.siteName.orEmpty() }
            "" -> null
            else -> raw.toObjectIdOrNull()?.let { WorkSiteRepository(mongo, tenant.id).findById(it) }?.let { it.id to it.name }
                ?: return refused("site_not_found")
        }
        val job = if (request.job == null) {
            base?.job
        } else {
            when (val resolved = JobResolver(mongo, tenant, projects).resolve(request.job)) {
                is Outcome.Refused -> return resolved
                is Outcome.Ok -> resolved.value
            }
        }
        val note = if (request.note == null) base?.note else request.note.trim().takeIf { it.isNotEmpty() }
        if ((note?.length ?: 0) > ShiftMath.MAX_NOTE) return refused("note_too_long")
        return Outcome.Ok(Details(site?.first, site?.second, job, note))
    }

    private fun day(value: String?): LocalDate? = value?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun Instant.local(zone: TimeZone): LocalTime = toLocalDateTime(zone).time.let { LocalTime(it.hour, it.minute) }

    private fun refused(error: String): Outcome.Refused = Outcome.Refused(error, 400)

    companion object {
        /** One request plans up to two weeks of days. */
        const val MAX_DAYS = 14
    }
}
