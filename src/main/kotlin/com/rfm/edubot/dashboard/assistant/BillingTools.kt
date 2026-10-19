package com.rfm.edubot.dashboard.assistant

import com.mongodb.client.model.Filters
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.QuoteInvoicing
import com.rfm.edubot.crm.QuoteService
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.dashboard.DashboardModules
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.bson.Document
import org.bson.types.ObjectId

/**
 * Money coming in: `list_quotes`, `list_invoices`, `convert_quote_to_invoice` and `list_services` (work not invoiced
 * yet). Its list_quotes and list_invoices take the place of the bot's, and count overdue invoices the way Home does.
 */
internal class BillingTools(private val s: AssistantToolSession) : AssistantToolGroup {
    override val spec: ToolGroupSpec get() = Companion

    override suspend fun execute(name: String, args: JsonObject): JsonObject = when (name) {
        LIST_QUOTES -> listQuotes(args)
        LIST_INVOICES -> listInvoices(args)
        CONVERT_QUOTE -> convertQuote(args)
        LIST_SERVICES -> listServices(args)
        else -> throw ToolProblem("unknown_tool", "Unknown tool.")
    }

    override suspend fun describe(name: String, args: JsonObject): JsonObject? = when (name) {
        CONVERT_QUOTE -> {
            val quote = s.resolveQuote(args.text("quote_id") ?: return null)
            buildJsonObject {
                put("quote", quote.number)
                s.clients.findById(quote.clientId)?.let { put("client", s.clientLabel(it)) }
                put("total_eur", eur(quote.totalCents))
                args.text("due_date")?.let { put("due_date", it) }
            }
        }
        else -> null
    }

    override suspend fun check(name: String, args: JsonObject) {
        if (name == CONVERT_QUOTE) quoteToInvoice(args)
    }

    private suspend fun listQuotes(args: JsonObject): JsonObject {
        val today = s.today()
        val status = args.text("status")?.uppercase()
        val statuses = when (status) {
            null -> emptyList()
            "OPEN" -> listOf(QuoteStatus.PENDENTE, QuoteStatus.SENT)
            else -> listOf(QuoteStatus.entries.firstOrNull { it.name == status } ?: throw ToolProblem("invalid_status", "Use PENDENTE, SENT, ACEITO or OPEN."))
        }
        val clientId = args.text("client_id")?.let { s.resolveClient(it).id }
        val (from, until) = s.range(args, "created_from", "created_to")
        val found = s.quotes.search(clientId, statuses, from, until)
        val shown = found.take(s.limit(args))
        val names = s.clientNames(shown.map { it.clientId })
        val invoiced = if (DashboardModules.INVOICES in s.usable) invoicedQuotes(shown.map { it.id }) else emptyMap()
        return buildJsonObject {
            putJsonArray("quotes") { shown.forEach { add(s.quoteRow(it, today, names[it.clientId], invoiced[it.id])) } }
            putJsonObject("summary") {
                put("count", found.size)
                put("total_eur", eur(found.sumOf { it.totalCents }))
                QuoteStatus.entries.forEach { st -> put(st.name.lowercase(), found.count { it.status == st }) }
            }
            if (found.size > shown.size) put("more", found.size - shown.size)
        }
    }

