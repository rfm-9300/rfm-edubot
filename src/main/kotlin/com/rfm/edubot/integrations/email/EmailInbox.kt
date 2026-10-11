package com.rfm.edubot.integrations.email

import com.rfm.edubot.agents.actions.AiSteps
import com.rfm.edubot.agents.store.AgentTaskRepository
import com.rfm.edubot.bookings.BookableServiceRepository
import com.rfm.edubot.bookings.BookingRepository
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PaymentRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.SupplierRepository
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.crm.model.Supplier
import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.requireModule
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.integrations.ConnectionStatus
import com.rfm.edubot.integrations.IntegrationConnection
import com.rfm.edubot.integrations.IntegrationProviders
import com.rfm.edubot.integrations.canConnect
import com.rfm.edubot.integrations.canManageIntegrations
import com.rfm.edubot.integrations.google.GoogleIntegration
import com.rfm.edubot.integrations.google.GoogleScopes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Tenant
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.bson.types.ObjectId
import java.text.Normalizer

/**
 * The Email page: the company's Gmail threads (what inbox sync kept and what the dashboard and agents
 * sent), read state for the team, replies in thread, and for each received email the dashboard actions
 * it calls for, from what can be read without a model ([EmailFacts]) and what the model read
 * ([EmailInsightsService]). Actions run through the dashboard's own forms and endpoints; the page only
 * records what they led to, and files the sender's mail under a client once one is created.
 */
