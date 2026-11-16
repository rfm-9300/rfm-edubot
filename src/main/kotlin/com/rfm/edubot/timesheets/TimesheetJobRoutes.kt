package com.rfm.edubot.timesheets

import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.moduleContext
import com.rfm.edubot.dashboard.toObjectIdOrNull
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.timesheets.model.Activity
import com.rfm.edubot.timesheets.model.TimesheetSettings
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

/**
 * The team's side of jobs, mounted inside the time clock's gate: what a job can name, changing how a shift's time
 * splits between jobs (with a reason), the hours by job and their CSV, and the company's activities (admins write).
 */
internal fun Route.timesheetJobRoutes(mongo: MongoModule, projects: JobProjects, clock: () -> Instant) {
    val settings = TimesheetSettingsRepository(mongo)
    val workLog = WorkLogService(mongo, projects)
    suspend fun rules(ctx: DashboardContext): TimesheetSettings = settings.get(ctx.tenant.id).forCompany(ctx.tenant)

    get("/job-options") {
        call.respond(workLog.options(call.moduleContext().tenant))
    }
    put("/shifts/{id}/segments") {
        val ctx = call.moduleContext()
        val shifts = ShiftRepository(mongo, ctx.tenant.id)
        val shift = call.parameters["id"]?.toObjectIdOrNull()?.let { shifts.findById(it) } ?: return@put call.respond(HttpStatusCode.NotFound)
        val request = call.receiveOrNull<SegmentsRequest>() ?: return@put call.respondInvalidBody()
        val rules = rules(ctx)
        if (!rules.logsJobs) return@put call.respondError(HttpStatusCode.Conflict, "work_log_off")
        val drafts = when (val d = workLog.drafts(ctx.tenant, shift, request.segments)) {
            is Outcome.Refused -> return@put call.respondRefusal(d)
            is Outcome.Ok -> d.value
        }
        val zone = ctx.tenant.zone()
        call.respondTransition(mongo, ctx, rules, clock, shifts.update(shift.id) { ShiftRules.teamJobs(it, drafts, ctx.who(), request.reason, rules, zone, clock()) })
    }
    get("/jobs") {
        val ctx = call.moduleContext()
        val (from, to, filter) = call.workQuery(ctx, clock) ?: return@get
        val rules = rules(ctx)
        val (rows, employees) = workLog.rows(ctx.tenant, from, to, filter, clock())
        call.respond(workLog.report(from, to, rows, employees, rules, ctx.tenant.zone()))
    }
    get("/jobs.csv") {
        val ctx = call.moduleContext()
        val (from, to, filter) = call.workQuery(ctx, clock) ?: return@get
        val rules = rules(ctx)
        val (rows, employees) = workLog.rows(ctx.tenant, from, to, filter, clock())
        val csv = TimesheetExport.workLog(rows, employees, rules, ctx.tenant.zone(), ctx.tenant.locale)
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "work-log-$from-$to.csv").toString(),
        )
        call.respondText(csv, ContentType.Text.CSV.withCharset(Charsets.UTF_8))
    }

    get("/activities") {
        val ctx = call.moduleContext()
        call.respond(ActivityRepository(mongo, ctx.tenant.id).list().map { it.dto() })
    }
    post("/activities") {
        val ctx = call.timesheetAdmin() ?: return@post
        val request = call.receiveOrNull<ActivityRequest>() ?: return@post call.respondInvalidBody()
        val activities = ActivityRepository(mongo, ctx.tenant.id)
        if (activities.count() >= ActivityRepository.MAX_ACTIVITIES) return@post call.respondError(HttpStatusCode.Conflict, "too_many_activities")
        val now = clock()
        val draft = Activity(tenantId = ctx.tenant.id, name = "", createdAt = now, updatedAt = now)
        val activity = call.activityFrom(activities, request, draft) ?: return@post
        call.respond(HttpStatusCode.Created, activities.create(activity).dto())
    }
    patch("/activities/{id}") {
        val ctx = call.timesheetAdmin() ?: return@patch
        val activities = ActivityRepository(mongo, ctx.tenant.id)
        val existing = call.parameters["id"]?.toObjectIdOrNull()?.let { activities.findById(it) } ?: return@patch call.respond(HttpStatusCode.NotFound)
        val request = call.receiveOrNull<ActivityRequest>() ?: return@patch call.respondInvalidBody()
        val activity = call.activityFrom(activities, request, existing.copy(updatedAt = clock())) ?: return@patch
        val saved = activities.replace(activity) ?: return@patch call.respond(HttpStatusCode.NotFound)
        call.respond(saved.dto())
    }
    delete("/activities/{id}") {
        val ctx = call.timesheetAdmin() ?: return@delete
        val activities = ActivityRepository(mongo, ctx.tenant.id)
        val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@delete call.respond(HttpStatusCode.NotFound)
        activities.findById(id) ?: return@delete call.respond(HttpStatusCode.NotFound)
        if (ShiftRepository(mongo, ctx.tenant.id).usesActivity(id) || PlannedShiftRepository(mongo, ctx.tenant.id).usesActivity(id)) {
            return@delete call.respondError(HttpStatusCode.Conflict, "activity_in_use")
        }
        activities.delete(id)
        call.respond(mapOf("deleted" to true))
    }
}

/** Omitted fields keep what is stored; an empty code clears it. */
@Serializable
internal data class ActivityRequest(val name: String? = null, val code: String? = null, val billable: Boolean? = null, val active: Boolean? = null)

private data class WorkQuery(val from: LocalDate, val to: LocalDate, val filter: WorkFilter)

/** The period (this week by default) and filters of a work log query; null after answering 400. */
private suspend fun ApplicationCall.workQuery(ctx: DashboardContext, clock: () -> Instant): WorkQuery? {
    val weekStart = ShiftMath.weekStart(ShiftMath.localDay(clock(), ctx.tenant.zone()))
    val (from, to) = dayRange(weekStart, weekStart.plus(6, DateTimeUnit.DAY)) ?: return null
    val employeeId = optionalId("employeeId") ?: return null
    val clientId = optionalId("clientId") ?: return null
    val projectId = optionalId("projectId") ?: return null
    val activityId = optionalId("activityId") ?: return null
    return WorkQuery(from, to, WorkFilter(employeeId.value, clientId.value, projectId.value, activityId.value))
}

/** [base] changed by [request], or null after answering 400 or 409 with the first problem. */
private suspend fun ApplicationCall.activityFrom(activities: ActivityRepository, request: ActivityRequest, base: Activity): Activity? {
    val name = request.name?.trim() ?: base.name
    val code = request.code?.let { it.trim().takeIf(String::isNotEmpty) } ?: base.code.takeIf { request.code == null }
    val problem = when {
        name.isEmpty() -> "name_required"
        name.length > ActivityRepository.MAX_NAME -> "name_too_long"
        (code?.length ?: 0) > ActivityRepository.MAX_CODE -> "code_too_long"
        else -> null
    }
    if (problem != null) {
        respondError(HttpStatusCode.BadRequest, problem)
        return null
    }
    if (activities.list().any { it.id != base.id && it.name.equals(name, ignoreCase = true) }) {
        respondError(HttpStatusCode.Conflict, "name_taken")
        return null
    }
    return base.copy(name = name, code = code, billable = request.billable ?: base.billable, active = request.active ?: base.active)
}
