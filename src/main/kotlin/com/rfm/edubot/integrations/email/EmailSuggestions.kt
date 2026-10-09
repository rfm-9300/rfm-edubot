package com.rfm.edubot.integrations.email

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.crm.model.Supplier
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.SubjectTypes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The dashboard actions the Email page offers for a received email; the dashboard opens each one's form. */
object EmailActionTypes {
    const val CLIENT_CREATE = "client.create"
    const val CLIENT_UPDATE = "client.update"
    const val QUOTE_CREATE = "quote.create"
    const val QUOTE_ACCEPT = "quote.accept"
    const val INVOICE_PAID = "invoice.paid"
    const val BOOKING_CREATE = "booking.create"
    const val BILL_CREATE = "bill.create"
    const val TASK_CREATE = "task.create"

    /** The record a done action points at, and the module that has to be on to take it. */
    val records: Map<String, Pair<String, String>> = mapOf(
        CLIENT_CREATE to (SubjectTypes.CLIENT to DashboardModules.CLIENTS),
        CLIENT_UPDATE to (SubjectTypes.CLIENT to DashboardModules.CLIENTS),
        QUOTE_CREATE to (SubjectTypes.QUOTE to DashboardModules.QUOTES),
        QUOTE_ACCEPT to (SubjectTypes.QUOTE to DashboardModules.QUOTES),
        INVOICE_PAID to (SubjectTypes.INVOICE to DashboardModules.INVOICES),
        BOOKING_CREATE to (SubjectTypes.BOOKING to DashboardModules.BOOKINGS),
        BILL_CREATE to (SubjectTypes.PAYMENT to DashboardModules.PAYMENTS),
        TASK_CREATE to (TASK to DashboardModules.AGENTS),
    )

    /** Actions on a record that already exists: each record gets its own suggestion. */
    val onRecord = setOf(QUOTE_ACCEPT, INVOICE_PAID)

    const val TASK = "task"
}

/**
 * One suggestion: [status] is null while it's open. [primary] ones are what the email asks for; the
 * rest are there when someone wants them. [prefill] carries the values the dashboard's form starts
 * with (a contact, lines, a date, an amount), straight from the email.
 */
data class EmailSuggestion(
    val type: String,
    val primary: Boolean,
    val status: EmailActionStatus? = null,
    val recordType: String? = null,
    val recordId: String? = null,
    val recordLabel: String? = null,
    val prefill: JsonObject = JsonObject(emptyMap()),
)

/** What the suggestions for one received email are made from. */
data class EmailSuggestionInput(
    val modules: Set<String>,
    val email: EmailMessage,
    val client: Client?,
    val facts: EmailFacts.Facts,
    val insights: EmailInsights?,
    /** Quotes and invoices the email names by number. */
    val quotes: List<Quote> = emptyList(),
    val invoices: List<Invoice> = emptyList(),
    /** The client's quotes and invoices still waiting, for a reply or payment that names none. */
    val clientOpenQuotes: List<Quote> = emptyList(),
    val clientOpenInvoices: List<Invoice> = emptyList(),
    /** A supplier whose name matches who sent a bill. */
    val supplier: Supplier? = null,
    val bookingServiceId: String? = null,
)

object EmailSuggestions {

