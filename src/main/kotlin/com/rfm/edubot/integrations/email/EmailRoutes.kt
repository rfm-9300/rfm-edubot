package com.rfm.edubot.integrations.email

import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PdfGenerator
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.dashboardContext
import com.rfm.edubot.dashboard.requireModule
import com.rfm.edubot.dashboard.toObjectIdOrNull
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.integrations.google.GmailClient
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
 * Email from the dashboard (docs/plan-agents-automations.md, "Google / Gmail integration"):
 *  - GET  /app/api/email                                 whether the company can send, and from which account
 *  - GET  /app/api/email/draft?type=quote|invoice&id=…   the first draft "Send by email" opens with
 *  - POST /app/api/email/send                            a quote or invoice to its client, with the PDF
 *  - GET  /app/api/email/messages?clientId=…             a client's emails, newest first
 */
fun Route.emailRoutes(email: EmailService, mongo: MongoModule, pdf: PdfGenerator = PdfGenerator()) {
    val messages = EmailMessageRepository(mongo)

    authenticate("dashboard") {
        route("/app/api/email") {
            get {
                val ctx = call.dashboardContext() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val account = email.sender(ctx.tenant)
                call.respond(EmailStatusDto(configured = email.configured, canSend = email.isAvailable(ctx.tenant), from = account?.accountEmail))
            }

            get("/draft") {
                val ctx = call.dashboardContext() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val params = call.request.queryParameters
                val doc = call.emailedDocument(ctx, mongo, pdf, params["type"], params["id"]) ?: return@get
                val account = email.sender(ctx.tenant)
                val locale = ctx.tenant.locale
                val company = ctx.tenant.documentTemplate.withCompanyFallback(ctx.tenant.name).companyName
                val name = doc.client.name.trim().substringBefore(' ')
                call.respond(
                    EmailDraftDto(
                        to = doc.client.email?.trim()?.takeIf { it.isNotEmpty() },
                        subject = EmailCopy.t(locale, "${doc.type}.subject", "number" to doc.number, "company" to company),
                        text = EmailCopy.documentBody(locale, doc.type, name, doc.number, company, signed = !account?.settings?.signature.isNullOrBlank()),
                        from = account?.accountEmail,
                        attachment = doc.filename,
                        canSend = email.isAvailable(ctx.tenant),
                        marksSent = doc.pendingQuote,
                    ),
                )
            }

            post("/send") {
                val ctx = call.dashboardContext() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val request = runCatching { call.receive<SendDocumentEmailRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                val doc = call.emailedDocument(ctx, mongo, pdf, request.type, request.id) ?: return@post
                val subject = request.subject.trim()
                val text = request.text.trim()
                val problem = when {
                    request.to.isBlank() -> "no_email"
                    subject.isEmpty() -> "empty_subject"
                    text.isEmpty() -> "empty_message"
                    subject.length > MAX_SUBJECT || text.length > MAX_TEXT || (request.cc?.length ?: 0) > MAX_CC -> "too_long"
                    else -> null
                }
                if (problem != null) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to problem))

                val outgoing = OutgoingEmail(
                    to = listOf(request.to.trim()),
                    subject = subject,
                    text = text,
                    cc = request.cc.orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() },
                    attachments = if (request.attachPdf) listOf(EmailAttachment(doc.filename, "application/pdf", doc.pdf())) else emptyList(),
                    clientId = doc.client.id,
                    record = SubjectRef.of(doc.type, doc.id),
                )
                // One drawer sends once, however often its button is pressed.
                val key = request.requestId?.takeIf { REQUEST_ID.matches(it) }?.let { "ui:${ctx.tenant.id.toHexString()}:$it" }
                when (val sent = email.send(ctx.tenant, outgoing, key)) {
                    is EmailSendResult.Sent -> {
                        val marked = doc.pendingQuote && !sent.alreadySent &&
                            QuoteRepository(mongo, ctx.tenant.id).update(doc.id, null, null, null, QuoteStatus.SENT) != null
                        call.respond(SentEmailDto(to = outgoing.to.single(), messageId = sent.messageId, alreadySent = sent.alreadySent, markedSent = marked))
                    }
                    is EmailSendResult.Failed -> call.respond(sent.httpStatus(), mapOf("error" to sent.key))
                }
            }

            get("/messages") {
                val ctx = call.dashboardContext() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                if (!ctx.requireModule(DashboardModules.CLIENTS)) return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "module_disabled"))
                val clientId = call.request.queryParameters["clientId"]?.toObjectIdOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_id"))
                call.respond(messages.forClient(ctx.tenant.id, clientId).map { it.dto() })
            }
        }
    }
}

