package com.rfm.edubot.building

import com.rfm.edubot.admin.CreateLineItemRequest
import com.rfm.edubot.building.model.ChangeOrder
import com.rfm.edubot.building.model.ChangeOrderKind
import com.rfm.edubot.building.model.ChangeOrderStatus
import com.rfm.edubot.building.model.ContractLine
import com.rfm.edubot.building.model.IssueEvent
import com.rfm.edubot.building.model.IssueKind
import com.rfm.edubot.building.model.IssuePriority
import com.rfm.edubot.building.model.IssueStatus
import com.rfm.edubot.building.model.Measurement
import com.rfm.edubot.building.model.MeasurementStatus
import com.rfm.edubot.building.model.PhaseProgressChange
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.ProjectPhase
import com.rfm.edubot.building.model.ProjectShare
import com.rfm.edubot.building.model.ProjectStatus
import com.rfm.edubot.building.model.SiteIssue
import com.rfm.edubot.building.model.SiteLogEntry
import com.rfm.edubot.building.model.SitePhoto
import com.rfm.edubot.building.model.Weather
import com.rfm.edubot.crm.QuoteInvoicing
import com.rfm.edubot.crm.QuoteServicing
import com.rfm.edubot.crm.eurToCents
import com.rfm.edubot.crm.model.LineItem
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.dashboardContext
import com.rfm.edubot.dashboard.isAdmin
import com.rfm.edubot.dashboard.requireModule
import com.rfm.edubot.dashboard.toObjectIdOrNull
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.timesheets.receiveOrNull
import com.rfm.edubot.timesheets.respondError
import com.rfm.edubot.timesheets.respondInvalidBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.io.readByteArray
import org.bson.types.ObjectId
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The Building category (`/app/api/building…`): projects with their phases, photos and the client's link
 * (module `projects`), the site diary (`site-diary`), issues (`site-issues`), change orders (`change-orders`)
 * and measurements (`measurements`). Every route checks its own module; an employee's sign-in has none of them.
 */
fun Route.buildingRoutes(mongo: MongoModule, storagePath: () -> String, clock: () -> Instant = SystemClock::now) {
    val store = PhotoStore(storagePath)
    val now = { Instant.fromEpochMilliseconds(clock().toEpochMilliseconds()) }
    suspend fun ApplicationCall.building(module: String): Building? {
        val ctx = dashboardContext()?.takeIf { it.requireModule(module) }
        if (ctx == null) {
            respond(HttpStatusCode.Forbidden)
            return null
        }
        return Building(mongo, ctx, now())
    }

    authenticate("dashboard") {
        route("/app/api/building") {
            projectRoutes(store) { call.building(it) }
            diaryRoutes { call.building(it) }
            issueRoutes { call.building(it) }
            changeRoutes { call.building(it) }
            measurementRoutes { call.building(it) }
        }
    }
}

private typealias Gate = suspend io.ktor.server.routing.RoutingContext.(String) -> Building?

