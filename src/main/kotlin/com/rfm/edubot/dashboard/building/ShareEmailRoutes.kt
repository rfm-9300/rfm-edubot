package com.rfm.edubot.dashboard.building

import com.rfm.edubot.building.BuildingRecords
import com.rfm.edubot.building.model.ChangeOrderStatus
import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.moduleContext
import com.rfm.edubot.dashboard.requireModules
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.http.ErrorBody
import com.rfm.edubot.http.idParam
import com.rfm.edubot.integrations.email.EmailAddresses
import com.rfm.edubot.integrations.email.EmailCopy
import com.rfm.edubot.integrations.email.EmailSendResult
import com.rfm.edubot.integrations.email.EmailService
import com.rfm.edubot.integrations.email.OutgoingEmail
import com.rfm.edubot.integrations.email.httpStatus
import com.rfm.edubot.persistence.MongoModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

/**
 * Sends the links the team gives people outside the company by email, beside copying them and WhatsApp: a sent
 * change's and a measurement's approval link, a supplier's link to their issues and the client's project page.
 * Each has a `GET …/email` that drafts the email (who it goes to by default, what it says, whether the company can
 * send at all, and when it was last sent) and a `POST …/email` that sends it through the company's own sender
 * ([EmailService]: its address on the platform's mail domain, else its Google account, with the same daily allowance).
 *
 * The text is the platform's, in the company's language, and says nothing about money; only the recipient is typed.
 * The gate is the link's own module, not email's: any company that can show the link can email it, and the answer
 * says clearly when it has nowhere to send from. A send is recorded in the activity log with who sent it.
 */
