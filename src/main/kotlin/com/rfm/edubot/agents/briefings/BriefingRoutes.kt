package com.rfm.edubot.agents.briefings

import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.actorId
import com.rfm.edubot.agents.actorName
import com.rfm.edubot.agents.canDecide
import com.rfm.edubot.agents.canManageAgents
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.AgentStatus
import com.rfm.edubot.agents.runtime.AgentRuntime
import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.ModuleDenial
import com.rfm.edubot.dashboard.moduleContext
import com.rfm.edubot.dashboard.requireModules
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId

/**
 * The Briefings page's API under `/app/api/briefings`: the agents that message each employee on a schedule, by day
 * and person ([BriefingService]). Everyone in the team sees them; sending is for those who manage agents, and
 * approving for whoever the briefing's approvers allow.
 */
fun Route.briefingRoutes(agents: AgentsModule, runtime: AgentRuntime) {
    val briefings = BriefingService(agents, runtime)

    authenticate("dashboard") {
        route("/app/api/briefings") {
            requireModules(DashboardModules.AGENTS, DashboardModules.EMPLOYEES, denial = ModuleDenial.MODULE_DISABLED) {
                get {
                    val ctx = call.moduleContext()
                    call.respond(BriefingListDto(briefings.today(ctx.tenant).toString(), ctx.canManageAgents(), briefings.list(ctx.tenant)))
                }

                get("/{id}") {
                    val ctx = call.moduleContext()
                    val agent = call.briefingParam(agents, ctx) ?: return@get
                    val today = briefings.today(ctx.tenant)
                    val day = call.request.queryParameters["day"]?.takeIf { it.isNotBlank() }?.let { raw ->
                        runCatching { LocalDate.parse(raw) }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_day"))
                    } ?: today
                    // A day ahead has nothing to show yet: its people's day can still change.
                    if (day > today) return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "future_day"))
                    call.respond(briefings.day(ctx.tenant, agent, day, ctx.canManageAgents()))
                }

                post("/{id}/send") {
                    val ctx = call.moduleContext()
                    if (!ctx.canManageAgents()) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                    val agent = call.briefingParam(agents, ctx) ?: return@post
                    if (agent.status != AgentStatus.ACTIVE) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "inactive"))
                    val request = runCatching { call.receive<BriefingSendRequest>() }.getOrDefault(BriefingSendRequest())
                    call.respond(briefings.send(ctx.tenant, agent, request.employeeIds, request.again, ctx.actorId()))
                }

                post("/{id}/approve") {
                    val ctx = call.moduleContext()
                    val agent = call.briefingParam(agents, ctx) ?: return@post
                    val request = runCatching { call.receive<BriefingApproveRequest>() }.getOrDefault(BriefingApproveRequest())
                    val approved = briefings.approve(ctx.tenant, agent, request.employeeIds, { ctx.canDecide(it) }, ctx.actorId(), ctx.actorName())
                    call.respond(BriefingApproveDto(approved))
                }
            }
        }
    }
}

/** The briefing in the path: an agent of this company that runs on a schedule once per employee. */
private suspend fun ApplicationCall.briefingParam(agents: AgentsModule, ctx: DashboardContext): Agent? {
    val id = parameters["id"]?.let { runCatching { ObjectId(it) }.getOrNull() } ?: run {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_id"))
        return null
    }
    return agents.agents.findById(ctx.tenant.id, id)?.takeIf { BriefingService.isBriefing(it) } ?: run {
        respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))
        return null
    }
}