    fun build(input: EmailSuggestionInput): List<EmailSuggestion> {
        val email = input.email
        if (email.direction != EmailDirection.INBOUND) return emptyList()
        val modules = input.modules
        val insights = input.insights
        val intent = insights?.intent
        val person = !email.automated
        val suggestions = mutableListOf<EmailSuggestion>()

        if (DashboardModules.CLIENTS in modules && person) {
            if (input.client == null) {
                // A supplier sending a bill isn't a client; mail the model couldn't place may or may not be one.
                if (intent != EmailIntents.SUPPLIER_BILL) {
                    suggestions += EmailSuggestion(EmailActionTypes.CLIENT_CREATE, primary = intent != EmailIntents.OTHER, prefill = newClient(input))
                }
            } else {
                clientUpdate(input)?.let { suggestions += it }
            }
        }

        if (DashboardModules.INVOICES in modules) {
            val named = input.invoices.filter { it.status.isOpen() }
            val candidates = named.ifEmpty {
                if (intent == EmailIntents.PAYMENT_SENT) listOfNotNull(pick(input.clientOpenInvoices, insights?.amountCents) { it.outstandingCents }) else emptyList()
            }
            candidates.forEach { invoice ->
                suggestions += EmailSuggestion(
                    EmailActionTypes.INVOICE_PAID,
                    primary = intent == EmailIntents.PAYMENT_SENT,
                    recordType = SubjectTypes.INVOICE,
                    recordId = invoice.id.toHexString(),
                    recordLabel = invoice.number,
                    prefill = buildJsonObject { put("amountCents", invoice.outstandingCents) },
                )
            }
        }

        if (DashboardModules.QUOTES in modules) {
            val replying = intent == EmailIntents.QUOTE_REPLY
            // A quote named in an email the model didn't read could be accepted; one it read as a refusal isn't.
            if (insights == null || (replying && insights.accepted != false)) {
                val named = input.quotes.filter { it.status != QuoteStatus.ACEITO }
                val candidates = named.ifEmpty {
                    if (replying && insights?.accepted == true) listOfNotNull(input.clientOpenQuotes.singleOrNull()) else emptyList()
                }
                candidates.forEach { quote ->
                    suggestions += EmailSuggestion(
                        EmailActionTypes.QUOTE_ACCEPT,
                        primary = replying && insights?.accepted == true,
                        recordType = SubjectTypes.QUOTE,
                        recordId = quote.id.toHexString(),
                        recordLabel = quote.number,
                        prefill = buildJsonObject { put("amountCents", quote.totalCents) },
                    )
                }
            }
            if (person && (insights == null || intent == EmailIntents.QUOTE_REQUEST)) {
                suggestions += EmailSuggestion(EmailActionTypes.QUOTE_CREATE, primary = intent == EmailIntents.QUOTE_REQUEST, prefill = quote(input))
            }
        }

        if (DashboardModules.BOOKINGS in modules && person && intent == EmailIntents.BOOKING_REQUEST) {
            suggestions += EmailSuggestion(EmailActionTypes.BOOKING_CREATE, primary = true, prefill = booking(input))
        }

        if (DashboardModules.PAYMENTS in modules) {
            val bill = intent == EmailIntents.SUPPLIER_BILL ||
                (insights == null && input.client == null && email.attachments.any { it.isPdf })
            if (bill) suggestions += EmailSuggestion(EmailActionTypes.BILL_CREATE, primary = intent == EmailIntents.SUPPLIER_BILL, prefill = bill(input))
        }

        if (DashboardModules.AGENTS in modules && person) {
            suggestions += EmailSuggestion(EmailActionTypes.TASK_CREATE, primary = intent == EmailIntents.COMPLAINT, prefill = task(input))
        }

        return settle(suggestions, email.actions)
    }

    /**
     * Marks what the team already did or set aside: dismissed suggestions go, done ones stay (with the record
     * they led to) after the open ones, and so do done actions no rule offers anymore, such as adding a sender
     * who is now a client.
     */
    private fun settle(suggestions: List<EmailSuggestion>, actions: List<EmailAction>): List<EmailSuggestion> {
        fun actionFor(s: EmailSuggestion) = actions.lastOrNull { it.type == s.type && (s.type !in EmailActionTypes.onRecord || it.recordId == s.recordId) }
        val settled = suggestions.mapNotNull { suggestion ->
            val action = actionFor(suggestion) ?: return@mapNotNull suggestion
            if (action.status == EmailActionStatus.DISMISSED) return@mapNotNull null
            suggestion.copy(
                status = action.status,
                recordType = action.recordType ?: suggestion.recordType,
                recordId = action.recordId ?: suggestion.recordId,
                recordLabel = action.recordLabel ?: suggestion.recordLabel,
            )
        }
        val shown = settled.map { it.type to it.recordId }.toSet()
        val doneElsewhere = actions
            .filter { it.status == EmailActionStatus.DONE && (it.type to it.recordId) !in shown }
            .map { EmailSuggestion(it.type, primary = false, status = it.status, recordType = it.recordType, recordId = it.recordId, recordLabel = it.recordLabel) }
        val open = settled.filter { it.status == null }
        return open.filter { it.primary } + open.filterNot { it.primary } + settled.filter { it.status != null } + doneElsewhere
    }

    private fun InvoiceStatus.isOpen() = this == InvoiceStatus.PENDING || this == InvoiceStatus.OVERDUE

    /** The one waiting record whose total matches [amountCents], or the only one waiting. */
    private fun <T> pick(open: List<T>, amountCents: Long?, total: (T) -> Long): T? =
        amountCents?.let { amount -> open.filter { total(it) == amount }.singleOrNull() } ?: open.singleOrNull()

    private fun senderAddress(email: EmailMessage) = EmailAddresses.normalize(email.replyTo) ?: email.from

