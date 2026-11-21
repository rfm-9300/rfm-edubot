package com.rfm.edubot.dashboard.building

import com.rfm.edubot.building.BuildingRecords
import com.rfm.edubot.building.Outcome
import com.rfm.edubot.building.PhotoStore
import com.rfm.edubot.building.SiteIssueService
import com.rfm.edubot.building.SitePhotoService
import com.rfm.edubot.building.Stamp
import com.rfm.edubot.building.SupplierLinkRepository
import com.rfm.edubot.building.api.SupplierIssueStatusRequest
import com.rfm.edubot.building.api.supplierIssuesDto
import com.rfm.edubot.building.model.IssueStatus
import com.rfm.edubot.building.model.SupplierLink
import com.rfm.edubot.crm.model.Supplier
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
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId

/**
 * The page a supplier opens, without an account, to see the site issues assigned to them across the company's
 * projects and say they started or fixed one, with a note and photos: `/app/share/issues/{token}`. It shows no money,
 * no client and no other issue, and works while the company is active, the supplier isn't archived and the link is on.
 */
internal fun Route.supplierIssueRoutes(mongo: MongoModule, tenants: TenantRepository, storagePath: () -> String) {
    val store = PhotoStore(storagePath)

    class Access(val link: SupplierLink, val supplier: Supplier, val tenant: Tenant) {
        val records = BuildingRecords(mongo, tenant.id)

        /** Who an answer or a photo from this link is stamped with: the name typed, then the supplier's. */
        fun stamp(name: String?): Stamp {
            val who = listOfNotNull(name?.trim()?.take(MAX_ACTOR_NAME)?.ifBlank { null }, supplier.name).distinct().joinToString(" · ")
            return Stamp(who, SystemClock.now(), tenant.zone())
        }

        fun actor(name: String?) = ActorContext(Actor(ActorType.SUPPLIER, id = supplier.id.toHexString(), name = stamp(name).who))

        suspend fun dto() = records.supplierIssuesDto(
            supplier, tenant, SystemClock.now().toLocalDateTime(tenant.zone()).date, hasLogo = tenant.documentTemplate.logoFile() != null,
            since = SystemClock.now().minus(RECENT_DAYS, DateTimeUnit.DAY, tenant.zone()),
        )

        /** One of this supplier's issues, or null. */
        suspend fun issue(raw: String?) = raw?.takeIf(ObjectId::isValid)?.let { records.issues.findById(ObjectId(it)) }?.takeIf { it.supplierId == supplier.id }
    }

    suspend fun ApplicationCall.access(): Access? {
        val link = parameters["token"]?.let { SupplierLinkRepository.byToken(mongo, it) }
        val tenant = link?.let { tenants.findById(it.tenantId) }?.takeIf { it.status == TenantStatus.ACTIVE }
        val supplier = tenant?.let { BuildingRecords(mongo, it.id).suppliers.findById(link.supplierId) }?.takeIf { it.archivedAt == null }
        if (link == null || tenant == null || supplier == null) {
            respond(HttpStatusCode.NotFound)
            return null
        }
        noIndex()
        return Access(link, supplier, tenant)
    }

    get("/app/share/issues/{token}") {
        val html = this::class.java.classLoader.getResource("share/issues.html")?.readText() ?: return@get call.respond(HttpStatusCode.NotFound)
        call.noIndex()
        call.respondText(html, ContentType.Text.Html)
    }
    get("/app/share/issues/{token}/data") {
        val access = call.access() ?: return@get
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(access.dto())
    }
    post("/app/share/issues/{token}/issues/{issueId}/status") {
        val access = call.access() ?: return@post
        val issue = access.issue(call.parameters["issueId"]) ?: return@post call.respond(HttpStatusCode.NotFound)
        val request = call.receive<SupplierIssueStatusRequest>()
        val outcome = withContext(access.actor(request.name)) {
            SiteIssueService(access.records).moveBySupplier(issue.id, access.supplier.id, request.status, request.name, request.note, call.linkProof(), access.stamp(request.name))
        }
        when (outcome) {
            is Outcome.Done -> call.respond(access.dto())
            is Outcome.Refused -> call.respondRefused(outcome)
            Outcome.NotFound -> call.respond(HttpStatusCode.NotFound)
        }
    }
    // Photos of the work, to an issue still theirs to work on: inside the team, never on the client's page.
    post("/app/share/issues/{token}/issues/{issueId}/photos") {
        val access = call.access() ?: return@post
        val issue = access.issue(call.parameters["issueId"]) ?: return@post call.respond(HttpStatusCode.NotFound)
        if (issue.status == IssueStatus.CLOSED) return@post call.respondRefused(Outcome.Refused("issue_closed", conflict = true))
        val upload = call.receivePhoto() ?: return@post
        val photo = SitePhotoService.Upload(upload.image, upload.thumb, issueId = issue.id.toHexString(), caption = upload.caption, shared = false, takenAt = upload.takenAt)
        val outcome = withContext(access.actor(upload.name)) {
            SitePhotoService(access.records, store).add(issue.projectId, photo, access.tenant.slug, access.stamp(upload.name))
        }
        when (outcome) {
            is Outcome.Done -> call.respond(HttpStatusCode.Created, access.dto())
            is Outcome.Refused -> call.respondRefused(outcome)
            Outcome.NotFound -> call.respond(HttpStatusCode.NotFound)
        }
    }
    get("/app/share/issues/{token}/photos/{id}") {
        val access = call.access() ?: return@get
        val photo = call.parameters["id"]?.takeIf(ObjectId::isValid)?.let { access.records.photos.findById(ObjectId(it)) }
        val issueId = photo?.issueId ?: return@get call.respond(HttpStatusCode.NotFound)
        access.issue(issueId.toHexString()) ?: return@get call.respond(HttpStatusCode.NotFound)
        val bytes = store.read(photo, thumb = call.request.queryParameters["size"] == "thumb") ?: return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
        call.respondBytes(bytes, ContentType.Image.JPEG)
    }
    get("/app/share/issues/{token}/logo") {
        val access = call.access() ?: return@get
        call.respondLogo(access.tenant.documentTemplate)
    }
}

private const val MAX_ACTOR_NAME = 160

/** Issues fixed or closed in the last days still show, so the supplier sees what they did. */
private const val RECENT_DAYS = 30
