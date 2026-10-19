package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.crm.api.createStandardItem
import com.rfm.edubot.crm.api.updateStandardItem
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** `/crm/standard-items`: the catalog of services and materials that quotes, invoices and bookings pick from. */
internal object CatalogRoutes : DashboardFeature(DashboardModules.CATALOG) {
    override fun Route.routes(deps: DashboardDeps) {
        route("/crm") {
            get("/standard-items") {
                val crm = deps.crm(call)
                call.respond(crm.standardItems.search(call.request.queryParameters["q"], call.request.queryParameters["type"]))
            }
            post("/standard-items") {
                call.createStandardItem(deps.crm(call).standardItems)
            }
            post("/standard-items/{id}") {
                val crm = deps.crm(call)
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                call.updateStandardItem(crm.standardItems, id)
            }
            delete("/standard-items/{id}") {
                val crm = deps.crm(call)
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                if (!crm.standardItems.delete(id)) return@delete call.respond(HttpStatusCode.NotFound)
                call.respond(mapOf("deleted" to true))
            }
        }
    }
}
