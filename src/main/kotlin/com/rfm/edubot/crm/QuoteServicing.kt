package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.ClientService
import com.rfm.edubot.crm.model.LineItem
import com.rfm.edubot.crm.model.QuoteStatus
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId

/**
 * A quote becoming the Serviços row that records its work, from the quote's detail: same lines, the quote turns accepted,
 * and the row is invoiced later with the client's other open work. Refused while the quote is billed ([QuoteInvoicing.billedBy]).
 */
object QuoteServicing {
    sealed interface Outcome {
        data class Created(val service: ClientService, val client: Client) : Outcome
        data class Refused(val reason: String) : Outcome
    }

    /** Without a [name], the row is named after the quote's lines. */
    suspend fun service(
        quotes: QuoteRepository,
        invoices: InvoiceRepository,
        services: ClientServiceRepository,
        clients: ClientRepository,
        quoteId: ObjectId,
        performedAt: LocalDate,
        name: String? = null,
    ): Outcome {
        val quote = quotes.findById(quoteId) ?: return Outcome.Refused(QuoteInvoicing.QUOTE_NOT_FOUND)
        val client = clients.findById(quote.clientId) ?: return Outcome.Refused(QuoteInvoicing.CLIENT_NOT_FOUND)
        QuoteInvoicing.billedBy(quote, invoices, services)?.let { return Outcome.Refused(it) }
        val service = services.create(
            clientId = quote.clientId,
            name = name?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME) ?: nameFrom(quote.items).ifEmpty { quote.number },
            notes = null,
            quantity = 1.0,
            unit = "",
            unitPriceCents = quote.totalCents,
            bookingServiceId = null,
            catalogItemId = null,
            performedAt = performedAt,
            items = quote.items,
            quoteId = quote.id,
        )
        quotes.update(quote.id, null, null, null, QuoteStatus.ACEITO)
        return Outcome.Created(service, client)
    }

    /** "Limpeza + Vidros", as the dashboard names a service typed without a name: a catalog line's details are left out. */
    fun nameFrom(items: List<LineItem>): String {
        val name = items.map { it.description.substringBefore(" - ").trim() }.filter { it.isNotEmpty() }.joinToString(" + ")
        return if (name.length > MAX_NAME) name.take(MAX_NAME - 1) + "…" else name
    }

    private const val MAX_NAME = 160
}
