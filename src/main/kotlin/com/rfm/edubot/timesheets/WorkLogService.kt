package com.rfm.edubot.timesheets

import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.timesheets.model.Job
import com.rfm.edubot.timesheets.model.ReviewStatus
import com.rfm.edubot.timesheets.model.Shift
import com.rfm.edubot.timesheets.model.TimesheetSettings
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.bson.types.ObjectId

/** One segment of one shift, as a line of the work log. */
internal data class WorkRow(val shift: Shift, val span: SegmentSpan) {
    val job: Job? get() = span.segment.job
}

/** Which rows a report keeps: an employee's, or time on one client, project or activity. */
internal data class WorkFilter(
    val employeeId: ObjectId? = null,
    val clientId: ObjectId? = null,
    val projectId: ObjectId? = null,
    val activityId: ObjectId? = null,
) {
    fun keeps(job: Job?): Boolean =
        (clientId == null || job?.clientId == clientId) &&
            (projectId == null || job?.projectId == projectId) &&
            (activityId == null || job?.activityId == activityId)
}

/** Jobs on shifts: what a job can name, typed splits turned into segments, and the hours by job. */
internal class WorkLogService(private val mongo: MongoModule, private val projects: JobProjects) {
    /** What a job can name in [tenant]: its clients, open projects and activities in use, each only with its module. */
    suspend fun options(tenant: Tenant): JobOptionsDto {
        val modules = DashboardModules.effectiveFor(tenant)
        val clients = if (DashboardModules.CLIENTS in modules) {
            ClientRepository(mongo, tenant.id).search("", limit = MAX_CLIENTS).sortedBy { it.name.lowercase() }.map { JobClientDto(it.id.toHexString(), it.number, it.name) }
        } else {
            emptyList()
        }
        val open = if (DashboardModules.PROJECTS in modules) {
            projects.open(tenant.id).map { JobProjectDto(it.id.toHexString(), it.number, it.name, it.clientId?.toHexString()) }
        } else {
            emptyList()
        }
        return JobOptionsDto(clients, open, ActivityRepository(mongo, tenant.id).list(activeOnly = true).map { it.dto() })
    }

    /**
     * Segments typed for [shift] as drafts: starts on the company's clock (one typed as an existing boundary keeps
     * that boundary's exact instant; the first segment always starts with the shift) and jobs checked.
     */
    suspend fun drafts(tenant: Tenant, shift: Shift, requests: List<SegmentRequest>): Outcome<List<SegmentDraft>> {
        if (requests.isEmpty()) return Outcome.Refused("segments_required", 400)
        if (requests.size > WorkLog.MAX_SEGMENTS) return Outcome.Refused("too_many_segments", 400)
        val zone = tenant.zone()
        val resolver = JobResolver(mongo, tenant, projects)
        val boundaries = shift.segments.filter { it.startAt > shift.startAt }.associateBy { ShiftMath.localTime(it.startAt, zone) }
        val drafts = requests.mapIndexed { i, request ->
            val start = if (i == 0) {
                shift.startAt
            } else {
                val time = ShiftMath.parseTime(request.start) ?: return Outcome.Refused("invalid_time", 400)
                boundaries[request.start.trim()]?.startAt ?: ShiftMath.endAfter(shift.startAt, time, zone)
            }
            val job = when (val resolved = resolver.resolve(request.job)) {
                is Outcome.Refused -> return resolved
                is Outcome.Ok -> resolved.value
            }
            SegmentDraft(start, job)
        }
        return Outcome.Ok(drafts)
    }

    /** Every segment of the shifts that started from [from] to [to] that [filter] keeps, and their employees. */
    suspend fun rows(tenant: Tenant, from: LocalDate, to: LocalDate, filter: WorkFilter, now: Instant): Pair<List<WorkRow>, Map<ObjectId, Employee>> {
        val shifts = ShiftRepository(mongo, tenant.id).list(from, to, filter.employeeId)
        val rows = shifts.flatMap { shift -> WorkLog.spans(shift, now).map { WorkRow(shift, it) } }.filter { filter.keeps(it.job) }
        return rows to ShiftView.employees(mongo, tenant.id, shifts)
    }

    /** The hours by client, project, activity and employee over a period, and its segments (the latest first). */
    fun report(
        from: LocalDate,
        to: LocalDate,
        rows: List<WorkRow>,
        employees: Map<ObjectId, Employee>,
        rules: TimesheetSettings,
        zone: TimeZone,
    ): JobReportDto {
        fun groups(key: (WorkRow) -> ObjectId?, name: (WorkRow) -> String?): List<JobGroupDto> =
            rows.groupBy(key).map { (id, own) ->
                JobGroupDto(
                    id = id?.toHexString(),
                    name = if (id == null) "" else own.maxBy { it.span.startAt }.let(name).orEmpty(),
                    minutes = own.sumOf { it.span.minutes },
                    billableMinutes = own.filter { it.job?.billable == true }.sumOf { it.span.minutes },
                    employees = own.map { it.shift.employeeId }.distinct().size,
                    entries = own.size,
                )
            }.filter { it.minutes > 0 || it.entries > 0 }.sortedWith(compareBy<JobGroupDto> { it.id == null }.thenByDescending { it.minutes })

        val sorted = rows.sortedByDescending { it.span.startAt }
        return JobReportDto(
            from = from.toString(),
            to = to.toString(),
            minutes = rows.sumOf { it.span.minutes },
            billableMinutes = rows.filter { it.job?.billable == true }.sumOf { it.span.minutes },
            unallocatedMinutes = rows.filter { WorkLog.missing(it.job, rules.jobNeeds) != null }.sumOf { it.span.minutes },
            byClient = groups({ it.job?.clientId }, { it.job?.clientName }),
            byProject = groups({ it.job?.projectId }, { it.job?.projectName }),
            byActivity = groups({ it.job?.activityId }, { it.job?.activityName }),
            byEmployee = groups({ it.shift.employeeId }, { employees[it.shift.employeeId]?.name }),
            entries = sorted.take(MAX_ENTRIES).map { it.dto(employees, rules, zone) },
            truncated = sorted.size > MAX_ENTRIES,
        )
    }

    private fun WorkRow.dto(employees: Map<ObjectId, Employee>, rules: TimesheetSettings, zone: TimeZone) = WorkEntryDto(
        shiftId = shift.id.toHexString(),
        employeeId = shift.employeeId.toHexString(),
        employeeName = employees[shift.employeeId]?.name.orEmpty(),
        day = ShiftMath.localDay(span.startAt, zone).toString(),
        startTime = ShiftMath.localTime(span.startAt, zone),
        endTime = span.endAt?.let { ShiftMath.localTime(it, zone) },
        minutes = span.minutes,
        job = job?.dto(),
        source = span.segment.source.name,
        complete = WorkLog.missing(job, rules.jobNeeds) == null,
        status = status(shift),
    )

    companion object {
        private const val MAX_CLIENTS = 2000
        const val MAX_ENTRIES = 500

        /** Codes about what was sent (400); the rest are about the shift's state (409). */
        val INPUT_ERRORS = setOf(
            "segments_required", "too_many_segments", "segment_outside_shift", "segments_same_start", "invalid_time",
            "reason_required", "reason_too_long",
        )

        fun status(shift: Shift): String = when {
            shift.isOpen -> "OPEN"
            shift.review.status == ReviewStatus.APPROVED -> "APPROVED"
            else -> "PENDING"
        }
    }
}