internal class EmailInbox(
    private val mongo: MongoModule,
    private val email: EmailService,
    private val google: GoogleIntegration,
    private val insights: EmailInsightsService,
    private val clock: () -> Instant = SystemClock::now,
) {
    private val messages = EmailMessageRepository(mongo, clock)

    sealed interface Result {
        data class Thread(val thread: EmailThreadDto) : Result

        /** [key] is a dashboard error key; [status] the HTTP status it goes out with. */
        data class Refused(val key: String, val status: Int) : Result
    }

    suspend fun status(ctx: DashboardContext): EmailInboxStatusDto {
        val accounts = google.connections.list(ctx.tenant.id, IntegrationProviders.GOOGLE)
        return EmailInboxStatusDto(
            configured = google.configured,
            inboxAvailable = google.inboxAvailable,
            canConnect = ctx.canConnect(),
            canManage = ctx.canManageIntegrations(),
            accounts = accounts.map { it.accountDto() },
            unread = messages.unreadThreads(ctx.tenant.id),
        )
    }

    suspend fun unread(tenant: Tenant): Int = messages.unreadThreads(tenant.id)

    suspend fun find(tenant: Tenant, id: ObjectId): EmailMessage? = messages.find(tenant.id, id)

    suspend fun threads(ctx: DashboardContext, filter: EmailThreadFilter, connectionId: ObjectId?, query: String?): List<EmailThreadRowDto> {
        val rows = messages.threads(ctx.tenant.id, filter, connectionId, query)
        val clients = clientNames(ctx, rows.mapNotNull { it.clientId })
        return rows.map { row ->
            val latest = row.latest
            val (name, address) = counterpart(latest)
            EmailThreadRowDto(
                id = latest.id.toHexString(),
                key = threadKey(latest),
                connectionId = latest.connectionId.toHexString(),
                subject = latest.subject,
                snippet = latest.snippet,
                direction = latest.direction.name,
                name = name,
                address = address,
                date = latest.date.toString(),
                count = row.count,
                unread = row.unread,
                hasAttachments = row.hasAttachments,
                automated = row.inbound > 0 && row.fromPeople == 0,
                clientId = row.clientId?.takeIf { it in clients }?.toHexString(),
                clientName = row.clientId?.let { clients[it] },
                intent = row.intent,
            )
        }
    }

    suspend fun thread(ctx: DashboardContext, message: EmailMessage): EmailThreadDto {
        val tenant = ctx.tenant
        val modules = DashboardModules.effectiveFor(tenant).toSet()
        val thread = messages.thread(message)
        val focus = thread.lastOrNull { it.direction == EmailDirection.INBOUND }
        val clientId = (focus?.clientId ?: thread.lastOrNull { it.clientId != null }?.clientId)
        val client = clientId?.takeIf { DashboardModules.CLIENTS in modules }?.let { ClientRepository(mongo, tenant.id).findById(it) }
        val connection = google.connections.find(tenant.id, message.connectionId)
        val template = tenant.documentTemplate
        val facts = focus?.let { EmailFacts.of(it.subject, it.bodyText, own = listOfNotNull(template.phone, template.taxId)) } ?: EmailFacts.Facts()
        val numbers = (facts.quoteNumbers + facts.invoiceNumbers + listOfNotNull(focus?.insights?.documentNumber).flatMap(EmailFacts::documentNumbers)).distinct()
        val quotes = if (DashboardModules.QUOTES in modules) quotesNamed(tenant, numbers) else emptyList()
        val invoices = if (DashboardModules.INVOICES in modules) invoicesNamed(tenant, numbers) else emptyList()
        val read = focus?.insights
        val suggestions = focus?.let {
            EmailSuggestions.build(
                EmailSuggestionInput(
                    modules = modules,
                    email = it,
                    client = client,
                    facts = facts,
                    insights = read,
                    quotes = quotes,
                    invoices = invoices,
                    clientOpenQuotes = client?.takeIf { DashboardModules.QUOTES in modules }?.let { c -> openQuotes(tenant, c) }.orEmpty(),
                    clientOpenInvoices = client?.takeIf { DashboardModules.INVOICES in modules }?.let { c -> openInvoices(tenant, c) }.orEmpty(),
                    supplier = read?.takeIf { insight -> insight.intent == EmailIntents.SUPPLIER_BILL && DashboardModules.PAYMENTS in modules }
                        ?.let { insight -> supplierNamed(tenant, listOfNotNull(insight.contact.company, insight.contact.name, it.fromName)) },
                    bookingServiceId = read?.serviceName?.takeIf { DashboardModules.BOOKINGS in modules }?.let { name -> bookingServiceId(tenant, name) },
                ),
            )
        }.orEmpty()
        val clientNames = clientNames(ctx, (quotes.map { it.clientId } + invoices.map { it.clientId }).distinct())
        return EmailThreadDto(
            key = threadKey(message),
            connectionId = message.connectionId.toHexString(),
            account = connection?.accountEmail,
            subject = thread.firstOrNull()?.subject ?: message.subject,
            messages = thread.map { it.messageDto() },
            client = client?.let { EmailClientDto(it.id.toHexString(), it.number, it.name, it.email, it.phone, it.archivedAt != null) },
            focusId = focus?.id?.toHexString(),
            insights = read?.dto(),
            insightsState = when {
                focus == null -> INSIGHTS_NONE
                read != null -> INSIGHTS_READY
                focus.bodyPurgedAt != null || focus.bodyText.isNullOrBlank() -> INSIGHTS_NO_TEXT
                else -> INSIGHTS_PENDING
            },
            autoAnalyze = focus != null && read == null && !focus.automated && !focus.bodyText.isNullOrBlank(),
            suggestions = suggestions.map { it.dto() },
            documents = quotes.map { EmailDocumentDto(SubjectTypes.QUOTE, it.id.toHexString(), it.number, it.status.name, it.totalCents, clientNames[it.clientId]) } +
                invoices.map { EmailDocumentDto(SubjectTypes.INVOICE, it.id.toHexString(), it.number, it.status.name, it.totalCents, clientNames[it.clientId]) },
            facts = EmailFactsDto(facts.phones, facts.taxIds),
            reply = replyDto(thread, connection),
        )
    }

    /** Marks the thread's received mail read; returns how many threads still wait to be read. */
    suspend fun markRead(tenant: Tenant, message: EmailMessage): Int {
        messages.markRead(tenant.id, messages.thread(message).filter { it.readAt == null }.map { it.id })
        return messages.unreadThreads(tenant.id)
    }

    /** Marks the thread's newest received email unread again. */
    suspend fun markUnread(tenant: Tenant, message: EmailMessage): Int {
        val focus = messages.thread(message).lastOrNull { it.direction == EmailDirection.INBOUND } ?: return messages.unreadThreads(tenant.id)
        messages.markUnread(tenant.id, focus.id)
        return messages.unreadThreads(tenant.id)
    }

    /** Has the model read the thread's newest received email, unless it already did and [refresh] is off. */
    suspend fun analyze(ctx: DashboardContext, message: EmailMessage, refresh: Boolean): Result {
        val tenant = ctx.tenant
        val thread = messages.thread(message)
        val focus = thread.lastOrNull { it.direction == EmailDirection.INBOUND } ?: return Result.Refused(NOTHING_RECEIVED, 409)
        if (focus.insights != null && !refresh) return Result.Thread(thread(ctx, message))
        val modules = DashboardModules.effectiveFor(tenant).toSet()
        val client = focus.clientId?.let { ClientRepository(mongo, tenant.id).findById(it) }
        val template = tenant.documentTemplate
        val facts = EmailFacts.of(focus.subject, focus.bodyText, own = listOfNotNull(template.phone, template.taxId))
        val numbers = facts.quoteNumbers + facts.invoiceNumbers
        val documents = (if (DashboardModules.QUOTES in modules) quotesNamed(tenant, numbers) else emptyList()).map { "${it.number} (quote, ${it.status.name.lowercase()}, ${euros(it.totalCents)})" } +
            (if (DashboardModules.INVOICES in modules) invoicesNamed(tenant, numbers) else emptyList()).map { "${it.number} (invoice, ${it.status.name.lowercase()}, ${euros(it.totalCents)})" }
        val services = if (DashboardModules.BOOKINGS in modules) BookableServiceRepository(mongo, tenant.id).list(activeOnly = true).map { it.name }.distinct() else emptyList()
        val context = EmailInsightsService.Context(
            companyName = template.withCompanyFallback(tenant.name).companyName.ifBlank { tenant.name },
            clientName = client?.name,
            documents = documents,
            services = services,
        )
        return when (val outcome = insights.analyze(tenant, focus, thread, context)) {
            is EmailInsightsService.Outcome.Read -> Result.Thread(thread(ctx, message))
            is EmailInsightsService.Outcome.Failed -> Result.Refused(
                outcome.key,
                when (outcome.key) {
                    AiSteps.TOKEN_BUDGET -> 429
                    EmailInsightsService.NO_TEXT -> 409
                    AiSteps.NO_RESULT -> 502
                    else -> 503
                },
            )
        }
    }

    /**
     * Answers the thread from the account it lives in: to the newest received email's Reply-To or sender, in
     * its Gmail thread, or, when nothing was received yet, to whoever the newest sent email went to.
     */
    suspend fun reply(ctx: DashboardContext, message: EmailMessage, request: EmailReplyRequest): Result {
        val text = request.text.trim()
        val cc = request.cc.orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        val problem = when {
            text.isEmpty() -> "empty_message"
            text.length > EmailMessage.MAX_BODY || (request.cc?.length ?: 0) > MAX_CC -> "too_long"
            else -> null
        }
        if (problem != null) return Result.Refused(problem, 400)
        val thread = messages.thread(message)
        val target = thread.lastOrNull { it.direction == EmailDirection.INBOUND } ?: thread.last()
        val to = replyAddress(target) ?: return Result.Refused(EmailService.INVALID_RECIPIENT, 400)
        val outgoing = OutgoingEmail(
            to = listOf(to),
            subject = EmailMessage.replySubject(target.subject),
            text = text,
            cc = cc,
            threadId = target.threadId,
            inReplyTo = target.messageIdHeader,
            clientId = thread.lastOrNull { it.clientId != null }?.clientId,
        )
        // One composer sends once, however often its button is pressed.
        val key = request.requestId?.takeIf { REQUEST_ID.matches(it) }?.let { "ui:${ctx.tenant.id.toHexString()}:$it" }
        return when (val sent = email.sendFrom(ctx.tenant, target.connectionId, outgoing, key)) {
            is EmailSendResult.Sent -> {
                messages.markRead(ctx.tenant.id, thread.filter { it.readAt == null }.map { it.id })
                Result.Thread(thread(ctx, message))
            }
            is EmailSendResult.Failed -> Result.Refused(sent.key, sent.httpStatus().value)
        }
    }

    /**
     * Records what someone did from a suggestion of the thread's newest received email: a record it led to
     * (checked to be the company's) or a suggestion set aside. A client created or completed from it also
     * gets the sender's mail filed under it.
     */
    suspend fun record(ctx: DashboardContext, message: EmailMessage, request: EmailActionRequest): Result {
        val tenant = ctx.tenant
        val (recordType, module) = EmailActionTypes.records[request.type] ?: return Result.Refused("invalid_action", 400)
        if (!ctx.requireModule(module)) return Result.Refused("module_disabled", 403)
        val status = when (request.status.lowercase()) {
            "done" -> EmailActionStatus.DONE
            "dismissed" -> EmailActionStatus.DISMISSED
            else -> return Result.Refused("invalid_status", 400)
        }
        val focus = messages.thread(message).lastOrNull { it.direction == EmailDirection.INBOUND } ?: return Result.Refused(NOTHING_RECEIVED, 409)
        val recordId = request.recordId?.takeIf { it.isNotBlank() }
        if (recordId == null && (status == EmailActionStatus.DONE || request.type in EmailActionTypes.onRecord)) return Result.Refused("record_required", 400)
        val label = recordId?.let { id -> recordLabel(tenant, recordType, id) ?: return Result.Refused("record_not_found", 404) }
        messages.recordAction(
            tenant.id,
            focus.id,
            EmailAction(
                type = request.type,
                status = status,
                recordType = recordId?.let { recordType },
                recordId = recordId,
                recordLabel = label,
                byName = ctx.user?.email ?: "operator",
                at = clock(),
            ),
        )
        if (status == EmailActionStatus.DONE && recordType == SubjectTypes.CLIENT && recordId != null) {
            messages.linkToClient(tenant.id, listOfNotNull(focus.from, focus.replyTo), ObjectId(recordId))
        }
        return Result.Thread(thread(ctx, message))
    }

    /** How the record reads, or null when it isn't the company's. */
    private suspend fun recordLabel(tenant: Tenant, type: String, rawId: String): String? {
        val id = runCatching { ObjectId(rawId) }.getOrNull() ?: return null
        return when (type) {
            SubjectTypes.CLIENT -> ClientRepository(mongo, tenant.id).findById(id)?.name
            SubjectTypes.QUOTE -> QuoteRepository(mongo, tenant.id).findById(id)?.number
            SubjectTypes.INVOICE -> InvoiceRepository(mongo, tenant.id).findById(id)?.number
            SubjectTypes.PAYMENT -> PaymentRepository(mongo, tenant.id).findById(id)?.number
            SubjectTypes.BOOKING -> BookingRepository(mongo, tenant.id).findById(id)?.let { it.serviceName.ifBlank { it.contactName } }
            EmailActionTypes.TASK -> AgentTaskRepository(mongo).findById(tenant.id, id)?.title
            else -> null
        }
    }

    private suspend fun clientNames(ctx: DashboardContext, ids: Collection<ObjectId>): Map<ObjectId, String> {
        if (ids.isEmpty() || DashboardModules.CLIENTS !in DashboardModules.effectiveFor(ctx.tenant)) return emptyMap()
        val clients = ClientRepository(mongo, ctx.tenant.id)
        return ids.distinct().mapNotNull { id -> clients.findById(id)?.let { id to it.name } }.toMap()
    }

    private suspend fun quotesNamed(tenant: Tenant, numbers: List<String>): List<Quote> {
        val repository = QuoteRepository(mongo, tenant.id)
        return numbers.filter { it.startsWith(EmailFacts.QUOTE_PREFIX) }.take(MAX_DOCUMENTS)
            .mapNotNull { number -> repository.findByNumber(number)?.takeIf { it.number.equals(number, ignoreCase = true) } }
    }

    private suspend fun invoicesNamed(tenant: Tenant, numbers: List<String>): List<Invoice> {
        val repository = InvoiceRepository(mongo, tenant.id)
        return numbers.filter { it.startsWith(EmailFacts.INVOICE_PREFIX) }.take(MAX_DOCUMENTS).mapNotNull { repository.findByNumber(it) }
    }

    private suspend fun openQuotes(tenant: Tenant, client: Client): List<Quote> =
        QuoteRepository(mongo, tenant.id).list(client.id).filter { it.status == QuoteStatus.SENT || it.status == QuoteStatus.PENDENTE }

    private suspend fun openInvoices(tenant: Tenant, client: Client): List<Invoice> =
        InvoiceRepository(mongo, tenant.id).list(client.id).filter { it.status == InvoiceStatus.PENDING || it.status == InvoiceStatus.OVERDUE }

    /** The supplier whose name is one of [names] (accents and case aside), or the only one whose name contains it. */
    private suspend fun supplierNamed(tenant: Tenant, names: List<String>): Supplier? {
        val suppliers = SupplierRepository(mongo, tenant.id)
        for (name in names.map { it.trim() }.filter { it.length >= 3 }.distinct()) {
            val found = suppliers.search(name)
            found.firstOrNull { fold(it.name) == fold(name) }?.let { return it }
            found.singleOrNull()?.takeIf { fold(it.name).contains(fold(name)) }?.let { return it }
        }
        return null
    }

    private suspend fun bookingServiceId(tenant: Tenant, name: String): String? =
        BookableServiceRepository(mongo, tenant.id).list(activeOnly = true).firstOrNull { it.name.equals(name, ignoreCase = true) }?.id

    private fun replyDto(thread: List<EmailMessage>, connection: IntegrationConnection?): EmailReplyDto {
        val target = thread.lastOrNull { it.direction == EmailDirection.INBOUND } ?: thread.lastOrNull()
        val canSend = google.configured && connection != null && connection.status == ConnectionStatus.ACTIVE && GoogleScopes.canSend(connection.scopes)
        return EmailReplyDto(
            to = target?.let(::replyAddress),
            subject = EmailMessage.replySubject(target?.subject.orEmpty()),
            canSend = canSend,
            problem = when {
                connection == null || !google.configured -> EmailService.NO_ACCOUNT
                !canSend -> EmailService.NEEDS_RECONNECT
                target?.direction == EmailDirection.INBOUND && target.automated -> AUTOMATED_SENDER
                else -> null
            },
        )
    }

    private fun replyAddress(target: EmailMessage): String? =
        if (target.direction == EmailDirection.INBOUND) EmailAddresses.normalize(target.replyTo) ?: target.from else target.to.firstOrNull()

    /** The other side of the conversation: who wrote the newest email, or whom it went to. */
    private fun counterpart(latest: EmailMessage): Pair<String, String> =
        if (latest.direction == EmailDirection.INBOUND) {
            (latest.fromName?.takeIf { it.isNotBlank() } ?: latest.from) to latest.from
        } else {
            val to = latest.to.firstOrNull().orEmpty()
            to to to
        }

    private fun IntegrationConnection.accountDto() = EmailInboxAccountDto(
        id = id.toHexString(),
        accountEmail = accountEmail,
        status = status.name,
        isDefault = isDefault,
        canSend = status == ConnectionStatus.ACTIVE && GoogleScopes.canSend(scopes),
        canRead = GoogleScopes.canRead(scopes),
        inboxSync = settings.inboxSync,
        inboxError = inbox.lastError,
        inboxSyncedAt = inbox.lastSyncedAt?.toString(),
    )

    private fun EmailMessage.messageDto() = EmailThreadMessageDto(
        id = id.toHexString(),
        direction = direction.name,
        from = from,
        fromName = fromName,
        replyTo = replyTo,
        to = to,
        cc = cc,
        subject = subject,
        body = bodyText,
        bodyPurged = bodyPurgedAt != null,
        snippet = snippet,
        attachments = attachments.map { it.filename },
        automated = automated,
        date = date.toString(),
        unread = direction == EmailDirection.INBOUND && readAt == null,
        sentByType = sentByType,
        sentBy = sentByName,
        recordType = record?.type,
        recordId = record?.id,
    )

    private fun EmailInsights.dto() = EmailInsightsDto(
        summary = summary,
        intent = intent,
        accepted = accepted,
        contact = EmailContactDto(contact.name, contact.company, contact.phone, contact.email, contact.taxId, contact.address, contact.postalCode, contact.city),
        request = request,
        items = items.map { EmailItemDto(it.description, it.quantity, it.unit) },
        date = date,
        time = time,
        dueDate = dueDate,
        amountCents = amountCents,
        documentNumber = documentNumber,
        serviceName = serviceName,
        reply = reply,
        generatedAt = generatedAt.toString(),
    )

    private fun EmailSuggestion.dto() = EmailSuggestionDto(type, primary, status?.name, recordType, recordId, recordLabel, prefill)

    companion object {
        const val INSIGHTS_READY = "ready"
        const val INSIGHTS_PENDING = "pending"
        const val INSIGHTS_NO_TEXT = "no_text"
        const val INSIGHTS_NONE = "none"
        const val NOTHING_RECEIVED = "nothing_received"
        const val AUTOMATED_SENDER = "automated_sender"
        private const val MAX_CC = 1000
        private const val MAX_DOCUMENTS = 5
        private val REQUEST_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

        /** The thread's identity in the page: its account and Gmail thread, or the message itself when it has none. */
        fun threadKey(message: EmailMessage): String = "${message.connectionId.toHexString()}:${message.threadId ?: message.id.toHexString()}"

        private fun fold(text: String): String =
            Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

        private fun euros(cents: Long): String = "%.2f €".format(java.util.Locale.ROOT, cents / 100.0)
    }
}

