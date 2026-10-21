package com.rfm.edubot.dashboard.building

import com.rfm.edubot.building.BuildingRecords
import com.rfm.edubot.building.PhotoStore
import com.rfm.edubot.building.ProjectRepository
import com.rfm.edubot.building.api.sharedProjectDto
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.dashboard.settings.logoFile
import com.rfm.edubot.dashboard.settings.respondLogo
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
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId

/**
 * The page a client follows their project from, without an account: `/app/share/{token}`, under `/app` so the
 * production proxy forwards it as it is. It shows the progress, the plan and what the team shared, never money,
 * issues, notes or people, and only while the company is active and the link is on.
 */
internal fun Route.projectShareRoutes(mongo: MongoModule, tenants: TenantRepository, storagePath: () -> String) {
    val store = PhotoStore(storagePath)

    suspend fun ApplicationCall.shared(): Pair<Project, Tenant>? {
        val project = parameters["token"]?.let { ProjectRepository.byShareToken(mongo, it) }
        val tenant = project?.let { tenants.findById(it.tenantId) }?.takeIf { it.status == TenantStatus.ACTIVE }
        if (project == null || tenant == null) {
            respond(HttpStatusCode.NotFound)
            return null
        }
        noIndex()
        return project to tenant
    }

    get("/app/share/assets/{asset}") {
        val asset = call.parameters["asset"].orEmpty()
        if (!asset.endsWith(".js")) return@get call.respond(HttpStatusCode.NotFound)
        val bytes = this::class.java.classLoader.getResource("share/$asset")?.readBytes() ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondBytes(bytes, ContentType.Application.JavaScript)
    }
    get("/app/share/{token}") {
        val html = this::class.java.classLoader.getResource("share/index.html")?.readText() ?: return@get call.respond(HttpStatusCode.NotFound)
        call.noIndex()
        call.respondText(html, ContentType.Text.Html)
    }
    get("/app/share/{token}/data") {
        val (project, tenant) = call.shared() ?: return@get
        val today = SystemClock.now().toLocalDateTime(tenant.zone()).date
        val dto = BuildingRecords(mongo, tenant.id).sharedProjectDto(project, tenant, today, hasLogo = tenant.documentTemplate.logoFile() != null)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(dto)
    }
    get("/app/share/{token}/photos/{id}") {
        val (project, tenant) = call.shared() ?: return@get
        val photo = call.parameters["id"]?.takeIf(ObjectId::isValid)?.let { BuildingRecords(mongo, tenant.id).photos.findById(ObjectId(it)) }
            ?.takeIf { it.projectId == project.id && it.shared }
            ?: return@get call.respond(HttpStatusCode.NotFound)
        val bytes = store.read(photo, thumb = call.request.queryParameters["size"] == "thumb") ?: return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
        call.respondBytes(bytes, ContentType.Image.JPEG)
    }
    get("/app/share/{token}/logo") {
        val (_, tenant) = call.shared() ?: return@get
        call.respondLogo(tenant.documentTemplate)
    }
}

/** The client's pages are for one person: no search engine indexes them, and their address isn't passed on. */
internal fun ApplicationCall.noIndex() {
    response.header("X-Robots-Tag", "noindex, nofollow")
    response.header("Referrer-Policy", "no-referrer")
}
