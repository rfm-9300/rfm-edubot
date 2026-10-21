package com.rfm.edubot.dashboard.building

import com.rfm.edubot.building.PhotoStore
import com.rfm.edubot.building.SitePhotoService
import com.rfm.edubot.building.api.PhaseProgressRequest
import com.rfm.edubot.building.api.PhasesRequest
import com.rfm.edubot.building.api.PhotoUpdateRequest
import com.rfm.edubot.building.api.ProjectRequest
import com.rfm.edubot.building.api.costsDto
import com.rfm.edubot.building.api.dto
import com.rfm.edubot.building.api.projectDtos
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.ProjectStatus
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.isAdmin
import com.rfm.edubot.dashboard.requireModule
import com.rfm.edubot.http.ApiErrors
import com.rfm.edubot.http.ErrorBody
import com.rfm.edubot.http.idParam
import com.rfm.edubot.http.idValue
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * `/building/projects`: projects with their phases and the link their client follows them from, their costs (the
 * bills with payments, the hours with the time clock), and their photos (`/building/photos`), which the diary and
 * issues pages upload too. Only an admin deletes a project.
 */
internal object ProjectRoutes : DashboardFeature(DashboardModules.PROJECTS) {
    override fun Route.routes(deps: DashboardDeps) {
        route("/building") {
            get("/projects") {
                val b = deps.building(call)
                val quoteId = call.request.queryParameters["quoteId"]?.takeIf { it.isNotBlank() }?.let { idValue(it, "quoteId") }
                val list = if (quoteId != null) b.records.projects.forQuote(quoteId) else b.records.projects.list(call.statusFilter<ProjectStatus>())
                call.respond(b.records.projectDtos(list, b.stamp.today))
            }
            post("/projects") {
                val b = deps.building(call)
                val request = call.receive<ProjectRequest>()
                val quoteId = request.quoteId?.takeIf { it.isNotBlank() }?.let { idValue(it, "quoteId") }
                call.respondOutcome(b.projects.create(request.toDraft(), quoteId, b.stamp), HttpStatusCode.Created) { b.detail(it) }
            }
            get("/projects/{id}") {
                val b = deps.building(call)
                val project = b.records.projects.findById(call.idParam("id")) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(b.detail(project))
            }
            patch("/projects/{id}") {
                val b = deps.building(call)
                val id = call.idParam("id")
                call.respondOutcome(b.projects.update(id, call.receive<ProjectRequest>().toDraft(), b.stamp)) { b.detail(it) }
            }
            get("/projects/{id}/costs") {
                val b = deps.building(call)
                val withBills = b.ctx.requireModule(DashboardModules.PAYMENTS)
                val withHours = b.ctx.requireModule(DashboardModules.TIMESHEETS)
                val costs = b.costs.costs(call.idParam("id"), withBills, withHours) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(b.records.costsDto(costs))
            }
            delete("/projects/{id}") {
                val b = deps.building(call)
                if (!b.ctx.isAdmin()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorBody("not_allowed"))
                call.respondOutcome(b.projects.delete(call.idParam("id"), b.tenant.slug)) { DeletedDto(true) }
            }
            put("/projects/{id}/phases") {
                val b = deps.building(call)
                val id = call.idParam("id")
                call.respondOutcome(b.projects.setPhases(id, call.receive<PhasesRequest>().toDrafts(), b.stamp)) { b.detail(it) }
            }
            patch("/projects/{id}/phases/{phaseId}") {
                val b = deps.building(call)
                val id = call.idParam("id")
                val progress = call.receive<PhaseProgressRequest>().progressPct
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorBody(ApiErrors.INVALID_BODY, "progressPct"))
                call.respondOutcome(b.projects.setPhaseProgress(id, call.parameters["phaseId"].orEmpty(), progress, b.stamp)) { b.detail(it) }
            }
            post("/projects/{id}/share") {
                val b = deps.building(call)
                call.respondOutcome(b.projects.share(call.idParam("id"), b.stamp)) { b.detail(it) }
            }
            post("/projects/{id}/share/rotate") {
                val b = deps.building(call)
                call.respondOutcome(b.projects.rotateShare(call.idParam("id"), b.stamp)) { b.detail(it) }
            }
            delete("/projects/{id}/share") {
                val b = deps.building(call)
                call.respondOutcome(b.projects.stopSharing(call.idParam("id"), b.stamp)) { b.detail(it) }
            }

            get("/projects/{id}/photos") {
                val b = deps.building(call)
                val project = b.records.projects.findById(call.idParam("id")) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(b.records.photos.list(project.id).map { it.dto() })
            }
            post("/projects/{id}/photos") {
                val b = deps.building(call)
                val id = call.idParam("id")
                val upload = call.receivePhoto() ?: return@post
                call.respondOutcome(b.photos.add(id, upload, b.tenant.slug, b.stamp), HttpStatusCode.Created) { it.dto() }
            }
            get("/photos/{id}") {
                val b = deps.building(call)
                val photo = b.records.photos.findById(call.idParam("id")) ?: return@get call.respond(HttpStatusCode.NotFound)
                val bytes = b.photos.bytes(photo, thumb = call.request.queryParameters["size"] == "thumb") ?: return@get call.respond(HttpStatusCode.NotFound)
                call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
                call.respondBytes(bytes, ContentType.Image.JPEG)
            }
            patch("/photos/{id}") {
                val b = deps.building(call)
                val id = call.idParam("id")
                val request = call.receive<PhotoUpdateRequest>()
                val photo = b.photos.edit(id, request.caption, request.shared) ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(photo.dto())
            }
            delete("/photos/{id}") {
                val b = deps.building(call)
                b.photos.delete(call.idParam("id")) ?: return@delete call.respond(HttpStatusCode.NotFound)
                call.respond(DeletedDto(true))
            }
        }
    }
}

