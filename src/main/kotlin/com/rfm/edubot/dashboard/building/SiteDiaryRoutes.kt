package com.rfm.edubot.dashboard.building

import com.rfm.edubot.building.api.SiteLogRequest
import com.rfm.edubot.building.api.logDtos
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.http.ErrorBody
import com.rfm.edubot.http.dateValue
import com.rfm.edubot.http.idParam
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus

/**
 * `/building/diary`: the site diary, one entry per project and day. Without a project or dates the list is the
 * company's last [DEFAULT_DAYS] days.
 */
internal object SiteDiaryRoutes : DashboardFeature(DashboardModules.SITE_DIARY) {
    private const val DEFAULT_DAYS = 30

    override fun Route.routes(deps: DashboardDeps) {
        route("/building") {
            get("/diary") {
                val b = deps.building(call)
                val params = call.request.queryParameters
                val projectId = call.projectFilter()
                val from = params["from"]?.takeIf { it.isNotBlank() }?.let { dateValue(it, "from") }
                    ?: b.stamp.today.minus(DEFAULT_DAYS, DateTimeUnit.DAY).takeIf { projectId == null }
                val to = params["to"]?.takeIf { it.isNotBlank() }?.let { dateValue(it, "to") }
                if (from != null && to != null && to < from) return@get call.respond(HttpStatusCode.BadRequest, ErrorBody("invalid_range"))
                call.respond(b.records.logDtos(b.records.logs.list(projectId, from, to)))
            }
            post("/diary") {
                val b = deps.building(call)
                val request = call.receive<SiteLogRequest>()
                val day = request.day?.takeIf { it.isNotBlank() }?.let { dateValue(it, "day") }
                call.respondOutcome(b.diary.create(projectOf(request.projectId), request.toDraft(day), b.stamp), HttpStatusCode.Created) {
                    b.records.logDtos(listOf(it)).single()
                }
            }
            get("/diary/{id}") {
                val b = deps.building(call)
                val entry = b.records.logs.findById(call.idParam("id")) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(b.records.logDtos(listOf(entry)).single())
            }
            patch("/diary/{id}") {
                val b = deps.building(call)
                val id = call.idParam("id")
                val request = call.receive<SiteLogRequest>()
                val day = request.day?.takeIf { it.isNotBlank() }?.let { dateValue(it, "day") }
                call.respondOutcome(b.diary.update(id, request.toDraft(day), b.stamp)) { b.records.logDtos(listOf(it)).single() }
            }
            delete("/diary/{id}") {
                val b = deps.building(call)
                if (!b.diary.delete(call.idParam("id"))) return@delete call.respond(HttpStatusCode.NotFound)
                call.respond(DeletedDto(true))
            }
        }
    }
}
