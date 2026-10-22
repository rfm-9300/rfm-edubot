package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.LineItem
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.persistence.MongoModule
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId

/** What [QuoteService.delete] did. */
sealed interface QuoteDeletion {
    data class Deleted(val quote: Quote) : QuoteDeletion
    data object NotFound : QuoteDeletion
    data class Refused(val reason: String) : QuoteDeletion
}

/**
 * Writes to a company's quotes. The Quotes page, the backoffice, the bot's and the assistant's tools, agents and
 * the email drawer change quotes through it; numbering and the domain events are the repository's.
 */
class QuoteService(
    private val quotes: QuoteRepository,
    private val invoices: InvoiceRepository,
    private val clientServices: ClientServiceRepository,
    private val clients: ClientRepository,
) {
    suspend fun create(clientId: ObjectId, items: List<LineItem>, notes: String?, validUntil: LocalDate?): Quote =
        quotes.create(clientId, items, notes, validUntil)

    /** Changes what is given; a null keeps what is stored. */
    suspend fun revise(id: ObjectId, items: List<LineItem>?, notes: String?, validUntil: LocalDate?, status: QuoteStatus?): Quote? =
        quotes.update(id, items, notes, validUntil, status)

    suspend fun setStatus(id: ObjectId, status: QuoteStatus): Quote? = quotes.update(id, null, null, null, status)

    /** The quote's invoice, with its lines; the quote turns accepted. */
    suspend fun invoice(quoteId: ObjectId, dueDate: LocalDate): QuoteInvoicing.Outcome =
        QuoteInvoicing.invoice(quotes, invoices, clientServices, clients, quoteId, dueDate)

    /** The quote's Serviços row, with its lines, invoiced later with the client's other open work; the quote turns accepted. */
    suspend fun service(quoteId: ObjectId, performedAt: LocalDate, name: String?): QuoteServicing.Outcome =
        QuoteServicing.service(quotes, invoices, clientServices, clients, quoteId, performedAt, name)

    /**
     * Marks the quote billed by [projectId]'s measurements, from the first one: it can no longer be invoiced or become
     * a Serviços row ([QuoteInvoicing.ALREADY_MEASURED]). A null clears the mark, once the project has no measurement.
     */
    suspend fun setBillingProject(quoteId: ObjectId, projectId: ObjectId?) = quotes.setProject(quoteId, projectId)

    /**
     * Removes a quote for good. A quote that is billed can't go: [QuoteDeletion.Refused] carries the reason of
     * [QuoteInvoicing.billedBy] (an invoice or a Serviços row that isn't cancelled, or a project's measurements), so
     * cancelling or deleting the invoice, or the Serviços row, frees it. A project made from the quote keeps its own
     * copy of the lines and only loses the link.
     */
    suspend fun delete(id: ObjectId): QuoteDeletion {
        val quote = quotes.findById(id) ?: return QuoteDeletion.NotFound
        QuoteInvoicing.billedBy(quote, invoices, clientServices)?.let { return QuoteDeletion.Refused(it) }
        return quotes.delete(id)?.let(QuoteDeletion::Deleted) ?: QuoteDeletion.NotFound
    }

    companion object {
        fun of(mongo: MongoModule, tenantId: ObjectId) = QuoteService(
            QuoteRepository(mongo, tenantId),
            InvoiceRepository(mongo, tenantId),
            ClientServiceRepository(mongo, tenantId),
            ClientRepository(mongo, tenantId),
        )
    }
}
