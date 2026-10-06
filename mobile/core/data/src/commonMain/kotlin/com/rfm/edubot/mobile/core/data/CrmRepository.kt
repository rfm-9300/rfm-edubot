package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.model.CatalogItem
import com.rfm.edubot.mobile.core.model.ClientService
import com.rfm.edubot.mobile.core.model.ConvertQuote
import com.rfm.edubot.mobile.core.model.CreateInvoice
import com.rfm.edubot.mobile.core.model.CreateQuote
import com.rfm.edubot.mobile.core.model.CrmClient
import com.rfm.edubot.mobile.core.model.Employee
import com.rfm.edubot.mobile.core.model.Invoice
import com.rfm.edubot.mobile.core.model.InvoiceClientServices
import com.rfm.edubot.mobile.core.model.Payment
import com.rfm.edubot.mobile.core.model.Quote
import com.rfm.edubot.mobile.core.model.SaveCatalogItem
import com.rfm.edubot.mobile.core.model.SaveClient
import com.rfm.edubot.mobile.core.model.Supplier
import com.rfm.edubot.mobile.core.network.CrmApi
import kotlinx.serialization.builtins.ListSerializer

class CrmRepository(
    private val api: CrmApi,
    cache: SnapshotCache,
) {
    val clients = CachedResource(
        key = "crm.clients",
        serializer = ListSerializer(CrmClient.serializer()),
        cache = cache,
        fetch = { api.clients() },
    )

    val quotes = CachedResource(
        key = "crm.quotes",
        serializer = ListSerializer(Quote.serializer()),
        cache = cache,
        fetch = { api.quotes() },
    )

    val invoices = CachedResource(
        key = "crm.invoices",
        serializer = ListSerializer(Invoice.serializer()),
        cache = cache,
        fetch = { api.invoices() },
    )

    val catalog = CachedResource(
        key = "crm.catalog",
        serializer = ListSerializer(CatalogItem.serializer()),
        cache = cache,
        fetch = { api.catalog() },
    )

    val services = CachedResource(
        key = "crm.services",
        serializer = ListSerializer(ClientService.serializer()),
        cache = cache,
        fetch = { api.services() },
    )

    val payments = CachedResource(
        key = "crm.payments",
        serializer = ListSerializer(Payment.serializer()),
        cache = cache,
        fetch = { api.payments() },
    )

    val suppliers = CachedResource(
        key = "crm.suppliers",
        serializer = ListSerializer(Supplier.serializer()),
        cache = cache,
        fetch = { api.suppliers() },
    )

    val employees = CachedResource(
        key = "crm.employees",
        serializer = ListSerializer(Employee.serializer()),
        cache = cache,
        fetch = { api.employees() },
    )

    suspend fun client(id: String): Outcome<CrmClient> = apiCall { api.client(id) }

    /**
     * Saves a client. The backend requires a NIF and an address and answers `tax_id_required` or
     * `address_required` otherwise, which reaches the form as an [com.rfm.edubot.mobile.core.common.AppError.Rejected].
     */
    suspend fun saveClient(request: SaveClient, id: String? = null): Outcome<CrmClient> {
        val saved = if (id == null) apiCall { api.createClient(request) } else apiCall { api.updateClient(id, request) }
        saved.valueOrNull?.let { client ->
            clients.mutate { current ->
                if (current.any { it.id == client.id }) current.map { if (it.id == client.id) client else it }
                else listOf(client) + current
            }
        }
        return saved
    }

    suspend fun archiveClient(client: CrmClient, archived: Boolean): Outcome<Unit> {
        val done = apiCall { api.archiveClient(client.id, archived) }
        if (done is Outcome.Success) clients.mutate { current -> current.filterNot { it.id == client.id } }
        return done
    }

    suspend fun createQuote(request: CreateQuote): Outcome<Quote> {
        val created = apiCall { api.createQuote(request) }
        created.valueOrNull?.let { quote -> quotes.mutate { listOf(quote) + it } }
        return created
    }

    suspend fun setQuoteStatus(quote: Quote, status: String): Outcome<Quote> {
        val updated = apiCall { api.setQuoteStatus(quote.id, status) }
        updated.valueOrNull?.let { saved ->
            quotes.mutate { current -> current.map { if (it.id == saved.id) saved else it } }
        }
        return updated
    }

    /** Turns an accepted quote into an invoice; both lists change, so both are refreshed. */
    suspend fun convertQuote(quote: Quote, dueDate: String): Outcome<Invoice> {
        val invoice = apiCall { api.convertQuote(quote.id, ConvertQuote(dueDate)) }
        invoice.valueOrNull?.let { created ->
            invoices.mutate { listOf(created) + it }
            quotes.refresh()
        }
        return invoice
    }

    suspend fun createInvoice(request: CreateInvoice): Outcome<Invoice> {
        val created = apiCall { api.createInvoice(request) }
        created.valueOrNull?.let { invoice -> invoices.mutate { listOf(invoice) + it } }
        return created
    }

    suspend fun markInvoicePaid(invoice: Invoice): Outcome<Invoice> {
        val updated = apiCall { api.markInvoicePaid(invoice.id) }
        updated.valueOrNull?.let { saved ->
            invoices.mutate { current -> current.map { if (it.id == saved.id) saved else it } }
        }
        return updated
    }

    suspend fun saveCatalogItem(request: SaveCatalogItem, id: String? = null): Outcome<CatalogItem> {
        val saved = apiCall { api.saveCatalogItem(request, id) }
        saved.valueOrNull?.let { item ->
            catalog.mutate { current ->
                if (current.any { it.id == item.id }) current.map { if (it.id == item.id) item else it }
                else listOf(item) + current
            }
        }
        return saved
    }

    suspend fun deleteCatalogItem(item: CatalogItem): Outcome<Unit> {
        val done = apiCall { api.deleteCatalogItem(item.id) }
        if (done is Outcome.Success) catalog.mutate { current -> current.filterNot { it.id == item.id } }
        return done
    }

    /** Bills several finished jobs for one client as a single invoice. */
    suspend fun invoiceServices(clientId: String, serviceIds: List<String>, dueDate: String): Outcome<Invoice> {
        val invoice = apiCall { api.invoiceServices(InvoiceClientServices(clientId, serviceIds, dueDate)) }
        invoice.valueOrNull?.let { created ->
            invoices.mutate { listOf(created) + it }
            services.refresh()
        }
        return invoice
    }

    suspend fun markPaymentPaid(payment: Payment): Outcome<Payment> {
        val updated = apiCall { api.markPaymentPaid(payment.id) }
        updated.valueOrNull?.let { saved ->
            payments.mutate { current -> current.map { if (it.id == saved.id) saved else it } }
        }
        return updated
    }
}
