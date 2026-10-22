package com.rfm.edubot.dashboard.building

import com.rfm.edubot.building.BuildingRecords
import com.rfm.edubot.building.ChangeOrderRepository
import com.rfm.edubot.building.ChangeOrderService
import com.rfm.edubot.building.Stamp
import com.rfm.edubot.building.api.ChangeDecisionRequest
import com.rfm.edubot.building.api.sharedDto
import com.rfm.edubot.building.model.ChangeOrder
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.dashboard.settings.logoFile
import com.rfm.edubot.dashboard.settings.respondLogo
import com.rfm.edubot.events.Actor
import com.rfm.edubot.events.ActorContext
import com.rfm.edubot.events.ActorType
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import com.rfm.edubot.timesheets.zone
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.withContext

/**
 * The page a client approves or rejects a change order from, without an account: `/app/share/change/{token}`,
 * beside the project's page. It shows the change as it was sent and, once decided, the decision; it works
 * while the company is active and until the change goes back to draft.
 */
internal fun Route.changeApprovalRoutes(mongo: MongoModule, tenants: TenantRepository) {
    class Approval(val change: ChangeOrder, val project: Project, val tenant: Tenant) {
        fun dto(change: ChangeOrder = this.change) = change.sharedDto(project, tenant, hasLogo = tenant.documentTemplate.logoFile() != null)
    }

    suspend fun ApplicationCall.approval(): Approval? {
        val change = parameters["token"]?.let { ChangeOrderRepository.byApprovalToken(mongo, it) }
        val tenant = change?.let { tenants.findById(it.tenantId) }?.takeIf { it.status == TenantStatus.ACTIVE }
        val project = tenant?.let { BuildingRecords(mongo, it.id).projects.findById(change.projectId) }
        if (change == null || tenant == null || project == null) {
            respond(HttpStatusCode.NotFound)
            return null
        }
        noIndex()
        response.header(HttpHeaders.CacheControl, "no-store")
        return Approval(change, project, tenant)
    }

    get("/app/share/change/{token}") {
        val html = this::class.java.classLoader.getResource("share/change.html")?.readText() ?: return@get call.respond(HttpStatusCode.NotFound)
        call.noIndex()
        call.respondText(html, ContentType.Text.Html)
    }
    get("/app/share/change/{token}/data") {
        val approval = call.approval() ?: return@get
        call.respond(approval.dto())
    }
    post("/app/share/change/{token}/decision") {
        val approval = call.approval() ?: return@post
        val request = call.receive<ChangeDecisionRequest>()
        val name = request.name.trim()
        val stamp = Stamp(name, SystemClock.now(), approval.tenant.zone())
        val outcome = withContext(ActorContext(Actor(ActorType.CLIENT, name = name.take(MAX_ACTOR_NAME)))) {
            ChangeOrderService(BuildingRecords(mongo, approval.tenant.id))
                .decideByLink(approval.change.id, call.parameters["token"].orEmpty(), request.decision, request.name, request.note, stamp)
        }
        call.respondOutcome(outcome) { approval.dto(it) }
    }
    get("/app/share/change/{token}/logo") {
        val approval = call.approval() ?: return@get
        call.respondLogo(approval.tenant.documentTemplate)
    }
}

private const val MAX_ACTOR_NAME = 160
