package com.rfm.edubot.dashboard

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** `/overview`: Home, the landing page every company has. */
internal object OverviewRoutes : DashboardFeature(DashboardModules.OVERVIEW) {
    override fun Route.routes(deps: DashboardDeps) {
        get("/overview") {
            val ctx = call.moduleContext()
            val extended = call.request.queryParameters["extended"] == "1"
            call.respond(OverviewService(deps.mongo).build(ctx.tenant, extended, ctx.assistantOwnerKey()))
        }
    }
}
