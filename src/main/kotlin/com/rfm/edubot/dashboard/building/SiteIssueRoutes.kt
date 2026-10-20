package com.rfm.edubot.dashboard.building

import com.rfm.edubot.building.api.IssueRequest
import com.rfm.edubot.building.api.issueDtos
import com.rfm.edubot.building.model.IssueStatus
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
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

/** `/building/issues`: punch items, defects and safety problems on site, with their status history and photos. */
internal object SiteIssueRoutes : DashboardFeature(DashboardModules.SITE_ISSUES) {
    override fun Route.routes(deps: DashboardDeps) {
        route("/building") {
            get("/issues") {
                val b = deps.building(call)
                val list = b.records.issues.list(call.projectFilter(), call.statusFilter<IssueStatus>())
                call.respond(b.records.issueDtos(list, b.stamp.today))
            }
            post("/issues") {
                val b = deps.building(call)
                val request = call.receive<IssueRequest>()
                call.respondOutcome(b.issues.create(projectOf(request.projectId), request.toDraft(), b.stamp), HttpStatusCode.Created) {
                    b.records.issueDtos(listOf(it), b.stamp.today, withPhotos = true).single()
                }
            }
            get("/issues/{id}") {
                val b = deps.building(call)
                val issue = b.records.issues.findById(call.idParam("id")) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(b.records.issueDtos(listOf(issue), b.stamp.today, withPhotos = true).single())
            }
            patch("/issues/{id}") {
                val b = deps.building(call)
                val id = call.idParam("id")
                call.respondOutcome(b.issues.update(id, call.receive<IssueRequest>().toDraft(), b.stamp)) {
                    b.records.issueDtos(listOf(it), b.stamp.today, withPhotos = true).single()
                }
            }
            delete("/issues/{id}") {
                val b = deps.building(call)
                if (!b.issues.delete(call.idParam("id"))) return@delete call.respond(HttpStatusCode.NotFound)
                call.respond(DeletedDto(true))
            }
        }
    }
}
