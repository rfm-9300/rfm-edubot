package com.rfm.edubot.timesheets

import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.isAdmin
import com.rfm.edubot.dashboard.moduleContext
import com.rfm.edubot.dashboard.requireModules
import com.rfm.edubot.dashboard.toObjectIdOrNull
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.timesheets.model.JobNeeds
import com.rfm.edubot.timesheets.model.Shift
import com.rfm.edubot.timesheets.model.ShiftFlags
import com.rfm.edubot.timesheets.model.ShiftTimes
import com.rfm.edubot.timesheets.model.TimesheetSettings
import com.rfm.edubot.timesheets.model.WorkSite
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

/**
 * The team's side of the time clock (`/app/api/timesheets…`, module `timesheets`): who is working, the period's
 * shifts and totals, corrections (always with a reason, kept as history), approvals and the CSV export. The
 * rules, work sites and revoking phones are for admins (and operators opening the dashboard).
 */
fun Route.timesheetRoutes(mongo: MongoModule, clock: () -> Instant = SystemClock::now, projects: JobProjects = JobProjects.None) {
    val settings = TimesheetSettingsRepository(mongo)
    val schedule = ScheduleService(mongo, projects, clock)
    suspend fun rules(ctx: DashboardContext): TimesheetSettings = settings.get(ctx.tenant.id).forCompany(ctx.tenant)
    authenticate("dashboard") {
        route("/app/api/timesheets") {
            requireModules(DashboardModules.TIMESHEETS) {
                get {
                    val ctx = call.moduleContext()
                    val now = clock()
                    val zone = ctx.tenant.zone()
                    val weekStart = ShiftMath.weekStart(ShiftMath.localDay(now, zone))
                    val (from, to) = call.dayRange(weekStart, weekStart.plus(6, DateTimeUnit.DAY)) ?: return@get
                    val employeeId = call.optionalId("employeeId") ?: return@get
                    val rules = rules(ctx)
                    val shifts = ShiftRepository(mongo, ctx.tenant.id).list(from, to, employeeId.value)
                    val plans = PlannedShiftRepository(mongo, ctx.tenant.id).list(from, to, employeeId.value)
                    val employees = ShiftView.employeesById(mongo, ctx.tenant.id, shifts.map { it.employeeId } + plans.map { it.employeeId })
                    val view = ShiftView(zone, rules, now, employees)
                    val planView = PlanView(zone, rules, now, shifts)
                    val planned = plans.groupBy { it.employeeId }
                    val worked = Timesheets.totals(shifts, rules, now).associateBy { it.employeeId }
                    val totals = (worked.keys + planned.keys).map { id ->
                        val t = worked[id]
                        val own = planned[id].orEmpty()
                        val employee = employees[id]
                        EmployeeTotalsDto(
                            employeeId = id.toHexString(),
                            employeeName = employee?.name.orEmpty(),
                            employeeNumber = employee?.number.orEmpty(),
                            workedMinutes = t?.workedMinutes ?: 0,
                            days = t?.days ?: 0,
                            overDailyMinutes = t?.overDailyMinutes ?: 0,
                            overWeeklyMinutes = t?.overWeeklyMinutes ?: 0,
                            toReview = t?.toReview ?: 0,
                            flagged = t?.flagged ?: 0,
                            open = t?.open ?: 0,
                            plannedMinutes = own.filter { it.excuse == null }.sumOf { Attendance.minutes(it) },
                            late = shifts.count { it.employeeId == id && ShiftFlags.LATE in it.flags },
                            absences = own.count { planView.status(it).state == Attendance.State.ABSENT },
                        )
                    }.sortedBy { it.employeeName.lowercase() }
                    call.respond(TimesheetDto(from.toString(), to.toString(), shifts.map { view.dto(it) }, totals, rules.dto(canEdit = ctx.isAdmin())))
                }
                get("/board") {
                    val ctx = call.moduleContext()
                    val now = clock()
                    val zone = ctx.tenant.zone()
                    val rules = rules(ctx)
                    val open = ShiftRepository(mongo, ctx.tenant.id).openShifts()
                    val view = ShiftView(zone, rules, now, ShiftView.employees(mongo, ctx.tenant.id, open))
                    val today = ShiftMath.localDay(now, zone)
                    val started = PlannedShiftRepository(mongo, ctx.tenant.id).list(today.minus(1, DateTimeUnit.DAY), today)
                        .filter { it.excuse == null && it.startAt <= now && it.endAt > now }
                    val expected = schedule.dtos(ctx.tenant, started, rules, now)
                        .filter { it.attendance.state == Attendance.State.DUE.name || it.attendance.state == Attendance.State.MISSING.name }
                    call.respond(BoardDto(open.map { view.dto(it) }, now.toString(), expected))
                }
                get("/shifts/{id}") {
                    val ctx = call.moduleContext()
                    val shift = call.parameters["id"]?.toObjectIdOrNull()?.let { ShiftRepository(mongo, ctx.tenant.id).findById(it) }
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(view(mongo, ctx, rules(ctx), clock, listOf(shift)).dto(shift, detail = true))
                }
                post("/shifts") {
                    val ctx = call.moduleContext()
                    val request = call.receiveOrNull<ShiftTimesRequest>() ?: return@post call.respondInvalidBody()
                    val employee = request.employeeId?.toObjectIdOrNull()?.let { EmployeeRepository(mongo, ctx.tenant.id).findById(it) }
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, "employee_not_found")
                    val zone = ctx.tenant.zone()
                    val day = request.day.parseDay() ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_day")
                    val times = request.times(day, zone) ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_time")
                    val now = clock()
                    val rules = rules(ctx)
                    val job = if (rules.logsJobs) {
                        when (val resolved = JobResolver(mongo, ctx.tenant, projects).resolve(request.job)) {
                            is Outcome.Refused -> return@post call.respondRefusal(resolved)
                            is Outcome.Ok -> resolved.value
                        }
                    } else {
                        null
                    }
                    val shifts = ShiftRepository(mongo, ctx.tenant.id)
                    val plans = PlannedShiftRepository(mongo, ctx.tenant.id)
                    val plan = Attendance.match(plans.list(day.minus(1, DateTimeUnit.DAY), day.plus(1, DateTimeUnit.DAY), employee.id), times.startAt, rules)
                    val earlier = plan?.let { shifts.linkedTo(listOf(it.id)) }.orEmpty()
                    val link = plan?.let { Attendance.link(it, linkedBefore = earlier.isNotEmpty()) }
                    when (val t = ShiftRules.manual(ctx.tenant.id, employee.id, times, request.note, ctx.who(), request.reason, rules, zone, now, job, link)) {
                        is Transition.Refused -> call.respondError(HttpStatusCode.BadRequest, t.error)
                        is Transition.Ok -> {
                            shifts.insert(t.shift)
                            if (plan != null) {
                                if (earlier.isEmpty()) plans.setMatched(plan.id, t.shift.id) else PlanLinks.relink(shifts, plan.id, rules, zone, now)
                            }
                            val saved = shifts.findById(t.shift.id) ?: t.shift
                            call.respond(HttpStatusCode.Created, view(mongo, ctx, rules, clock, listOf(saved)).dto(saved, detail = true))
                        }
                    }
                }
                patch("/shifts/{id}") {
                    val ctx = call.moduleContext()
                    val shifts = ShiftRepository(mongo, ctx.tenant.id)
                    val shift = call.parameters["id"]?.toObjectIdOrNull()?.let { shifts.findById(it) } ?: return@patch call.respond(HttpStatusCode.NotFound)
                    val request = call.receiveOrNull<ShiftTimesRequest>() ?: return@patch call.respondInvalidBody()
                    val zone = ctx.tenant.zone()
                    val day = if (request.day.isBlank()) shift.day else request.day.parseDay() ?: return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_day")
                    val times = request.times(day, zone) ?: return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_time")
                    val rules = rules(ctx)
                    val edited = shifts.update(shift.id) { ShiftRules.edit(it, times, ctx.who(), request.reason, rules, zone, clock()) }
                    val plan = shift.planned
                    if (edited is Transition.Ok && plan != null && edited.shift.startAt != shift.startAt) {
                        PlanLinks.relink(shifts, plan.id, rules, zone, clock())
                        return@patch call.respondTransition(mongo, ctx, rules, clock, shifts.findById(shift.id)?.let { Transition.Ok(it) })
                    }
                    call.respondTransition(mongo, ctx, rules, clock, edited)
                }
                post("/shifts/{id}/close") {
                    val ctx = call.moduleContext()
                    val shifts = ShiftRepository(mongo, ctx.tenant.id)
                    val shift = call.parameters["id"]?.toObjectIdOrNull()?.let { shifts.findById(it) } ?: return@post call.respond(HttpStatusCode.NotFound)
                    val request = call.receiveOrNull<CloseShiftRequest>() ?: return@post call.respondInvalidBody()
                    val time = ShiftMath.parseTime(request.end) ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_time")
                    val zone = ctx.tenant.zone()
                    val endAt = ShiftMath.endAfter(shift.startAt, time, zone)
                    val rules = rules(ctx)
                    call.respondTransition(mongo, ctx, rules, clock, shifts.update(shift.id) { ShiftRules.closeByTeam(it, endAt, ctx.who(), request.reason, rules, zone, clock()) })
                }
                post("/shifts/approve") {
                    val ctx = call.moduleContext()
                    val request = call.receiveOrNull<ApproveRequest>() ?: return@post call.respondInvalidBody()
                    if (request.ids.size > MAX_APPROVE) return@post call.respondError(HttpStatusCode.BadRequest, "too_many")
                    val ids = request.ids.mapNotNull { it.toObjectIdOrNull() }.distinct()
                    val shifts = ShiftRepository(mongo, ctx.tenant.id)
                    val rules = rules(ctx)
                    val results = ids.map { id -> shifts.update(id) { ShiftRules.approve(it, ctx.who(), rules, clock()) } }
                    val approved = results.count { it is Transition.Ok }
                    val unallocated = results.count { it is Transition.Refused && it.error == "unallocated" }
                    // One shift that can't be approved says why; a batch counts what it left.
                    if (ids.size == 1 && unallocated == 1) return@post call.respondError(HttpStatusCode.Conflict, "unallocated")
                    call.respond(ApprovedDto(approved, skipped = unallocated))
                }
                get("/export.csv") {
                    val ctx = call.moduleContext()
                    val now = clock()
                    val zone = ctx.tenant.zone()
                    val weekStart = ShiftMath.weekStart(ShiftMath.localDay(now, zone))
                    val (from, to) = call.dayRange(weekStart, weekStart.plus(6, DateTimeUnit.DAY)) ?: return@get
                    val employeeId = call.optionalId("employeeId") ?: return@get
                    val shifts = ShiftRepository(mongo, ctx.tenant.id).list(from, to, employeeId.value)
                    val csv = TimesheetExport.csv(shifts, ShiftView.employees(mongo, ctx.tenant.id, shifts), zone, ctx.tenant.locale, now)
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "timesheets-$from-$to.csv").toString(),
                    )
                    call.respondText(csv, ContentType.Text.CSV.withCharset(Charsets.UTF_8))
                }

                get("/settings") {
                    val ctx = call.moduleContext()
                    call.respond(settings.get(ctx.tenant.id).dto(canEdit = ctx.isAdmin()))
                }
                put("/settings") {
                    val ctx = call.timesheetAdmin() ?: return@put
                    val request = call.receiveOrNull<SettingsRequest>() ?: return@put call.respondInvalidBody()
                    val current = settings.get(ctx.tenant.id)
                    val next = request.applyTo(current) ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_policy")
                    if (next.maxShiftHours !in TimesheetSettings.MAX_SHIFT_HOURS || next.dailyHours !in TimesheetSettings.DAILY_HOURS ||
                        next.weeklyHours !in TimesheetSettings.WEEKLY_HOURS
                    ) {
                        return@put call.respondError(HttpStatusCode.BadRequest, "invalid_hours")
                    }
                    if (next.lateGraceMinutes !in TimesheetSettings.GRACE_MINUTES || next.earlyLeaveGraceMinutes !in TimesheetSettings.GRACE_MINUTES ||
                        next.earlyClockInMinutes !in TimesheetSettings.EARLY_CLOCK_IN_MINUTES || next.noShowMinutes !in TimesheetSettings.NO_SHOW_MINUTES
                    ) {
                        return@put call.respondError(HttpStatusCode.BadRequest, "invalid_minutes")
                    }
                    call.respond(settings.save(ctx.tenant.id, next.copy(updatedAt = clock(), updatedBy = ctx.who())).dto(canEdit = true))
                }

                timesheetJobRoutes(mongo, projects, clock)
                timesheetScheduleRoutes(mongo, schedule, clock)

                get("/sites") {
                    val ctx = call.moduleContext()
                    call.respond(siteDtos(mongo, ctx.tenant.id, WorkSiteRepository(mongo, ctx.tenant.id).list()))
                }
                post("/sites") {
                    val ctx = call.timesheetAdmin() ?: return@post
                    val request = call.receiveOrNull<SiteRequest>() ?: return@post call.respondInvalidBody()
                    val sites = WorkSiteRepository(mongo, ctx.tenant.id)
                    if (sites.count() >= WorkSiteRepository.MAX_SITES) return@post call.respondError(HttpStatusCode.Conflict, "too_many_sites")
                    val now = clock()
                    val draft = WorkSite(
                        tenantId = ctx.tenant.id,
                        name = "",
                        latitude = Double.NaN,
                        longitude = Double.NaN,
                        radiusM = WorkSiteRepository.DEFAULT_RADIUS_M,
                        createdAt = now,
                        updatedAt = now,
                    )
                    val site = call.siteFrom(mongo, ctx, request, draft) ?: return@post
                    call.respond(HttpStatusCode.Created, siteDtos(mongo, ctx.tenant.id, listOf(sites.create(site))).single())
                }
                patch("/sites/{id}") {
                    val ctx = call.timesheetAdmin() ?: return@patch
                    val sites = WorkSiteRepository(mongo, ctx.tenant.id)
                    val existing = call.parameters["id"]?.toObjectIdOrNull()?.let { sites.findById(it) } ?: return@patch call.respond(HttpStatusCode.NotFound)
                    val request = call.receiveOrNull<SiteRequest>() ?: return@patch call.respondInvalidBody()
                    val site = call.siteFrom(mongo, ctx, request, existing.copy(updatedAt = clock())) ?: return@patch
                    val saved = sites.replace(site) ?: return@patch call.respond(HttpStatusCode.NotFound)
                    call.respond(siteDtos(mongo, ctx.tenant.id, listOf(saved)).single())
                }
                delete("/sites/{id}") {
                    val ctx = call.timesheetAdmin() ?: return@delete
                    val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@delete call.respond(HttpStatusCode.NotFound)
                    if (!WorkSiteRepository(mongo, ctx.tenant.id).delete(id)) return@delete call.respond(HttpStatusCode.NotFound)
                    call.respond(mapOf("deleted" to true))
                }

                get("/devices") {
                    val ctx = call.moduleContext()
                    val employeeId = call.request.queryParameters["employeeId"]?.toObjectIdOrNull()
                        ?: return@get call.respondError(HttpStatusCode.BadRequest, "employee_required")
                    call.respond(TimeDeviceRepository(mongo, ctx.tenant.id).list(employeeId).map { it.dto() })
                }
                delete("/devices/{id}") {
                    val ctx = call.timesheetAdmin() ?: return@delete
                    val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@delete call.respond(HttpStatusCode.NotFound)
                    val device = TimeDeviceRepository(mongo, ctx.tenant.id).revoke(id, ctx.who(), clock()) ?: return@delete call.respond(HttpStatusCode.NotFound)
                    call.respond(device.dto())
                }
            }
        }
    }
}

