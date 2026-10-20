package com.rfm.edubot.building

import com.rfm.edubot.building.model.Project
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
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * The page a client follows their project from, without an account: `/app/share/{token}`, under `/app` so the
 * production proxy forwards it as it is. It shows the progress, the plan and what the team shared, never money,
 * issues, notes or people, and only while the company is active and the link is on.
 */
fun Route.projectShareRoutes(mongo: MongoModule, tenants: TenantRepository, storagePath: () -> String, clock: () -> Instant = SystemClock::now) {
    val store = PhotoStore(storagePath)

    suspend fun ApplicationCall.shared(): Pair<Project, Tenant>? {
        val project = parameters["token"]?.let { ProjectRepository.byShareToken(mongo, it) }
        val tenant = project?.let { tenants.findById(it.tenantId) }?.takeIf { it.status == TenantStatus.ACTIVE }
        if (project == null || tenant == null) {
            respond(HttpStatusCode.NotFound)
            return null
        }
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
        val today = clock().toLocalDateTime(tenant.zone()).date
        val photos = SitePhotoRepository(mongo, tenant.id).list(project.id, sharedOnly = true, limit = MAX_PHOTOS)
        val updates = SiteLogRepository(mongo, tenant.id).list(project.id, null, null, limit = MAX_UPDATES * 3).filter { it.shared }.take(MAX_UPDATES)
        val byLog = photos.filter { it.logId != null }.groupBy { it.logId }
        val days = approvedEffect(ChangeOrderRepository(mongo, tenant.id).list(project.id)).days
        val template = tenant.documentTemplate
        call.noIndex()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(
            SharedProjectDto(
                company = template.companyName.ifBlank { tenant.name },
                hasLogo = template.logoPath?.let { Files.isRegularFile(Path.of(it)) } == true,
                locale = tenant.locale,
                number = project.number,
                name = project.name,
                status = project.status.name,
                address = project.address,
                progressPct = ProjectProgress.actualPct(project).roundToInt().coerceIn(0, 100),
                plannedStart = (project.plannedStart ?: ProjectProgress.plannedWindow(project)?.first)?.toString(),
                plannedEnd = ProjectProgress.revisedEnd(project, days)?.toString(),
                actualStart = project.actualStart?.toString(),
                actualEnd = project.actualEnd?.toString(),
                phases = project.phases.map { phase ->
                    val state = when (ProjectProgress.phaseState(phase, today)) {
                        PhaseState.DONE -> PhaseState.DONE
                        PhaseState.NOT_STARTED -> PhaseState.NOT_STARTED
                        else -> if (phase.progressPct > 0) PhaseState.IN_PROGRESS else PhaseState.NOT_STARTED
                    }
                    SharedPhaseDto(phase.name, phase.plannedStart?.toString(), phase.plannedEnd?.toString(), phase.progressPct.coerceIn(0, 100), state.name)
                },
                updates = updates.map { entry ->
                    SharedUpdateDto(entry.day.toString(), entry.weather?.name, entry.workDone, byLog[entry.id].orEmpty().map { SharedPhotoDto(it.id.toHexString(), it.caption, it.day.toString()) })
                },
                photos = photos.map { SharedPhotoDto(it.id.toHexString(), it.caption, it.day.toString()) },
                updatedAt = (listOf(project.updatedAt) + updates.map { it.updatedAt } + photos.map { it.createdAt }).max().toString(),
            ),
        )
    }
    get("/app/share/{token}/photos/{id}") {
        val (project, tenant) = call.shared() ?: return@get
        val photo = call.parameters["id"]?.let { runCatching { ObjectId(it) }.getOrNull() }
            ?.let { SitePhotoRepository(mongo, tenant.id).findById(it) }
            ?.takeIf { it.projectId == project.id && it.shared }
            ?: return@get call.respond(HttpStatusCode.NotFound)
        val bytes = store.read(photo, thumb = call.request.queryParameters["size"] == "thumb") ?: return@get call.respond(HttpStatusCode.NotFound)
        call.noIndex()
        call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
        call.respondBytes(bytes, ContentType.Image.JPEG)
    }
    get("/app/share/{token}/logo") {
        val (_, tenant) = call.shared() ?: return@get
        val path = tenant.documentTemplate.logoPath?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf { Files.isRegularFile(it) }
            ?: return@get call.respond(HttpStatusCode.NotFound)
        val type = if (path.fileName.toString().endsWith(".png", ignoreCase = true)) ContentType.Image.PNG else ContentType.Image.JPEG
        call.respondBytes(Files.readAllBytes(path), type)
    }
}

private const val MAX_PHOTOS = 60
private const val MAX_UPDATES = 30

private fun ApplicationCall.noIndex() {
    response.header("X-Robots-Tag", "noindex, nofollow")
    response.header("Referrer-Policy", "no-referrer")
}
