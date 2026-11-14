package com.rfm.edubot.timesheets

import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import com.rfm.edubot.timesheets.Timesheets.workedUntil
import com.rfm.edubot.timesheets.model.Activity
import com.rfm.edubot.timesheets.model.Job
import com.rfm.edubot.timesheets.model.PlannedShift
import com.rfm.edubot.timesheets.model.Punch
import com.rfm.edubot.timesheets.model.Shift
import com.rfm.edubot.timesheets.model.ShiftBreak
import com.rfm.edubot.timesheets.model.ShiftEdit
import com.rfm.edubot.timesheets.model.ShiftSegment
import com.rfm.edubot.timesheets.model.ShiftTimes
import com.rfm.edubot.timesheets.model.TimeDevice
import com.rfm.edubot.timesheets.model.TimesheetSettings
import com.rfm.edubot.timesheets.model.VerificationMethod
import com.rfm.edubot.timesheets.model.WorkSite
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

internal fun Tenant.zone(): TimeZone = TimeZone.of(TenantTimeZones.normalize(timezone))

/**
 * A shift as both sides read it. Times come as instants and as `HH:mm` in the company's timezone, so a
 * browser or phone in another zone shows and edits the company's clock. [punches] and [edits] are only
 * sent where someone looks at one shift.
 */
@Serializable
internal data class ShiftDto(
    val id: String,
    val employeeId: String,
    val employeeName: String? = null,
    val employeeNumber: String? = null,
    val status: String,
    val day: String,
    val startAt: String,
    val endAt: String? = null,
    val startTime: String,
    val endTime: String? = null,
    /** The end falls on the day after [day] (a night shift). */
    val endsNextDay: Boolean,
    val breaks: List<BreakDto>,
    /** Up to now for an open shift. */
    val workedMinutes: Int,
    val breakMinutes: Int,
    val onBreak: Boolean,
    /** Open for longer than the company's limit: a forgotten clock-out. */
    val overdue: Boolean,
    val siteId: String? = null,
    val siteName: String? = null,
    val note: String? = null,
    val flags: List<String>,
    val review: String,
    val reviewedBy: String? = null,
    val reviewedAt: String? = null,
    val manual: Boolean,
    val edited: Boolean,
    /** What the working time was for; empty when the company doesn't log jobs. */
    val segments: List<SegmentDto>,
    val planned: PlanLinkDto? = null,
    val lateMinutes: Int,
    val earlyLeaveMinutes: Int,
    /** Minutes on no complete job, by the company's rules (0 when it doesn't log jobs). */
    val unallocatedMinutes: Int,
    val punches: List<PunchDto>? = null,
    val edits: List<EditDto>? = null,
)

/** What a block of work was for; the names are as they were when it was picked. */
@Serializable
internal data class JobDto(
    val clientId: String? = null,
    val clientName: String? = null,
    val projectId: String? = null,
    val projectName: String? = null,
    val activityId: String? = null,
    val activityName: String? = null,
    val note: String? = null,
    val billable: Boolean,
)

@Serializable
internal data class SegmentDto(
    val startAt: String,
    /** Null while it runs. */
    val endAt: String? = null,
    val startTime: String,
    val endTime: String? = null,
    val minutes: Int,
    val job: JobDto? = null,
    /** `CLOCK`, `EMPLOYEE` or `TEAM`. */
    val source: String,
    val siteName: String? = null,
    /** Has every part the company's jobs need. */
    val complete: Boolean,
)

@Serializable internal data class PlanLinkDto(val id: String, val startAt: String, val endAt: String, val startTime: String, val endTime: String)

@Serializable internal data class SegmentBriefDto(val startTime: String, val job: JobDto? = null)

@Serializable internal data class JobsChangeDto(val before: List<SegmentBriefDto>, val after: List<SegmentBriefDto>)

@Serializable internal data class JobNeedsDto(val client: Boolean, val project: Boolean, val activity: Boolean, val note: Boolean)

@Serializable internal data class ExcuseDto(val reason: String, val by: String, val at: String)

/** How a planned shift went, as [Attendance.State] names it. */
@Serializable
internal data class AttendanceDto(
    val state: String,
    val lateMinutes: Int,
    val earlyLeaveMinutes: Int,
    /** Past the company's grace. */
    val late: Boolean,
    val leftEarly: Boolean,
    val workedMinutes: Int,
    val shiftIds: List<String>,
    val arrivedTime: String? = null,
    val leftTime: String? = null,
)