private const val MAX_APPROVE = 500
private const val MAX_SITE_NAME = 120
private const val MAX_SITE_ADDRESS = 300

/** Times typed in the company's timezone: `HH:mm` on [day], a time before the start being the next day. */
@Serializable
internal data class ShiftTimesRequest(
    /** Only for a new shift. */
    val employeeId: String? = null,
    /** `yyyy-MM-dd`; a correction keeps the shift's day when left empty. */
    val day: String = "",
    val start: String = "",
    val end: String = "",
    val breaks: List<BreakTimesRequest> = emptyList(),
    val reason: String = "",
    val note: String? = null,
    /** Only for a new shift, when the company logs jobs: what it was for. */
    val job: JobRequest? = null,
) {
    fun times(day: LocalDate, zone: TimeZone): ShiftTimes? {
        val startTime = ShiftMath.parseTime(start) ?: return null
        val endTime = ShiftMath.parseTime(end) ?: return null
        val breakTimes = breaks.map { b -> (ShiftMath.parseTime(b.start) ?: return null) to (ShiftMath.parseTime(b.end) ?: return null) }
        return ShiftMath.wallTimes(day, startTime, endTime, breakTimes, zone)
    }
}

@Serializable internal data class BreakTimesRequest(val start: String = "", val end: String = "")

