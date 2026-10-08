package com.rfm.edubot.timesheets

import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.dashboardContext
import com.rfm.edubot.dashboard.isAdmin
import com.rfm.edubot.dashboard.requireModule
import com.rfm.edubot.dashboard.toObjectIdOrNull
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.timesheets.model.Shift
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
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

/**
 * The team's side of the time clock (`/app/api/timesheets…`, module `timesheets`): who is working, the period's
 * shifts and totals, corrections (always with a reason, kept as history), approvals and the CSV export. The
 * rules, work sites and revoking phones are for admins (and operators opening the dashboard).
 */
fun Route.timesheetRoutes(mongo: MongoModule, clock: () -> Instant = SystemClock::now) {
    val settings = TimesheetSettingsRepository(mongo)
    authenticate("dashboard") {
        route("/app/api/timesheets") {
            get {
                val ctx = call.timesheetContext() ?: return@get
                val now = clock()
                val zone = ctx.tenant.zone()
                val weekStart = ShiftMath.weekStart(ShiftMath.localDay(now, zone))
                val (from, to) = call.dayRange(weekStart, weekStart.plus(6, DateTimeUnit.DAY)) ?: return@get
                val employeeId = call.optionalId("employeeId") ?: return@get
                val rules = settings.get(ctx.tenant.id)
                val shifts = ShiftRepository(mongo, ctx.tenant.id).list(from, to, employeeId.value)
                val employees = ShiftView.employees(mongo, ctx.tenant.id, shifts)
                val view = ShiftView(zone, rules, now, employees)
                val totals = Timesheets.totals(shifts, rules, now).map { t ->
                    val employee = employees[t.employeeId]
                    EmployeeTotalsDto(
                        employeeId = t.employeeId.toHexString(),
                        employeeName = employee?.name.orEmpty(),
                        employeeNumber = employee?.number.orEmpty(),
                        workedMinutes = t.workedMinutes,
                        days = t.days,
                        overDailyMinutes = t.overDailyMinutes,
                        overWeeklyMinutes = t.overWeeklyMinutes,
                        toReview = t.toReview,
                        flagged = t.flagged,
                        open = t.open,
                    )
                }.sortedBy { it.employeeName.lowercase() }
                call.respond(TimesheetDto(from.toString(), to.toString(), shifts.map { view.dto(it) }, totals, rules.dto(canEdit = ctx.isAdmin())))
            }
            get("/board") {
                val ctx = call.timesheetContext() ?: return@get
                val now = clock()
                val open = ShiftRepository(mongo, ctx.tenant.id).openShifts()
                val view = ShiftView(ctx.tenant.zone(), settings.get(ctx.tenant.id), now, ShiftView.employees(mongo, ctx.tenant.id, open))
                call.respond(BoardDto(open.map { view.dto(it) }, now.toString()))
            }
            get("/shifts/{id}") {
                val ctx = call.timesheetContext() ?: return@get
                val shift = call.parameters["id"]?.toObjectIdOrNull()?.let { ShiftRepository(mongo, ctx.tenant.id).findById(it) }
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(view(mongo, ctx, settings, clock, listOf(shift)).dto(shift, detail = true))
            }
            post("/shifts") {
                val ctx = call.timesheetContext() ?: return@post
                val request = call.receiveOrNull<ShiftTimesRequest>() ?: return@post call.respondInvalidBody()
                val employee = request.employeeId?.toObjectIdOrNull()?.let { EmployeeRepository(mongo, ctx.tenant.id).findById(it) }
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "employee_not_found")
                val zone = ctx.tenant.zone()
                val day = request.day.parseDay() ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_day")
                val times = request.times(day, zone) ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_time")
                val now = clock()
                when (val t = ShiftRules.manual(ctx.tenant.id, employee.id, times, request.note, ctx.who(), request.reason, settings.get(ctx.tenant.id), zone, now)) {
                    is Transition.Refused -> call.respondError(HttpStatusCode.BadRequest, t.error)
                    is Transition.Ok -> {
                        ShiftRepository(mongo, ctx.tenant.id).insert(t.shift)
                        call.respond(HttpStatusCode.Created, view(mongo, ctx, settings, clock, listOf(t.shift)).dto(t.shift, detail = true))
                    }
                }
            }
            patch("/shifts/{id}") {
                val ctx = call.timesheetContext() ?: return@patch
                val shifts = ShiftRepository(mongo, ctx.tenant.id)
                val shift = call.parameters["id"]?.toObjectIdOrNull()?.let { shifts.findById(it) } ?: return@patch call.respond(HttpStatusCode.NotFound)
                val request = call.receiveOrNull<ShiftTimesRequest>() ?: return@patch call.respondInvalidBody()
                val zone = ctx.tenant.zone()
                val day = if (request.day.isBlank()) shift.day else request.day.parseDay() ?: return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_day")
                val times = request.times(day, zone) ?: return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_time")
                val rules = settings.get(ctx.tenant.id)
                call.respondTransition(mongo, ctx, settings, clock, shifts.update(shift.id) { ShiftRules.edit(it, times, ctx.who(), request.reason, rules, zone, clock()) })
            }
            post("/shifts/{id}/close") {
                val ctx = call.timesheetContext() ?: return@post
                val shifts = ShiftRepository(mongo, ctx.tenant.id)
                val shift = call.parameters["id"]?.toObjectIdOrNull()?.let { shifts.findById(it) } ?: return@post call.respond(HttpStatusCode.NotFound)
                val request = call.receiveOrNull<CloseShiftRequest>() ?: return@post call.respondInvalidBody()
                val time = ShiftMath.parseTime(request.end) ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_time")
                val zone = ctx.tenant.zone()
                val endAt = ShiftMath.endAfter(shift.startAt, time, zone)
                val rules = settings.get(ctx.tenant.id)
                call.respondTransition(mongo, ctx, settings, clock, shifts.update(shift.id) { ShiftRules.closeByTeam(it, endAt, ctx.who(), request.reason, rules, zone, clock()) })
            }
            post("/shifts/approve") {
                val ctx = call.timesheetContext() ?: return@post
                val request = call.receiveOrNull<ApproveRequest>() ?: return@post call.respondInvalidBody()
                if (request.ids.size > MAX_APPROVE) return@post call.respondError(HttpStatusCode.BadRequest, "too_many")
                val ids = request.ids.mapNotNull { it.toObjectIdOrNull() }.distinct()
                val shifts = ShiftRepository(mongo, ctx.tenant.id)
                val approved = ids.count { id -> shifts.update(id) { ShiftRules.approve(it, ctx.who(), clock()) } is Transition.Ok }
                call.respond(ApprovedDto(approved))
            }
            get("/export.csv") {
                val ctx = call.timesheetContext() ?: return@get
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
                val ctx = call.timesheetContext() ?: return@get
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
                call.respond(settings.save(ctx.tenant.id, next.copy(updatedAt = clock(), updatedBy = ctx.who())).dto(canEdit = true))
            }

            get("/sites") {
                val ctx = call.timesheetContext() ?: return@get
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
                val ctx = call.timesheetContext() ?: return@get
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
) {
    /** Null when a policy name isn't one. */
    fun applyTo(current: TimesheetSettings): TimesheetSettings? = current.copy(
        location = location?.let { locationPolicyOf(it) ?: return null } ?: current.location,
        geofence = geofence?.let { geofencePolicyOf(it) ?: return null } ?: current.geofence,
        biometric = biometric?.let { biometricPolicyOf(it) ?: return null } ?: current.biometric,
        maxShiftHours = maxShiftHours ?: current.maxShiftHours,
        dailyHours = dailyHours ?: current.dailyHours,
        weeklyHours = weeklyHours ?: current.weeklyHours,
    )
}

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

private class OptionalId(val value: ObjectId?)

/** The team with the time clock module; an employee's own sign-in never has it. */
private suspend fun ApplicationCall.timesheetContext(): DashboardContext? {
    val ctx = dashboardContext()?.takeIf { it.requireModule(DashboardModules.TIMESHEETS) }
    if (ctx == null) respond(HttpStatusCode.Forbidden)
    return ctx
}

private suspend fun ApplicationCall.timesheetAdmin(): DashboardContext? {
    val ctx = timesheetContext() ?: return null
    if (!ctx.isAdmin()) {
        respondError(HttpStatusCode.Forbidden, "not_allowed")
        return null
    }
    return ctx
}

private fun DashboardContext.who(): String = user?.email ?: "operator"

/** A query id that may be absent; null after answering 400 when it's present but malformed. */
private suspend fun ApplicationCall.optionalId(name: String): OptionalId? {
    val raw = request.queryParameters[name]?.takeIf { it.isNotBlank() } ?: return OptionalId(null)
    return raw.toObjectIdOrNull()?.let(::OptionalId) ?: run {
        respondError(HttpStatusCode.BadRequest, "invalid_$name")
        null
    }
}

private fun String.parseDay(): LocalDate? = runCatching { LocalDate.parse(trim()) }.getOrNull()

private suspend fun view(
    mongo: MongoModule,
    ctx: DashboardContext,
    settings: TimesheetSettingsRepository,
    clock: () -> Instant,
    shifts: List<Shift>,
) = ShiftView(ctx.tenant.zone(), settings.get(ctx.tenant.id), clock(), ShiftView.employees(mongo, ctx.tenant.id, shifts))

private suspend fun ApplicationCall.respondTransition(
    mongo: MongoModule,
    ctx: DashboardContext,
    settings: TimesheetSettingsRepository,
    clock: () -> Instant,
    transition: Transition?,
) {
    when (transition) {
        null -> respond(HttpStatusCode.NotFound)
        is Transition.Refused -> respondError(if (transition.error in CONFLICTS) HttpStatusCode.Conflict else HttpStatusCode.BadRequest, transition.error)
        is Transition.Ok -> respond(view(mongo, ctx, settings, clock, listOf(transition.shift)).dto(transition.shift, detail = true))
    }
}

/** Errors about the shift's state rather than the times sent. */
private val CONFLICTS = setOf("shift_open", "not_clocked_in", "already_approved", "conflict")

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
