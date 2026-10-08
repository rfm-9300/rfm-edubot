package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.QuoteStatus
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId

/** A quote becoming its invoice, from the Quotes page or the dashboard assistant: same lines, and the quote turns accepted. */
object QuoteInvoicing {
    const val QUOTE_NOT_FOUND = "quote_not_found"
    const val CLIENT_NOT_FOUND = "client_not_found"
    const val ALREADY_INVOICED = "already_invoiced"

    sealed interface Outcome {
        data class Invoiced(val invoice: Invoice, val client: Client) : Outcome
        data class Refused(val reason: String) : Outcome
    }

    suspend fun invoice(
        quotes: QuoteRepository,
        invoices: InvoiceRepository,
        clients: ClientRepository,
        quoteId: ObjectId,
        dueDate: LocalDate,
    ): Outcome {
        val quote = quotes.findById(quoteId) ?: return Outcome.Refused(QUOTE_NOT_FOUND)
        val client = clients.findById(quote.clientId) ?: return Outcome.Refused(CLIENT_NOT_FOUND)
        // A cancelled invoice frees its quote to be invoiced again.
        if (invoices.list(quote.clientId).any { it.quoteId == quote.id && it.status != InvoiceStatus.CANCELLED }) {
            return Outcome.Refused(ALREADY_INVOICED)
        }
        val invoice = invoices.create(quote.clientId, quote.id, quote.items, dueDate)
        quotes.update(quote.id, null, null, null, QuoteStatus.ACEITO)
        return Outcome.Invoiced(invoice, client)
    }
}