    private fun newClient(input: EmailSuggestionInput): JsonObject {
        val contact = input.insights?.contact ?: EmailContact()
        val person = contact.name ?: input.email.fromName?.trim()?.takeIf { it.isNotEmpty() && !it.contains('@') }
        val company = contact.company?.takeIf { !it.equals(person, ignoreCase = true) }
        return buildJsonObject {
            put("name", company ?: person ?: input.email.from.substringBefore('@'))
            if (company != null && person != null) put("contactPerson", person)
            put("email", contact.email ?: senderAddress(input.email))
            (contact.phone ?: input.facts.phones.firstOrNull())?.let { put("phone", it) }
            (contact.taxId ?: input.facts.taxIds.firstOrNull())?.let { put("taxId", it) }
            contact.address?.let { put("address", it) }
            contact.postalCode?.let { put("postalCode", it) }
            contact.city?.let { put("city", it) }
        }
    }

    /** The details the email gives that the client's record is missing, when there are any. */
    private fun clientUpdate(input: EmailSuggestionInput): EmailSuggestion? {
        val client = input.client ?: return null
        val contact = input.insights?.contact ?: EmailContact()
        val missing = buildMap {
            if (client.taxId.isNullOrBlank()) (contact.taxId ?: input.facts.taxIds.firstOrNull())?.let { put("taxId", it) }
            if (client.address.isNullOrBlank()) contact.address?.let { put("address", it) }
            if (client.postalCode.isNullOrBlank()) contact.postalCode?.let { put("postalCode", it) }
            if (client.city.isNullOrBlank()) contact.city?.let { put("city", it) }
            if (client.email.isNullOrBlank()) put("email", contact.email ?: senderAddress(input.email))
            val person = contact.name?.takeIf { contact.company != null && !it.equals(client.name, ignoreCase = true) }
            if (client.contactPerson.isNullOrBlank()) person?.let { put("contactPerson", it) }
        }
        if (missing.isEmpty()) return null
        return EmailSuggestion(
            EmailActionTypes.CLIENT_UPDATE,
            primary = false,
            recordType = SubjectTypes.CLIENT,
            recordId = client.id.toHexString(),
            recordLabel = client.name,
            prefill = buildJsonObject { missing.forEach { (key, value) -> put(key, value) } },
        )
    }

    private fun quote(input: EmailSuggestionInput): JsonObject = buildJsonObject {
        input.client?.let { put("clientId", it.id.toHexString()) }
        put("items", items(input.insights))
        input.insights?.request?.let { put("notes", it) }
    }

    private fun items(insights: EmailInsights?): JsonArray = buildJsonArray {
        insights?.items?.forEach { item ->
            add(
                buildJsonObject {
                    put("description", item.description)
                    item.quantity?.let { put("quantity", it) }
                    item.unit?.let { put("unit", it) }
                },
            )
        }
    }

    private fun booking(input: EmailSuggestionInput): JsonObject = buildJsonObject {
        val insights = input.insights
        val contact = insights?.contact ?: EmailContact()
        input.client?.let { put("clientId", it.id.toHexString()) }
        put("contactName", input.client?.name ?: contact.name ?: input.email.fromName ?: input.email.from.substringBefore('@'))
        (input.client?.phone ?: contact.phone ?: input.facts.phones.firstOrNull())?.let { put("contactPhone", it) }
        insights?.date?.let { put("date", it) }
        insights?.time?.let { put("time", it) }
        input.bookingServiceId?.let { put("serviceId", it) }
        insights?.request?.let { put("notes", it) }
    }

    private fun bill(input: EmailSuggestionInput): JsonObject = buildJsonObject {
        val insights = input.insights
        val contact = insights?.contact ?: EmailContact()
        input.supplier?.let { put("supplierId", it.id.toHexString()) }
        put("supplierName", contact.company ?: contact.name ?: input.email.fromName ?: input.email.from.substringBefore('@'))
        (contact.phone ?: input.facts.phones.firstOrNull())?.let { put("supplierPhone", it) }
        contact.address?.let { put("supplierAddress", it) }
        insights?.amountCents?.let { put("amountCents", it) }
        insights?.dueDate?.let { put("dueDate", it) }
        put("description", insights?.documentNumber ?: input.email.subject.ifBlank { input.email.from })
    }

    private fun task(input: EmailSuggestionInput): JsonObject = buildJsonObject {
        put("name", input.client?.name ?: input.email.fromName ?: input.email.from)
        put("subject", input.email.subject)
        input.insights?.summary?.let { put("summary", it) }
        input.insights?.date?.let { put("dueDate", it) }
    }
}
