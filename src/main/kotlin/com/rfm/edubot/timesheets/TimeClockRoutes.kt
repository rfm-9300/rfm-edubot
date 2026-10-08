package com.rfm.edubot.timesheets

import com.rfm.edubot.dashboard.EmployeePortal
import com.rfm.edubot.dashboard.portalSession
import com.rfm.edubot.dashboard.toObjectIdOrNull
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.serialization.Serializable

/**
 * An employee's own time clock (`/app/api/portal/time…`): clock in and out, breaks, a forgotten clock-out,
 * their shifts, and the phone that signs their punches. Their company needs `employees` and `timesheets`.
 */
fun Route.timeClockRoutes(mongo: MongoModule, notifications: NotificationRepository, clock: () -> Instant = SystemClock::now) {
    val timeClock = TimeClock(mongo, notifications, clock)
    authenticate("dashboard") {
        route("/app/api/portal/time") {
            get {
                val session = call.portalSession(EmployeePortal.MY_HOURS) ?: return@get
                call.respond(timeClock.status(session.tenant, session.employee))
            }
            get("/shifts") {
                val session = call.portalSession(EmployeePortal.MY_HOURS) ?: return@get
                val zone = session.tenant.zone()
                val now = timeClock.now()
                val today = ShiftMath.localDay(now, zone)
                val (from, to) = call.dayRange(today.minus(DEFAULT_HISTORY_DAYS, DateTimeUnit.DAY), today) ?: return@get
                val view = ShiftView(zone, timeClock.rules(session.tenant), now)
                val shifts = ShiftRepository(mongo, session.tenant.id).list(from, to, session.employee.id)
                call.respond(shifts.sortedByDescending { it.startAt }.map { view.dto(it, detail = true) })
            }
            post("/challenge") {
                val session = call.portalSession(EmployeePortal.MY_HOURS) ?: return@post
                call.respond(timeClock.challenge(session.tenant, session.employee))
            }
            post("/punches") {
                val session = call.portalSession(EmployeePortal.MY_HOURS) ?: return@post
                val request = call.receiveOrNull<PunchRequest>() ?: return@post call.respondInvalidBody()
                when (val outcome = timeClock.punch(session.tenant, session.employee, request)) {
                    is Outcome.Refused -> call.respondRefusal(outcome)
                    is Outcome.Ok -> {
                        val view = ShiftView(session.tenant.zone(), timeClock.rules(session.tenant), timeClock.now())
                        call.respond(HttpStatusCode.Created, PunchResultDto(timeClock.status(session.tenant, session.employee), view.dto(outcome.value, detail = true)))
                    }
                }
            }
            post("/shifts/{id}/close") {
                val session = call.portalSession(EmployeePortal.MY_HOURS) ?: return@post
                val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@post call.respond(HttpStatusCode.NotFound)
                val request = call.receiveOrNull<CloseShiftRequest>() ?: return@post call.respondInvalidBody()
                when (val outcome = timeClock.closeForgotten(session.tenant, session.employee, id, request.end)) {
                    is Outcome.Refused -> call.respondRefusal(outcome)
                    is Outcome.Ok -> {
                        val view = ShiftView(session.tenant.zone(), timeClock.rules(session.tenant), timeClock.now())
                        call.respond(PunchResultDto(timeClock.status(session.tenant, session.employee), view.dto(outcome.value, detail = true)))
                    }
                }
            }
            patch("/shifts/{id}") {
                val session = call.portalSession(EmployeePortal.MY_HOURS) ?: return@patch
                val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@patch call.respond(HttpStatusCode.NotFound)
                val request = call.receiveOrNull<NoteRequest>() ?: return@patch call.respondInvalidBody()
                when (val outcome = timeClock.note(session.tenant, session.employee, id, request.note)) {
                    is Outcome.Refused -> call.respondRefusal(outcome)
                    is Outcome.Ok -> call.respond(ShiftView(session.tenant.zone(), timeClock.rules(session.tenant), timeClock.now()).dto(outcome.value, detail = true))
                }
            }
            post("/devices") {
                val session = call.portalSession(EmployeePortal.MY_HOURS) ?: return@post
                val request = call.receiveOrNull<EnrollRequest>() ?: return@post call.respondInvalidBody()
                when (val outcome = timeClock.enroll(session.tenant, session.employee, session.ctx.user?.id, request)) {
                    is Outcome.Refused -> call.respondRefusal(outcome)
                    is Outcome.Ok -> call.respond(HttpStatusCode.Created, outcome.value.dto())
                }
            }
            delete("/devices/{keyId}") {
                val session = call.portalSession(EmployeePortal.MY_HOURS) ?: return@delete
                val keyId = call.parameters["keyId"].orEmpty()
                if (!timeClock.removeDevice(session.tenant, session.employee, keyId)) return@delete call.respond(HttpStatusCode.NotFound)
                call.respond(mapOf("removed" to true))
            }
        }
    }
}

private const val DEFAULT_HISTORY_DAYS = 30
private const val MAX_RANGE_DAYS = 93

/** `HH:mm` in the company's timezone, on the shift's day or the next. */
@Serializable internal data class CloseShiftRequest(val end: String = "", val reason: String = "")

@Serializable internal data class NoteRequest(val note: String? = null)

internal suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? = runCatching { receive<T>() }.getOrNull()

internal suspend fun ApplicationCall.respondInvalidBody() = respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))

internal suspend fun ApplicationCall.respondError(status: HttpStatusCode, error: String) = respond(status, mapOf("error" to error))

internal suspend fun ApplicationCall.respondRefusal(refused: Outcome.Refused) =
    respond(HttpStatusCode.fromValue(refused.status), PunchRefusalDto(refused.error, refused.siteName, refused.distanceM))

/**
 * `from` and `to` (`yyyy-MM-dd`, both included) from the query, or the defaults; null after answering 400 for
 * a malformed, inverted or longer than [MAX_RANGE_DAYS] range.
 */
internal suspend fun ApplicationCall.dayRange(defaultFrom: LocalDate, defaultTo: LocalDate): Pair<LocalDate, LocalDate>? {
    val params = request.queryParameters
    fun day(name: String, default: LocalDate): LocalDate? =
        params[name]?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() } ?: default.takeIf { params[name].isNullOrBlank() }
    val from = day("from", defaultFrom)
    val to = day("to", defaultTo)
    if (from == null || to == null || to < from) {
        respondError(HttpStatusCode.BadRequest, "invalid_range")
        return null
    }
    if (from.daysUntil(to) >= MAX_RANGE_DAYS) {
        respondError(HttpStatusCode.BadRequest, "range_too_long")
        return null
    }
    return from to to
}