private fun Route.projectRoutes(store: PhotoStore, gate: Gate) {
    get("/projects") {
        val b = gate(DashboardModules.PROJECTS) ?: return@get
        val statuses = call.request.queryParameters["status"]?.split(',')?.mapNotNull { enumOf<ProjectStatus>(it) }.orEmpty()
        call.respond(b.projectDtos(b.projects.list(statuses)))
    }
    post("/projects") {
        val b = gate(DashboardModules.PROJECTS) ?: return@post
        val request = call.receiveOrNull<ProjectRequest>() ?: return@post call.respondInvalidBody()
        val quote = request.quoteId?.takeIf { it.isNotBlank() }?.let { raw ->
            raw.toObjectIdOrNull()?.let { b.quotes.findById(it) } ?: return@post call.respondError(HttpStatusCode.BadRequest, "quote_not_found")
        }
        if (quote != null && b.projects.forQuote(quote.id).isNotEmpty()) return@post call.respondError(HttpStatusCode.Conflict, "quote_in_project")
        val draft = Project(
            tenantId = b.tenantId,
            number = "",
            name = quote?.let { QuoteServicing.nameFrom(it.items).ifEmpty { it.number } }.orEmpty(),
            clientId = quote?.clientId,
            quoteId = quote?.id,
            contract = quote?.items.orEmpty().map { it.toContractLine(newId()) },
            createdAt = b.now,
            updatedAt = b.now,
        )
        val project = when (val made = b.projectFrom(request, draft, locked = false)) {
            is ProjectChange.Refused -> return@post call.respondError(HttpStatusCode.BadRequest, made.error)
            is ProjectChange.Ok -> made.project.copy(number = b.projects.nextNumber())
        }
        if (project.name.isBlank()) return@post call.respondError(HttpStatusCode.BadRequest, "name_required")
        b.projects.insert(project)
        if (quote != null && quote.status != QuoteStatus.ACEITO) b.quotes.update(quote.id, null, null, null, QuoteStatus.ACEITO)
        call.respond(HttpStatusCode.Created, b.projectDtos(listOf(project), detail = true).single())
    }
    get("/projects/{id}") {
        val b = gate(DashboardModules.PROJECTS) ?: return@get
        val project = b.project(call.parameters["id"]) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(b.projectDtos(listOf(project), detail = true).single())
    }
    patch("/projects/{id}") {
        val b = gate(DashboardModules.PROJECTS) ?: return@patch
        val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@patch call.respond(HttpStatusCode.NotFound)
        val request = call.receiveOrNull<ProjectRequest>() ?: return@patch call.respondInvalidBody()
        val locked = b.measurements.count(id) > 0
        val outcome = b.projects.update(id) { current -> b.projectFrom(request, current.copy(updatedAt = b.now), locked) }
        call.respondProject(b, outcome)
    }
    delete("/projects/{id}") {
        val b = gate(DashboardModules.PROJECTS) ?: return@delete
        if (!b.ctx.isAdmin()) return@delete call.respondError(HttpStatusCode.Forbidden, "not_allowed")
        val project = b.project(call.parameters["id"]) ?: return@delete call.respond(HttpStatusCode.NotFound)
        if (b.measurements.count(project.id) > 0) return@delete call.respondError(HttpStatusCode.Conflict, "in_use")
        b.logs.deleteForProject(project.id)
        b.issues.deleteForProject(project.id)
        b.changes.deleteForProject(project.id)
        b.photos.deleteForProject(project.id)
        store.deleteProject(b.ctx.tenant.slug, project.id)
        b.projects.delete(project.id)
        call.respond(mapOf("deleted" to true))
    }
    put("/projects/{id}/phases") {
        val b = gate(DashboardModules.PROJECTS) ?: return@put
        val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@put call.respond(HttpStatusCode.NotFound)
        val request = call.receiveOrNull<PhasesRequest>() ?: return@put call.respondInvalidBody()
        val outcome = b.projects.update(id) { current ->
            when (val phases = phasesFrom(request.phases, current.phases, b.today)) {
                is Parsed.Bad -> ProjectChange.Refused(phases.error)
                is Parsed.Good -> ProjectChange.Ok(current.copy(phases = phases.value, updatedAt = b.now))
            }
        }
        call.respondProject(b, outcome)
    }
    patch("/projects/{id}/phases/{phaseId}") {
        val b = gate(DashboardModules.PROJECTS) ?: return@patch
        val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@patch call.respond(HttpStatusCode.NotFound)
        val phaseId = call.parameters["phaseId"].orEmpty()
        val progress = call.receiveOrNull<PhaseProgressRequest>()?.progressPct ?: return@patch call.respondInvalidBody()
        if (progress !in 0..100) return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_progress")
        val outcome = b.projects.update(id) { current ->
            if (current.phases.none { it.id == phaseId }) return@update ProjectChange.Refused("phase_not_found")
            ProjectChange.Ok(current.copy(phases = current.phases.map { if (it.id == phaseId) it.withProgress(progress, b.today) else it }, updatedAt = b.now))
        }
        call.respondProject(b, outcome)
    }
    post("/projects/{id}/share") {
        val b = gate(DashboardModules.PROJECTS) ?: return@post
        val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@post call.respond(HttpStatusCode.NotFound)
        val outcome = b.projects.update(id) { current ->
            val share = current.share?.copy(enabled = true) ?: ProjectShare(shareToken(), true, b.now)
            ProjectChange.Ok(current.copy(share = share, updatedAt = b.now))
        }
        call.respondProject(b, outcome)
    }
    post("/projects/{id}/share/rotate") {
        val b = gate(DashboardModules.PROJECTS) ?: return@post
        val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@post call.respond(HttpStatusCode.NotFound)
        val outcome = b.projects.update(id) { current -> ProjectChange.Ok(current.copy(share = ProjectShare(shareToken(), true, b.now), updatedAt = b.now)) }
        call.respondProject(b, outcome)
    }
    delete("/projects/{id}/share") {
        val b = gate(DashboardModules.PROJECTS) ?: return@delete
        val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@delete call.respond(HttpStatusCode.NotFound)
        val outcome = b.projects.update(id) { current -> ProjectChange.Ok(current.copy(share = current.share?.copy(enabled = false), updatedAt = b.now)) }
        call.respondProject(b, outcome)
    }

    get("/projects/{id}/photos") {
        val b = gate(DashboardModules.PROJECTS) ?: return@get
        val project = b.project(call.parameters["id"]) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(b.photos.list(project.id).map { it.dto() })
    }
    post("/projects/{id}/photos") {
        val b = gate(DashboardModules.PROJECTS) ?: return@post
        val project = b.project(call.parameters["id"]) ?: return@post call.respond(HttpStatusCode.NotFound)
        var full: ByteArray? = null
        var thumb: ByteArray? = null
        var tooLarge = false
        val fields = mutableMapOf<String, String>()
        call.receiveMultipart().forEachPart { part ->
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
        if (tooLarge) return@post call.respondError(HttpStatusCode.PayloadTooLarge, "photo_too_large")
        val image = full?.takeIf { it.isNotEmpty() } ?: return@post call.respondError(HttpStatusCode.BadRequest, "no_file")
        val small = thumb?.takeIf { it.isNotEmpty() } ?: image.takeIf { it.size <= PhotoStore.MAX_THUMB_BYTES }
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "no_thumbnail")
        if (!PhotoStore.isJpeg(image) || !PhotoStore.isJpeg(small)) return@post call.respondError(HttpStatusCode.UnsupportedMediaType, "photo_type")
        if (b.photos.count(project.id) >= SitePhotoRepository.MAX_PER_PROJECT) return@post call.respondError(HttpStatusCode.Conflict, "too_many_photos")
        val log = fields["logId"]?.takeIf { it.isNotBlank() }?.let { raw ->
            raw.toObjectIdOrNull()?.let { b.logs.findById(it) }?.takeIf { it.projectId == project.id }
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "log_not_found")
        }
        val issue = fields["issueId"]?.takeIf { it.isNotBlank() }?.let { raw ->
            raw.toObjectIdOrNull()?.let { b.issues.findById(it) }?.takeIf { it.projectId == project.id }
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "issue_not_found")
        }
        val phaseId = fields["phaseId"]?.takeIf { it.isNotBlank() }?.also { phase ->
            if (project.phases.none { it.id == phase }) return@post call.respondError(HttpStatusCode.BadRequest, "phase_not_found")
        }
        val takenAt = fields["takenAt"]?.let { runCatching { Instant.parse(it.trim()) }.getOrNull() }?.takeIf { it <= b.now.plus(1, DateTimeUnit.DAY, b.zone) }
        val id = ObjectId()
        val (fullPath, thumbPath) = store.write(b.ctx.tenant.slug, project.id, id, image, small)
        val photo = SitePhoto(
            id = id,
            tenantId = b.tenantId,
            projectId = project.id,
            logId = log?.id,
            issueId = issue?.id,
            phaseId = phaseId,
            caption = fields["caption"]?.trim()?.take(MAX_CAPTION)?.ifEmpty { null },
            day = log?.day ?: takenAt?.let(b::localDay) ?: b.today,
            takenAt = takenAt,
            shared = fields["shared"]?.toBooleanStrictOrNull() ?: (issue == null),
            path = fullPath,
            thumbPath = thumbPath,
            bytes = image.size.toLong(),
            uploadedBy = b.who,
            createdAt = b.now,
        )
        b.photos.insert(photo)
        call.respond(HttpStatusCode.Created, photo.dto())
    }
    get("/photos/{id}") {
        val b = gate(DashboardModules.PROJECTS) ?: return@get
        val photo = call.parameters["id"]?.toObjectIdOrNull()?.let { b.photos.findById(it) } ?: return@get call.respond(HttpStatusCode.NotFound)
        val bytes = store.read(photo, thumb = call.request.queryParameters["size"] == "thumb") ?: return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
        call.respondBytes(bytes, ContentType.Image.JPEG)
    }
    patch("/photos/{id}") {
        val b = gate(DashboardModules.PROJECTS) ?: return@patch
        val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@patch call.respond(HttpStatusCode.NotFound)
        val request = call.receiveOrNull<PhotoUpdateRequest>() ?: return@patch call.respondInvalidBody()
        val photo = b.photos.update(id, request.caption?.trim()?.take(MAX_CAPTION), request.shared) ?: return@patch call.respond(HttpStatusCode.NotFound)
        call.respond(photo.dto())
    }
    delete("/photos/{id}") {
        val b = gate(DashboardModules.PROJECTS) ?: return@delete
        val photo = call.parameters["id"]?.toObjectIdOrNull()?.let { b.photos.delete(it) } ?: return@delete call.respond(HttpStatusCode.NotFound)
        store.delete(photo)
        call.respond(mapOf("deleted" to true))
    }
}