private suspend fun TenantBuilding.detail(project: Project) = records.projectDtos(listOf(project), stamp.today, detail = true).single()

/**
 * The photo the browser uploads, shrunk, as the `file` part with its `thumb`, and the photo's fields; or null
 * after answering why not: too large (413), missing (400) or not JPEG (415). The server never decodes an image.
 */
internal suspend fun ApplicationCall.receivePhoto(): SitePhotoService.Upload? {
    var full: ByteArray? = null
    var thumb: ByteArray? = null
    var tooLarge = false
    val fields = mutableMapOf<String, String>()
    receiveMultipart().forEachPart { part ->
        when (part) {
            is PartData.FileItem -> {
                val limit = if (part.name == "thumb") PhotoStore.MAX_THUMB_BYTES else PhotoStore.MAX_FULL_BYTES
                val bytes = part.provider().readRemaining(limit + 1L).readByteArray()
                if (bytes.size > limit) tooLarge = true
                if (part.name == "thumb") thumb = bytes else full = bytes
            }
            is PartData.FormItem -> fields[part.name.orEmpty()] = part.value
            else -> Unit
        }
        part.dispose()
    }
    suspend fun refuse(status: HttpStatusCode, code: String): SitePhotoService.Upload? {
        respond(status, ErrorBody(code))
        return null
    }
    if (tooLarge) return refuse(HttpStatusCode.PayloadTooLarge, "photo_too_large")
    val image = full?.takeIf { it.isNotEmpty() } ?: return refuse(HttpStatusCode.BadRequest, "no_file")
    // A photo small enough is its own thumbnail.
    val small = thumb?.takeIf { it.isNotEmpty() } ?: image.takeIf { it.size <= PhotoStore.MAX_THUMB_BYTES }
        ?: return refuse(HttpStatusCode.BadRequest, "no_thumbnail")
    if (!PhotoStore.isJpeg(image) || !PhotoStore.isJpeg(small)) return refuse(HttpStatusCode.UnsupportedMediaType, "photo_type")
    return SitePhotoService.Upload(
        image = image,
        thumb = small,
        logId = fields["logId"],
        issueId = fields["issueId"],
        phaseId = fields["phaseId"],
        caption = fields["caption"],
        shared = fields["shared"]?.toBooleanStrictOrNull(),
        takenAt = fields["takenAt"],
    )
}