@Serializable
data class EmailInboxStatusDto(
    /** The platform has a Google OAuth client; without one nobody can connect Gmail. */
    val configured: Boolean,
    /** The platform lets companies read their inboxes (Google verified the restricted scopes). */
    val inboxAvailable: Boolean,
    /** Signed in as a company admin, who can connect Gmail and allow reading. */
    val canConnect: Boolean,
    /** A company admin or an operator, who can change an account's settings. */
    val canManage: Boolean,
    val accounts: List<EmailInboxAccountDto>,
    /** Threads with received mail nobody opened yet. */
    val unread: Int,
)

@Serializable
data class EmailInboxAccountDto(
    val id: String,
    val accountEmail: String,
    val status: String,
    val isDefault: Boolean,
    val canSend: Boolean,
    val canRead: Boolean,
    val inboxSync: Boolean,
    val inboxError: String?,
    val inboxSyncedAt: String?,
)

@Serializable
data class EmailThreadRowDto(
    /** The newest message's id, which the thread endpoints take. */
    val id: String,
    /** Stable while new mail arrives in the thread. */
    val key: String,
    val connectionId: String,
    val subject: String,
    val snippet: String,
    val direction: String,
    /** The other side of the conversation. */
    val name: String,
    val address: String,
    val date: String,
    val count: Int,
    val unread: Int,
    val hasAttachments: Boolean,
    /** Every received email in it came from a machine (a notification, a newsletter, an auto-reply). */
    val automated: Boolean,
    val clientId: String?,
    val clientName: String?,
    val intent: String?,
)