/** How the dashboard shows a refused send. */
internal fun EmailSendResult.Failed.httpStatus(): HttpStatusCode = when (key) {
    EmailService.DAILY_LIMIT, GmailClient.RATE_LIMITED -> HttpStatusCode.TooManyRequests
    EmailService.NO_ACCOUNT, EmailService.NEEDS_RECONNECT -> HttpStatusCode.Conflict
    EmailService.INVALID_RECIPIENT, EmailService.TOO_MANY_RECIPIENTS, EmailService.INVALID_MESSAGE, EmailService.TOO_LARGE -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadGateway
}

private const val MAX_SUBJECT = 300
private const val MAX_TEXT = 20_000
private const val MAX_CC = 1000
private val REQUEST_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

private class EmailedDocument(
    val type: String,
    val id: ObjectId,
    val number: String,
    val client: Client,
    /** Emailing a pending quote is sending it. */
    val pendingQuote: Boolean,
    val pdf: () -> ByteArray,
) {
    val filename: String get() = "$number.pdf"
}

private suspend fun ApplicationCall.emailedDocument(ctx: DashboardContext, mongo: MongoModule, pdf: PdfGenerator, type: String?, rawId: String?): EmailedDocument? {
    val module = when (type) {
        SubjectTypes.QUOTE -> DashboardModules.QUOTES
        SubjectTypes.INVOICE -> DashboardModules.INVOICES
        else -> {
            respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_type"))
            return null
        }
    }
    if (!ctx.requireModule(module)) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "module_disabled"))
        return null
    }
    val id = rawId?.toObjectIdOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_id"))
        return null
    }
    val template = ctx.tenant.documentTemplate.withCompanyFallback(ctx.tenant.name)
    val clients = ClientRepository(mongo, ctx.tenant.id)
    val doc = if (type == SubjectTypes.QUOTE) {
        QuoteRepository(mongo, ctx.tenant.id).findById(id)?.let { quote ->
            clients.findById(quote.clientId)?.let { client ->
                EmailedDocument(type, id, quote.number, client, quote.status == QuoteStatus.PENDENTE) { pdf.generateQuote(quote, client, template) }
            }
        }
    } else {
        InvoiceRepository(mongo, ctx.tenant.id).findById(id)?.let { invoice ->
            clients.findById(invoice.clientId)?.let { client ->
                EmailedDocument(type, id, invoice.number, client, pendingQuote = false) { pdf.generateInvoice(invoice, client, template) }
            }
        }
    }
    if (doc == null) respond(HttpStatusCode.NotFound)
    return doc
}

@Serializable
data class EmailStatusDto(
    /** The platform has a Google OAuth client; false hides email everywhere. */
    val configured: Boolean,
    /** The company's default account can send now. */
    val canSend: Boolean,
    val from: String? = null,
)

@Serializable
data class EmailDraftDto(
    val to: String? = null,
    val subject: String,
    val text: String,
    val from: String? = null,
    val attachment: String,
    val canSend: Boolean,
    /** Sending moves a pending quote to sent. */
    val marksSent: Boolean = false,
)

@Serializable
data class SendDocumentEmailRequest(
    val type: String,
    val id: String,
    val to: String,
    val cc: String? = null,
    val subject: String,
    val text: String,
    val attachPdf: Boolean = true,
    /** Picked by the drawer when it opens. */
    val requestId: String? = null,
)

@Serializable
data class SentEmailDto(val to: String, val messageId: String, val alreadySent: Boolean = false, val markedSent: Boolean = false)

@Serializable
data class EmailMessageDto(
    val id: String,
    val direction: String,
    val from: String,
    val fromName: String? = null,
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val subject: String,
    val snippet: String,
    /** Null once the retention period dropped it ([bodyPurged]). */
    val body: String? = null,
    val bodyPurged: Boolean = false,
    val attachments: List<String> = emptyList(),
    val recordType: String? = null,
    val recordId: String? = null,
    val sentByType: String? = null,
    val sentBy: String? = null,
    val date: String,
)

private fun EmailMessage.dto() = EmailMessageDto(
    id = id.toHexString(),
    direction = direction.name,
    from = from,
    fromName = fromName,
    to = to,
    cc = cc,
    subject = subject,
    snippet = snippet,
    body = bodyText,
    bodyPurged = bodyPurgedAt != null,
    attachments = attachments.map { it.filename },
    recordType = record?.type,
    recordId = record?.id,
    sentByType = sentByType,
    sentBy = sentByName,
    date = date.toString(),
)