internal fun Route.shareEmailRoutes(email: EmailService, mongo: MongoModule) {
    val log = DomainEventLog(mongo)

    suspend fun ApplicationCall.draft(ctx: DashboardContext, target: ShareTarget) {
        val origin = shareOrigin(request.queryParameters["origin"])
            ?: return respond(HttpStatusCode.BadRequest, ErrorBody("invalid_origin"))
        val account = email.sender(ctx.tenant)
        val (subject, text) = target.compose(ctx, origin, signed = !account?.settings?.signature.isNullOrBlank())
        val last = log.timeline(ctx.tenant.id, target.subject, limit = LAST_LOOKUP).firstOrNull { it.type in LINK_EMAILED }
        respond(
            ShareEmailDraftDto(
                to = target.to,
                subject = subject,
                text = text,
                from = account?.accountEmail,
                canSend = email.isAvailable(ctx.tenant),
                configured = email.configured,
                lastSentAt = last?.occurredAt?.toString(),
                lastSentTo = (last?.payload?.get("to") as? kotlinx.serialization.json.JsonPrimitive)?.content,
                lastSentBy = last?.actor?.name,
            ),
        )
    }

    suspend fun ApplicationCall.send(ctx: DashboardContext, target: ShareTarget) {
        val request = runCatching { receive<ShareEmailRequest>() }.getOrNull()
            ?: return respond(HttpStatusCode.BadRequest, ErrorBody("invalid_body"))
        val origin = shareOrigin(request.origin) ?: return respond(HttpStatusCode.BadRequest, ErrorBody("invalid_origin"))
        val to = request.to.trim()
        if (to.isEmpty()) return respond(HttpStatusCode.BadRequest, ErrorBody("no_email"))
        val address = EmailAddresses.normalize(to) ?: return respond(HttpStatusCode.BadRequest, ErrorBody("invalid_recipient"))
        val account = email.sender(ctx.tenant)
        val (subject, text) = target.compose(ctx, origin, signed = !account?.settings?.signature.isNullOrBlank())
        val outgoing = OutgoingEmail(to = listOf(address), subject = subject, text = text, clientId = target.clientId, record = target.subject)
        // One press of the button sends once, however often it is pressed.
        val key = request.requestId?.takeIf { REQUEST_ID.matches(it) }?.let { "ui:${ctx.tenant.id.toHexString()}:$it" }
        when (val sent = email.send(ctx.tenant, outgoing, key)) {
            is EmailSendResult.Sent -> {
                if (!sent.alreadySent) target.recordSent(address)
                respond(ShareEmailSentDto(to = address, alreadySent = sent.alreadySent))
            }
            is EmailSendResult.Failed -> respond(sent.httpStatus(), ErrorBody(sent.key))
        }
    }

    /** Both routes of a link: [resolve] finds what its link is for, or answers why it can't be emailed. */
    fun Route.emailOf(path: String, resolve: suspend ApplicationCall.(DashboardContext, BuildingRecords) -> ShareTarget?) {
        get(path) {
            val ctx = call.moduleContext()
            val target = call.resolve(ctx, BuildingRecords(mongo, ctx.tenant.id)) ?: return@get
            call.draft(ctx, target)
        }
        post(path) {
            val ctx = call.moduleContext()
            val target = call.resolve(ctx, BuildingRecords(mongo, ctx.tenant.id)) ?: return@post
            call.send(ctx, target)
        }
    }

    authenticate("dashboard") {
        route("/app/api/building") {
            requireModules(DashboardModules.CHANGE_ORDERS) {
                emailOf("/changes/{id}/link/email") { _, records ->
                    val change = records.changes.findById(idParam("id"))
                    val project = change?.let { records.projects.findById(it.projectId) }
                    if (change == null || project == null) return@emailOf notFound()
                    if (change.status != ChangeOrderStatus.SENT || change.approval == null) return@emailOf linkOff()
                    val client = project.clientId?.let { records.clients.findById(it) }
                    val token = change.approval.token
                    ShareTarget(
                        subject = SubjectRef.of(SubjectTypes.CHANGE_ORDER, change.id),
                        path = "/app/share/change/${token}",
                        kind = "change",
                        to = client?.email.clean(),
                        clientId = client?.id,
                        greeting = client?.name.firstName(),
                        params = mapOf("number" to change.number, "title" to change.title.oneLine(), "project" to project.name.oneLine()),
                        record = { records.events.changeLinkEmailed(change, project, it) },
                    )
                }
            }
            requireModules(DashboardModules.MEASUREMENTS) {
                emailOf("/measurements/{id}/link/email") { _, records ->
                    val measurement = records.measurements.findById(idParam("id"))
                    val project = measurement?.let { records.projects.findById(it.projectId) }
                    if (measurement == null || project == null) return@emailOf notFound()
                    val approval = measurement.approval ?: return@emailOf linkOff()
                    if (!measurement.awaitingClient) return@emailOf linkOff()
                    val client = project.clientId?.let { records.clients.findById(it) }
                    ShareTarget(
                        subject = SubjectRef.of(SubjectTypes.MEASUREMENT, measurement.id),
                        path = "/app/share/measurement/${approval.token}",
                        kind = "measurement",
                        to = client?.email.clean(),
                        clientId = client?.id,
                        greeting = client?.name.firstName(),
                        params = mapOf("number" to measurement.number.toString(), "project" to project.name.oneLine()),
                        record = { records.events.measurementLinkEmailed(measurement, project, it) },
                    )
                }
            }
            requireModules(DashboardModules.SITE_ISSUES) {
                emailOf("/suppliers/{id}/link/email") { _, records ->
                    val supplier = records.suppliers.findById(idParam("id"))?.takeIf { it.archivedAt == null } ?: return@emailOf notFound()
                    val link = records.supplierLinks.forSupplier(supplier.id) ?: return@emailOf linkOff()
                    ShareTarget(
                        subject = SubjectRef.of(SubjectTypes.SUPPLIER, supplier.id),
                        path = "/app/share/issues/${link.token}",
                        kind = "issues",
                        // Suppliers have no email on record: the team types it.
                        to = null,
                        clientId = null,
                        greeting = (supplier.contactPerson ?: supplier.name).firstName(),
                        params = emptyMap(),
                        record = { records.events.supplierLinkEmailed(supplier.id, supplier.name, it) },
                    )
                }
            }
            requireModules(DashboardModules.PROJECTS) {
                emailOf("/projects/{id}/share/email") { _, records ->
                    val project = records.projects.findById(idParam("id")) ?: return@emailOf notFound()
                    val share = project.share?.takeIf { it.enabled } ?: return@emailOf linkOff()
                    val client = project.clientId?.let { records.clients.findById(it) }
                    ShareTarget(
                        subject = SubjectRef.of(SubjectTypes.PROJECT, project.id),
                        path = "/app/share/${share.token}",
                        kind = "project",
                        to = client?.email.clean(),
                        clientId = client?.id,
                        greeting = client?.name.firstName(),
                        params = mapOf("project" to project.name.oneLine()),
                        record = { records.events.projectLinkEmailed(project, it) },
                    )
                }
            }
        }
    }
}

