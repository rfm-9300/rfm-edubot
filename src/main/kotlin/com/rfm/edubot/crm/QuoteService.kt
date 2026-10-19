package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.LineItem
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.persistence.MongoModule
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId

/**
 * Writes to a company's quotes. The Quotes page, the backoffice, the bot's and the assistant's tools, agents and
 * the email drawer change quotes through it; numbering and the domain events are the repository's.
 */
class QuoteService(
    private val quotes: QuoteRepository,
    private val invoices: InvoiceRepository,
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
        QuoteInvoicing.invoice(quotes, invoices, clients, quoteId, dueDate)

    companion object {
        fun of(mongo: MongoModule, tenantId: ObjectId) =
            QuoteService(QuoteRepository(mongo, tenantId), InvoiceRepository(mongo, tenantId), ClientRepository(mongo, tenantId))
    }
}
