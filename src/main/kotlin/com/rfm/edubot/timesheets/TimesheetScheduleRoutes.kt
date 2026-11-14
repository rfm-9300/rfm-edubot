package com.rfm.edubot.timesheets

import com.rfm.edubot.dashboard.isAdmin
import com.rfm.edubot.dashboard.moduleContext
import com.rfm.edubot.dashboard.toObjectIdOrNull
import com.rfm.edubot.persistence.MongoModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.plus

/**
 * The team's planner, mounted inside the time clock's gate: planned shifts with how each went, planning several
 * days at once, changes, copying a week and excusing an absence. Everyone on the team plans, as everyone corrects
 * shifts; the rules stay with admins.
 */
internal fun Route.timesheetScheduleRoutes(mongo: MongoModule, schedule: ScheduleService, clock: () -> Instant) {
    get("/schedule") {
        val ctx = call.moduleContext()
        val weekStart = ShiftMath.weekStart(ShiftMath.localDay(clock(), ctx.tenant.zone()))
        val (from, to) = call.dayRange(weekStart, weekStart.plus(6, DateTimeUnit.DAY)) ?: return@get
        val employeeId = call.optionalId("employeeId") ?: return@get
        call.respond(schedule.view(ctx.tenant, from, to, employeeId.value, canEdit = ctx.isAdmin()))
    }
    post("/schedule") {
        val ctx = call.moduleContext()
        val request = call.receiveOrNull<PlanRequest>() ?: return@post call.respondInvalidBody()
        when (val outcome = schedule.create(ctx.tenant, request, ctx.who())) {
            is Outcome.Refused -> call.respondRefusal(outcome)
            is Outcome.Ok -> call.respond(HttpStatusCode.Created, schedule.dtos(ctx.tenant, outcome.value, schedule.rules(ctx.tenant), clock()))
        }
    }
    post("/schedule/copy") {
        val ctx = call.moduleContext()
        val request = call.receiveOrNull<CopyWeekRequest>() ?: return@post call.respondInvalidBody()
        when (val outcome = schedule.copyWeek(ctx.tenant, request, ctx.who())) {
            is Outcome.Refused -> call.respondRefusal(outcome)
            is Outcome.Ok -> call.respond(outcome.value)
        }
    }
    get("/schedule/{id}") {
        val ctx = call.moduleContext()
        val plan = call.parameters["id"]?.toObjectIdOrNull()?.let { schedule.find(ctx.tenant, it) } ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(schedule.dtos(ctx.tenant, listOf(plan), schedule.rules(ctx.tenant), clock()).single())
    }
    patch("/schedule/{id}") {
        val ctx = call.moduleContext()
        val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@patch call.respond(HttpStatusCode.NotFound)
        val request = call.receiveOrNull<PlanRequest>() ?: return@patch call.respondInvalidBody()
        when (val outcome = schedule.update(ctx.tenant, id, request)) {
            is Outcome.Refused -> call.respondRefusal(outcome)
            is Outcome.Ok -> call.respond(schedule.dtos(ctx.tenant, listOf(outcome.value), schedule.rules(ctx.tenant), clock()).single())
        }
    }
    delete("/schedule/{id}") {
        val ctx = call.moduleContext()
        val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@delete call.respond(HttpStatusCode.NotFound)
        when (val outcome = schedule.delete(ctx.tenant, id)) {
            is Outcome.Refused -> call.respondRefusal(outcome)
            is Outcome.Ok -> call.respond(mapOf("deleted" to true))
        }
    }
    post("/schedule/{id}/excuse") {
        val ctx = call.moduleContext()
        val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@post call.respond(HttpStatusCode.NotFound)
        val request = call.receiveOrNull<ExcuseRequest>() ?: return@post call.respondInvalidBody()
        when (val outcome = schedule.excuse(ctx.tenant, id, request.reason, ctx.who())) {
            is Outcome.Refused -> call.respondRefusal(outcome)
            is Outcome.Ok -> call.respond(schedule.dtos(ctx.tenant, listOf(outcome.value), schedule.rules(ctx.tenant), clock()).single())
        }
    }
    delete("/schedule/{id}/excuse") {
        val ctx = call.moduleContext()
        val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@delete call.respond(HttpStatusCode.NotFound)
        when (val outcome = schedule.unexcuse(ctx.tenant, id)) {
            is Outcome.Refused -> call.respondRefusal(outcome)
            is Outcome.Ok -> call.respond(schedule.dtos(ctx.tenant, listOf(outcome.value), schedule.rules(ctx.tenant), clock()).single())
        }
    }
}
