package com.rfm.edubot.timesheets

import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.notifications.NotificationAudience
import com.rfm.edubot.notifications.NotificationKinds
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.timesheets.Timesheets.workedUntil
import com.rfm.edubot.timesheets.model.DevicePlatform
import com.rfm.edubot.timesheets.model.LocationPolicy
import com.rfm.edubot.timesheets.model.Punch
import com.rfm.edubot.timesheets.model.PunchChannel
import com.rfm.edubot.timesheets.model.PunchLocation
import com.rfm.edubot.timesheets.model.PunchType
import com.rfm.edubot.timesheets.model.PunchVerification
import com.rfm.edubot.timesheets.model.PlannedShift
import com.rfm.edubot.timesheets.model.Shift
import com.rfm.edubot.timesheets.model.TimeDevice
import com.rfm.edubot.timesheets.model.TimesheetSettings
import com.rfm.edubot.timesheets.model.VerificationMethod
import com.rfm.edubot.timesheets.model.WorkLogPolicy
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory

/** A punch from the employee's app or browser. */
@Serializable
internal data class PunchRequest(
    val type: String = "",
    val location: LocationRequest? = null,
    /** Why there is no location, as the app knows it (`PERMISSION_DENIED`, `DISABLED`, `TIMEOUT`, `UNAVAILABLE`). */
    val locationError: String? = null,
    val verification: VerificationRequest? = null,
    /** `APP` or `WEB`. */
    val channel: String? = null,
    /** Only kept with a clock-in. */
    val note: String? = null,
    /** What the work is for, on a clock-in or a switch, when the company logs jobs. */
    val job: JobRequest? = null,
)

/** A shift's time split between jobs: [start] is `HH:mm` on the company's clock (the first one is the shift's start). */
@Serializable internal data class SegmentRequest(val start: String = "", val job: JobRequest? = null)

/** [reason] is only for the team, which must give one. */
@Serializable internal data class SegmentsRequest(val segments: List<SegmentRequest> = emptyList(), val reason: String = "")

@Serializable
internal data class LocationRequest(
    val latitude: Double = Double.NaN,
    val longitude: Double = Double.NaN,
    val accuracyM: Double? = null,
    val mocked: Boolean = false,
)

/** [signature] is over [DeviceKeys.message] of [nonce] and the punch type. */
@Serializable internal data class VerificationRequest(val keyId: String = "", val nonce: String = "", val signature: String = "")

/** [signature] is over [DeviceKeys.message] of [nonce] and `ENROLL`, made with the key being enrolled. */
@Serializable
internal data class EnrollRequest(
    val name: String = "",
    val platform: String = "",
    val publicKey: String = "",
    val nonce: String = "",
    val signature: String = "",
)

internal sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>

    /** [detail] names the field at fault; [opensAt] is when a clock-in refused as too early may happen. */
    data class Refused(
        val error: String,
        val status: Int,
        val siteName: String? = null,
        val distanceM: Int? = null,
        val detail: String? = null,
        val opensAt: Instant? = null,
    ) : Outcome<Nothing>
}

