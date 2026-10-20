package com.rfm.edubot.dashboard.building

import com.rfm.edubot.building.api.ChangeOrderRequest
import com.rfm.edubot.building.api.ChangeStatusRequest
import com.rfm.edubot.building.api.changeDtos
import com.rfm.edubot.building.model.ChangeOrderStatus
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.http.enumValue
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

/** `/building/changes`: change orders (work added or taken out), sent to the client and approved or rejected. */
internal object ChangeOrderRoutes : DashboardFeature(DashboardModules.CHANGE_ORDERS) {
    override fun Route.routes(deps: DashboardDeps) {
        route("/building") {
            get("/changes") {
                val b = deps.building(call)
                call.respond(b.records.changeDtos(b.records.changes.list(call.projectFilter(), call.statusFilter<ChangeOrderStatus>())))
            }
            post("/changes") {
                val b = deps.building(call)
                val request = call.receive<ChangeOrderRequest>()
                call.respondOutcome(b.changes.create(projectOf(request.projectId), request.toDraft(), b.stamp), HttpStatusCode.Created) {
                    b.records.changeDtos(listOf(it)).single()
                }
            }
            get("/changes/{id}") {
                val b = deps.building(call)
                val change = b.records.changes.findById(call.idParam("id")) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(b.records.changeDtos(listOf(change)).single())
            }
            patch("/changes/{id}") {
                val b = deps.building(call)
                val id = call.idParam("id")
                call.respondOutcome(b.changes.update(id, call.receive<ChangeOrderRequest>().toDraft(), b.stamp)) { b.records.changeDtos(listOf(it)).single() }
            }
            post("/changes/{id}/status") {
                val b = deps.building(call)
                val id = call.idParam("id")
                val request = call.receive<ChangeStatusRequest>()
                val target = enumValue<ChangeOrderStatus>(request.status.trim().uppercase(), "status")
                call.respondOutcome(b.changes.setStatus(id, target, request.decidedBy, request.note, b.stamp)) { b.records.changeDtos(listOf(it)).single() }
            }
            delete("/changes/{id}") {
                val b = deps.building(call)
                call.respondOutcome(b.changes.delete(call.idParam("id"))) { DeletedDto(true) }
            }
        }
    }
}