@Serializable
data class EmailThreadDto(
    val key: String,
    val connectionId: String,
    /** The company's account the thread lives in. */
    val account: String?,
    val subject: String,
    val messages: List<EmailThreadMessageDto>,
    val client: EmailClientDto?,
    /** The newest received email, which the suggestions and the model's reading are about. */
    val focusId: String?,
    val insights: EmailInsightsDto?,
    /** `ready`, `pending` (not read by the model yet), `no_text` (its text was dropped) or `none` (nothing received). */
    val insightsState: String,
    /** The page may have the model read it now: a person wrote it and its text is there. */
    val autoAnalyze: Boolean,
    val suggestions: List<EmailSuggestionDto>,
    /** Quotes and invoices the email names that the company has. */
    val documents: List<EmailDocumentDto>,
    val facts: EmailFactsDto,
    val reply: EmailReplyDto,
)

@Serializable
data class EmailThreadMessageDto(
    val id: String,
    val direction: String,
    val from: String,
    val fromName: String?,
    val replyTo: String?,
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    /** Null once the retention period dropped it ([bodyPurged]). */
    val body: String?,
    val bodyPurged: Boolean,
    val snippet: String,
    val attachments: List<String>,
    val automated: Boolean,
    val date: String,
    val unread: Boolean,
    val sentByType: String?,
    val sentBy: String?,
    val recordType: String?,
    val recordId: String?,
)

