package com.rfm.edubot.dashboard

import com.rfm.edubot.dashboard.overview.ServiceInsightsOverview
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.TenantTimeZones
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
        requireModules(DashboardModules.SERVICES) {
            // Home's Service insights card; `months` is one of ServiceInsightsOverview.PERIODS, anything else the default.
            get("/overview/services") {
                val ctx = call.moduleContext()
                val months = call.request.queryParameters["months"]?.toIntOrNull()
                    ?.takeIf { it in ServiceInsightsOverview.PERIODS } ?: ServiceInsightsOverview.DEFAULT_MONTHS
                val window = OverviewMath.window(SystemClock.now(), TenantTimeZones.normalize(ctx.tenant.timezone))
                val employees = DashboardModules.EMPLOYEES in DashboardModules.effectiveFor(ctx.tenant)
                call.respond(ServiceInsightsOverview(deps.mongo).insights(ctx.tenant.id, window, months, employees))
            }
        }
    }
}