/** The employee's side of the time clock. */
internal class TimeClock(
    private val mongo: MongoModule,
    private val notifications: NotificationRepository,
    private val clock: () -> Instant = SystemClock::now,
    private val projects: JobProjects = JobProjects.None,
) {
    private val settings = TimesheetSettingsRepository(mongo)
    private val challenges = TimeChallengeRepository(mongo)
    private val workLog = WorkLogService(mongo, projects)

    suspend fun status(tenant: Tenant, employee: Employee): TimeClockStatusDto {
        val now = clock()
        val zone = tenant.zone()
        val rules = rules(tenant)
        val shifts = ShiftRepository(mongo, tenant.id)
        val today = ShiftMath.localDay(now, zone)
        val weekStart = ShiftMath.weekStart(today)
        val recent = shifts.list(minOf(weekStart, today.minus(1, DateTimeUnit.DAY)), today, employee.id)
        val week = recent.filter { it.day >= weekStart }
        val open = shifts.open(employee.id)
        val view = ShiftView(zone, rules, now)
        val plans = PlannedShiftRepository(mongo, tenant.id).list(today.minus(1, DateTimeUnit.DAY), today.plus(UPCOMING_DAYS, DateTimeUnit.DAY), employee.id)
        val plansView = PlanView(zone, rules, now, recent + listOfNotNull(open).filter { o -> recent.none { it.id == o.id } })
        val current = open?.planned?.id?.let { id -> plans.firstOrNull { it.id == id } }
            ?: Attendance.match(plans, now, rules)
            ?: plans.firstOrNull { it.excuse == null && it.startAt > now && ShiftMath.localDay(it.startAt, zone) == today }
        val clockIn = if (open == null) Attendance.clockIn(plans, now, rules) else Attendance.ClockIn.Allowed(null)
        return TimeClockStatusDto(
            policy = rules.dto(),
            state = when {
                open == null -> "OFF"
                open.onBreak -> "ON_BREAK"
                else -> "WORKING"
            },
            open = open?.let(view::dto),
            overdue = open != null && ShiftRules.isOverdue(open, rules, now),
            todayMinutes = week.filter { it.day == today }.sumOf { it.workedUntil(now) },
            weekMinutes = week.sumOf { it.workedUntil(now) },
            sites = WorkSiteRepository(mongo, tenant.id).list(activeOnly = true).map { it.dto() },
            devices = TimeDeviceRepository(mongo, tenant.id).active(employee.id).map { it.dto() },
            serverTime = now.toString(),
            timezone = zone.id,
            planned = current?.let(plansView::dto),
            upcoming = plans.filter { it.id != current?.id && it.endAt > now && it.excuse == null }.map(plansView::dto),
            clockIn = when (clockIn) {
                is Attendance.ClockIn.Allowed -> ClockInDto("ALLOWED")
                is Attendance.ClockIn.TooEarly -> ClockInDto("TOO_EARLY", clockIn.opensAt.toString(), ShiftMath.localTime(clockIn.opensAt, zone))
                Attendance.ClockIn.NotScheduled -> ClockInDto("NOT_SCHEDULED")
            },
        )
    }

    /** What the employee can name in a job. */
    suspend fun jobOptions(tenant: Tenant): JobOptionsDto = workLog.options(tenant)

    /** The employee's planned shifts from [from] to [to], with how each went. */
    suspend fun schedule(tenant: Tenant, employee: Employee, from: LocalDate, to: LocalDate): List<PlannedShiftDto> {
        val now = clock()
        val plans = PlannedShiftRepository(mongo, tenant.id).list(from, to, employee.id)
        val shifts = ShiftRepository(mongo, tenant.id).list(from.minus(1, DateTimeUnit.DAY), minOf(to, ShiftMath.localDay(now, tenant.zone())), employee.id)
        val view = PlanView(tenant.zone(), rules(tenant), now, shifts)
        return plans.map(view::dto)
    }

    /** The employee splits their own shift's time between jobs, until the team approves it. */
    suspend fun editSegments(tenant: Tenant, employee: Employee, shiftId: ObjectId, request: SegmentsRequest): Outcome<Shift> {
        val rules = rules(tenant)
        if (!rules.logsJobs) return Outcome.Refused("work_log_off", 409)
        val shifts = ShiftRepository(mongo, tenant.id)
        val shift = shifts.findById(shiftId)?.takeIf { it.employeeId == employee.id } ?: return Outcome.Refused("not_found", 404)
        val drafts = when (val d = workLog.drafts(tenant, shift, request.segments)) {
            is Outcome.Refused -> return d
            is Outcome.Ok -> d.value
        }
        val zone = tenant.zone()
        return when (val t = shifts.update(shift.id) { ShiftRules.employeeJobs(it, drafts, rules, zone, clock()) }) {
            null -> Outcome.Refused("not_found", 404)
            is Transition.Refused -> Outcome.Refused(t.error, if (t.error in WorkLogService.INPUT_ERRORS) 400 else 409)
            is Transition.Ok -> Outcome.Ok(t.shift)
        }
    }

    suspend fun challenge(tenant: Tenant, employee: Employee): ChallengeDto {
        val (nonce, expiresAt) = challenges.issue(tenant.id, employee.id, clock())
        return ChallengeDto(nonce, expiresAt.toString())
    }

    /**
     * A punch, checked in order of cost so a refusal doesn't spend the phone's one-use nonce: the clock's state, the
     * job (clock-in and switch), the planned shift (clock-in, under the strict schedule), then the signature, the
     * location and the work sites.
     */
    suspend fun punch(tenant: Tenant, employee: Employee, request: PunchRequest): Outcome<Shift> {
        val type = PunchType.entries.firstOrNull { it.name == request.type.trim().uppercase() } ?: return Outcome.Refused("invalid_type", 400)
        val now = clock()
        val zone = tenant.zone()
        val rules = rules(tenant)
        val shifts = ShiftRepository(mongo, tenant.id)
        val open = shifts.open(employee.id)
        if (type == PunchType.IN && open != null) return Outcome.Refused("already_clocked_in", 409)
        if (type != PunchType.IN && open == null) return Outcome.Refused("not_clocked_in", 409)

        val picksJob = type == PunchType.IN || type == PunchType.SWITCH
        if (type == PunchType.SWITCH && !rules.logsJobs) return Outcome.Refused("work_log_off", 409)
        val job = if (picksJob && rules.logsJobs) {
            when (val resolved = JobResolver(mongo, tenant, projects).resolve(request.job)) {
                is Outcome.Refused -> return resolved
                is Outcome.Ok -> resolved.value
            }
        } else {
            null
        }
        if (picksJob && rules.workLog == WorkLogPolicy.REQUIRED) {
            when (val part = WorkLog.missing(job, rules.jobNeeds)) {
                null -> Unit
                "job" -> return Outcome.Refused("job_required", 400)
                else -> return Outcome.Refused("job_incomplete", 400, detail = part)
            }
        }
        if (type == PunchType.SWITCH) {
            if (open!!.onBreak) return Outcome.Refused("on_break", 409)
            if (WorkLog.isCurrent(open, job)) return Outcome.Refused("same_job", 409)
        }
        val plans = PlannedShiftRepository(mongo, tenant.id)
        val plan = if (type == PunchType.IN) {
            val today = ShiftMath.localDay(now, zone)
            when (val decision = Attendance.clockIn(plans.list(today.minus(1, DateTimeUnit.DAY), today.plus(1, DateTimeUnit.DAY), employee.id), now, rules)) {
                is Attendance.ClockIn.Allowed -> decision.plan
                is Attendance.ClockIn.TooEarly -> return Outcome.Refused("too_early", 409, opensAt = decision.opensAt)
                Attendance.ClockIn.NotScheduled -> return Outcome.Refused("not_scheduled", 409)
            }
        } else {
            null
        }

        val verification = when (val v = request.verification?.let { verify(tenant, employee, it, type.name, now) }) {
            null -> PunchVerification()
            is Outcome.Refused -> return v
            is Outcome.Ok -> PunchVerification(VerificationMethod.DEVICE_KEY, v.value.id, v.value.name)
        }
        val sites = WorkSiteRepository(mongo, tenant.id).list(activeOnly = true)
        val location = request.location?.let { PunchLocation(it.latitude, it.longitude, it.accuracyM, it.mocked) }
        val input = PunchInput(type, location, verified = verification.method == VerificationMethod.DEVICE_KEY)
        val accepted = when (val decision = PunchPolicy.decide(rules, sites, input)) {
            is PunchDecision.Refuse -> return Outcome.Refused(decision.error, decision.status, decision.site?.siteName, decision.site?.distanceM)
            is PunchDecision.Accept -> decision
        }
        val punch = Punch(
            type = type,
            at = now,
            channel = if (request.channel?.uppercase() == PunchChannel.APP.name) PunchChannel.APP else PunchChannel.WEB,
            location = accepted.location,
            locationError = request.locationError?.takeIf { accepted.location == null && rules.location != LocationPolicy.OFF }?.let(::locationErrorCode),
            site = accepted.site,
            verification = verification,
            flags = accepted.flags,
            job = job,
        )

        if (type == PunchType.IN) {
            val site = accepted.site?.takeIf { it.inside }?.let { verdict -> sites.firstOrNull { it.id == verdict.siteId } }
            val earlier = plan?.let { shifts.linkedTo(listOf(it.id)) }.orEmpty()
            val link = plan?.let { Attendance.link(it, linkedBefore = earlier.isNotEmpty()) }
            val shift = ShiftRules.start(tenant.id, employee.id, punch, site, request.note, rules, zone, now, link)
            if (!shifts.insertOpen(shift)) return Outcome.Refused("already_clocked_in", 409)
            if (plan != null) linked(plans, shifts, plan, shift, earlier, rules, zone, now)
            return Outcome.Ok(shift)
        }
        return when (val t = shifts.update(open!!.id) { ShiftRules.punch(it, punch, rules, zone, now) }) {
            null -> Outcome.Refused("not_clocked_in", 409)
            is Transition.Refused -> Outcome.Refused(t.error, 409)
            is Transition.Ok -> Outcome.Ok(t.shift)
        }
    }

    /** The employee says when they stopped, after forgetting to clock out; the team is told so it checks. */
    suspend fun closeForgotten(tenant: Tenant, employee: Employee, shiftId: ObjectId, end: String): Outcome<Shift> {
        val time = ShiftMath.parseTime(end) ?: return Outcome.Refused("invalid_time", 400)
        val shifts = ShiftRepository(mongo, tenant.id)
        val shift = shifts.findById(shiftId)?.takeIf { it.employeeId == employee.id } ?: return Outcome.Refused("not_found", 404)
        val now = clock()
        val zone = tenant.zone()
        val rules = rules(tenant)
        val endAt = ShiftMath.endAfter(shift.startAt, time, zone)
        val closed = when (val t = shifts.update(shift.id) { ShiftRules.closeForgotten(it, endAt, rules, zone, now) }) {
            null -> return Outcome.Refused("not_found", 404)
            is Transition.Refused -> return Outcome.Refused(t.error, 409)
            is Transition.Ok -> t.shift
        }
        runCatching {
            notifications.notify(
                tenantId = tenant.id,
                kind = NotificationKinds.TIME_MISSED_CLOCK_OUT,
                audience = NotificationAudience.ALL,
                params = mapOf("employee" to employee.name, "day" to closed.day.toString(), "end" to ShiftMath.localTime(endAt, zone)),
                link = DashboardModules.TIMESHEETS,
                subject = SubjectRef.of(SubjectTypes.EMPLOYEE, employee.id),
                ref = "shift:${closed.id.toHexString()}",
            )
        }.onFailure { log.warn("Could not tell the team about shift {}: {}", closed.id, it.message) }
        return Outcome.Ok(closed)
    }

    suspend fun note(tenant: Tenant, employee: Employee, shiftId: ObjectId, note: String?): Outcome<Shift> {
        val shifts = ShiftRepository(mongo, tenant.id)
        shifts.findById(shiftId)?.takeIf { it.employeeId == employee.id } ?: return Outcome.Refused("not_found", 404)
        return when (val t = shifts.update(shiftId) { ShiftRules.note(it, note, clock()) }) {
            null -> Outcome.Refused("not_found", 404)
            is Transition.Refused -> Outcome.Refused(t.error, if (t.error == "note_too_long") 400 else 409)
            is Transition.Ok -> Outcome.Ok(t.shift)
        }
    }

    /**
     * Enrolls the phone whose key signed this server's nonce, as the employee's one phone. Admins are told when
     * it's a new key, since a phone set up by someone else is how a stolen password would be used.
     */
    suspend fun enroll(tenant: Tenant, employee: Employee, userId: ObjectId?, request: EnrollRequest): Outcome<TimeDevice> {
        val platform = DevicePlatform.entries.firstOrNull { it.name == request.platform.trim().uppercase() }
            ?: return Outcome.Refused("invalid_platform", 400)
        val key = DeviceKeys.parse(request.publicKey) ?: return Outcome.Refused("invalid_public_key", 400)
        val now = clock()
        if (!challenges.consume(tenant.id, employee.id, request.nonce, now)) return Outcome.Refused("challenge_expired", 403)
        if (!DeviceKeys.verify(key, DeviceKeys.message(request.nonce, ENROLL), request.signature)) return Outcome.Refused("invalid_signature", 403)
        val keyId = DeviceKeys.keyId(key)
        val name = request.name.trim().take(MAX_DEVICE_NAME).ifEmpty { platform.name.lowercase().replaceFirstChar { it.uppercase() } }
        val devices = TimeDeviceRepository(mongo, tenant.id)
        val known = devices.list(employee.id).any { it.keyId == keyId }
        val device = devices.enroll(employee.id, userId, keyId, DeviceKeys.encode(key), platform, name, now)
        if (!known) {
            runCatching {
                notifications.notify(
                    tenantId = tenant.id,
                    kind = NotificationKinds.TIME_DEVICE_ENROLLED,
                    audience = NotificationAudience.ADMINS,
                    params = mapOf("employee" to employee.name, "device" to device.name),
                    link = DashboardModules.TIMESHEETS,
                    subject = SubjectRef.of(SubjectTypes.EMPLOYEE, employee.id),
                    ref = "employee:${employee.id.toHexString()}",
                )
            }.onFailure { log.warn("Could not tell the admins about phone {}: {}", device.id, it.message) }
            log.info("Employee {} of tenant {} enrolled a {} for the time clock", employee.number, tenant.slug, platform)
        }
        return Outcome.Ok(device)
    }

    suspend fun removeDevice(tenant: Tenant, employee: Employee, keyId: String): Boolean =
        TimeDeviceRepository(mongo, tenant.id).revokeKey(employee.id, keyId, by = "employee", now = clock()) != null

    suspend fun rules(tenant: Tenant): TimesheetSettings = settings.get(tenant.id).forCompany(tenant)

    fun now(): Instant = clock()

    /** [shift] just linked to [plan]: the plan records its first attendee, or the shifts linked to it are put in order. */
    private suspend fun linked(
        plans: PlannedShiftRepository,
        shifts: ShiftRepository,
        plan: PlannedShift,
        shift: Shift,
        earlier: List<Shift>,
        rules: TimesheetSettings,
        zone: TimeZone,
        now: Instant,
    ) {
        if (earlier.isEmpty()) plans.setMatched(plan.id, shift.id) else PlanLinks.relink(shifts, plan.id, rules, zone, now)
    }

    /** The nonce is spent before the signature is checked, so a wrong guess can't be retried with the same one. */
    private suspend fun verify(tenant: Tenant, employee: Employee, request: VerificationRequest, action: String, now: Instant): Outcome<TimeDevice> {
        val devices = TimeDeviceRepository(mongo, tenant.id)
        val device = devices.findActive(employee.id, request.keyId.trim()) ?: return Outcome.Refused("device_not_enrolled", 403)
        if (!challenges.consume(tenant.id, employee.id, request.nonce, now)) return Outcome.Refused("challenge_expired", 403)
        val key = DeviceKeys.parse(device.publicKey) ?: return Outcome.Refused("device_not_enrolled", 403)
        if (!DeviceKeys.verify(key, DeviceKeys.message(request.nonce, action), request.signature)) return Outcome.Refused("invalid_signature", 403)
        devices.touch(device.id, now)
        return Outcome.Ok(device)
    }

    private fun locationErrorCode(raw: String): String? =
        raw.trim().uppercase().takeIf { it in LOCATION_ERRORS }

    companion object {
        const val ENROLL = "ENROLL"
        /** How far ahead the status lists planned shifts. */
        const val UPCOMING_DAYS = 13
        private const val MAX_DEVICE_NAME = 80
        private val LOCATION_ERRORS = setOf("PERMISSION_DENIED", "DISABLED", "TIMEOUT", "UNAVAILABLE")
        private val log = LoggerFactory.getLogger(TimeClock::class.java)
    }
}