private fun Route.diaryRoutes(gate: Gate) {
    get("/diary") {
        val b = gate(DashboardModules.SITE_DIARY) ?: return@get
        val params = call.request.queryParameters
        val projectId = params["projectId"]?.takeIf { it.isNotBlank() }?.let { it.toObjectIdOrNull() ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_projectId") }
        val from = params["from"]?.takeIf { it.isNotBlank() }?.let { it.parseDay() ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_range") }
            ?: b.today.minus(DEFAULT_DIARY_DAYS, DateTimeUnit.DAY).takeIf { projectId == null }
        val to = params["to"]?.takeIf { it.isNotBlank() }?.let { it.parseDay() ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_range") }
        if (from != null && to != null && to < from) return@get call.respondError(HttpStatusCode.BadRequest, "invalid_range")
        call.respond(b.logDtos(b.logs.list(projectId, from, to)))
    }
    post("/diary") {
        val b = gate(DashboardModules.SITE_DIARY) ?: return@post
        val request = call.receiveOrNull<SiteLogRequest>() ?: return@post call.respondInvalidBody()
        val project = b.project(request.projectId) ?: return@post call.respondError(HttpStatusCode.BadRequest, "project_not_found")
        val day = request.day?.takeIf { it.isNotBlank() }?.let { it.parseDay() ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_day") } ?: b.today
        if (day > b.today) return@post call.respondError(HttpStatusCode.BadRequest, "future_day")
        b.logs.findByDay(project.id, day)?.let { return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "day_taken", "id" to it.id.toHexString())) }
        val draft = SiteLogEntry(
            tenantId = b.tenantId, projectId = project.id, day = day, workDone = "",
            createdBy = b.who, updatedBy = b.who, createdAt = b.now, updatedAt = b.now,
        )
        val entry = when (val parsed = logFrom(request, draft)) {
            is Parsed.Bad -> return@post call.respondError(HttpStatusCode.BadRequest, parsed.error)
            is Parsed.Good -> parsed.value
        }
        val progress = when (val moved = b.applyDiaryProgress(project.id, request.phaseProgress, day, emptyList())) {
            is Parsed.Bad -> return@post call.respondError(HttpStatusCode.BadRequest, moved.error)
            is Parsed.Good -> moved.value
        }
        val stored = entry.copy(progress = progress)
        if (!b.logs.insert(stored)) return@post call.respondError(HttpStatusCode.Conflict, "day_taken")
        call.respond(HttpStatusCode.Created, b.logDtos(listOf(stored)).single())
    }
    get("/diary/{id}") {
        val b = gate(DashboardModules.SITE_DIARY) ?: return@get
        val entry = call.parameters["id"]?.toObjectIdOrNull()?.let { b.logs.findById(it) } ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(b.logDtos(listOf(entry)).single())
    }
    patch("/diary/{id}") {
        val b = gate(DashboardModules.SITE_DIARY) ?: return@patch
        val entry = call.parameters["id"]?.toObjectIdOrNull()?.let { b.logs.findById(it) } ?: return@patch call.respond(HttpStatusCode.NotFound)
        val request = call.receiveOrNull<SiteLogRequest>() ?: return@patch call.respondInvalidBody()
        val day = request.day?.takeIf { it.isNotBlank() }?.let { it.parseDay() ?: return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_day") } ?: entry.day
        if (day > b.today) return@patch call.respondError(HttpStatusCode.BadRequest, "future_day")
        val edited = when (val parsed = logFrom(request, entry.copy(day = day, updatedBy = b.who, updatedAt = b.now))) {
            is Parsed.Bad -> return@patch call.respondError(HttpStatusCode.BadRequest, parsed.error)
            is Parsed.Good -> parsed.value
        }
        val progress = when (val moved = b.applyDiaryProgress(entry.projectId, request.phaseProgress, day, entry.progress)) {
            is Parsed.Bad -> return@patch call.respondError(HttpStatusCode.BadRequest, moved.error)
            is Parsed.Good -> moved.value
        }
        val stored = edited.copy(progress = progress)
        if (!b.logs.replace(stored)) return@patch call.respondError(HttpStatusCode.Conflict, "day_taken")
        call.respond(b.logDtos(listOf(stored)).single())
    }
    delete("/diary/{id}") {
        val b = gate(DashboardModules.SITE_DIARY) ?: return@delete
        val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@delete call.respond(HttpStatusCode.NotFound)
        if (!b.logs.delete(id)) return@delete call.respond(HttpStatusCode.NotFound)
        b.photos.detach("logId", id)
        call.respond(mapOf("deleted" to true))
    }
}

private fun Route.issueRoutes(gate: Gate) {
    get("/issues") {
        val b = gate(DashboardModules.SITE_ISSUES) ?: return@get
        val params = call.request.queryParameters
        val projectId = params["projectId"]?.takeIf { it.isNotBlank() }?.let { it.toObjectIdOrNull() ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_projectId") }
        val statuses = params["status"]?.split(',')?.mapNotNull { enumOf<IssueStatus>(it) }.orEmpty()
        call.respond(b.issueDtos(b.issues.list(projectId, statuses)))
    }
    post("/issues") {
        val b = gate(DashboardModules.SITE_ISSUES) ?: return@post
        val request = call.receiveOrNull<IssueRequest>() ?: return@post call.respondInvalidBody()
        val project = b.project(request.projectId) ?: return@post call.respondError(HttpStatusCode.BadRequest, "project_not_found")
        val draft = SiteIssue(
            tenantId = b.tenantId, projectId = project.id, number = "", title = "",
            createdBy = b.who, createdAt = b.now, updatedAt = b.now,
        )
        val issue = when (val parsed = b.issueFrom(request, draft, project)) {
            is Parsed.Bad -> return@post call.respondError(HttpStatusCode.BadRequest, parsed.error)
            is Parsed.Good -> parsed.value
        }
        if (issue.title.isBlank()) return@post call.respondError(HttpStatusCode.BadRequest, "title_required")
        val stored = issue.copy(
            number = b.issues.nextNumber(),
            history = listOf(IssueEvent(b.now, b.who, null, issue.status, request.note?.trim()?.take(MAX_NOTE)?.ifEmpty { null })),
        ).stamped(issue.status, b.now)
        b.issues.insert(stored)
        call.respond(HttpStatusCode.Created, b.issueDtos(listOf(stored), withPhotos = true).single())
    }
    get("/issues/{id}") {
        val b = gate(DashboardModules.SITE_ISSUES) ?: return@get
        val issue = call.parameters["id"]?.toObjectIdOrNull()?.let { b.issues.findById(it) } ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(b.issueDtos(listOf(issue), withPhotos = true).single())
    }
    patch("/issues/{id}") {
        val b = gate(DashboardModules.SITE_ISSUES) ?: return@patch
        val issue = call.parameters["id"]?.toObjectIdOrNull()?.let { b.issues.findById(it) } ?: return@patch call.respond(HttpStatusCode.NotFound)
        val request = call.receiveOrNull<IssueRequest>() ?: return@patch call.respondInvalidBody()
        val project = b.projects.findById(issue.projectId) ?: return@patch call.respond(HttpStatusCode.NotFound)
        val edited = when (val parsed = b.issueFrom(request, issue.copy(updatedAt = b.now), project)) {
            is Parsed.Bad -> return@patch call.respondError(HttpStatusCode.BadRequest, parsed.error)
            is Parsed.Good -> parsed.value
        }
        val note = request.note?.trim()?.take(MAX_NOTE)?.ifEmpty { null }
        val next = if (edited.status != issue.status) {
            edited.copy(history = issue.history + IssueEvent(b.now, b.who, issue.status, edited.status, note)).stamped(edited.status, b.now)
        } else {
            edited
        }
        val stored = b.issues.replace(next) ?: return@patch call.respond(HttpStatusCode.NotFound)
        call.respond(b.issueDtos(listOf(stored), withPhotos = true).single())
    }
    delete("/issues/{id}") {
        val b = gate(DashboardModules.SITE_ISSUES) ?: return@delete
        val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@delete call.respond(HttpStatusCode.NotFound)
        if (!b.issues.delete(id)) return@delete call.respond(HttpStatusCode.NotFound)
        b.photos.detach("issueId", id)
        call.respond(mapOf("deleted" to true))
    }
}

private fun Route.changeRoutes(gate: Gate) {
    get("/changes") {
        val b = gate(DashboardModules.CHANGE_ORDERS) ?: return@get
        val params = call.request.queryParameters
        val projectId = params["projectId"]?.takeIf { it.isNotBlank() }?.let { it.toObjectIdOrNull() ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_projectId") }
        val statuses = params["status"]?.split(',')?.mapNotNull { enumOf<ChangeOrderStatus>(it) }.orEmpty()
        call.respond(b.changeDtos(b.changes.list(projectId, statuses)))
    }
    post("/changes") {
        val b = gate(DashboardModules.CHANGE_ORDERS) ?: return@post
        val request = call.receiveOrNull<ChangeOrderRequest>() ?: return@post call.respondInvalidBody()
        val project = b.project(request.projectId) ?: return@post call.respondError(HttpStatusCode.BadRequest, "project_not_found")
        val draft = ChangeOrder(
            tenantId = b.tenantId, projectId = project.id, number = "", title = "", items = emptyList(), totalCents = 0,
            createdBy = b.who, createdAt = b.now, updatedAt = b.now,
        )
        val change = when (val parsed = changeFrom(request, draft)) {
            is Parsed.Bad -> return@post call.respondError(HttpStatusCode.BadRequest, parsed.error)
            is Parsed.Good -> parsed.value
        }
        if (change.title.isBlank()) return@post call.respondError(HttpStatusCode.BadRequest, "title_required")
        if (change.items.isEmpty()) return@post call.respondError(HttpStatusCode.BadRequest, "items_required")
        val stored = change.copy(number = b.changes.nextNumber())
        b.changes.insert(stored)
        call.respond(HttpStatusCode.Created, b.changeDtos(listOf(stored)).single())
    }
    get("/changes/{id}") {
        val b = gate(DashboardModules.CHANGE_ORDERS) ?: return@get
        val change = call.parameters["id"]?.toObjectIdOrNull()?.let { b.changes.findById(it) } ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(b.changeDtos(listOf(change)).single())
    }
    patch("/changes/{id}") {
        val b = gate(DashboardModules.CHANGE_ORDERS) ?: return@patch
        val change = call.parameters["id"]?.toObjectIdOrNull()?.let { b.changes.findById(it) } ?: return@patch call.respond(HttpStatusCode.NotFound)
        if (change.status != ChangeOrderStatus.DRAFT && change.status != ChangeOrderStatus.SENT) return@patch call.respondError(HttpStatusCode.Conflict, "change_locked")
        val request = call.receiveOrNull<ChangeOrderRequest>() ?: return@patch call.respondInvalidBody()
        val edited = when (val parsed = changeFrom(request, change.copy(updatedAt = b.now))) {
            is Parsed.Bad -> return@patch call.respondError(HttpStatusCode.BadRequest, parsed.error)
            is Parsed.Good -> parsed.value
        }
        if (edited.title.isBlank()) return@patch call.respondError(HttpStatusCode.BadRequest, "title_required")
        if (edited.items.isEmpty()) return@patch call.respondError(HttpStatusCode.BadRequest, "items_required")
        val stored = b.changes.replace(edited) ?: return@patch call.respond(HttpStatusCode.NotFound)
        call.respond(b.changeDtos(listOf(stored)).single())
    }
    post("/changes/{id}/status") {
        val b = gate(DashboardModules.CHANGE_ORDERS) ?: return@post
        val change = call.parameters["id"]?.toObjectIdOrNull()?.let { b.changes.findById(it) } ?: return@post call.respond(HttpStatusCode.NotFound)
        val request = call.receiveOrNull<ChangeStatusRequest>() ?: return@post call.respondInvalidBody()
        val target = enumOf<ChangeOrderStatus>(request.status) ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_status")
        val open = change.status == ChangeOrderStatus.DRAFT || change.status == ChangeOrderStatus.SENT
        val allowed = when (target) {
            ChangeOrderStatus.SENT, ChangeOrderStatus.APPROVED, ChangeOrderStatus.REJECTED -> open
            ChangeOrderStatus.DRAFT -> change.status != ChangeOrderStatus.DRAFT
        }
        if (!allowed) return@post call.respondError(HttpStatusCode.Conflict, "invalid_transition")
        if (target == ChangeOrderStatus.DRAFT && change.status == ChangeOrderStatus.APPROVED && measuredLines(change, b.measurements.list(change.projectId))) {
            return@post call.respondError(HttpStatusCode.Conflict, "change_measured")
        }
        val note = request.note?.trim()?.take(MAX_NOTE)?.ifEmpty { null }
        val next = when (target) {
            ChangeOrderStatus.SENT -> change.copy(status = target, sentAt = b.now)
            ChangeOrderStatus.APPROVED, ChangeOrderStatus.REJECTED -> change.copy(
                status = target,
                decidedAt = b.now,
                decidedBy = request.decidedBy?.trim()?.take(MAX_NAME)?.ifEmpty { null } ?: b.who,
                decisionNote = note,
            )
            ChangeOrderStatus.DRAFT -> change.copy(status = target, decidedAt = null, decidedBy = null, decisionNote = null)
        }.copy(updatedAt = b.now)
        val stored = b.changes.replace(next) ?: return@post call.respond(HttpStatusCode.NotFound)
        call.respond(b.changeDtos(listOf(stored)).single())
    }
    delete("/changes/{id}") {
        val b = gate(DashboardModules.CHANGE_ORDERS) ?: return@delete
        val change = call.parameters["id"]?.toObjectIdOrNull()?.let { b.changes.findById(it) } ?: return@delete call.respond(HttpStatusCode.NotFound)
        if (change.status == ChangeOrderStatus.APPROVED) return@delete call.respondError(HttpStatusCode.Conflict, "change_approved")
        b.changes.delete(change.id)
        call.respond(mapOf("deleted" to true))
    }
}

private fun Route.measurementRoutes(gate: Gate) {
    get("/measurements") {
        val b = gate(DashboardModules.MEASUREMENTS) ?: return@get
        val params = call.request.queryParameters
        val projectId = params["projectId"]?.takeIf { it.isNotBlank() }?.let { it.toObjectIdOrNull() ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_projectId") }
        val statuses = params["status"]?.split(',')?.mapNotNull { enumOf<MeasurementStatus>(it) }.orEmpty()
        call.respond(b.measurementDtos(b.measurements.list(projectId, statuses)))
    }
    post("/measurements") {
        val b = gate(DashboardModules.MEASUREMENTS) ?: return@post
        val request = call.receiveOrNull<MeasurementRequest>() ?: return@post call.respondInvalidBody()
        val project = b.project(request.projectId) ?: return@post call.respondError(HttpStatusCode.BadRequest, "project_not_found")
        val previous = b.measurements.latest(project.id)
        if (previous?.status == MeasurementStatus.DRAFT) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "measurement_open", "id" to previous.id.toHexString()))
        val periodEnd = request.periodEnd?.takeIf { it.isNotBlank() }?.let { it.parseDay() ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_day") } ?: b.today
        if (previous != null && periodEnd < previous.periodEnd) return@post call.respondError(HttpStatusCode.BadRequest, "period_before_previous")
        val quote = project.quoteId?.let { b.quotes.findById(it) }
        if (quote != null && quote.projectId != project.id && QuoteInvoicing.billedBy(quote, b.invoices, com.rfm.edubot.crm.ClientServiceRepository(b.mongo, b.tenantId)) != null) {
            return@post call.respondError(HttpStatusCode.Conflict, "quote_billed")
        }
        val billable = MeasurementMath.billableLines(project, b.changes.list(project.id))
        if (billable.isEmpty()) return@post call.respondError(HttpStatusCode.BadRequest, "nothing_to_measure")
        val lines = MeasurementMath.draftLines(billable, previous)
        val totals = MeasurementMath.totals(lines, project.retentionPct)
        val measurement = Measurement(
            tenantId = b.tenantId,
            projectId = project.id,
            number = (previous?.number ?: 0) + 1,
            periodEnd = periodEnd,
            lines = lines,
            retentionPct = project.retentionPct,
            grossCents = totals.grossCents,
            retentionCents = totals.retentionCents,
            netCents = totals.netCents,
            toDateCents = totals.toDateCents,
            notes = request.notes?.trim()?.take(MAX_TEXT)?.ifEmpty { null },
            createdBy = b.who,
            createdAt = b.now,
            updatedAt = b.now,
        )
        b.measurements.insert(measurement) ?: return@post call.respondError(HttpStatusCode.Conflict, "conflict")
        if (quote != null && quote.projectId == null) b.quotes.setProject(quote.id, project.id)
        call.respond(HttpStatusCode.Created, b.measurementDtos(listOf(measurement)).single())
    }
    get("/measurements/{id}") {
        val b = gate(DashboardModules.MEASUREMENTS) ?: return@get
        val measurement = call.parameters["id"]?.toObjectIdOrNull()?.let { b.measurements.findById(it) } ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(b.measurementDtos(listOf(measurement)).single())
    }
    patch("/measurements/{id}") {
        val b = gate(DashboardModules.MEASUREMENTS) ?: return@patch
        val measurement = call.parameters["id"]?.toObjectIdOrNull()?.let { b.measurements.findById(it) } ?: return@patch call.respond(HttpStatusCode.NotFound)
        if (measurement.status != MeasurementStatus.DRAFT) return@patch call.respondError(HttpStatusCode.Conflict, "measurement_locked")
        val request = call.receiveOrNull<MeasurementUpdateRequest>() ?: return@patch call.respondInvalidBody()
        val periodEnd = request.periodEnd?.takeIf { it.isNotBlank() }?.let { it.parseDay() ?: return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_day") } ?: measurement.periodEnd
        val before = b.measurements.list(measurement.projectId).firstOrNull { it.number == measurement.number - 1 }
        if (before != null && periodEnd < before.periodEnd) return@patch call.respondError(HttpStatusCode.BadRequest, "period_before_previous")
        val typed = request.lines.orEmpty().associate { it.key to it.toDateQty }
        val lines = when (val applied = MeasurementMath.apply(measurement.lines, typed)) {
            is MeasurementMath.Result.Refused -> return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to applied.error, "key" to applied.key))
            is MeasurementMath.Result.Ok -> applied.lines
        }
        val totals = MeasurementMath.totals(lines, measurement.retentionPct)
        val next = measurement.copy(
            periodEnd = periodEnd,
            lines = lines,
            notes = request.notes?.let { it.trim().take(MAX_TEXT).ifEmpty { null } } ?: measurement.notes,
            grossCents = totals.grossCents,
            retentionCents = totals.retentionCents,
            netCents = totals.netCents,
            toDateCents = totals.toDateCents,
            updatedAt = b.now,
        )
        val stored = b.measurements.replace(measurement, next) ?: return@patch call.respondError(HttpStatusCode.Conflict, "conflict")
        call.respond(b.measurementDtos(listOf(stored)).single())
    }
    post("/measurements/{id}/approve") {
        val b = gate(DashboardModules.MEASUREMENTS) ?: return@post
        val measurement = call.parameters["id"]?.toObjectIdOrNull()?.let { b.measurements.findById(it) } ?: return@post call.respond(HttpStatusCode.NotFound)
        if (measurement.status != MeasurementStatus.DRAFT) return@post call.respondError(HttpStatusCode.Conflict, "measurement_locked")
        if (measurement.lines.all { abs(MeasurementMath.periodQty(it)) < 1e-9 }) return@post call.respondError(HttpStatusCode.BadRequest, "nothing_measured")
        val next = measurement.copy(status = MeasurementStatus.APPROVED, approvedAt = b.now, approvedBy = b.who, updatedAt = b.now)
        val stored = b.measurements.replace(measurement, next) ?: return@post call.respondError(HttpStatusCode.Conflict, "conflict")
        call.respond(b.measurementDtos(listOf(stored)).single())
    }
    post("/measurements/{id}/reopen") {
        val b = gate(DashboardModules.MEASUREMENTS) ?: return@post
        val measurement = call.parameters["id"]?.toObjectIdOrNull()?.let { b.measurements.findById(it) } ?: return@post call.respond(HttpStatusCode.NotFound)
        if (measurement.status != MeasurementStatus.APPROVED) return@post call.respondError(HttpStatusCode.Conflict, "measurement_locked")
        if (b.measurements.latest(measurement.projectId)?.id != measurement.id) return@post call.respondError(HttpStatusCode.Conflict, "not_latest")
        val next = measurement.copy(status = MeasurementStatus.DRAFT, approvedAt = null, approvedBy = null, updatedAt = b.now)
        val stored = b.measurements.replace(measurement, next) ?: return@post call.respondError(HttpStatusCode.Conflict, "conflict")
        call.respond(b.measurementDtos(listOf(stored)).single())
    }
    post("/measurements/{id}/invoice") {
        val b = gate(DashboardModules.MEASUREMENTS) ?: return@post
        if (!b.has(DashboardModules.INVOICES)) return@post call.respondError(HttpStatusCode.Forbidden, "invoices_off")
        val measurement = call.parameters["id"]?.toObjectIdOrNull()?.let { b.measurements.findById(it) } ?: return@post call.respond(HttpStatusCode.NotFound)
        if (measurement.status == MeasurementStatus.INVOICED) return@post call.respondError(HttpStatusCode.Conflict, "already_invoiced")
        if (measurement.status != MeasurementStatus.APPROVED) return@post call.respondError(HttpStatusCode.Conflict, "not_approved")
        val request = call.receiveOrNull<MeasurementInvoiceRequest>() ?: return@post call.respondInvalidBody()
        val dueDate = request.dueDate.parseDay() ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_due_date")
        val project = b.projects.findById(measurement.projectId) ?: return@post call.respond(HttpStatusCode.NotFound)
        val clientId = project.clientId?.takeIf { b.clients.findById(it) != null } ?: return@post call.respondError(HttpStatusCode.BadRequest, "no_client")
        val locale = b.ctx.tenant.locale
        val prefix = BuildingCopy.t(locale, "measurement", "n" to measurement.number.toString())
        val items = MeasurementMath.invoiceItems(
            measurement,
            BuildingCopy.t(locale, "retention", "pct" to BuildingCopy.percent(locale, measurement.retentionPct)),
        ).map { if (it.totalCents >= 0) it.copy(description = "$prefix · ${it.description}") else it }
        if (items.sumOf { it.totalCents } <= 0) return@post call.respondError(HttpStatusCode.BadRequest, "nothing_to_invoice")
        val claimed = measurement.copy(status = MeasurementStatus.INVOICED, invoicedAt = b.now, updatedAt = b.now)
        b.measurements.replace(measurement, claimed) ?: return@post call.respondError(HttpStatusCode.Conflict, "conflict")
        val invoice = b.invoices.create(clientId, null, items, dueDate, projectId = project.id, measurementId = measurement.id)
        val stored = claimed.copy(invoiceId = invoice.id)
        b.measurements.replace(claimed, stored)
        call.respond(HttpStatusCode.Created, MeasurementInvoicedDto(b.measurementDtos(listOf(stored)).single(), invoice.id.toHexString(), invoice.number))
    }
    delete("/measurements/{id}") {
        val b = gate(DashboardModules.MEASUREMENTS) ?: return@delete
        val measurement = call.parameters["id"]?.toObjectIdOrNull()?.let { b.measurements.findById(it) } ?: return@delete call.respond(HttpStatusCode.NotFound)
        if (measurement.status == MeasurementStatus.INVOICED) return@delete call.respondError(HttpStatusCode.Conflict, "measurement_invoiced")
        if (b.measurements.latest(measurement.projectId)?.id != measurement.id) return@delete call.respondError(HttpStatusCode.Conflict, "not_latest")
        b.measurements.delete(measurement.id)
        if (b.measurements.count(measurement.projectId) == 0L) {
            val project = b.projects.findById(measurement.projectId)
            val quote = project?.quoteId?.let { b.quotes.findById(it) }
            if (quote?.projectId == project?.id && quote != null) b.quotes.setProject(quote.id, null)
        }
        call.respond(mapOf("deleted" to true))
    }
}

private const val MAX_NAME = 160
private const val MAX_ADDRESS = 300
private const val MAX_TEXT = 4000
private const val MAX_NOTE = 1000
private const val MAX_CAPTION = 300
private const val MAX_PHASES = 60
private const val MAX_PHASE_NAME = 120
private const val MAX_CONTRACT_LINES = 300
private const val MAX_LINE_DESCRIPTION = 300
private const val MAX_UNIT = 20
private const val MAX_ITEMS = 100
private const val MAX_RETENTION_PCT = 20.0
private const val MAX_WEIGHT = 10_000
private const val MAX_QUANTITY = 1_000_000_000.0
private const val MAX_PRICE_EUR = 100_000_000.0
private const val MAX_DAYS_IMPACT = 730
private const val DEFAULT_DIARY_DAYS = 30
private val random = SecureRandom()

private sealed interface Parsed<out T> {
    data class Good<T>(val value: T) : Parsed<T>
    data class Bad(val error: String) : Parsed<Nothing>
}

private inline fun <T> Parsed<T>.orElse(fail: (String) -> Nothing): T = when (this) {
    is Parsed.Good -> value
    is Parsed.Bad -> fail(error)
}

private inline fun <reified E : Enum<E>> enumOf(value: String?): E? =
    value?.trim()?.uppercase()?.let { raw -> enumValues<E>().firstOrNull { it.name == raw } }

private fun String.parseDay(): LocalDate? = runCatching { LocalDate.parse(trim()) }.getOrNull()

private fun newId(): String = ObjectId().toHexString()

private fun shareToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

private suspend fun Building.project(raw: String?): Project? = raw?.toObjectIdOrNull()?.let { projects.findById(it) }

private suspend fun ApplicationCall.respondProject(b: Building, outcome: ProjectChange?) = when (outcome) {
    null -> respond(HttpStatusCode.NotFound)
    is ProjectChange.Refused -> respondError(if (outcome.error == "conflict" || outcome.error == "contract_locked") HttpStatusCode.Conflict else HttpStatusCode.BadRequest, outcome.error)
    is ProjectChange.Ok -> respond(b.projectDtos(listOf(outcome.project), detail = true).single())
}

private fun LineItem.toContractLine(id: String) = ContractLine(id, description, quantity, unit, unitPriceCents, totalCents)

/** A phase moved to [progress]: it started the first day it moved, and ended the day it reached 100%. */
private fun ProjectPhase.withProgress(progress: Int, day: LocalDate): ProjectPhase = copy(
    progressPct = progress,
    actualStart = actualStart ?: day.takeIf { progress > 0 },
    actualEnd = if (progress >= 100) actualEnd ?: day else null,
)

/** A text field of an edit: null keeps [current], blank clears it. */
private fun text(value: String?, current: String?, max: Int): Parsed<String?> {
    if (value == null) return Parsed.Good(current)
    val trimmed = value.trim()
    return if (trimmed.length > max) Parsed.Bad("too_long") else Parsed.Good(trimmed.ifEmpty { null })
}

/** A date field of an edit: null keeps [current], blank clears it. */
private fun date(value: String?, current: LocalDate?): Parsed<LocalDate?> {
    if (value == null) return Parsed.Good(current)
    if (value.isBlank()) return Parsed.Good(null)
    return value.parseDay()?.let { Parsed.Good(it) } ?: Parsed.Bad("invalid_date")
}

private suspend fun Building.projectFrom(request: ProjectRequest, base: Project, locked: Boolean): ProjectChange {
    fun bad(error: String) = ProjectChange.Refused(error)
    val name = request.name?.trim() ?: base.name
    if (request.name != null && name.isEmpty()) return bad("name_required")
    if (name.length > MAX_NAME) return bad("name_too_long")
    val status = request.status?.let { enumOf<ProjectStatus>(it) ?: return bad("invalid_status") } ?: base.status
    val clientId = when (val raw = request.clientId?.trim()) {
        null -> base.clientId
        "" -> null
        else -> raw.toObjectIdOrNull()?.takeIf { clients.findById(it) != null } ?: return bad("client_not_found")
    }
    val managerId = when (val raw = request.managerId?.trim()) {
        null -> base.managerId
        "" -> null
        else -> raw.toObjectIdOrNull()?.takeIf { employees.findById(it) != null } ?: return bad("employee_not_found")
    }
    val address = text(request.address, base.address, MAX_ADDRESS).orElse { return bad("address_too_long") }
    val description = text(request.description, base.description, MAX_TEXT).orElse { return bad("description_too_long") }
    val plannedStart = date(request.plannedStart, base.plannedStart).orElse { return bad(it) }
    val plannedEnd = date(request.plannedEnd, base.plannedEnd).orElse { return bad(it) }
    var actualStart = date(request.actualStart, base.actualStart).orElse { return bad(it) }
    var actualEnd = date(request.actualEnd, base.actualEnd).orElse { return bad(it) }
    if (plannedStart != null && plannedEnd != null && plannedEnd < plannedStart) return bad("invalid_dates")
    val retention = request.retentionPct ?: base.retentionPct
    if (!retention.isFinite() || retention < 0 || retention > MAX_RETENTION_PCT) return bad("invalid_retention")
    val manual = request.manualProgressPct ?: base.manualProgressPct
    if (manual !in 0..100) return bad("invalid_progress")
    val contract = request.contract?.let { lines ->
        when (val parsed = contractFrom(lines, base.contract)) {
            is Parsed.Bad -> return bad(parsed.error)
            is Parsed.Good -> parsed.value
        }
    } ?: base.contract
    if (locked && contract.map { it.copy(description = "") } != base.contract.map { it.copy(description = "") }) return bad("contract_locked")
    if (status != base.status) {
        if (status == ProjectStatus.ACTIVE) actualStart = actualStart ?: today
        if (status == ProjectStatus.COMPLETED) actualEnd = actualEnd ?: today
    }
    if (actualStart != null && actualEnd != null && actualEnd < actualStart) return bad("invalid_dates")
    return ProjectChange.Ok(
        base.copy(
            name = name,
            status = status,
            clientId = clientId,
            managerId = managerId,
            address = address,
            description = description,
            plannedStart = plannedStart,
            plannedEnd = plannedEnd,
            actualStart = actualStart,
            actualEnd = actualEnd,
            retentionPct = retention,
            manualProgressPct = manual,
            contract = contract,
        ),
    )
}

/** Lines typed for a contract; a line keeps its id when it names one of [current]'s, so measurements still find it. */
private fun contractFrom(lines: List<ContractLineRequest>, current: List<ContractLine>): Parsed<List<ContractLine>> {
    if (lines.size > MAX_CONTRACT_LINES) return Parsed.Bad("too_many_lines")
    val known = current.map { it.id }.toSet()
    val used = mutableSetOf<String>()
    return Parsed.Good(
        lines.map { line ->
            val description = line.description.trim()
            when {
                description.isEmpty() -> return Parsed.Bad("line_description_required")
                description.length > MAX_LINE_DESCRIPTION -> return Parsed.Bad("line_description_too_long")
                line.unit.trim().length > MAX_UNIT -> return Parsed.Bad("line_unit_too_long")
                !line.quantity.isFinite() || line.quantity <= 0 || line.quantity > MAX_QUANTITY -> return Parsed.Bad("invalid_quantity")
                !line.unitPriceEur.isFinite() || abs(line.unitPriceEur) > MAX_PRICE_EUR -> return Parsed.Bad("invalid_price")
            }
            val id = line.id?.takeIf { it in known && used.add(it) } ?: newId().also { used.add(it) }
            val unitPriceCents = eurToCents(line.unitPriceEur)
            ContractLine(id, description, line.quantity, line.unit.trim(), unitPriceCents, (line.quantity * unitPriceCents).roundToLong())
        },
    )
}

/** The phases typed in the editor, keeping each known phase's id and actual dates. */
private fun phasesFrom(requests: List<PhaseRequest>, current: List<ProjectPhase>, today: LocalDate): Parsed<List<ProjectPhase>> {
    if (requests.size > MAX_PHASES) return Parsed.Bad("too_many_phases")
    val byId = current.associateBy { it.id }
    val used = mutableSetOf<String>()
    return Parsed.Good(
        requests.map { request ->
            val name = request.name.trim()
            if (name.isEmpty()) return Parsed.Bad("phase_name_required")
            if (name.length > MAX_PHASE_NAME) return Parsed.Bad("phase_name_too_long")
            val start = request.plannedStart?.takeIf { it.isNotBlank() }?.let { it.parseDay() ?: return Parsed.Bad("invalid_date") }
            val end = request.plannedEnd?.takeIf { it.isNotBlank() }?.let { it.parseDay() ?: return Parsed.Bad("invalid_date") }
            if (start != null && end != null && end < start) return Parsed.Bad("invalid_dates")
            if (request.progressPct !in 0..100) return Parsed.Bad("invalid_progress")
            if (request.weight != null && request.weight !in 1..MAX_WEIGHT) return Parsed.Bad("invalid_weight")
            val existing = request.id?.let { byId[it] }?.takeIf { used.add(it.id) }
            val phase = existing ?: ProjectPhase(id = newId().also { used.add(it) }, name = name)
            phase.copy(name = name, plannedStart = start, plannedEnd = end, weight = request.weight)
                .let { if (it.progressPct != request.progressPct || existing == null) it.withProgress(request.progressPct, today) else it }
        },
    )
}

private fun logFrom(request: SiteLogRequest, base: SiteLogEntry): Parsed<SiteLogEntry> {
    val workDone = request.workDone?.trim() ?: base.workDone
    if (workDone.isEmpty()) return Parsed.Bad("work_required")
    if (workDone.length > MAX_TEXT) return Parsed.Bad("work_too_long")
    val weather = when (val raw = request.weather?.trim()) {
        null -> base.weather
        "" -> null
        else -> enumOf<Weather>(raw) ?: return Parsed.Bad("invalid_weather")
    }
    val temperature = if ("temperatureC" in request.clear) null else request.temperatureC ?: base.temperatureC
    if (temperature != null && temperature !in -50..60) return Parsed.Bad("invalid_temperature")
    val workers = if ("workers" in request.clear) null else request.workers ?: base.workers
    if (workers != null && workers !in 0..10_000) return Parsed.Bad("invalid_workers")
    val lost = if ("lostHours" in request.clear) null else request.lostHours ?: base.lostHours
    if (lost != null && (!lost.isFinite() || lost < 0 || lost > 10_000)) return Parsed.Bad("invalid_hours")
    return Parsed.Good(
        base.copy(
            weather = weather,
            temperatureC = temperature,
            workers = workers,
            workDone = workDone,
            equipment = text(request.equipment, base.equipment, MAX_TEXT).orElse { return Parsed.Bad("text_too_long") },
            materials = text(request.materials, base.materials, MAX_TEXT).orElse { return Parsed.Bad("text_too_long") },
            visitors = text(request.visitors, base.visitors, MAX_TEXT).orElse { return Parsed.Bad("text_too_long") },
            incidents = text(request.incidents, base.incidents, MAX_TEXT).orElse { return Parsed.Bad("text_too_long") },
            lostHours = lost,
            shared = request.shared ?: base.shared,
        ),
    )
}

/**
 * Moves the project's phases to the progress a diary entry typed, and returns the entry's record of it: what
 * each phase went from and to, keeping the first "from" of a phase the entry had already moved.
 */
private suspend fun Building.applyDiaryProgress(
    projectId: ObjectId,
    typed: List<PhaseProgressEntry>?,
    day: LocalDate,
    recorded: List<PhaseProgressChange>,
): Parsed<List<PhaseProgressChange>> {
    if (typed == null) return Parsed.Good(recorded)
    if (typed.any { it.progressPct !in 0..100 }) return Parsed.Bad("invalid_progress")
    var changes = recorded
    val outcome = projects.update(projectId) { current ->
        val byId = current.phases.associateBy { it.id }
        if (typed.any { it.phaseId !in byId }) return@update ProjectChange.Refused("phase_not_found")
        val wanted = typed.associate { it.phaseId to it.progressPct }
        val firstFrom = recorded.associate { it.phaseId to it.fromPct }
        val moved = wanted.map { (id, to) -> PhaseProgressChange(id, byId.getValue(id).name, firstFrom[id] ?: byId.getValue(id).progressPct, to) }
        changes = (recorded.filter { it.phaseId !in wanted } + moved).filter { it.fromPct != it.toPct }
        ProjectChange.Ok(current.copy(phases = current.phases.map { phase -> wanted[phase.id]?.let { phase.withProgress(it, day) } ?: phase }, updatedAt = now))
    }
    return when (outcome) {
        null -> Parsed.Bad("project_not_found")
        is ProjectChange.Refused -> Parsed.Bad(outcome.error)
        is ProjectChange.Ok -> Parsed.Good(changes)
    }
}

private suspend fun Building.issueFrom(request: IssueRequest, base: SiteIssue, project: Project): Parsed<SiteIssue> {
    val title = request.title?.trim() ?: base.title
    if (request.title != null && title.isEmpty()) return Parsed.Bad("title_required")
    if (title.length > MAX_NAME) return Parsed.Bad("title_too_long")
    val description = text(request.description, base.description, MAX_TEXT).orElse { return Parsed.Bad("description_too_long") }
    val location = text(request.location, base.location, MAX_NAME).orElse { return Parsed.Bad("location_too_long") }
    val kind = request.kind?.let { enumOf<IssueKind>(it) ?: return Parsed.Bad("invalid_kind") } ?: base.kind
    val priority = request.priority?.let { enumOf<IssuePriority>(it) ?: return Parsed.Bad("invalid_priority") } ?: base.priority
    val status = request.status?.let { enumOf<IssueStatus>(it) ?: return Parsed.Bad("invalid_status") } ?: base.status
    val phaseId = when (val raw = request.phaseId?.trim()) {
        null -> base.phaseId
        "" -> null
        else -> raw.takeIf { id -> project.phases.any { it.id == id } } ?: return Parsed.Bad("phase_not_found")
    }
    val employeeId = when (val raw = request.employeeId?.trim()) {
        null -> base.employeeId
        "" -> null
        else -> raw.toObjectIdOrNull()?.takeIf { employees.findById(it) != null } ?: return Parsed.Bad("employee_not_found")
    }
    val supplierId = when (val raw = request.supplierId?.trim()) {
        null -> base.supplierId
        "" -> null
        else -> raw.toObjectIdOrNull()?.takeIf { suppliers.findById(it) != null } ?: return Parsed.Bad("supplier_not_found")
    }
    // One assignee: picking an employee drops the supplier, and the other way round.
    val (employee, supplier) = when {
        request.employeeId?.isNotBlank() == true -> employeeId to null
        request.supplierId?.isNotBlank() == true -> null to supplierId
        else -> employeeId to supplierId
    }
    val dueDate = date(request.dueDate, base.dueDate).orElse { return Parsed.Bad(it) }
    return Parsed.Good(
        base.copy(
            title = title,
            description = description,
            location = location,
            kind = kind,
            priority = priority,
            status = status,
            phaseId = phaseId,
            employeeId = employee,
            supplierId = supplier,
            dueDate = dueDate,
        ),
    )
}

/** When it was resolved and closed, following [status]: reopening clears them. */
private fun SiteIssue.stamped(status: IssueStatus, now: Instant): SiteIssue = when (status) {
    IssueStatus.OPEN, IssueStatus.IN_PROGRESS -> copy(resolvedAt = null, closedAt = null)
    IssueStatus.RESOLVED -> copy(resolvedAt = resolvedAt ?: now, closedAt = null)
    IssueStatus.CLOSED -> copy(resolvedAt = resolvedAt ?: now, closedAt = closedAt ?: now)
}

private fun changeFrom(request: ChangeOrderRequest, base: ChangeOrder): Parsed<ChangeOrder> {
    val title = request.title?.trim() ?: base.title
    if (request.title != null && title.isEmpty()) return Parsed.Bad("title_required")
    if (title.length > MAX_NAME) return Parsed.Bad("title_too_long")
    val description = text(request.description, base.description, MAX_TEXT).orElse { return Parsed.Bad("description_too_long") }
    val kind = request.kind?.let { enumOf<ChangeOrderKind>(it) ?: return Parsed.Bad("invalid_kind") } ?: base.kind
    val days = request.daysImpact ?: base.daysImpact
    if (abs(days) > MAX_DAYS_IMPACT) return Parsed.Bad("invalid_days")
    val items = request.items?.let { lines ->
        if (lines.size > MAX_ITEMS) return Parsed.Bad("too_many_items")
        lines.map { line -> line.validLine() ?: return Parsed.Bad(line.lineError()) }
    } ?: base.items
    return Parsed.Good(base.copy(title = title, description = description, kind = kind, daysImpact = days, items = items, totalCents = items.sumOf { it.totalCents }))
}

private fun CreateLineItemRequest.validLine(): LineItem? {
    if (lineError().isNotEmpty()) return null
    val unitPriceCents = eurToCents(unitPriceEur)
    return LineItem(description.trim(), quantity, unit.trim(), unitPriceCents, (quantity * unitPriceCents).roundToLong())
}

private fun CreateLineItemRequest.lineError(): String = when {
    description.isBlank() -> "item_description_required"
    description.trim().length > MAX_LINE_DESCRIPTION -> "item_description_too_long"
    unit.trim().length > MAX_UNIT -> "item_unit_too_long"
    !quantity.isFinite() || quantity <= 0 || quantity > MAX_QUANTITY -> "invalid_quantity"
    !unitPriceEur.isFinite() || unitPriceEur < 0 || unitPriceEur > MAX_PRICE_EUR -> "invalid_price"
    else -> ""
}
