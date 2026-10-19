package com.rfm.edubot.dashboard.assistant

import com.mongodb.client.model.Filters
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PaymentRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.SupplierRepository
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.Payment
import com.rfm.edubot.crm.model.PaymentStatus
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.InboxService
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.DirectoryFields
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bson.Document
import org.bson.types.ObjectId

/**
 * What the assistant's tool groups share in one dashboard session: the company and its records, how a client,
 * quote, payment or chat named in a call is found, and how amounts, dates and rows read. [usable] are the
 * modules the assistant may use; [inbox] sends replies.
 */
internal class AssistantToolSession(
    val mongo: MongoModule,
    val ctx: DashboardContext,
    val usable: Collection<String>,
    val inbox: InboxService?,
) {
    val tenant: Tenant get() = ctx.tenant
    val zone: TimeZone = TimeZone.of(TenantTimeZones.normalize(tenant.timezone))
    val clients by lazy { ClientRepository(mongo, tenant.id) }
    val quotes by lazy { QuoteRepository(mongo, tenant.id) }
    val invoices by lazy { InvoiceRepository(mongo, tenant.id) }
    val payments by lazy { PaymentRepository(mongo, tenant.id) }

    // ---- lookups ----

    suspend fun resolveClient(ref: String): Client {
        val trimmed = ref.trim()
        if (ObjectId.isValid(trimmed)) return clients.findById(ObjectId(trimmed)) ?: throw ToolProblem("client_not_found", "No client with that id: search for it again.")
        val matches = clients.search(trimmed)
        val exact = matches.filter { it.number.equals(trimmed, true) || it.name.equals(trimmed, true) || it.phone == trimmed }
        return exact.singleOrNull() ?: matches.singleOrNull()
            ?: throw ToolProblem(if (matches.isEmpty()) "client_not_found" else "client_ambiguous", "Use search_clients to find the right client and pass its id.")
    }

    suspend fun resolveQuote(ref: String): Quote {
        val trimmed = ref.trim()
        val quote = if (ObjectId.isValid(trimmed)) quotes.findById(ObjectId(trimmed)) else quotes.search(limit = 1_000).firstOrNull { it.number.equals(trimmed, true) }
        return quote ?: throw ToolProblem("quote_not_found", "No quote with that id or number: use list_quotes.")
    }

    suspend fun resolvePayment(ref: String): Payment {
        val trimmed = ref.trim()
        val payment = if (ObjectId.isValid(trimmed)) payments.findById(ObjectId(trimmed)) else payments.list().firstOrNull { it.number.equals(trimmed, true) }
        return payment ?: throw ToolProblem("payment_not_found", "No payment with that id or number: use list_payments.")
    }

    suspend fun resolveConversation(ref: String): Conversation {
        val id = ref.trim().takeIf { ObjectId.isValid(it) }?.let(::ObjectId) ?: throw ToolProblem("conversation_not_found", "Use list_conversations and pass a conversation's id.")
        return ConversationRepository(mongo, tenant.id).findById(id) ?: throw ToolProblem("conversation_not_found", "Use list_conversations and pass a conversation's id.")
    }

    suspend fun payee(payment: Payment): String? = when {
        payment.supplierId != null -> SupplierRepository(mongo, tenant.id).findById(payment.supplierId)?.name
        payment.employeeId != null -> EmployeeRepository(mongo, tenant.id).findById(payment.employeeId)?.name
        else -> null
    }

    /** Name and number of each client, read in one query. */
    suspend fun clientNames(ids: Collection<ObjectId>): Map<ObjectId, Pair<String, String>> {
        if (ids.isEmpty()) return emptyMap()
        return mongo.database.getCollection<Document>("crm.clients")
            .find(Filters.and(Filters.eq("tenantId", tenant.id), Filters.`in`("_id", ids.distinct())))
            .toList()
            .associate { it.getObjectId("_id") to (it.getString("name").orEmpty() to it.getString("number").orEmpty()) }
    }

    fun clientJson(client: Client, fields: DirectoryFields) = buildJsonObject {
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

    fun clientLabel(client: Client) = "${client.name} (${client.number})"

    // ---- rows ----

    fun quoteRow(quote: Quote, today: LocalDate, client: Pair<String, String>?, invoice: String?) = buildJsonObject {
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

    fun invoiceRow(invoice: Invoice, today: LocalDate, client: Pair<String, String>?) = buildJsonObject {
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

    // ---- values ----

    fun today(): LocalDate = SystemClock.now().toLocalDateTime(zone).date

    fun localTime(at: Instant): String = at.toLocalDateTime(zone).let { "${it.date} ${"%02d:%02d".format(it.hour, it.minute)}" }

    /** Home's rule: a pending invoice past its due date counts as overdue before any job marks it. */
    fun effective(invoice: Invoice, today: LocalDate): InvoiceStatus =
        if (invoice.status == InvoiceStatus.PENDING && invoice.dueDate < today) InvoiceStatus.OVERDUE else invoice.status

    fun effective(payment: Payment, today: LocalDate = today()): PaymentStatus =
        if (payment.status == PaymentStatus.PENDING && payment.dueDate < today) PaymentStatus.OVERDUE else payment.status

    /** [from, until) in the company's timezone for the inclusive local dates named [fromKey] and [toKey]. */
    fun range(args: JsonObject, fromKey: String, toKey: String): Pair<Instant?, Instant?> =
        args.date(fromKey)?.atStartOfDayIn(zone) to args.date(toKey)?.plus(DatePeriod(days = 1))?.atStartOfDayIn(zone)

    fun limit(args: JsonObject): Int = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 50
    }
}

/** A call the assistant refuses: [code] is stable, [message] what the model reads to put it right. */
internal class ToolProblem(val code: String, override val message: String) : RuntimeException(message)

internal fun missing(field: String) = ToolProblem("${field}_required", "$field is required.")

/** A refusal as the tool result the model reads. */
internal fun toolError(code: String, message: String): JsonObject = buildJsonObject {
    put("error", code)
    put("message", message)
}

internal fun eur(cents: Long): Double = cents / 100.0

internal fun JsonObject.text(name: String): String? = raw(name)?.trim()?.takeIf { it.isNotEmpty() }
internal fun JsonObject.raw(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.has(name: String): Boolean = this[name] is JsonPrimitive
internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
internal fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
internal fun JsonObject.date(name: String): LocalDate? = text(name)?.let { value ->
    runCatching { LocalDate.parse(value.take(10)) }.getOrElse { throw ToolProblem("invalid_date", "$name must be a date like 2026-10-31.") }
}
