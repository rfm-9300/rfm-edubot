package com.rfm.edubot.dashboard

import com.mongodb.ErrorCategory
import com.mongodb.MongoServerException
import com.mongodb.client.model.Filters
import com.rfm.edubot.admin.CreateClientRequest
import com.rfm.edubot.agents.ai.UntrustedContent
import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.ai.tools.ToolCallContext
import com.rfm.edubot.ai.tools.ToolPack
import com.rfm.edubot.bookings.BookingRepository
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageAuthor
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.crm.ClientFields
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.CustomFields
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PaymentRepository
import com.rfm.edubot.crm.QuoteInvoicing
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.SupplierRepository
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.Payment
import com.rfm.edubot.crm.model.PaymentStatus
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.DirectoryFields
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.TenantTimeZones
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.bson.Document
import org.bson.types.ObjectId

/**
 * Tools only the dashboard assistant gets, because it works for the company's team: the business
 * overview, a client's whole record and changes to it, money in and out with real filters, and the inbox.
 * Its create_client, list_quotes and list_invoices take the place of the customer bot's versions, so the
 * assistant checks what the company requires of a client and counts overdue invoices the way Home does.
 * [usable] are the modules the assistant may use; [inbox] sends replies, and without it none are offered.
 */
internal class AssistantTools(
    private val mongo: MongoModule,
    private val ctx: DashboardContext,
    private val usable: Collection<String>,
    private val inbox: InboxService? = null,
) : ToolPack {
    private val tenant get() = ctx.tenant
    private val zone = TimeZone.of(TenantTimeZones.normalize(tenant.timezone))
    private val clients by lazy { ClientRepository(mongo, tenant.id) }
    private val quotes by lazy { QuoteRepository(mongo, tenant.id) }
    private val invoices by lazy { InvoiceRepository(mongo, tenant.id) }
    private val payments by lazy { PaymentRepository(mongo, tenant.id) }

    override val definitions: List<ToolDefinition> = DEFINITIONS.filter { offered(it.name) }
    override fun knows(name: String): Boolean = name in MODULE_OF
    override fun isReadOnly(name: String): Boolean = name in READ_ONLY
    override fun moduleOf(name: String): String? = MODULE_OF[name]

    private fun offered(name: String): Boolean = when (name) {
        CONVERT_QUOTE -> DashboardModules.QUOTES in usable && DashboardModules.INVOICES in usable
        REPLY -> inbox != null
        else -> true
    }

    override suspend fun execute(call: ToolCall, context: ToolCallContext): JsonObject = try {
        if (!offered(call.name)) throw Problem("not_available", "This isn't available here.")
        val args = call.arguments
        when (call.name) {
            OVERVIEW -> overview()
            GET_CLIENT -> getClient(args)
            CREATE_CLIENT -> createClient(args)
            UPDATE_CLIENT -> updateClient(args)
            LIST_QUOTES -> listQuotes(args)
            LIST_INVOICES -> listInvoices(args)
            CONVERT_QUOTE -> convertQuote(args)
            LIST_SERVICES -> listServices(args)
            LIST_PAYMENTS -> listPayments(args)
            MARK_PAYMENT_PAID -> markPaymentPaid(args)
            LIST_SUPPLIERS -> listSuppliers(args)
            LIST_EMPLOYEES -> listEmployees(args)
            LIST_CONVERSATIONS -> listConversations(args)
            GET_CONVERSATION -> getConversation(args)
            REPLY -> reply(args)
            else -> error("unknown_tool", "Unknown tool.")
        }
    } catch (e: Problem) {
        error(e.code, e.message)
    }

    override suspend fun describe(call: ToolCall): JsonObject? {
        val args = call.arguments
        return when (call.name) {
            UPDATE_CLIENT -> {
                val client = resolveClient(args.text("client_id") ?: return null)
                buildJsonObject {
                    put("client", clientLabel(client))
                    putJsonArray("changes") {
                        clientChanges(client, args).forEach { (field, from, to) ->
                            addJsonObject { put("field", field); from?.let { put("from", it) }; put("to", to) }
                        }
                    }
                }
            }
            CONVERT_QUOTE -> {
                val quote = resolveQuote(args.text("quote_id") ?: return null)
                buildJsonObject {
                    put("quote", quote.number)
                    clients.findById(quote.clientId)?.let { put("client", clientLabel(it)) }
                    put("total_eur", eur(quote.totalCents))
                    args.text("due_date")?.let { put("due_date", it) }
                }
            }
            MARK_PAYMENT_PAID -> {
                val payment = resolvePayment(args.text("payment_id") ?: return null)
                buildJsonObject {
                    put("payment", payment.number)
                    payee(payment)?.let { put("payee", it) }
                    put("total_eur", eur(payment.totalCents))
                    put("due_date", payment.dueDate.toString())
                    put("status", effective(payment).name)
                }
            }
            REPLY -> {
                val conversation = resolveConversation(args.text("conversation_id") ?: return null)
                buildJsonObject {
                    put("contact", contactName(conversation))
                    put("channel", conversation.channel.name)
                    args.text("text")?.let { put("text", it) }
                    if (conversation.channel == Platform.WHATSAPP) put("window_open", windowOpen(conversation))
                }
            }
            else -> null
        }
    }

    // ---- overview ----

    private suspend fun overview(): JsonObject {
        val o = OverviewService(mongo).build(tenant, extended = false, assistantOwnerKey = ctx.assistantOwnerKey())
        fun on(module: String) = module in usable
        return buildJsonObject {
            put("today", o.today)
            put("timezone", o.timezone)
            putJsonObject("overview") {
                if (on(DashboardModules.INVOICES)) o.cash?.let { c ->
                    putJsonObject("money_in") {
                        put("received_this_month_eur", eur(c.collectedThisMonthCents))
                        put("received_last_month_eur", eur(c.collectedLastMonthCents))
                        put("invoiced_this_month_eur", eur(c.issuedThisMonthCents))
                        put("outstanding_eur", eur(c.outstandingCents))
                        put("overdue_eur", eur(c.overdueCents))
                        put("overdue_invoices", c.overdueCount)
                        put("due_next_7_days_eur", eur(c.dueSoonCents))
                        put("due_next_7_days_invoices", c.dueSoonCount)
                    }
                    if (c.topOverdue.isNotEmpty()) {
                        putJsonArray("largest_overdue") {
                            c.topOverdue.forEach { addJsonObject { put("client", it.name); it.number?.let { n -> put("invoice", n) }; put("amount_eur", eur(it.amountCents)) } }
                        }
                    }
                }
                if (on(DashboardModules.PAYMENTS)) o.payments?.let { p ->
                    putJsonObject("money_out") {
                        put("paid_this_month_eur", eur(p.paidThisMonthCents))
                        put("to_pay_eur", eur(p.outstandingCents))
                        put("overdue_eur", eur(p.overdueCents))
                        put("overdue_payments", p.overdueCount)
                        put("due_next_7_days_eur", eur(p.dueSoonCents))
                        put("due_next_7_days_payments", p.dueSoonCount)
                    }
                }
                if (on(DashboardModules.QUOTES)) o.pipeline?.let { q ->
                    putJsonObject("quotes") {
                        put("open_eur", eur(q.openCents))
                        put("pending", q.pendingCount)
                        put("sent", q.sentCount)
                        put("accepted", q.acceptedCount)
                        put("accepted_this_month_eur", eur(q.acceptedThisMonthCents))
                        put("win_rate_pct", q.winRatePct)
                        put("expiring_soon", q.expiringSoonCount)
                    }
                }
                if (on(DashboardModules.CLIENTS)) o.customers?.let { c ->
                    putJsonObject("clients") { put("total", c.total); put("new_this_month", c.newThisMonth); put("new_last_month", c.newLastMonth) }
                }
                if (on(DashboardModules.SERVICES)) o.services?.let { s ->
                    putJsonObject("services") {
                        put("not_invoiced", s.openCount)
                        put("not_invoiced_eur", eur(s.openCents))
                        put("invoiced_this_month_eur", eur(s.invoicedThisMonthCents))
                    }
                }
                if (on(DashboardModules.CONVERSATIONS)) o.inbox?.let { i ->
                    putJsonObject("inbox") {
                        put("waiting_for_reply", i.waiting)
                        put("messages_today", i.messagesToday)
                        put("bot_paused_chats", i.autoReplyPaused)
                    }
                }
                if (on(DashboardModules.BOOKINGS)) o.calendar?.let { b ->
                    putJsonObject("bookings") {
                        put("today", b.today)
                        put("this_week", b.thisWeek)
                        put("waiting_confirmation", b.pending)
                        b.next?.let { next -> put("next", "${localTime(Instant.parse(next.startAt))} · ${next.contactName}") }
                    }
                }
                if (on(DashboardModules.EMPLOYEES)) o.employees?.let { e ->
                    putJsonObject("employees") { put("total", e.total); put("services_to_approve", e.pendingSubmissions) }
                }
                if (on(DashboardModules.AGENTS)) o.agents?.let { a ->
                    putJsonObject("agents") {
                        put("active", a.activeAgents)
                        put("approvals_waiting", a.pendingApprovals)
                        put("tasks_due", a.tasksDue)
                        put("failed_this_week", a.failedThisWeek)
                    }
                }
            }
            putJsonArray("needs_attention") {
                o.attention.filter { it.tab == DashboardModules.OVERVIEW || it.tab in usable }.take(10).forEach { item ->
                    addJsonObject {
                        put("kind", item.kind)
                        put("area", item.tab)
                        put("detail", if (item.tab in OUTSIDER_AREAS) UntrustedContent.wrap("attention.${item.kind}", item.detail) else item.detail)
                        item.amountCents?.let { put("amount_eur", eur(it)) }
                        item.at?.let { put("at", it) }
                    }
                }
            }
        }
    }

    // ---- clients ----

    private suspend fun getClient(args: JsonObject): JsonObject {
        val client = resolveClient(args.text("client_id") ?: throw missing("client_id"))
        val fields = ClientFields.of(tenant)
        val today = today()
        return buildJsonObject {
            put("client", clientJson(client, fields))
            if (DashboardModules.INVOICES in usable) {
                val all = invoices.search(clientId = client.id, limit = 500).filter { it.status != InvoiceStatus.CANCELLED }
                putJsonObject("invoices") {
                    put("count", all.size)
                    put("total_eur", eur(all.sumOf { it.totalCents }))
                    put("paid_eur", eur(all.sumOf { it.paidCents }))
                    put("outstanding_eur", eur(all.sumOf { it.outstandingCents }))
                    val overdue = all.filter { effective(it, today) == InvoiceStatus.OVERDUE }
                    put("overdue", overdue.size)
                    put("overdue_eur", eur(overdue.sumOf { it.outstandingCents }))
                    putJsonArray("latest") { all.take(5).forEach { add(invoiceRow(it, today, null)) } }
                }
            }
            if (DashboardModules.QUOTES in usable) {
                val all = quotes.search(clientId = client.id, limit = 500)
                putJsonObject("quotes") {
                    put("count", all.size)
                    val open = all.filter { it.status != QuoteStatus.ACEITO }
                    put("open", open.size)
                    put("open_eur", eur(open.sumOf { it.totalCents }))
                    put("accepted", all.count { it.status == QuoteStatus.ACEITO })
                    putJsonArray("latest") { all.take(5).forEach { add(quoteRow(it, today, null, null)) } }
                }
            }
            if (DashboardModules.SERVICES in usable) {
                val open = ClientServiceRepository(mongo, tenant.id).list(client.id, ClientServiceStatus.OPEN)
                putJsonObject("services_not_invoiced") { put("count", open.size); put("total_eur", eur(open.sumOf { it.totalCents })) }
            }
            if (DashboardModules.BOOKINGS in usable) {
                BookingRepository(mongo, tenant.id).list(from = SystemClock.now(), clientId = client.id).firstOrNull()?.let { next ->
                    put("next_booking", "${localTime(next.startAt)} · ${next.serviceName} · ${next.status.name}")
                }
            }
        }
    }

    override suspend fun check(call: ToolCall): JsonObject? = try {
        val args = call.arguments
        when (call.name) {
            CREATE_CLIENT -> newClient(args).let { null }
            UPDATE_CLIENT -> clientUpdate(args).let { null }
            CONVERT_QUOTE -> quoteToInvoice(args).let { null }
            MARK_PAYMENT_PAID -> openPayment(args).let { null }
            REPLY -> replyTarget(args).let { null }
            else -> null
        }
    } catch (e: Problem) {
        error(e.code, e.message)
    }

    /** A new client's details, checked like the dashboard's form checks them. */
    private class NewClient(val request: CreateClientRequest, val custom: Map<String, JsonPrimitive?>)

    private suspend fun newClient(args: JsonObject): NewClient {
        val name = args.text("name") ?: throw missing("name")
        val phone = args.text("phone") ?: throw missing("phone")
        val fields = ClientFields.of(tenant)
        val request = CreateClientRequest(
            name = name,
            phone = phone,
            address = args.text("address"),
            email = args.text("email"),
            taxId = args.text("tax_id"),
            notes = args.text("notes"),
            postalCode = args.text("postal_code"),
            city = args.text("city"),
            contactPerson = args.text("contact_person"),
            customFields = customValues(args, fields),
        )
        request.requiredError(fields = fields)?.let { throw Problem(it, "The company requires ${FIELD_NAMES[it] ?: "this field"} for new clients: ask the person for it.") }
        request.detailsError()?.let { throw Problem(it, "${FIELD_NAMES[it]?.replaceFirstChar(Char::uppercase) ?: "A field"} isn't valid: check it with the person.") }
        val custom = customChanges(fields, request, emptyMap())
        clients.findByPhone(phone)?.let { throw phoneTaken(it) }
        return NewClient(request, custom)
    }

    private suspend fun createClient(args: JsonObject): JsonObject {
        val draft = newClient(args)
        val request = draft.request
        val client = try {
            clients.create(
                request.name, request.phone, request.address, request.email, request.taxId, request.notes,
                request.postalCode, request.city, request.contactPerson,
                customFields = draft.custom.mapNotNull { (key, value) -> value?.let { key to it } }.toMap(),
            )
        } catch (e: MongoServerException) {
            if (ErrorCategory.fromErrorCode(e.code) != ErrorCategory.DUPLICATE_KEY) throw e
            throw clients.findByPhone(request.phone)?.let(::phoneTaken) ?: Problem("phone_taken", "Another record already has this phone.")
        }
        return buildJsonObject {
            put("created", true)
            put("type", "client")
            put("id", client.id.toHexString())
            put("client", clientJson(client, ClientFields.of(tenant)))
        }
    }

    /** A change to an existing client, checked like the dashboard's form checks it. */
    private class ClientUpdate(val client: Client, val request: CreateClientRequest, val custom: Map<String, JsonPrimitive?>)

    private suspend fun clientUpdate(args: JsonObject): ClientUpdate {
        val client = resolveClient(args.text("client_id") ?: throw missing("client_id"))
        if (clientChanges(client, args).isEmpty()) throw Problem("nothing_to_change", "Say which details to change.")
        val fields = ClientFields.of(tenant)
        val phone = args.text("phone") ?: client.phone
        val request = CreateClientRequest(
            name = args.text("name") ?: client.name,
            phone = phone,
            address = if (args.has("address")) args.text("address") else client.address,
            email = args.raw("email"),
            taxId = args.raw("tax_id"),
            notes = args.raw("notes"),
            postalCode = args.raw("postal_code"),
            city = args.raw("city"),
            contactPerson = args.raw("contact_person"),
            customFields = customValues(args, fields),
        )
        request.requiredError(client, fields)?.let { throw Problem(it, "The company requires ${FIELD_NAMES[it] ?: "this field"}: it can't be emptied.") }
        request.detailsError()?.let { throw Problem(it, "${FIELD_NAMES[it]?.replaceFirstChar(Char::uppercase) ?: "A field"} isn't valid: check it with the person.") }
        val custom = customChanges(fields, request, client.customFields)
        if (args.has("phone")) clients.findByPhone(phone)?.takeIf { it.id != client.id }?.let { throw phoneTaken(it) }
        return ClientUpdate(client, request, custom)
    }

    private fun customChanges(fields: DirectoryFields, request: CreateClientRequest, stored: Map<String, JsonPrimitive>): Map<String, JsonPrimitive?> =
        when (val checked = CustomFields.values(fields.custom, request.customFields, stored)) {
            is CustomFields.Values.Valid -> checked.changes
            is CustomFields.Values.Invalid -> throw Problem(checked.error, "The field \"${fields.custom.firstOrNull { it.key == checked.field }?.label ?: checked.field}\" is missing or isn't valid: check it with the person.")
        }

    private suspend fun updateClient(args: JsonObject): JsonObject {
        val change = clientUpdate(args)
        val request = change.request
        val updated = try {
            clients.update(
                change.client.id, request.name, request.phone, request.address, request.email, request.taxId, request.notes,
                request.postalCode, request.city, request.contactPerson, customFieldChanges = change.custom,
            )
        } catch (e: MongoServerException) {
            if (ErrorCategory.fromErrorCode(e.code) != ErrorCategory.DUPLICATE_KEY) throw e
            throw Problem("phone_taken", "Another record already has this phone.")
        } ?: throw Problem("client_not_found", "That client no longer exists.")
        return buildJsonObject {
            put("updated", true)
            put("type", "client")
            put("id", updated.id.toHexString())
            put("client", clientJson(updated, ClientFields.of(tenant)))
        }
    }

    /** (field, from, to) for each detail [args] would change on [client]. */
    private fun clientChanges(client: Client, args: JsonObject): List<Triple<String, String?, String>> {
        val current = mapOf(
            "name" to client.name, "phone" to client.phone, "email" to client.email, "tax_id" to client.taxId,
            "address" to client.address, "postal_code" to client.postalCode, "city" to client.city,
            "contact_person" to client.contactPerson, "notes" to client.notes,
        )
        val standard = current.mapNotNull { (field, from) ->
            if (!args.has(field)) return@mapNotNull null
            val to = args.raw(field)?.trim().orEmpty()
            if (to == from.orEmpty()) null else Triple(field, from, to)
        }
        val fields = ClientFields.of(tenant)
        val custom = (args["custom_fields"] as? JsonObject).orEmpty().mapNotNull { (label, value) ->
            val field = fields.custom.firstOrNull { it.label.equals(label.trim(), ignoreCase = true) || it.key == label } ?: return@mapNotNull null
            val to = (value as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val from = client.customFields[field.key]?.contentOrNull
            if (to == from.orEmpty()) null else Triple(field.label, from, to)
        }
        return standard + custom
    }

    /** The custom values in [args], keyed by field key as [CustomFields.values] expects; labels are accepted. */
    private fun customValues(args: JsonObject, fields: DirectoryFields): Map<String, JsonElement>? {
        val given = args["custom_fields"] as? JsonObject ?: return null
        return given.mapNotNull { (name, value) ->
            val field = fields.custom.firstOrNull { it.label.equals(name.trim(), ignoreCase = true) || it.key == name } ?: return@mapNotNull null
            field.key to value
        }.toMap()
    }

    private fun phoneTaken(owner: Client) = Problem(
        "phone_taken",
        "${owner.name} (${owner.number}) already has this phone${if (owner.archivedAt != null) " (archived — it can be restored in Clients)" else ""}. Use that client or ask for another number.",
    )

    // ---- quotes and invoices ----

    private suspend fun listQuotes(args: JsonObject): JsonObject {
        val today = today()
        val status = args.text("status")?.uppercase()
        val statuses = when (status) {
            null -> emptyList()
            "OPEN" -> listOf(QuoteStatus.PENDENTE, QuoteStatus.SENT)
            else -> listOf(QuoteStatus.entries.firstOrNull { it.name == status } ?: throw Problem("invalid_status", "Use PENDENTE, SENT, ACEITO or OPEN."))
        }
        val clientId = args.text("client_id")?.let { resolveClient(it).id }
        val (from, until) = range(args, "created_from", "created_to")
        val found = quotes.search(clientId, statuses, from, until)
        val shown = found.take(limit(args))
        val names = clientNames(shown.map { it.clientId })
        val invoiced = if (DashboardModules.INVOICES in usable) invoicedQuotes(shown.map { it.id }) else emptyMap()
        return buildJsonObject {
            putJsonArray("quotes") { shown.forEach { add(quoteRow(it, today, names[it.clientId], invoiced[it.id])) } }
            putJsonObject("summary") {
                put("count", found.size)
                put("total_eur", eur(found.sumOf { it.totalCents }))
                QuoteStatus.entries.forEach { s -> put(s.name.lowercase(), found.count { it.status == s }) }
            }
            if (found.size > shown.size) put("more", found.size - shown.size)
        }
    }

    private suspend fun listInvoices(args: JsonObject): JsonObject {
        val today = today()
        val status = args.text("status")?.uppercase()
        val stored = when (status) {
            null -> emptyList()
            "OPEN", "PENDING", "OVERDUE" -> listOf(InvoiceStatus.PENDING, InvoiceStatus.OVERDUE)
            "PAID" -> listOf(InvoiceStatus.PAID)
            "CANCELLED" -> listOf(InvoiceStatus.CANCELLED)
            else -> throw Problem("invalid_status", "Use PENDING, OVERDUE, OPEN, PAID or CANCELLED.")
        }
        val clientId = args.text("client_id")?.let { resolveClient(it).id }
        val (from, until) = range(args, "issued_from", "issued_to")
        val found = invoices.search(clientId, stored, from, until, args.date("due_from"), args.date("due_to"))
            .filter { status == null || status == "OPEN" || effective(it, today).name == status }
        val ordered = if (status in setOf("OPEN", "PENDING", "OVERDUE")) found.sortedBy { it.dueDate } else found
        val shown = ordered.take(limit(args))
        val names = clientNames(shown.map { it.clientId })
        val live = found.filter { it.status != InvoiceStatus.CANCELLED }
        val overdue = live.filter { effective(it, today) == InvoiceStatus.OVERDUE }
        return buildJsonObject {
            put("today", today.toString())
            putJsonArray("invoices") { shown.forEach { add(invoiceRow(it, today, names[it.clientId])) } }
            putJsonObject("summary") {
                put("count", found.size)
                put("total_eur", eur(live.sumOf { it.totalCents }))
                put("paid_eur", eur(live.sumOf { it.paidCents }))
                put("outstanding_eur", eur(live.sumOf { it.outstandingCents }))
                put("overdue", overdue.size)
                put("overdue_eur", eur(overdue.sumOf { it.outstandingCents }))
            }
            if (ordered.size > shown.size) put("more", ordered.size - shown.size)
        }
    }

    private suspend fun quoteToInvoice(args: JsonObject): Pair<Quote, LocalDate> {
        val quote = resolveQuote(args.text("quote_id") ?: throw missing("quote_id"))
        val dueDate = args.date("due_date") ?: throw Problem("due_date_required", "Ask when the invoice is due (YYYY-MM-DD).")
        invoicedQuotes(listOf(quote.id))[quote.id]?.let { throw Problem(QuoteInvoicing.ALREADY_INVOICED, "${quote.number} is already on invoice $it.") }
        return quote to dueDate
    }

    private suspend fun convertQuote(args: JsonObject): JsonObject {
        val (quote, dueDate) = quoteToInvoice(args)
        return when (val outcome = QuoteInvoicing.invoice(quotes, invoices, clients, quote.id, dueDate)) {
            is QuoteInvoicing.Outcome.Invoiced -> buildJsonObject {
                put("created", true)
                put("type", "invoice")
                put("id", outcome.invoice.id.toHexString())
                put("number", outcome.invoice.number)
                put("quote_number", quote.number)
                put("client", clientLabel(outcome.client))
                put("total_eur", eur(outcome.invoice.totalCents))
                put("due_date", outcome.invoice.dueDate.toString())
            }
            is QuoteInvoicing.Outcome.Refused -> throw Problem(
                outcome.reason,
                when (outcome.reason) {
                    QuoteInvoicing.ALREADY_INVOICED -> "This quote already has an invoice that isn't cancelled."
                    else -> "That quote or its client no longer exists."
                },
            )
        }
    }

    private suspend fun invoicedQuotes(quoteIds: List<ObjectId>): Map<ObjectId, String> {
        if (quoteIds.isEmpty()) return emptyMap()
        return mongo.database.getCollection<Document>("crm.invoices")
            .find(Filters.and(Filters.eq("tenantId", tenant.id), Filters.`in`("quoteId", quoteIds), Filters.ne("status", InvoiceStatus.CANCELLED.name)))
            .toList()
            .associate { it.getObjectId("quoteId") to it.getString("number").orEmpty() }
    }

    private fun quoteRow(quote: Quote, today: LocalDate, client: Pair<String, String>?, invoice: String?) = buildJsonObject {
        put("id", quote.id.toHexString())
        put("number", quote.number)
        put("client_id", quote.clientId.toHexString())
        client?.let { (name, number) -> put("client_name", name); put("client_number", number) }
        put("status", quote.status.name)
        put("created", quote.createdAt.toLocalDateTime(zone).date.toString())
        quote.validUntil?.let {
            put("valid_until", it.toString())
            if (it < today && quote.status != QuoteStatus.ACEITO) put("expired", true)
        }
        put("total_eur", eur(quote.totalCents))
        put("lines", quote.items.size)
        invoice?.let { put("invoice", it) }
    }

    private fun invoiceRow(invoice: Invoice, today: LocalDate, client: Pair<String, String>?) = buildJsonObject {
        val status = effective(invoice, today)
        put("id", invoice.id.toHexString())
        put("number", invoice.number)
        put("client_id", invoice.clientId.toHexString())
        client?.let { (name, number) -> put("client_name", name); put("client_number", number) }
        put("status", status.name)
        put("issued", invoice.createdAt.toLocalDateTime(zone).date.toString())
        put("due_date", invoice.dueDate.toString())
        if (status == InvoiceStatus.OVERDUE) put("days_overdue", today.toEpochDays() - invoice.dueDate.toEpochDays())
        put("total_eur", eur(invoice.totalCents))
        put("paid_eur", eur(invoice.paidCents))
        put("outstanding_eur", eur(invoice.outstandingCents))
        if (invoice.installments.isNotEmpty()) put("installments_left", invoice.installments.count { it.paidAt == null })
    }

    // ---- services, payments, suppliers, employees ----

    private suspend fun listServices(args: JsonObject): JsonObject {
        val status = args.text("status")?.uppercase()?.let { s ->
            ClientServiceStatus.entries.firstOrNull { it.name == s } ?: throw Problem("invalid_status", "Use OPEN, INVOICED or CANCELLED.")
        }
        val clientId = args.text("client_id")?.let { resolveClient(it).id }
        val found = ClientServiceRepository(mongo, tenant.id).list(clientId, status)
        val shown = found.take(limit(args))
        val names = clientNames(shown.map { it.clientId })
        return buildJsonObject {
            putJsonArray("services") {
                shown.forEach { s ->
                    addJsonObject {
                        put("id", s.id.toHexString())
                        put("name", s.name)
                        put("client_id", s.clientId.toHexString())
                        names[s.clientId]?.let { (name, number) -> put("client_name", name); put("client_number", number) }
                        put("status", s.status.name)
                        s.performedAt?.let { put("done_on", it.toString()) }
                        put("total_eur", eur(s.totalCents))
                    }
                }
            }
            putJsonObject("summary") { put("count", found.size); put("total_eur", eur(found.sumOf { it.totalCents })) }
            if (found.size > shown.size) put("more", found.size - shown.size)
        }
    }

    private suspend fun listPayments(args: JsonObject): JsonObject {
        val today = today()
        val status = args.text("status")?.uppercase()
        if (status != null && status != "OPEN" && PaymentStatus.entries.none { it.name == status }) {
            throw Problem("invalid_status", "Use PENDING, OVERDUE, OPEN, PAID or CANCELLED.")
        }
        val query = args.text("query")?.lowercase()
        val dueFrom = args.date("due_from")
        val dueTo = args.date("due_to")
        val rows = payments.list().map { it to payee(it) }.filter { (payment, payee) ->
            val state = effective(payment, today)
            (status == null || (status == "OPEN" && state in OPEN_PAYMENTS) || state.name == status) &&
                (query == null || payee?.lowercase()?.contains(query) == true || payment.number.lowercase() == query) &&
                (dueFrom == null || payment.dueDate >= dueFrom) && (dueTo == null || payment.dueDate <= dueTo)
        }
        val shown = rows.take(limit(args))
        val open = rows.filter { effective(it.first, today) in OPEN_PAYMENTS }
        return buildJsonObject {
            put("today", today.toString())
            putJsonArray("payments") {
                shown.forEach { (p, payee) ->
                    addJsonObject {
                        put("id", p.id.toHexString())
                        put("number", p.number)
                        payee?.let { put("payee", it) }
                        put("payee_type", if (p.supplierId != null) "supplier" else "employee")
                        put("status", effective(p, today).name)
                        put("due_date", p.dueDate.toString())
                        put("amount_eur", eur(p.totalCents))
                        p.paidAt?.let { put("paid_on", it.toLocalDateTime(zone).date.toString()) }
                        p.notes?.let { put("notes", it) }
                    }
                }
            }
            putJsonObject("summary") {
                put("count", rows.size)
                put("total_eur", eur(rows.filter { it.first.status != PaymentStatus.CANCELLED }.sumOf { it.first.totalCents }))
                put("to_pay_eur", eur(open.sumOf { it.first.totalCents }))
                put("overdue", open.count { effective(it.first, today) == PaymentStatus.OVERDUE })
            }
            if (rows.size > shown.size) put("more", rows.size - shown.size)
        }
    }

    private suspend fun openPayment(args: JsonObject): Payment {
        val payment = resolvePayment(args.text("payment_id") ?: throw missing("payment_id"))
        if (payment.status == PaymentStatus.CANCELLED) throw Problem("payment_cancelled", "${payment.number} is cancelled.")
        if (payment.status == PaymentStatus.PAID) throw Problem("already_paid", "${payment.number} is already paid.")
        return payment
    }

    private suspend fun markPaymentPaid(args: JsonObject): JsonObject {
        val payment = openPayment(args)
        val paid = payments.markPaid(payment.id) ?: throw Problem("payment_not_found", "That payment no longer exists.")
        return buildJsonObject {
            put("updated", true)
            put("type", "payment")
            put("id", paid.id.toHexString())
            put("number", paid.number)
            put("status", paid.status.name)
        }
    }

    private suspend fun listSuppliers(args: JsonObject): JsonObject {
        val found = SupplierRepository(mongo, tenant.id).search(args.text("query").orEmpty())
        return buildJsonObject {
            putJsonArray("suppliers") {
                found.take(limit(args)).forEach { s ->
                    addJsonObject {
                        put("id", s.id.toHexString()); put("number", s.number); put("name", s.name); put("phone", s.phone)
                        s.type?.let { put("type", it) }
                        if (s.services.isNotEmpty()) put("services", JsonArray(s.services.map { JsonPrimitive(it.description) }))
                    }
                }
            }
        }
    }

    private suspend fun listEmployees(args: JsonObject): JsonObject {
        val found = EmployeeRepository(mongo, tenant.id).search(args.text("query").orEmpty())
        return buildJsonObject {
            putJsonArray("employees") {
                found.take(limit(args)).forEach { e ->
                    addJsonObject {
                        put("id", e.id.toHexString()); put("number", e.number); put("name", e.name); put("phone", e.phone)
                        e.role?.let { put("role", it) }
                    }
                }
            }
        }
    }

    // ---- conversations ----

    private suspend fun listConversations(args: JsonObject): JsonObject {
        val term = args.text("query")
        val users = UserRepository(mongo, tenant.id)
        val matches = term?.let { q -> users.list(q).map { it.id } }.orEmpty()
        val channel = args.text("channel")?.uppercase()?.let { c -> Platform.entries.firstOrNull { it.name == c } ?: throw Problem("invalid_channel", "Use WHATSAPP, INSTAGRAM or WEB.") }
        val waitingOnly = args.bool("waiting_only") == true
        val found = ConversationRepository(mongo, tenant.id).list(term, limit = 100, userIds = matches)
            .filter { channel == null || it.channel == channel }
        val last = MessageRepository(mongo, tenant.id).lastByConversationIds(found.map { it.id })
        val names = users.displayNamesByIds(found.map { it.userId })
        val rows = found.filter { !waitingOnly || last[it.id]?.role == UserRole.USER }
        val shown = rows.take(limit(args))
        val windows = shown.filter { it.channel == Platform.WHATSAPP }.associate { it.id to windowOpen(it) }
        return buildJsonObject {
            putJsonArray("conversations") {
                shown.forEach { c ->
                    addJsonObject {
                        put("id", c.id.toHexString())
                        put("contact", UntrustedContent.wrap("conversation.contactName", names[c.userId]?.takeIf { it.isNotBlank() } ?: c.waId))
                        put("channel", c.channel.name)
                        if (c.channel == Platform.WHATSAPP) put("phone", "+${c.waId}")
                        put("waiting", last[c.id]?.role == UserRole.USER)
                        put("unread", c.unreadCount)
                        last[c.id]?.let { put("last_message", UntrustedContent.wrap("conversation.lastMessage", preview(it).take(160))) }
                        put("last_at", localTime(c.lastMessageAt))
                        if (!c.autoReplyEnabled) put("bot_paused", true)
                        windows[c.id]?.let { put("reply_window_open", it) }
                    }
                }
            }
            put("count", rows.size)
        }
    }

    private suspend fun getConversation(args: JsonObject): JsonObject {
        val conversation = resolveConversation(args.text("conversation_id") ?: throw missing("conversation_id"))
        val messages = MessageRepository(mongo, tenant.id).threadByConversation(conversation.id, limit = (args.int("limit") ?: 20).coerceIn(1, 50))
        return buildJsonObject {
            put("id", conversation.id.toHexString())
            put("contact", UntrustedContent.wrap("conversation.contactName", contactName(conversation)))
            put("channel", conversation.channel.name)
            if (!conversation.autoReplyEnabled) put("bot_paused", true)
            if (conversation.channel == Platform.WHATSAPP) put("reply_window_open", windowOpen(conversation))
            putJsonArray("messages") {
                messages.forEach { m ->
                    addJsonObject {
                        val from = when {
                            m.role == UserRole.USER -> "customer"
                            m.author == MessageAuthor.AGENT -> "team"
                            m.author == MessageAuthor.AUTOMATION || m.origin?.startsWith("agent:") == true -> "automation"
                            else -> "bot"
                        }
                        put("from", from)
                        put("at", localTime(m.createdAt))
                        val text = preview(m)
                        put("text", if (from == "customer") UntrustedContent.wrap("event.text", text) else text)
                    }
                }
            }
        }
    }

    private suspend fun replyTarget(args: JsonObject): Pair<Conversation, String> {
        val conversation = resolveConversation(args.text("conversation_id") ?: throw missing("conversation_id"))
        val text = args.text("text") ?: throw missing("text")
        if (text.length > InboxService.MAX_TEXT_LENGTH) throw Problem("invalid_text", INBOX_HINTS.getValue("invalid_text"))
        if (conversation.channel == Platform.WEB) throw Problem("web_read_only", INBOX_HINTS.getValue("web_read_only"))
        if (conversation.channel == Platform.WHATSAPP && !windowOpen(conversation)) throw Problem("window_closed", INBOX_HINTS.getValue("window_closed"))
        return conversation to text
    }

    private suspend fun reply(args: JsonObject): JsonObject {
        val service = inbox ?: throw Problem("not_available", "Replies can't be sent here.")
        val (conversation, text) = replyTarget(args)
        val message = try {
            service.sendText(tenant, conversation, text, InboxService.Agent(ctx.user?.id?.toHexString(), ctx.user?.email))
        } catch (e: InboxError) {
            throw Problem(e.key, INBOX_HINTS[e.key] ?: "The message couldn't be sent.")
        }
        return buildJsonObject {
            put("sent", true)
            put("type", "conversation")
            put("id", conversation.id.toHexString())
            put("message_id", message.id.toHexString())
            put("contact", contactName(conversation))
        }
    }

    private suspend fun contactName(conversation: Conversation): String =
        UserRepository(mongo, tenant.id).findById(conversation.userId)?.displayName?.takeIf { it.isNotBlank() } ?: conversation.waId

    private suspend fun windowOpen(conversation: Conversation): Boolean {
        val last = conversation.lastInboundAt ?: MessageRepository(mongo, tenant.id).lastInboundAt(conversation.id)
        return inbox?.windowExpiresAt(conversation, last)?.let { SystemClock.now() < it } ?: (last != null && SystemClock.now() < last.plus(InboxService.WINDOW))
    }

    private fun preview(message: Message): String = when (val content = message.content) {
        is MessageContent.Text -> content.body
        is MessageContent.Template -> content.body
        is MessageContent.Image -> "[photo] ${content.caption.orEmpty()}".trim()
        is MessageContent.Audio -> "[voice message] ${content.transcription.orEmpty()}".trim()
        is MessageContent.Video -> "[video] ${content.caption.orEmpty()}".trim()
        is MessageContent.Document -> "[document ${content.fileName.orEmpty()}] ${content.caption.orEmpty()}".trim()
    }

    // ---- lookups ----

    private suspend fun resolveClient(ref: String): Client {
        val trimmed = ref.trim()
        if (ObjectId.isValid(trimmed)) return clients.findById(ObjectId(trimmed)) ?: throw Problem("client_not_found", "No client with that id: search for it again.")
        val matches = clients.search(trimmed)
        val exact = matches.filter { it.number.equals(trimmed, true) || it.name.equals(trimmed, true) || it.phone == trimmed }
        return exact.singleOrNull() ?: matches.singleOrNull()
            ?: throw Problem(if (matches.isEmpty()) "client_not_found" else "client_ambiguous", "Use search_clients to find the right client and pass its id.")
    }

    private suspend fun resolveQuote(ref: String): Quote {
        val trimmed = ref.trim()
        val quote = if (ObjectId.isValid(trimmed)) quotes.findById(ObjectId(trimmed)) else quotes.search(limit = 1_000).firstOrNull { it.number.equals(trimmed, true) }
        return quote ?: throw Problem("quote_not_found", "No quote with that id or number: use list_quotes.")
    }

    private suspend fun resolvePayment(ref: String): Payment {
        val trimmed = ref.trim()
        val payment = if (ObjectId.isValid(trimmed)) payments.findById(ObjectId(trimmed)) else payments.list().firstOrNull { it.number.equals(trimmed, true) }
        return payment ?: throw Problem("payment_not_found", "No payment with that id or number: use list_payments.")
    }

    private suspend fun resolveConversation(ref: String): Conversation {
        val id = ref.trim().takeIf { ObjectId.isValid(it) }?.let(::ObjectId) ?: throw Problem("conversation_not_found", "Use list_conversations and pass a conversation's id.")
        return ConversationRepository(mongo, tenant.id).findById(id) ?: throw Problem("conversation_not_found", "Use list_conversations and pass a conversation's id.")
    }

    private suspend fun payee(payment: Payment): String? = when {
        payment.supplierId != null -> SupplierRepository(mongo, tenant.id).findById(payment.supplierId)?.name
        payment.employeeId != null -> EmployeeRepository(mongo, tenant.id).findById(payment.employeeId)?.name
        else -> null
    }

    /** Name and number of each client, read in one query. */
    private suspend fun clientNames(ids: Collection<ObjectId>): Map<ObjectId, Pair<String, String>> {
        if (ids.isEmpty()) return emptyMap()
        return mongo.database.getCollection<Document>("crm.clients")
            .find(Filters.and(Filters.eq("tenantId", tenant.id), Filters.`in`("_id", ids.distinct())))
            .toList()
            .associate { it.getObjectId("_id") to (it.getString("name").orEmpty() to it.getString("number").orEmpty()) }
    }

    private fun clientJson(client: Client, fields: DirectoryFields) = buildJsonObject {
        put("id", client.id.toHexString())
        put("number", client.number)
        put("name", client.name)
        put("phone", client.phone)
        client.email?.let { put("email", it) }
        client.taxId?.let { put("tax_id", it) }
        client.address?.let { put("address", it) }
        client.postalCode?.let { put("postal_code", it) }
        client.city?.let { put("city", it) }
        client.contactPerson?.let { put("contact_person", it) }
        client.notes?.let { put("notes", it) }
        val custom = fields.custom.mapNotNull { field -> client.customFields[field.key]?.let { field.label to it } }
        if (custom.isNotEmpty()) putJsonObject("custom_fields") { custom.forEach { (label, value) -> put(label, value) } }
        if (client.archivedAt != null) put("archived", true)
        put("client_since", client.createdAt.toLocalDateTime(zone).date.toString())
    }

    private fun clientLabel(client: Client) = "${client.name} (${client.number})"

    // ---- helpers ----

    private fun today(): LocalDate = SystemClock.now().toLocalDateTime(zone).date

    private fun localTime(at: Instant): String = at.toLocalDateTime(zone).let { "${it.date} ${"%02d:%02d".format(it.hour, it.minute)}" }

    /** Home's rule: a pending invoice past its due date counts as overdue before any job marks it. */
    private fun effective(invoice: Invoice, today: LocalDate): InvoiceStatus =
        if (invoice.status == InvoiceStatus.PENDING && invoice.dueDate < today) InvoiceStatus.OVERDUE else invoice.status

    private fun effective(payment: Payment, today: LocalDate = today()): PaymentStatus =
        if (payment.status == PaymentStatus.PENDING && payment.dueDate < today) PaymentStatus.OVERDUE else payment.status

    /** [from, until) in the company's timezone for the inclusive local dates named [fromKey] and [toKey]. */
    private fun range(args: JsonObject, fromKey: String, toKey: String): Pair<Instant?, Instant?> =
        args.date(fromKey)?.atStartOfDayIn(zone) to args.date(toKey)?.plus(DatePeriod(days = 1))?.atStartOfDayIn(zone)

    private fun limit(args: JsonObject): Int = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    private fun JsonObject.text(name: String): String? = raw(name)?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.raw(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.has(name: String): Boolean = this[name] is JsonPrimitive
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
    private fun JsonObject.date(name: String): LocalDate? = text(name)?.let { value ->
        runCatching { LocalDate.parse(value.take(10)) }.getOrElse { throw Problem("invalid_date", "$name must be a date like 2026-10-31.") }
    }

    private fun eur(cents: Long): Double = cents / 100.0

    private class Problem(val code: String, override val message: String) : RuntimeException(message)

    private fun missing(field: String) = Problem("${field}_required", "$field is required.")

    private fun error(code: String, message: String): JsonObject = buildJsonObject {
        put("error", code)
        put("message", message)
    }

    companion object {
        const val OVERVIEW = "get_business_overview"
        const val GET_CLIENT = "get_client"
        const val CREATE_CLIENT = "create_client"
        const val UPDATE_CLIENT = "update_client"
        const val LIST_QUOTES = "list_quotes"
        const val LIST_INVOICES = "list_invoices"
        const val CONVERT_QUOTE = "convert_quote_to_invoice"
        const val LIST_SERVICES = "list_services"
        const val LIST_PAYMENTS = "list_payments"
        const val MARK_PAYMENT_PAID = "mark_payment_paid"
        const val LIST_SUPPLIERS = "list_suppliers"
        const val LIST_EMPLOYEES = "list_employees"
        const val LIST_CONVERSATIONS = "list_conversations"
        const val GET_CONVERSATION = "get_conversation"
        const val REPLY = "reply_to_conversation"

        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 50
        private val OPEN_PAYMENTS = setOf(PaymentStatus.PENDING, PaymentStatus.OVERDUE)
        private val OUTSIDER_AREAS = setOf(DashboardModules.CONVERSATIONS, DashboardModules.CONTACTS, DashboardModules.INSTAGRAM)

        val MODULE_OF: Map<String, String> = mapOf(
            OVERVIEW to DashboardModules.OVERVIEW,
            GET_CLIENT to DashboardModules.CLIENTS,
            CREATE_CLIENT to DashboardModules.CLIENTS,
            UPDATE_CLIENT to DashboardModules.CLIENTS,
            LIST_QUOTES to DashboardModules.QUOTES,
            LIST_INVOICES to DashboardModules.INVOICES,
            CONVERT_QUOTE to DashboardModules.INVOICES,
            LIST_SERVICES to DashboardModules.SERVICES,
            LIST_PAYMENTS to DashboardModules.PAYMENTS,
            MARK_PAYMENT_PAID to DashboardModules.PAYMENTS,
            LIST_SUPPLIERS to DashboardModules.SUPPLIERS,
            LIST_EMPLOYEES to DashboardModules.EMPLOYEES,
            LIST_CONVERSATIONS to DashboardModules.CONVERSATIONS,
            GET_CONVERSATION to DashboardModules.CONVERSATIONS,
            REPLY to DashboardModules.CONVERSATIONS,
        )

        val READ_ONLY: Set<String> = setOf(
            OVERVIEW, GET_CLIENT, LIST_QUOTES, LIST_INVOICES, LIST_SERVICES, LIST_PAYMENTS, LIST_SUPPLIERS, LIST_EMPLOYEES,
            LIST_CONVERSATIONS, GET_CONVERSATION,
        )

        /** The client form's error codes, as the field they are about. */
        private val FIELD_NAMES = mapOf(
            "tax_id_required" to "a tax number (NIF)",
            "email_required" to "an email",
            "contact_person_required" to "a contact person",
            "address_required" to "an address",
            "postal_code_required" to "a postal code",
            "city_required" to "a city",
            "invalid_email" to "the email",
            "tax_id_too_long" to "the tax number",
            "notes_too_long" to "the notes",
            "address_too_long" to "the address",
            "postal_code_too_long" to "the postal code",
            "city_too_long" to "the city",
            "contact_person_too_long" to "the contact person",
        )

        private val INBOX_HINTS = mapOf(
            "window_closed" to "WhatsApp's 24-hour window is closed: only an approved template can be sent now, from the Inbox.",
            "web_read_only" to "Website chats can't be answered from the dashboard.",
            "no_channel" to "That channel isn't connected anymore.",
            "invalid_text" to "The message is empty or too long (4096 characters at most).",
        )

        private val CLIENT_FIELDS = buildJsonObject {
            put("name", prop("string"))
            put("phone", prop("string", "With the country code, e.g. +351 912 345 678"))
            put("email", prop("string"))
            put("tax_id", prop("string", "Tax number (NIF)"))
            put("address", prop("string"))
            put("postal_code", prop("string"))
            put("city", prop("string"))
            put("contact_person", prop("string", "Who to talk to, for a company"))
            put("notes", prop("string", "Staff-only notes"))
            put("custom_fields", buildJsonObject { put("type", "object"); put("description", "The company's own client fields, by label") })
        }

        private val DATE = "YYYY-MM-DD"

        private val DEFINITIONS = listOf(
            tool(
                OVERVIEW,
                "The business at a glance, as on Home: money received, outstanding and overdue, bills to pay, open quotes, clients, " +
                    "work not invoiced, chats waiting for a reply, bookings, and what needs attention. Use it for \"how is business\" questions.",
                buildJsonObject {},
            ),
            tool(
                GET_CLIENT,
                "A client's full record: contact details, tax number, notes and the company's own fields, plus their invoices (outstanding, overdue), " +
                    "quotes, work not invoiced and next booking.",
                buildJsonObject { put("client_id", prop("string", "From search_clients (or the client's number, e.g. CLT-004)")) },
                listOf("client_id"),
            ),
            tool(
                CREATE_CLIENT,
                "Create a client. Search first so you don't duplicate one. The company may require more than name and phone.",
                CLIENT_FIELDS,
                listOf("name", "phone"),
            ),
            tool(
                UPDATE_CLIENT,
                "Change a client's details. Pass only the fields to change; an empty string clears an optional one.",
                buildJsonObject {
                    put("client_id", prop("string", "From search_clients"))
                    CLIENT_FIELDS.forEach { (key, value) -> put(key, value) }
                },
                listOf("client_id"),
            ),
            tool(
                LIST_QUOTES,
                "Find quotes, newest first, with a summary of the matches. OPEN means pending or sent; expired quotes are flagged.",
                buildJsonObject {
                    put("client_id", prop("string"))
                    put("status", prop("string", null, listOf("PENDENTE", "SENT", "ACEITO", "OPEN")))
                    put("created_from", prop("string", DATE))
                    put("created_to", prop("string", DATE))
                    put("limit", prop("integer", "Rows to return, 20 by default, 50 at most"))
                },
            ),
            tool(
                LIST_INVOICES,
                "Find invoices with a summary of all matches (total, paid, outstanding, overdue). OVERDUE includes pending invoices past their due date; " +
                    "OPEN is everything not paid or cancelled, sorted by due date. Dates filter the issue date or the due date.",
                buildJsonObject {
                    put("client_id", prop("string"))
                    put("status", prop("string", null, listOf("PENDING", "OVERDUE", "OPEN", "PAID", "CANCELLED")))
                    put("issued_from", prop("string", DATE))
                    put("issued_to", prop("string", DATE))
                    put("due_from", prop("string", DATE))
                    put("due_to", prop("string", DATE))
                    put("limit", prop("integer", "Rows to return, 20 by default, 50 at most"))
                },
            ),
            tool(
                CONVERT_QUOTE,
                "Invoice a quote: a new invoice with the quote's lines and the due date given; the quote becomes accepted.",
                buildJsonObject {
                    put("quote_id", prop("string", "The quote's id or number, e.g. ORC-003"))
                    put("due_date", prop("string", DATE))
                },
                listOf("quote_id", "due_date"),
            ),
            tool(
                LIST_SERVICES,
                "Work done for clients (Services): OPEN rows are not invoiced yet, INVOICED ones are on an invoice.",
                buildJsonObject {
                    put("client_id", prop("string"))
                    put("status", prop("string", null, listOf("OPEN", "INVOICED", "CANCELLED")))
                    put("limit", prop("integer"))
                },
            ),
            tool(
                LIST_PAYMENTS,
                "Bills the company pays to suppliers and employees, by due date. OVERDUE includes pending ones past their due date; OPEN is pending or overdue.",
                buildJsonObject {
                    put("status", prop("string", null, listOf("PENDING", "OVERDUE", "OPEN", "PAID", "CANCELLED")))
                    put("query", prop("string", "Part of the supplier's or employee's name, or the payment number"))
                    put("due_from", prop("string", DATE))
                    put("due_to", prop("string", DATE))
                    put("limit", prop("integer"))
                },
            ),
            tool(
                MARK_PAYMENT_PAID,
                "Mark a bill the company owes as paid.",
                buildJsonObject { put("payment_id", prop("string", "The payment's id or number, e.g. PAG-002")) },
                listOf("payment_id"),
            ),
            tool(LIST_SUPPLIERS, "Find suppliers by name, phone or type.", buildJsonObject { put("query", prop("string")); put("limit", prop("integer")) }),
            tool(LIST_EMPLOYEES, "Find employees by name, phone or role.", buildJsonObject { put("query", prop("string")); put("limit", prop("integer")) }),
            tool(
                LIST_CONVERSATIONS,
                "Customer chats (WhatsApp, Instagram, website), most recent first: who, channel, whether they wait for a reply, the last message " +
                    "and whether WhatsApp's 24-hour reply window is open.",
                buildJsonObject {
                    put("query", prop("string", "Part of the contact's name or phone"))
                    put("waiting_only", prop("boolean", "Only chats whose last message is from the customer"))
                    put("channel", prop("string", null, listOf("WHATSAPP", "INSTAGRAM", "WEB")))
                    put("limit", prop("integer"))
                },
            ),
            tool(
                GET_CONVERSATION,
                "The latest messages of one customer chat, oldest first, to summarise it or prepare a reply.",
                buildJsonObject { put("conversation_id", prop("string", "From list_conversations")); put("limit", prop("integer", "Messages, 20 by default")) },
                listOf("conversation_id"),
            ),
            tool(
                REPLY,
                "Send a text reply to a customer chat, from the person you're helping. Write the exact message the customer will read.",
                buildJsonObject { put("conversation_id", prop("string", "From list_conversations")); put("text", prop("string")) },
                listOf("conversation_id", "text"),
            ),
        )

        private fun tool(name: String, description: String, properties: JsonObject, required: List<String> = emptyList()) = ToolDefinition(
            name = name,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", properties)
                put("required", JsonArray(required.map(::JsonPrimitive)))
            },
        )

        private fun prop(type: String, description: String? = null, values: List<String>? = null): JsonObject = buildJsonObject {
            put("type", type)
            description?.let { put("description", it) }
            values?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
        }
    }
}