@Serializable internal data class ApproveRequest(val ids: List<String> = emptyList())

/** Omitted fields keep the current rule. */
@Serializable
internal data class SettingsRequest(
    val location: String? = null,
    val geofence: String? = null,
    val biometric: String? = null,
    val maxShiftHours: Int? = null,
    val dailyHours: Int? = null,
    val weeklyHours: Int? = null,
    val workLog: String? = null,
    val jobNeeds: JobNeedsRequest? = null,
    val schedule: String? = null,
    val lateGraceMinutes: Int? = null,
    val earlyLeaveGraceMinutes: Int? = null,
    val earlyClockInMinutes: Int? = null,
    val noShowMinutes: Int? = null,
) {
    /** Null when a policy name isn't one. */
    fun applyTo(current: TimesheetSettings): TimesheetSettings? = current.copy(
        location = location?.let { locationPolicyOf(it) ?: return null } ?: current.location,
        geofence = geofence?.let { geofencePolicyOf(it) ?: return null } ?: current.geofence,
        biometric = biometric?.let { biometricPolicyOf(it) ?: return null } ?: current.biometric,
        maxShiftHours = maxShiftHours ?: current.maxShiftHours,
        dailyHours = dailyHours ?: current.dailyHours,
        weeklyHours = weeklyHours ?: current.weeklyHours,
        workLog = workLog?.let { workLogPolicyOf(it) ?: return null } ?: current.workLog,
        jobNeeds = jobNeeds?.let {
            JobNeeds(
                client = it.client ?: current.jobNeeds.client,
                project = it.project ?: current.jobNeeds.project,
                activity = it.activity ?: current.jobNeeds.activity,
                note = it.note ?: current.jobNeeds.note,
            )
        } ?: current.jobNeeds,
        schedule = schedule?.let { schedulePolicyOf(it) ?: return null } ?: current.schedule,
        lateGraceMinutes = lateGraceMinutes ?: current.lateGraceMinutes,
        earlyLeaveGraceMinutes = earlyLeaveGraceMinutes ?: current.earlyLeaveGraceMinutes,
        earlyClockInMinutes = earlyClockInMinutes ?: current.earlyClockInMinutes,
        noShowMinutes = noShowMinutes ?: current.noShowMinutes,
    )
}