@Serializable
data class EmailClientDto(val id: String, val number: String, val name: String, val email: String?, val phone: String, val archived: Boolean)

@Serializable
data class EmailContactDto(
    val name: String?,
    val company: String?,
    val phone: String?,
    val email: String?,
    val taxId: String?,
    val address: String?,
    val postalCode: String?,
    val city: String?,
)

@Serializable
data class EmailItemDto(val description: String, val quantity: Double?, val unit: String?)

@Serializable
data class EmailInsightsDto(
    val summary: String,
    val intent: String,
    val accepted: Boolean?,
    val contact: EmailContactDto,
    val request: String?,
    val items: List<EmailItemDto>,
    val date: String?,
    val time: String?,
    val dueDate: String?,
    val amountCents: Long?,
    val documentNumber: String?,
    val serviceName: String?,
    val reply: String?,
    val generatedAt: String,
)

@Serializable
data class EmailSuggestionDto(
    val type: String,
    val primary: Boolean,
    /** Null while open, `DONE` once someone did it. */
    val status: String?,
    val recordType: String?,
    val recordId: String?,
    val recordLabel: String?,
    val prefill: JsonObject,
)

@Serializable
data class EmailDocumentDto(val type: String, val id: String, val number: String, val status: String, val totalCents: Long, val clientName: String?)

@Serializable
data class EmailFactsDto(val phones: List<String>, val taxIds: List<String>)

@Serializable
data class EmailReplyDto(
    val to: String?,
    val subject: String,
    val canSend: Boolean,
    /** Why a reply can't go out (`no_email_account`, `needs_reconnect`), or a warning (`automated_sender`). */
    val problem: String?,
)

@Serializable
data class EmailReplyRequest(val text: String, val cc: String? = null, val requestId: String? = null)

@Serializable
data class EmailActionRequest(val type: String, val status: String, val recordId: String? = null)
