package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.LineItem
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId

/**
 * Writes to a company's invoices. The Invoices and Serviços pages, the backoffice and the bot's tools change
 * invoices through it. An invoice that bills Serviços rows frees them again when it is cancelled or deleted.
 */
class InvoiceService(
    private val invoices: InvoiceRepository,
    private val clientServices: ClientServiceRepository,
    private val clients: ClientRepository,
) {
    sealed interface ServicesInvoiced {
        data class Invoiced(val invoice: Invoice, val client: Client) : ServicesInvoiced

        /** One of [ClientServiceBilling]'s reasons. */
        data class Refused(val reason: String) : ServicesInvoiced

        data object ClientNotFound : ServicesInvoiced
    }

    suspend fun create(clientId: ObjectId, quoteId: ObjectId?, items: List<LineItem>, dueDate: LocalDate, taxOfficeCode: String? = null): Invoice =
        invoices.create(clientId, quoteId, items, dueDate, taxOfficeCode)

    /** Pays the whole invoice; a paid or cancelled one comes back unchanged. */
    suspend fun markPaid(id: ObjectId): Invoice? = invoices.markPaid(id)

    /** A blank [code] clears it. */
    suspend fun setTaxOfficeCode(id: ObjectId, code: String?): Invoice? = invoices.setTaxOfficeCode(id, code)

    suspend fun setInstallments(id: ObjectId, parts: List<InvoiceInstallments.Part>): InvoiceChange = invoices.setInstallments(id, parts)

    suspend fun receiveInstallment(id: ObjectId, index: Int): InvoiceChange = invoices.receiveInstallment(id, index)

    /** Keeps the invoice, as cancelled, and frees the Serviços rows it billed. */
    suspend fun cancel(id: ObjectId): InvoiceChange {
        val change = invoices.cancel(id)
        if (change is InvoiceChange.Done) clientServices.reopenInvoiced(id)
        return change
    }

    /** Removes the invoice for good and frees the Serviços rows it billed; null when there was none. */
    suspend fun delete(id: ObjectId): Invoice? {
        val deleted = invoices.delete(id) ?: return null
        clientServices.reopenInvoiced(id)
        return deleted
    }

    /** One invoice of [serviceIds], open Serviços rows of [clientId], which are then marked invoiced by it. */
    suspend fun invoiceClientServices(clientId: ObjectId, serviceIds: List<ObjectId>, dueDate: LocalDate): ServicesInvoiced {
        val services = clientServices.findByIds(serviceIds)
        val items = when (val prepared = ClientServiceBilling.prepareInvoice(clientId, services)) {
            is ClientServiceBilling.Outcome.Rejected -> return ServicesInvoiced.Refused(prepared.reason)
            is ClientServiceBilling.Outcome.Ready -> prepared.items
        }
        val client = clients.findById(clientId) ?: return ServicesInvoiced.ClientNotFound
        val invoice = invoices.create(clientId, null, items, dueDate)
        clientServices.markInvoiced(services.map { it.id }, invoice.id)
        return ServicesInvoiced.Invoiced(invoice, client)
    }
}