@Serializable
internal data class PlannedShiftDto(
    val id: String,
    val employeeId: String,
    val employeeName: String? = null,
    val employeeNumber: String? = null,
    val day: String,
    val startAt: String,
    val endAt: String,
    val startTime: String,
    val endTime: String,
    val endsNextDay: Boolean,
    val minutes: Int,
    val siteId: String? = null,
    val siteName: String? = null,
    val job: JobDto? = null,
    val note: String? = null,
    val excuse: ExcuseDto? = null,
    val attendance: AttendanceDto,
)

/** Whether the employee may clock in now: `ALLOWED`, `TOO_EARLY` (until [opensAt]) or `NOT_SCHEDULED`. */
@Serializable internal data class ClockInDto(val state: String, val opensAt: String? = null, val opensTime: String? = null)

@Serializable internal data class JobClientDto(val id: String, val number: String, val name: String)

@Serializable internal data class JobProjectDto(val id: String, val number: String, val name: String, val clientId: String? = null)

@Serializable
internal data class ActivityDto(val id: String, val name: String, val code: String? = null, val billable: Boolean, val active: Boolean)

/** What a job can name: the company's clients, open Building projects and activities in use (each only with its module). */
@Serializable
internal data class JobOptionsDto(val clients: List<JobClientDto>, val projects: List<JobProjectDto>, val activities: List<ActivityDto>)

/** Hours on one client, project, activity or employee over a period. [id] is null for time on none. */
@Serializable
internal data class JobGroupDto(val id: String? = null, val name: String, val minutes: Int, val billableMinutes: Int, val employees: Int, val entries: Int)

/** One segment as a row of the work log. */
@Serializable
internal data class WorkEntryDto(
    val shiftId: String,
    val employeeId: String,
    val employeeName: String,
    val day: String,
    val startTime: String,
    val endTime: String? = null,
    val minutes: Int,
    val job: JobDto? = null,
    val source: String,
    val complete: Boolean,
    /** `OPEN`, `PENDING` or `APPROVED`. */
    val status: String,
)

@Serializable
internal data class JobReportDto(
    val from: String,
    val to: String,
    val minutes: Int,
    val billableMinutes: Int,
    /** Time on no complete job by the company's rules (with no job at all when it doesn't need one). */
    val unallocatedMinutes: Int,
    val byClient: List<JobGroupDto>,
    val byProject: List<JobGroupDto>,
    val byActivity: List<JobGroupDto>,
    val byEmployee: List<JobGroupDto>,
    val entries: List<WorkEntryDto>,
    /** More rows than [entries] holds: the CSV has them all. */
    val truncated: Boolean,
)

@Serializable internal data class ScheduleDto(val from: String, val to: String, val planned: List<PlannedShiftDto>, val policy: TimesheetPolicyDto)

@Serializable internal data class CopiedDto(val created: Int, val skipped: Int)

@Serializable
internal data class BreakDto(val startAt: String, val endAt: String? = null, val startTime: String, val endTime: String? = null)

@Serializable
internal data class PunchDto(
    val type: String,
    val at: String,
    val time: String,
    val channel: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Double? = null,
    val mocked: Boolean,
    val locationError: String? = null,
    val siteName: String? = null,
    val siteDistanceM: Int? = null,
    val inside: Boolean? = null,
    val verified: Boolean,
    val deviceName: String? = null,
    val flags: List<String>,
    val by: String? = null,
    val recordedAt: String? = null,
    /** The job a clock-in or switch picked. */
    val job: JobDto? = null,
)

/** One correction; [jobs] when it changed how the time splits between jobs (the times then stay the same). */
@Serializable
internal data class EditDto(val at: String, val by: String, val reason: String, val before: TimesDto? = null, val after: TimesDto, val jobs: JobsChangeDto? = null)

@Serializable
internal data class TimesDto(val startAt: String, val endAt: String? = null, val startTime: String, val endTime: String? = null, val breaks: List<BreakDto>)

@Serializable
internal data class TimesheetPolicyDto(
    val location: String,
    val geofence: String,
    val biometric: String,
    val maxShiftHours: Int,
    val dailyHours: Int,
    val weeklyHours: Int,
    val canEdit: Boolean,
    /** `OFF`, `OPTIONAL` or `REQUIRED`. */
    val workLog: String,
    val jobNeeds: JobNeedsDto,
    /** `OFF`, `FLAG` or `BLOCK`. */
    val schedule: String,
    val lateGraceMinutes: Int,
    val earlyLeaveGraceMinutes: Int,
    val earlyClockInMinutes: Int,
    val noShowMinutes: Int,
)