/** Which parts a complete job has; a part left out keeps its rule. */
@Serializable
internal data class JobNeedsRequest(val client: Boolean? = null, val project: Boolean? = null, val activity: Boolean? = null, val note: Boolean? = null)

/** Omitted fields keep what is stored; an empty address or client clears it. */
@Serializable
internal data class SiteRequest(
    val name: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusM: Int? = null,
    val clientId: String? = null,
    val active: Boolean? = null,
)

internal class OptionalId(val value: ObjectId?)

/** The session behind the time clock's gate, for a change only admins make: 403 `not_allowed` for anyone else. */
internal suspend fun ApplicationCall.timesheetAdmin(): DashboardContext? {
    val ctx = moduleContext()
    if (!ctx.isAdmin()) {
        respondError(HttpStatusCode.Forbidden, "not_allowed")
        return null
    }
    return ctx
}

internal fun DashboardContext.who(): String = user?.email ?: "operator"

/** A query id that may be absent; null after answering 400 when it's present but malformed. */
internal suspend fun ApplicationCall.optionalId(name: String): OptionalId? {
    val raw = request.queryParameters[name]?.takeIf { it.isNotBlank() } ?: return OptionalId(null)
    return raw.toObjectIdOrNull()?.let(::OptionalId) ?: run {
        respondError(HttpStatusCode.BadRequest, "invalid_$name")
        null
    }
}

