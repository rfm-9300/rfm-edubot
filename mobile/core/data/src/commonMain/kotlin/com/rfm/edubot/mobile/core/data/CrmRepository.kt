package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.model.CatalogCategory
import com.rfm.edubot.mobile.core.model.CatalogItem
import com.rfm.edubot.mobile.core.model.ClientService
import com.rfm.edubot.mobile.core.model.ConvertQuote
import com.rfm.edubot.mobile.core.model.CreateInvoice
import com.rfm.edubot.mobile.core.model.CreateQuote
import com.rfm.edubot.mobile.core.model.CreateService
import com.rfm.edubot.mobile.core.model.CrmClient
import com.rfm.edubot.mobile.core.model.DirectoryFields
import com.rfm.edubot.mobile.core.model.Employee
import com.rfm.edubot.mobile.core.model.Invoice
import com.rfm.edubot.mobile.core.model.InvoiceClientServices
import com.rfm.edubot.mobile.core.model.Payment
import com.rfm.edubot.mobile.core.model.Quote
import com.rfm.edubot.mobile.core.model.QuoteToService
import com.rfm.edubot.mobile.core.model.SaveCatalogItem
import com.rfm.edubot.mobile.core.model.SaveClient
import com.rfm.edubot.mobile.core.model.ServiceSubmission
import com.rfm.edubot.mobile.core.model.Supplier
import com.rfm.edubot.mobile.core.model.UpdateService
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

    /** The company's client fields; the form is built from them. Rarely changes, so it is cached like a list. */
    val clientFields = CachedResource(
        key = "crm.clientFields",
        serializer = DirectoryFields.serializer(),
        cache = cache,
        fetch = { api.clientFields() },
    )

    val categories = CachedResource(
        key = "crm.catalogCategories",
        serializer = ListSerializer(CatalogCategory.serializer()),
        cache = cache,
        fetch = { api.catalogCategories() },
    )

    suspend fun client(id: String): Outcome<CrmClient> = apiCall { api.client(id) }

    /**
     * Saves a client. Which fields are required is the company's choice ([clientFields]); a missing
     * one comes back as `tax_id_required`, `email_required`, `custom_field_required` (with the
     * field's key as `detail`)…, which reaches the form as an [com.rfm.edubot.mobile.core.common.AppError.Rejected].
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

    /** Bills an accepted quote as a Serviços row instead of an invoice; the quote is billed once either way. */
    suspend fun quoteToService(quote: Quote, performedAt: String? = null): Outcome<ClientService> {
        val service = apiCall { api.quoteToService(quote.id, QuoteToService(performedAt = performedAt)) }
        service.valueOrNull?.let { created -> services.mutate { listOf(created) + it } }
        return service
    }

    suspend fun deleteQuote(quote: Quote): Outcome<Unit> {
        val done = apiCall { api.deleteQuote(quote.id) }
        if (done is Outcome.Success) quotes.mutate { current -> current.filterNot { it.id == quote.id } }
        return done
    }

    suspend fun quote(id: String): Outcome<Quote> = apiCall { api.quote(id) }

    suspend fun quotesFor(clientId: String): Outcome<List<Quote>> = apiCall { api.quotes(clientId = clientId) }

    suspend fun invoice(id: String): Outcome<Invoice> = apiCall { api.invoice(id) }

    suspend fun invoicesFor(clientId: String): Outcome<List<Invoice>> = apiCall { api.invoices(clientId = clientId) }

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

    /** Receives one installment of an invoice paid in parts; the last one marks it paid. */
    suspend fun receiveInstallment(invoice: Invoice, index: Int): Outcome<Invoice> =
        replaceInvoice(apiCall { api.receiveInstallment(invoice.id, index) })

    /** Cancelling keeps the invoice (numbered documents stay) and frees what it billed to be invoiced again. */
    suspend fun cancelInvoice(invoice: Invoice): Outcome<Invoice> {
        val cancelled = replaceInvoice(apiCall { api.cancelInvoice(invoice.id) })
        if (cancelled is Outcome.Success) services.refresh()
        return cancelled
    }

    suspend fun deleteInvoice(invoice: Invoice): Outcome<Unit> {
        val done = apiCall { api.deleteInvoice(invoice.id) }
        if (done is Outcome.Success) {
            invoices.mutate { current -> current.filterNot { it.id == invoice.id } }
            services.refresh()
        }
        return done
    }

    private suspend fun replaceInvoice(updated: Outcome<Invoice>): Outcome<Invoice> {
        updated.valueOrNull?.let { saved -> invoices.mutate { current -> current.map { if (it.id == saved.id) saved else it } } }
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

    suspend fun service(id: String): Outcome<ClientService> = apiCall { api.service(id) }

    suspend fun servicesFor(clientId: String? = null, employeeId: String? = null, from: String? = null, to: String? = null): Outcome<List<ClientService>> =
        apiCall { api.services(clientId = clientId, employeeId = employeeId, from = from, to = to) }

    suspend fun createService(request: CreateService): Outcome<ClientService> {
        val created = apiCall { api.createService(request) }
        created.valueOrNull?.let { service -> services.mutate { listOf(service) + it } }
        return created
    }

    /** Cancels or reopens a row (`CANCELLED` / `OPEN`), or changes who does the work. */
    suspend fun updateService(service: ClientService, change: UpdateService): Outcome<ClientService> {
        val updated = apiCall { api.updateService(service.id, change) }
        updated.valueOrNull?.let { saved -> services.mutate { current -> current.map { if (it.id == saved.id) saved else it } } }
        return updated
    }

    suspend fun deleteService(service: ClientService): Outcome<Unit> {
        val done = apiCall { api.deleteService(service.id) }
        if (done is Outcome.Success) services.mutate { current -> current.filterNot { it.id == service.id } }
        return done
    }

    suspend fun supplier(id: String): Outcome<Supplier> = apiCall { api.supplier(id) }

    suspend fun employee(id: String): Outcome<Employee> = apiCall { api.employee(id) }

    suspend fun paymentsTo(supplierId: String? = null, employeeId: String? = null): Outcome<List<Payment>> =
        apiCall { api.payments(supplierId = supplierId, employeeId = employeeId) }

    suspend fun submissions(employeeId: String? = null, status: String? = null): Outcome<List<ServiceSubmission>> =
        apiCall { api.serviceSubmissions(employeeId, status) }

    /** Approving saves a Serviços row done by that employee, so the list is fetched again. */
    suspend fun approveSubmission(submission: ServiceSubmission): Outcome<ServiceSubmission> {
        val approved = apiCall { api.approveSubmission(submission.id) }
        if (approved is Outcome.Success) services.refresh()
        return approved
    }

    suspend fun rejectSubmission(submission: ServiceSubmission, reason: String?): Outcome<ServiceSubmission> =
        apiCall { api.rejectSubmission(submission.id, reason) }

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