@Serializable
internal data class SiteDto(
    val id: String,
    val name: String,
    val address: String? = null,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Int,
    val clientId: String? = null,
    val clientName: String? = null,
    val active: Boolean,
)

@Serializable
internal data class DeviceDto(
    val id: String,
    val keyId: String,
    val name: String,
    val platform: String,
    val active: Boolean,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val revokedAt: String? = null,
    val revokedBy: String? = null,
)

/** What the employee's clock screen needs, in one call. */
@Serializable
internal data class TimeClockStatusDto(
    val policy: TimesheetPolicyDto,
    /** `OFF`, `WORKING` or `ON_BREAK`. */
    val state: String,
    val open: ShiftDto? = null,
    val overdue: Boolean,
    val todayMinutes: Int,
    val weekMinutes: Int,
    val sites: List<SiteDto>,
    val devices: List<DeviceDto>,
    val serverTime: String,
    val timezone: String,
    /** The planned shift that matters now: the open shift's, the one clocking in would match, or today's next. */
    val planned: PlannedShiftDto? = null,
    /** The planned shifts still to come in the next two weeks, besides [planned]. */
    val upcoming: List<PlannedShiftDto>,
    val clockIn: ClockInDto,
)

@Serializable internal data class ChallengeDto(val nonce: String, val expiresAt: String)

@Serializable internal data class PunchResultDto(val status: TimeClockStatusDto, val shift: ShiftDto)

/**
 * A refused punch; [siteName] and [distanceM] say how far the nearest site was for `outside_sites`, [detail] which
 * part of the job is missing for `job_incomplete`, and [opensAt] when clocking in opens for `too_early`.
 */
@Serializable
internal data class PunchRefusalDto(
    val error: String,
    val siteName: String? = null,
    val distanceM: Int? = null,
    val detail: String? = null,
    val opensAt: String? = null,
    val opensTime: String? = null,
)

@Serializable
internal data class EmployeeTotalsDto(
    val employeeId: String,
    val employeeName: String,
    val employeeNumber: String,
    val workedMinutes: Int,
    val days: Int,
    val overDailyMinutes: Int,
    val overWeeklyMinutes: Int,
    val toReview: Int,
    val flagged: Int,
    val open: Int,
    /** Planned for the period (excused shifts left out). */
    val plannedMinutes: Int,
    /** Shifts flagged late. */
    val late: Int,
    /** Planned shifts nobody worked, not excused, already over. */
    val absences: Int,
)

@Serializable
internal data class TimesheetDto(
    val from: String,
    val to: String,
    val shifts: List<ShiftDto>,
    val totals: List<EmployeeTotalsDto>,
    val policy: TimesheetPolicyDto,
)

/** Who is working now, and the planned shifts started that nobody clocked in for yet ([expected]). */
@Serializable internal data class BoardDto(val working: List<ShiftDto>, val serverTime: String, val expected: List<PlannedShiftDto>)

/** [skipped]: closed shifts left pending, such as ones with time on no complete job under the strict work log. */
@Serializable internal data class ApprovedDto(val approved: Int, val skipped: Int = 0)

internal fun TimesheetSettings.dto(canEdit: Boolean = false) = TimesheetPolicyDto(
    location = location.name,
    geofence = geofence.name,
    biometric = biometric.name,
    maxShiftHours = maxShiftHours,
    dailyHours = dailyHours,
    weeklyHours = weeklyHours,
    canEdit = canEdit,
    workLog = workLog.name,
    jobNeeds = JobNeedsDto(jobNeeds.client, jobNeeds.project, jobNeeds.activity, jobNeeds.note),
    schedule = schedule.name,
    lateGraceMinutes = lateGraceMinutes,
    earlyLeaveGraceMinutes = earlyLeaveGraceMinutes,
    earlyClockInMinutes = earlyClockInMinutes,
    noShowMinutes = noShowMinutes,
)

internal fun Job.dto() = JobDto(
    clientId = clientId?.toHexString(),
    clientName = clientName,
    projectId = projectId?.toHexString(),
    projectName = projectName,
    activityId = activityId?.toHexString(),
    activityName = activityName,
    note = note,
    billable = billable,
)

