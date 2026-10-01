package com.rfm.edubot.agents

import com.rfm.edubot.agents.model.AgentSettings
import com.rfm.edubot.agents.model.PlatformAgentLimits
import com.rfm.edubot.agents.model.RunStatus
import com.rfm.edubot.tenant.TenantRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days

@Serializable private data class AdminAgentsDto(
    val agents: List<AgentDto>,
    val settings: AgentSettingsView,
    val runs7d: Map<String, Long>,
    val pendingApprovals: Long,
)

@Serializable private data class AgentSettingsView(val agentsPaused: Boolean, val maxActiveAgents: Int, val runsPerDay: Int, val emailSendsPerDay: Int, val companyPaused: Boolean)

@Serializable private data class LimitsRequest(val maxActiveAgents: Int? = null, val runsPerDay: Int? = null, val emailSendsPerDay: Int? = null)

private fun AgentSettings.view() = AgentSettingsView(platform.agentsPaused, platform.maxActiveAgents, platform.runsPerDay, platform.emailSendsPerDay, company.paused)

/** The backoffice's view of a company's agents: health, a kill switch and limits only operators set. */
fun Route.agentAdminRoutes(agents: AgentsModule, runtime: com.rfm.edubot.agents.runtime.AgentRuntime, tenants: TenantRepository) {
    authenticate("admin-jwt") {
        route("/admin/api/tenants/{slug}/agents") {
            get {
                val tenant = tenants.findBySlug(call.parameters["slug"].orEmpty()) ?: return@get call.respond(HttpStatusCode.NotFound)
                val since = agents.services.clock() - 7.days
                val runs7d = RunStatus.entries.associate { status -> status.name to agents.runs.countByStatus(tenant.id, listOf(status), since) }
                    .filterValues { it > 0 }
                call.respond(
                    AdminAgentsDto(
                        agents = agents.agents.list(tenant.id, includeArchived = true).map { it.dto() },
                        settings = agents.settings.get(tenant.id).view(),
                        runs7d = runs7d,
                        pendingApprovals = agents.approvals.countPending(tenant.id),
                    ),
                )
            }
            post("/pause") {
                val tenant = tenants.findBySlug(call.parameters["slug"].orEmpty()) ?: return@post call.respond(HttpStatusCode.NotFound)
                val settings = agents.settings.get(tenant.id)
                val saved = agents.settings.savePlatform(tenant.id, settings.platform.copy(agentsPaused = true))
                runtime.dispatcher.refresh(tenant.id)
                call.respond(saved.view())
            }
            post("/resume") {
                val tenant = tenants.findBySlug(call.parameters["slug"].orEmpty()) ?: return@post call.respond(HttpStatusCode.NotFound)
                val settings = agents.settings.get(tenant.id)
                val saved = agents.settings.savePlatform(tenant.id, settings.platform.copy(agentsPaused = false))
                runtime.dispatcher.refresh(tenant.id)
                call.respond(saved.view())
            }
            put("/limits") {
                val tenant = tenants.findBySlug(call.parameters["slug"].orEmpty()) ?: return@put call.respond(HttpStatusCode.NotFound)
                val request = runCatching { call.receive<LimitsRequest>() }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                val current = agents.settings.get(tenant.id).platform
                val limits = PlatformAgentLimits(
                    agentsPaused = current.agentsPaused,
                    maxActiveAgents = (request.maxActiveAgents ?: current.maxActiveAgents).coerceIn(0, 500),
                    runsPerDay = (request.runsPerDay ?: current.runsPerDay).coerceIn(0, 100_000),
                    emailSendsPerDay = (request.emailSendsPerDay ?: current.emailSendsPerDay).coerceIn(0, 2_000),
                )
                call.respond(agents.settings.savePlatform(tenant.id, limits).view())
            }
        }
    }
}