/** What an emailed link is for, resolved from the record: its address, who it greets, and how to log the send. */
internal class ShareTarget(
    val subject: SubjectRef,
    private val path: String,
    private val kind: String,
    /** The address on record, if the record has one. */
    val to: String?,
    val clientId: ObjectId?,
    private val greeting: String,
    private val params: Map<String, String>,
    private val record: suspend (String) -> Unit,
) {
    /** The email's subject and text, in the company's language, linking to [origin] + the link's path. */
    fun compose(ctx: DashboardContext, origin: String, signed: Boolean): Pair<String, String> {
        val company = ctx.tenant.documentTemplate.withCompanyFallback(ctx.tenant.name).companyName.oneLine()
        return EmailCopy.shareLink(ctx.tenant.locale, kind, greeting, params + ("company" to company) + ("url" to origin + path), signed)
    }

    suspend fun recordSent(to: String) = record(to)
}

private suspend fun ApplicationCall.notFound(): ShareTarget? {
    respond(HttpStatusCode.NotFound)
    return null
}

/** The record has no link now (not sent, answered, edited, turned off): nothing to email. */
private suspend fun ApplicationCall.linkOff(): ShareTarget? {
    respond(HttpStatusCode.Conflict, ErrorBody("link_off"))
    return null
}

/**
 * The scheme and host the dashboard is open on, which the link is built on; the page names no path, so the address
 * the email carries is always one of the company's own link pages. Null when it isn't one.
 */
internal fun shareOrigin(raw: String?): String? =
    raw?.trim()?.trimEnd('/')?.takeIf { it.length <= MAX_ORIGIN && ORIGIN.matches(it) }

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
private fun String?.firstName(): String = this?.trim()?.substringBefore(' ').orEmpty()

/** Text that goes in one line of an email: no line breaks from a name someone typed. */
private fun String.oneLine(): String = trim().replace(Regex("\\s+"), " ")

private val ORIGIN = Regex("^https?://[A-Za-z0-9.-]+(:\\d{1,5})?$")
private val REQUEST_ID = Regex("^[A-Za-z0-9_-]{8,64}$")
private const val MAX_ORIGIN = 200
private const val LAST_LOOKUP = 100

private val LINK_EMAILED = setOf(
    DomainEventTypes.CHANGE_ORDER_LINK_EMAILED,
    DomainEventTypes.MEASUREMENT_LINK_EMAILED,
    DomainEventTypes.PROJECT_LINK_EMAILED,
    DomainEventTypes.SUPPLIER_LINK_EMAILED,
)

@Serializable
internal data class ShareEmailRequest(val to: String = "", val origin: String = "", val requestId: String? = null)

@Serializable
internal data class ShareEmailSentDto(val to: String, val alreadySent: Boolean)

/** What "Send by email" shows before sending: the recipient on record, the email as it will go, and whether it can. */
@Serializable
internal data class ShareEmailDraftDto(
    val to: String? = null,
    val subject: String,
    val text: String,
    /** The account it goes out from. */
    val from: String? = null,
    /** The company's sender works now; false with [from] set means its account needs reconnecting. */
    val canSend: Boolean,
    /** The platform offers email at all. */
    val configured: Boolean,
    /** The last time this link was emailed: when, to whom and by whom. */
    val lastSentAt: String? = null,
    val lastSentTo: String? = null,
    val lastSentBy: String? = null,
)
