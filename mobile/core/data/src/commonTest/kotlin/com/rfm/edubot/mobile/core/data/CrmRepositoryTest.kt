package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.InMemorySnapshotStore
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
import com.rfm.edubot.mobile.core.network.ApiException
import com.rfm.edubot.mobile.core.network.CrmApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private open class FakeCrmApi : CrmApi {
    var clients = mutableListOf(CrmClient(id = "cl1", name = "Existing", phone = "351900000001"))
    var quotes = mutableListOf(Quote(id = "q1", number = "Q1", status = "PENDENTE", totalEur = 100.0))
    var invoices = mutableListOf<Invoice>()
    var lastSavedClient: SaveClient? = null
        private set

    override suspend fun clients(query: String?, archived: Boolean): List<CrmClient> = clients

    override suspend fun client(id: String): CrmClient = clients.first { it.id == id }

    override suspend fun createClient(request: SaveClient): CrmClient {
        lastSavedClient = request
        return CrmClient(
            id = "cl-new",
            name = request.name,
            phone = request.phone,
            taxId = request.taxId,
            address = request.address,
        )
    }

    override suspend fun updateClient(id: String, request: SaveClient): CrmClient {
        lastSavedClient = request
        return clients.first { it.id == id }.copy(name = request.name, taxId = request.taxId)
    }

    override suspend fun archiveClient(id: String, archived: Boolean) = Unit

    override suspend fun quotes(clientId: String?, status: String?): List<Quote> = quotes

    override suspend fun quote(id: String): Quote = quotes.first { it.id == id }

    override suspend fun createQuote(request: CreateQuote): Quote =
        Quote(id = "q-new", number = "Q2", clientId = request.clientId, status = "PENDENTE")

    override suspend fun setQuoteStatus(id: String, status: String): Quote =
        quotes.first { it.id == id }.copy(status = status)

    override suspend fun convertQuote(id: String, request: ConvertQuote): Invoice =
        Invoice(id = "inv-from-quote", number = "F1", status = "PENDING", dueDate = request.dueDate, quoteId = id)

    override suspend fun invoices(clientId: String?, status: String?): List<Invoice> = invoices

    override suspend fun invoice(id: String): Invoice = invoices.first { it.id == id }

    override suspend fun createInvoice(request: CreateInvoice): Invoice =
        Invoice(id = "inv-new", number = "F2", status = "PENDING", dueDate = request.dueDate)

    override suspend fun markInvoicePaid(id: String): Invoice =
        invoices.first { it.id == id }.copy(status = "PAID", paidAt = "2026-01-02T00:00:00Z")

    override suspend fun catalog(query: String?): List<CatalogItem> = emptyList()

    override suspend fun saveCatalogItem(request: SaveCatalogItem, id: String?): CatalogItem =
        CatalogItem(id = id ?: "item-new", type = request.type, title = request.title)

    override suspend fun deleteCatalogItem(id: String) = Unit

    override suspend fun services(clientId: String?, status: String?): List<ClientService> = emptyList()

    override suspend fun invoiceServices(request: InvoiceClientServices): Invoice =
        Invoice(id = "inv-services", number = "F3", status = "PENDING", dueDate = request.dueDate)

    override suspend fun suppliers(archived: Boolean): List<Supplier> = emptyList()

    override suspend fun employees(archived: Boolean): List<Employee> = emptyList()

    override suspend fun payments(status: String?): List<Payment> = emptyList()

    override suspend fun markPaymentPaid(id: String): Payment =
        Payment(id = id, status = "PAID", paidAt = "2026-01-02T00:00:00Z")
}

class CrmRepositoryTest {
    private fun repository(api: FakeCrmApi = FakeCrmApi()) =
        CrmRepository(api, SnapshotCache(InMemorySnapshotStore())) to api

    @Test
    fun `creating a client sends the NIF and address the backend requires`() = runTest {
        val (repository, api) = repository()
        val request = SaveClient(
            name = "New Client",
            phone = "351900000002",
            taxId = "123456789",
            address = "Rua X 1",
        )

        val saved = repository.saveClient(request)

        assertTrue(saved is Outcome.Success)
        assertEquals("123456789", api.lastSavedClient?.taxId)
        assertEquals("Rua X 1", api.lastSavedClient?.address)
    }

    @Test
    fun `a new client appears at the top of the list without a refetch`() = runTest {
        val (repository, _) = repository()
        repository.clients.load()

        repository.saveClient(SaveClient("New", "351900000002", "123456789", "Rua X 1"))

        assertEquals(listOf("cl-new", "cl1"), repository.clients.state.value.value?.map { it.id })
    }

    @Test
    fun `editing a client replaces its row rather than adding another`() = runTest {
        val (repository, _) = repository()
        repository.clients.load()

        repository.saveClient(SaveClient("Renamed", "351900000001", "123456789", "Rua X 1"), id = "cl1")

        val clients = repository.clients.state.value.value!!
        assertEquals(1, clients.size)
        assertEquals("Renamed", clients.single().name)
    }

    @Test
    fun `the backend's rejection code reaches the form`() = runTest {
        val api = object : FakeCrmApi() {
            override suspend fun createClient(request: SaveClient): CrmClient =
                throw ApiException(AppError.Rejected("tax_id_required", 400))
        }
        val (repository, _) = repository(api)

        val saved = repository.saveClient(SaveClient("New", "351900000002", "", "Rua X 1"))

        assertEquals(AppError.Rejected("tax_id_required", 400), (saved as Outcome.Failure).error)
    }

    @Test
    fun `a failed save leaves the list untouched`() = runTest {
        val api = object : FakeCrmApi() {
            override suspend fun createClient(request: SaveClient): CrmClient =
                throw ApiException(AppError.Rejected("phone_taken", 409))
        }
        val (repository, _) = repository(api)
        repository.clients.load()

        repository.saveClient(SaveClient("New", "351900000001", "123456789", "Rua X 1"))

        assertEquals(listOf("cl1"), repository.clients.state.value.value?.map { it.id })
    }

    @Test
    fun `archiving removes the client from the active list`() = runTest {
        val (repository, _) = repository()
        repository.clients.load()

        repository.archiveClient(repository.clients.state.value.value!!.first(), archived = true)

        assertEquals(emptyList(), repository.clients.state.value.value)
    }

    @Test
    fun `marking an invoice paid updates its row`() = runTest {
        val api = FakeCrmApi().apply {
            invoices = mutableListOf(Invoice(id = "inv1", number = "F1", status = "PENDING", dueDate = "2026-01-01"))
        }
        val (repository, _) = repository(api)
        repository.invoices.load()

        repository.markInvoicePaid(repository.invoices.state.value.value!!.first())

        val invoice = repository.invoices.state.value.value!!.single()
        assertEquals("PAID", invoice.status)
        assertTrue(invoice.paid)
    }

    @Test
    fun `moving a quote along the pipeline updates its row`() = runTest {
        val (repository, _) = repository()
        repository.quotes.load()

        repository.setQuoteStatus(repository.quotes.state.value.value!!.first(), "SENT")

        assertEquals("SENT", repository.quotes.state.value.value?.single()?.status)
    }

    @Test
    fun `converting a quote adds the invoice to the invoice list`() = runTest {
        val (repository, _) = repository()
        repository.quotes.load()
        repository.invoices.load()

        val invoice = repository.convertQuote(repository.quotes.state.value.value!!.first(), "2026-02-01")

        assertTrue(invoice is Outcome.Success)
        assertEquals(listOf("inv-from-quote"), repository.invoices.state.value.value?.map { it.id })
    }
}