    private suspend fun listInvoices(args: JsonObject): JsonObject {
        val today = s.today()
        val status = args.text("status")?.uppercase()
        val stored = when (status) {
            null -> emptyList()
            "OPEN", "PENDING", "OVERDUE" -> listOf(InvoiceStatus.PENDING, InvoiceStatus.OVERDUE)
            "PAID" -> listOf(InvoiceStatus.PAID)
            "CANCELLED" -> listOf(InvoiceStatus.CANCELLED)
            else -> throw ToolProblem("invalid_status", "Use PENDING, OVERDUE, OPEN, PAID or CANCELLED.")
        }
        val clientId = args.text("client_id")?.let { s.resolveClient(it).id }
        val (from, until) = s.range(args, "issued_from", "issued_to")
        val found = s.invoices.search(clientId, stored, from, until, args.date("due_from"), args.date("due_to"))
            .filter { status == null || status == "OPEN" || s.effective(it, today).name == status }
        val ordered = if (status in setOf("OPEN", "PENDING", "OVERDUE")) found.sortedBy { it.dueDate } else found
        val shown = ordered.take(s.limit(args))
        val names = s.clientNames(shown.map { it.clientId })
        val live = found.filter { it.status != InvoiceStatus.CANCELLED }
        val overdue = live.filter { s.effective(it, today) == InvoiceStatus.OVERDUE }
        return buildJsonObject {
            put("today", today.toString())
            putJsonArray("invoices") { shown.forEach { add(s.invoiceRow(it, today, names[it.clientId])) } }
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
        val quote = s.resolveQuote(args.text("quote_id") ?: throw missing("quote_id"))
        val dueDate = args.date("due_date") ?: throw ToolProblem("due_date_required", "Ask when the invoice is due (YYYY-MM-DD).")
        invoicedQuotes(listOf(quote.id))[quote.id]?.let { throw ToolProblem(QuoteInvoicing.ALREADY_INVOICED, "${quote.number} is already on invoice $it.") }
        return quote to dueDate
    }

    private suspend fun convertQuote(args: JsonObject): JsonObject {
        val (quote, dueDate) = quoteToInvoice(args)
        return when (val outcome = QuoteService(s.quotes, s.invoices, s.clients).invoice(quote.id, dueDate)) {
            is QuoteInvoicing.Outcome.Invoiced -> buildJsonObject {
                put("created", true)
                put("type", "invoice")
                put("id", outcome.invoice.id.toHexString())
                put("number", outcome.invoice.number)
                put("quote_number", quote.number)
                put("client", s.clientLabel(outcome.client))
                put("total_eur", eur(outcome.invoice.totalCents))
                put("due_date", outcome.invoice.dueDate.toString())
            }
            is QuoteInvoicing.Outcome.Refused -> throw ToolProblem(
                outcome.reason,
                when (outcome.reason) {
                    QuoteInvoicing.ALREADY_INVOICED -> "This quote already has an invoice that isn't cancelled."
                    else -> "That quote or its client no longer exists."
                },
            )
        }
    }

    /** The number of the invoice each of [quoteIds] is on, for those an invoice that isn't cancelled bills. */
    private suspend fun invoicedQuotes(quoteIds: List<ObjectId>): Map<ObjectId, String> {
        if (quoteIds.isEmpty()) return emptyMap()
        return s.mongo.database.getCollection<Document>("crm.invoices")
            .find(Filters.and(Filters.eq("tenantId", s.tenant.id), Filters.`in`("quoteId", quoteIds), Filters.ne("status", InvoiceStatus.CANCELLED.name)))
            .toList()
            .associate { it.getObjectId("quoteId") to it.getString("number").orEmpty() }
    }

    private suspend fun listServices(args: JsonObject): JsonObject {
        val status = args.text("status")?.uppercase()?.let { st ->
            ClientServiceStatus.entries.firstOrNull { it.name == st } ?: throw ToolProblem("invalid_status", "Use OPEN, INVOICED or CANCELLED.")
        }
        val clientId = args.text("client_id")?.let { s.resolveClient(it).id }
        val found = ClientServiceRepository(s.mongo, s.tenant.id).list(clientId, status)
        val shown = found.take(s.limit(args))
        val names = s.clientNames(shown.map { it.clientId })
        return buildJsonObject {
            putJsonArray("services") {
                shown.forEach { row ->
                    addJsonObject {
                        put("id", row.id.toHexString())
                        put("name", row.name)
                        put("client_id", row.clientId.toHexString())
                        names[row.clientId]?.let { (name, number) -> put("client_name", name); put("client_number", number) }
                        put("status", row.status.name)
                        row.performedAt?.let { put("done_on", it.toString()) }
                        put("total_eur", eur(row.totalCents))
                    }
                }
            }
            putJsonObject("summary") { put("count", found.size); put("total_eur", eur(found.sumOf { it.totalCents })) }
            if (found.size > shown.size) put("more", found.size - shown.size)
        }
    }

    companion object : ToolGroupSpec {
        const val LIST_QUOTES = "list_quotes"
        const val LIST_INVOICES = "list_invoices"
        const val CONVERT_QUOTE = "convert_quote_to_invoice"
        const val LIST_SERVICES = "list_services"

        override val definitions = listOf(
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
        )
        override val moduleOf = mapOf(
            LIST_QUOTES to DashboardModules.QUOTES,
            LIST_INVOICES to DashboardModules.INVOICES,
            CONVERT_QUOTE to DashboardModules.INVOICES,
            LIST_SERVICES to DashboardModules.SERVICES,
        )
        override val readOnly = setOf(LIST_QUOTES, LIST_INVOICES, LIST_SERVICES)
    }
}