internal fun Activity.dto() = ActivityDto(id.toHexString(), name, code, billable, active)

internal fun TimeDevice.dto() = DeviceDto(
    id = id.toHexString(),
    keyId = keyId,
    name = name,
    platform = platform.name,
    active = active,
    createdAt = createdAt.toString(),
    lastUsedAt = lastUsedAt?.toString(),
    revokedAt = revokedAt?.toString(),
    revokedBy = revokedBy,
)

internal fun WorkSite.dto(clientName: String? = null) = SiteDto(
    id = id.toHexString(),
    name = name,
    address = address,
    latitude = latitude,
    longitude = longitude,
    radiusM = radiusM,
    clientId = clientId?.toHexString(),
    clientName = clientName,
    active = active,
)

/** Turns shifts into what the API sends, in the company's timezone. */
internal class ShiftView(
    private val zone: TimeZone,
    private val settings: TimesheetSettings,
    private val now: Instant,
    private val employees: Map<ObjectId, Employee> = emptyMap(),
) {
    fun dto(shift: Shift, detail: Boolean = false): ShiftDto {
        val employee = employees[shift.employeeId]
        val end = shift.endAt
        return ShiftDto(
            id = shift.id.toHexString(),
            employeeId = shift.employeeId.toHexString(),
            employeeName = employee?.name,
            employeeNumber = employee?.number,
            status = shift.status.name,
            day = shift.day.toString(),
            startAt = shift.startAt.toString(),
            endAt = end?.toString(),
            startTime = ShiftMath.localTime(shift.startAt, zone),
            endTime = end?.let { ShiftMath.localTime(it, zone) },
            endsNextDay = end != null && ShiftMath.localDay(end, zone) > shift.day,
            breaks = shift.breaks.map { it.dto() },
            workedMinutes = shift.workedUntil(now),
            breakMinutes = if (shift.isOpen) ShiftMath.breakMinutes(shift.times, now) else shift.breakMinutes,
            onBreak = shift.onBreak,
            overdue = ShiftRules.isOverdue(shift, settings, now),
            siteId = shift.siteId?.toHexString(),
            siteName = shift.siteName,
            note = shift.note,
            flags = shift.flags,
            review = shift.review.status.name,
            reviewedBy = shift.review.by,
            reviewedAt = shift.review.at?.toString(),
            manual = shift.manual,
            edited = shift.edits.any { it.before != null },
            segments = if (shift.segments.isEmpty()) emptyList() else WorkLog.spans(shift, now).map { it.dto() },
            planned = shift.planned?.let {
                PlanLinkDto(it.id.toHexString(), it.startAt.toString(), it.endAt.toString(), ShiftMath.localTime(it.startAt, zone), ShiftMath.localTime(it.endAt, zone))
            },
            lateMinutes = shift.lateMinutes,
            earlyLeaveMinutes = shift.earlyLeaveMinutes,
            unallocatedMinutes = if (settings.logsJobs && shift.segments.isNotEmpty()) WorkLog.unallocatedMinutes(shift, settings.jobNeeds, now) else 0,
            punches = if (detail) shift.punches.map { it.dto() } else null,
            edits = if (detail) shift.edits.map { it.dto() } else null,
        )
    }

    private fun SegmentSpan.dto() = SegmentDto(
        startAt = startAt.toString(),
        endAt = endAt?.toString(),
        startTime = ShiftMath.localTime(startAt, zone),
        endTime = endAt?.let { ShiftMath.localTime(it, zone) },
        minutes = minutes,
        job = segment.job?.dto(),
        source = segment.source.name,
        siteName = segment.siteName,
        complete = WorkLog.missing(segment.job, settings.jobNeeds) == null,
    )

    private fun ShiftSegment.brief() = SegmentBriefDto(ShiftMath.localTime(startAt, zone), job?.dto())

    private fun ShiftBreak.dto() = BreakDto(
        startAt = startAt.toString(),
        endAt = endAt?.toString(),
        startTime = ShiftMath.localTime(startAt, zone),
        endTime = endAt?.let { ShiftMath.localTime(it, zone) },
    )

    private fun ShiftTimes.dto() = TimesDto(
        startAt = startAt.toString(),
        endAt = endAt?.toString(),
        startTime = ShiftMath.localTime(startAt, zone),
        endTime = endAt?.let { ShiftMath.localTime(it, zone) },
        breaks = breaks.map { it.dto() },
    )

    private fun ShiftEdit.dto() = EditDto(
        at = at.toString(),
        by = by,
        reason = reason,
        before = before?.dto(),
        after = after.dto(),
        jobs = jobs?.let { change -> JobsChangeDto(change.before.map { it.brief() }, change.after.map { it.brief() }) },
    )

    private fun Punch.dto() = PunchDto(
        type = type.name,
        at = at.toString(),
        time = ShiftMath.localTime(at, zone),
        channel = channel.name,
        latitude = location?.latitude,
        longitude = location?.longitude,
        accuracyM = location?.accuracyM,
        mocked = location?.mocked == true,
        locationError = locationError,
        siteName = site?.siteName,
        siteDistanceM = site?.distanceM,
        inside = site?.inside,
        verified = verification.method == VerificationMethod.DEVICE_KEY,
        deviceName = verification.deviceName,
        flags = flags,
        by = by,
        recordedAt = recordedAt?.toString(),
        job = job?.dto(),
    )

    companion object {
        /** The employees behind [shifts], each looked up once. */
        suspend fun employees(mongo: MongoModule, tenantId: ObjectId, shifts: List<Shift>): Map<ObjectId, Employee> =
            employeesById(mongo, tenantId, shifts.map { it.employeeId })

        suspend fun employeesById(mongo: MongoModule, tenantId: ObjectId, ids: Collection<ObjectId>): Map<ObjectId, Employee> {
            val repository = EmployeeRepository(mongo, tenantId)
            return ids.distinct().mapNotNull { id -> repository.findById(id)?.let { id to it } }.toMap()
        }
    }
}

