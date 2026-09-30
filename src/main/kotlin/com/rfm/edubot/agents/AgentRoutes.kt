package com.rfm.edubot.agents

import com.rfm.edubot.agents.registry.AgentCatalog
import com.rfm.edubot.agents.runtime.AgentRuntime
import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.dashboardContext
import com.rfm.edubot.dashboard.requireModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** The Agents module's dashboard API under `/app/api/agents`. */
fun Route.agentRoutes(agents: AgentsModule, runtime: AgentRuntime) {
    authenticate("dashboard") {
        route("/app/api/agents") {
            get("/catalog") {
                val ctx = call.agentsContext() ?: return@get
                call.respond(AgentCatalog.build(agents.registry, agents.availability(ctx.tenant)))
            }
        }
    }
}

/** The caller's dashboard context when the company has the agents module; answers 403 otherwise. */
internal suspend fun ApplicationCall.agentsContext(): DashboardContext? {
    val ctx = dashboardContext() ?: run {
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    if (!ctx.requireModule(DashboardModules.AGENTS)) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "module_disabled"))
        return null
    }
    return ctx
}
