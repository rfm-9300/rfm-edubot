package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId

/**
 * A quote becoming its invoice, from the Quotes page or the dashboard assistant: same lines, and the quote turns accepted.
 * A quote is billed once, by its invoice or by the Serviços row made from it ([QuoteServicing]), until that one is cancelled.
 */
object QuoteInvoicing {
    const val QUOTE_NOT_FOUND = "quote_not_found"
    const val CLIENT_NOT_FOUND = "client_not_found"
    const val ALREADY_INVOICED = "already_invoiced"
    const val ALREADY_SERVICED = "already_serviced"

    sealed interface Outcome {
        data class Invoiced(val invoice: Invoice, val client: Client) : Outcome
        data class Refused(val reason: String) : Outcome
    }

    suspend fun invoice(
        quotes: QuoteRepository,
        invoices: InvoiceRepository,
        services: ClientServiceRepository,
        clients: ClientRepository,
        quoteId: ObjectId,
        dueDate: LocalDate,
    ): Outcome {
        val quote = quotes.findById(quoteId) ?: return Outcome.Refused(QUOTE_NOT_FOUND)
        val client = clients.findById(quote.clientId) ?: return Outcome.Refused(CLIENT_NOT_FOUND)
        billedBy(quote, invoices, services)?.let { return Outcome.Refused(it) }
        val invoice = invoices.create(quote.clientId, quote.id, quote.items, dueDate)
        quotes.update(quote.id, null, null, null, QuoteStatus.ACEITO)
        return Outcome.Invoiced(invoice, client)
    }

    /** [ALREADY_INVOICED] or [ALREADY_SERVICED] while [quote] has an invoice or a Serviços row that isn't cancelled. */
    suspend fun billedBy(quote: Quote, invoices: InvoiceRepository, services: ClientServiceRepository): String? = when {
        invoices.list(quote.clientId).any { it.quoteId == quote.id && it.status != InvoiceStatus.CANCELLED } -> ALREADY_INVOICED
        services.findByQuoteId(quote.id).any { it.status != ClientServiceStatus.CANCELLED } -> ALREADY_SERVICED
        else -> null
    }
}
