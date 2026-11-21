package com.rfm.edubot.dashboard.building

import com.rfm.edubot.building.BuildingDocuments
import com.rfm.edubot.building.BuildingRecords
import com.rfm.edubot.building.MeasurementRepository
import com.rfm.edubot.building.MeasurementService
import com.rfm.edubot.building.Outcome
import com.rfm.edubot.building.PhotoStore
import com.rfm.edubot.building.Stamp
import com.rfm.edubot.building.api.ChangeDecisionRequest
import com.rfm.edubot.building.api.sharedDto
import com.rfm.edubot.building.model.Measurement
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
 * The page a client approves or disputes a measurement from, without an account: `/app/share/measurement/{token}`,
 * beside the project's and the change's pages. It shows the work measured as it was sent and, once answered, the
 * answer; it works while the company is active and until the draft is edited, reopened or deleted.
 */
internal fun Route.measurementApprovalRoutes(mongo: MongoModule, tenants: TenantRepository, storagePath: () -> String) {
    class Approval(val measurement: Measurement, val project: Project, val tenant: Tenant) {
        val records = BuildingRecords(mongo, tenant.id)

        fun dto(measurement: Measurement = this.measurement) =
            measurement.sharedDto(project, tenant, hasLogo = tenant.documentTemplate.logoFile() != null)
    }

    suspend fun ApplicationCall.approval(): Approval? {
        val measurement = parameters["token"]?.let { MeasurementRepository.byApprovalToken(mongo, it) }
        val tenant = measurement?.let { tenants.findById(it.tenantId) }?.takeIf { it.status == TenantStatus.ACTIVE }
        val project = tenant?.let { BuildingRecords(mongo, it.id).projects.findById(measurement.projectId) }
        if (measurement == null || tenant == null || project == null) {
            respond(HttpStatusCode.NotFound)
            return null
        }
        noIndex()
        response.header(HttpHeaders.CacheControl, "no-store")
        return Approval(measurement, project, tenant)
    }

    get("/app/share/measurement/{token}") {
        val html = this::class.java.classLoader.getResource("share/measurement.html")?.readText() ?: return@get call.respond(HttpStatusCode.NotFound)
        call.noIndex()
        call.respondText(html, ContentType.Text.Html)
    }
    get("/app/share/measurement/{token}/data") {
        val approval = call.approval() ?: return@get
        call.respond(approval.dto())
    }
    post("/app/share/measurement/{token}/decision") {
        val approval = call.approval() ?: return@post
        val request = call.receive<ChangeDecisionRequest>()
        val name = request.name.trim()
        val stamp = Stamp(name, SystemClock.now(), approval.tenant.zone())
        val outcome = withContext(ActorContext(Actor(ActorType.CLIENT, name = name.take(MAX_ACTOR_NAME)))) {
            MeasurementService(approval.records)
                .decideByLink(approval.measurement.id, call.parameters["token"].orEmpty(), request.decision, request.name, request.note, call.linkProof(), stamp)
        }
        when (outcome) {
            is Outcome.Done -> call.respond(approval.dto(outcome.value))
            is Outcome.Refused -> call.respondRefused(outcome)
            Outcome.NotFound -> call.respond(HttpStatusCode.NotFound)
        }
    }
    // The measurement sheet (auto de medição) as it stands, to keep or print and sign.
    get("/app/share/measurement/{token}/pdf") {
        val approval = call.approval() ?: return@get
        val stamp = Stamp(approval.measurement.createdBy, SystemClock.now(), approval.tenant.zone())
        val template = approval.tenant.documentTemplate.withCompanyFallback(approval.tenant.name)
        val document = BuildingDocuments(approval.records, PhotoStore(storagePath)).measurement(approval.measurement.id, template, approval.tenant.locale, stamp)
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondPdf(document)
    }
    get("/app/share/measurement/{token}/logo") {
        val approval = call.approval() ?: return@get
        call.respondLogo(approval.tenant.documentTemplate)
    }
}

private const val MAX_ACTOR_NAME = 160