/** Turns planned shifts into what the API sends, with how each went by [now] from the employees' [shifts]. */
internal class PlanView(
    private val zone: TimeZone,
    private val settings: TimesheetSettings,
    private val now: Instant,
    shifts: List<Shift>,
    private val employees: Map<ObjectId, Employee> = emptyMap(),
) {
    private val byEmployee = shifts.groupBy { it.employeeId }

    fun status(plan: PlannedShift): Attendance.Status = Attendance.status(plan, byEmployee[plan.employeeId].orEmpty(), settings, now)

    fun dto(plan: PlannedShift): PlannedShiftDto {
        val employee = employees[plan.employeeId]
        val status = status(plan)
        val first = status.shifts.firstOrNull()
        val last = status.shifts.lastOrNull()
        return PlannedShiftDto(
            id = plan.id.toHexString(),
            employeeId = plan.employeeId.toHexString(),
            employeeName = employee?.name,
            employeeNumber = employee?.number,
            day = plan.day.toString(),
            startAt = plan.startAt.toString(),
            endAt = plan.endAt.toString(),
            startTime = ShiftMath.localTime(plan.startAt, zone),
            endTime = ShiftMath.localTime(plan.endAt, zone),
            endsNextDay = ShiftMath.localDay(plan.endAt, zone) > plan.day,
            minutes = Attendance.minutes(plan),
            siteId = plan.siteId?.toHexString(),
            siteName = plan.siteName,
            job = plan.job?.dto(),
            note = plan.note,
            excuse = plan.excuse?.let { ExcuseDto(it.reason, it.by, it.at.toString()) },
            attendance = AttendanceDto(
                state = status.state.name,
                lateMinutes = status.lateMinutes,
                earlyLeaveMinutes = status.earlyLeaveMinutes,
                late = status.late,
                leftEarly = status.leftEarly,
                workedMinutes = status.workedMinutes,
                shiftIds = status.shifts.map { it.id.toHexString() },
                arrivedTime = first?.let { ShiftMath.localTime(it.startAt, zone) },
                leftTime = last?.endAt?.takeIf { status.shifts.none { s -> s.isOpen } }?.let { ShiftMath.localTime(it, zone) },
            ),
        )
    }
}

/** Sites with the name of the client each one belongs to. */
internal suspend fun siteDtos(mongo: MongoModule, tenantId: ObjectId, sites: List<WorkSite>): List<SiteDto> {
    val clients = ClientRepository(mongo, tenantId)
    val names = sites.mapNotNull { it.clientId }.distinct().mapNotNull { id -> clients.findById(id)?.let { id to it.name } }.toMap()
    return sites.map { it.dto(it.clientId?.let(names::get)) }
}