internal fun String.parseDay(): LocalDate? = runCatching { LocalDate.parse(trim()) }.getOrNull()

internal suspend fun view(
    mongo: MongoModule,
    ctx: DashboardContext,
    rules: TimesheetSettings,
    clock: () -> Instant,
    shifts: List<Shift>,
) = ShiftView(ctx.tenant.zone(), rules, clock(), ShiftView.employees(mongo, ctx.tenant.id, shifts))

internal suspend fun ApplicationCall.respondTransition(
    mongo: MongoModule,
    ctx: DashboardContext,
    rules: TimesheetSettings,
    clock: () -> Instant,
    transition: Transition?,
) {
    when (transition) {
        null -> respond(HttpStatusCode.NotFound)
        is Transition.Refused -> respondError(if (transition.error in CONFLICTS) HttpStatusCode.Conflict else HttpStatusCode.BadRequest, transition.error)
        is Transition.Ok -> respond(view(mongo, ctx, rules, clock, listOf(transition.shift)).dto(transition.shift, detail = true))
    }
}

/** Errors about the shift's state rather than the times sent. */
private val CONFLICTS = setOf("shift_open", "not_clocked_in", "already_approved", "conflict", "work_log_off")

/** [base] changed by [request], or null after answering 400 with the first problem. */
private suspend fun ApplicationCall.siteFrom(mongo: MongoModule, ctx: DashboardContext, request: SiteRequest, base: WorkSite): WorkSite? {
    val name = request.name?.trim() ?: base.name
    val problem = when {
        name.isEmpty() -> "name_required"
        name.length > MAX_SITE_NAME -> "name_too_long"
        (request.address?.trim()?.length ?: 0) > MAX_SITE_ADDRESS -> "address_too_long"
        request.radiusM != null && request.radiusM !in WorkSiteRepository.RADIUS_M -> "invalid_radius"
        else -> null
    }
    if (problem != null) {
        respondError(HttpStatusCode.BadRequest, problem)
        return null
    }
    val latitude = request.latitude ?: base.latitude
    val longitude = request.longitude ?: base.longitude
    if (!Geofence.validCoordinates(latitude, longitude)) {
        respondError(HttpStatusCode.BadRequest, "invalid_location")
        return null
    }
    val clientId = when (val raw = request.clientId?.trim()) {
        null -> base.clientId
        "" -> null
        else -> raw.toObjectIdOrNull()?.takeIf { ClientRepository(mongo, ctx.tenant.id).findById(it) != null } ?: run {
            respondError(HttpStatusCode.BadRequest, "client_not_found")
            return null
        }
    }
    return base.copy(
        name = name,
        address = request.address?.let { it.trim().takeIf(String::isNotEmpty) } ?: base.address.takeIf { request.address == null },
        latitude = latitude,
        longitude = longitude,
        radiusM = request.radiusM ?: base.radiusM,
        clientId = clientId,
        active = request.active ?: base.active,
    )
}
