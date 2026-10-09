package com.rfm.edubot.integrations.email

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.crm.model.Supplier
import com.rfm.edubot.dashboard.DashboardModules
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmailSuggestionsTest {
    private val now = Instant.parse("2026-10-09T10:00:00Z")
    private val tenantId = ObjectId()
    private val all = setOf(
        DashboardModules.CLIENTS, DashboardModules.QUOTES, DashboardModules.INVOICES, DashboardModules.BOOKINGS,
        DashboardModules.PAYMENTS, DashboardModules.AGENTS, DashboardModules.EMAIL,
    )

    private fun email(
        from: String = "maria@cliente.pt",
        fromName: String? = "Maria Silva",
        automated: Boolean = false,
        pdf: Boolean = false,
        actions: List<EmailAction> = emptyList(),
        insights: EmailInsights? = null,
    ) = EmailMessage(
        tenantId = tenantId, connectionId = ObjectId(), providerMessageId = "gm-${ObjectId()}", threadId = "th-1",
        direction = EmailDirection.INBOUND, from = from, fromName = fromName, to = listOf("obras@example.pt"),
        subject = "Pedido", snippet = "", bodyText = "Olá",
        attachments = if (pdf) listOf(EmailAttachmentInfo("fatura.pdf", "application/pdf", 1000)) else emptyList(),
        automated = automated, date = now, createdAt = now, actions = actions, insights = insights,
    )

    private fun insights(intent: String, block: EmailInsights.() -> EmailInsights = { this }) =
        EmailInsights(summary = "Resumo", intent = intent, generatedAt = now).block()

    private fun client(taxId: String? = null, address: String? = null) =
        Client(tenantId = tenantId, number = "CLT-001", name = "Maria Silva", phone = "+351 912 345 678", email = "maria@cliente.pt", taxId = taxId, address = address, createdAt = now, updatedAt = now)

    private fun quote(number: String, status: QuoteStatus, clientId: ObjectId = ObjectId()) =
        Quote(tenantId = tenantId, number = number, clientId = clientId, items = emptyList(), status = status, totalCents = 45_000, createdAt = now, updatedAt = now)

    private fun invoice(number: String, status: InvoiceStatus, cents: Long = 12_300) =
        Invoice(tenantId = tenantId, number = number, clientId = ObjectId(), items = emptyList(), status = status, dueDate = LocalDate(2026, 10, 30), totalCents = cents, createdAt = now, updatedAt = now)

    private fun build(
        email: EmailMessage = email(),
        modules: Set<String> = all,
        client: Client? = null,
        facts: EmailFacts.Facts = EmailFacts.Facts(),
        quotes: List<Quote> = emptyList(),
        invoices: List<Invoice> = emptyList(),
        clientOpenQuotes: List<Quote> = emptyList(),
        clientOpenInvoices: List<Invoice> = emptyList(),
        supplier: Supplier? = null,
        bookingServiceId: String? = null,
    ) = EmailSuggestions.build(
        EmailSuggestionInput(modules, email, client, facts, email.insights, quotes, invoices, clientOpenQuotes, clientOpenInvoices, supplier, bookingServiceId),
    )

    private fun List<EmailSuggestion>.types() = map { it.type }
    private fun JsonObject.text(name: String) = this[name]?.jsonPrimitive?.content

    @Test
    fun `a stranger's email offers to add them as a client, from their signature`() {
        val read = insights(EmailIntents.QUOTE_REQUEST) {
            copy(contact = EmailContact(name = "Maria Silva", company = "Silva & Filhos", city = "Lisboa"), items = listOf(EmailItem("Pintura", 20.0, "m2")), request = "Pintar a sala")
        }
        val suggestions = build(email(insights = read), facts = EmailFacts.Facts(phones = listOf("+351 912 345 678"), taxIds = listOf("123456789")))
        assertEquals(listOf(EmailActionTypes.CLIENT_CREATE, EmailActionTypes.QUOTE_CREATE, EmailActionTypes.TASK_CREATE), suggestions.types())
        val create = suggestions.first()
        assertTrue(create.primary)
        assertEquals("Silva & Filhos", create.prefill.text("name"))
        assertEquals("Maria Silva", create.prefill.text("contactPerson"))
        assertEquals("maria@cliente.pt", create.prefill.text("email"))
        assertEquals("+351 912 345 678", create.prefill.text("phone"))
        assertEquals("123456789", create.prefill.text("taxId"))
        assertEquals("Lisboa", create.prefill.text("city"))
        val quote = suggestions[1]
        assertTrue(quote.primary)
        assertEquals("Pintura", quote.prefill["items"]!!.jsonArray.single().jsonObject.text("description"))
        assertEquals("Pintar a sala", quote.prefill.text("notes"))
        assertNull(quote.prefill.text("clientId"), "no client yet")
    }

    @Test
    fun `a client's email completes their record with what it adds, and prepares the quote for them`() {
        val maria = client()
        val read = insights(EmailIntents.QUOTE_REQUEST) { copy(contact = EmailContact(taxId = "123456789", address = "Rua A, 1")) }
        val suggestions = build(email(insights = read), client = maria)
        assertEquals(listOf(EmailActionTypes.QUOTE_CREATE, EmailActionTypes.CLIENT_UPDATE, EmailActionTypes.TASK_CREATE), suggestions.types())
        assertEquals(maria.id.toHexString(), suggestions.first().prefill.text("clientId"))
        val update = suggestions[1]
        assertEquals(maria.id.toHexString(), update.recordId)
        assertEquals("123456789", update.prefill.text("taxId"))
        assertEquals("Rua A, 1", update.prefill.text("address"))
        assertTrue(build(email(insights = read), client = client(taxId = "123456789", address = "Rua B")).none { it.type == EmailActionTypes.CLIENT_UPDATE })
    }

    @Test
    fun `a payment notice offers to mark the invoice paid, named or matched by amount`() {
        val paid = insights(EmailIntents.PAYMENT_SENT) { copy(amountCents = 12_300) }
        val named = invoice("FAT-007", InvoiceStatus.OVERDUE)
        val first = build(email(insights = paid), client = client(), invoices = listOf(named, invoice("FAT-008", InvoiceStatus.PAID))).first()
        assertEquals(EmailActionTypes.INVOICE_PAID, first.type)
        assertTrue(first.primary)
        assertEquals("FAT-007", first.recordLabel)
        assertEquals(12_300L, first.prefill["amountCents"]!!.jsonPrimitive.long)

        val matched = build(
            email(insights = paid), client = client(),
            clientOpenInvoices = listOf(invoice("FAT-010", InvoiceStatus.PENDING, 5_000), invoice("FAT-011", InvoiceStatus.PENDING, 12_300)),
        ).filter { it.type == EmailActionTypes.INVOICE_PAID }
        assertEquals(listOf("FAT-011"), matched.map { it.recordLabel })

        val unread = build(email(), client = client(), invoices = listOf(named)).single { it.type == EmailActionTypes.INVOICE_PAID }
        assertEquals(false, unread.primary, "without the model's reading it's offered, not pushed")
    }

    @Test
    fun `an accepted quote is offered for acceptance, a refusal isn't`() {
        val maria = client()
        val sent = quote("ORC-003", QuoteStatus.SENT, maria.id)
        val accepted = build(email(insights = insights(EmailIntents.QUOTE_REPLY) { copy(accepted = true) }), client = maria, clientOpenQuotes = listOf(sent))
        assertEquals(EmailActionTypes.QUOTE_ACCEPT, accepted.first().type)
        assertEquals("ORC-003", accepted.first().recordLabel)
        assertTrue(accepted.first().primary)
        val refused = build(email(insights = insights(EmailIntents.QUOTE_REPLY) { copy(accepted = false) }), client = maria, quotes = listOf(sent))
        assertTrue(refused.none { it.type == EmailActionTypes.QUOTE_ACCEPT })
        assertTrue(build(email(insights = insights(EmailIntents.QUOTE_REPLY) { copy(accepted = true) }), client = maria, quotes = listOf(quote("ORC-004", QuoteStatus.ACEITO))).none { it.type == EmailActionTypes.QUOTE_ACCEPT })
    }

    @Test
    fun `a booking request carries the day, time and service`() {
        val read = insights(EmailIntents.BOOKING_REQUEST) { copy(date = "2026-10-14", time = "09:30", serviceName = "Limpeza") }
        val booking = build(email(insights = read), client = client(), bookingServiceId = "svc-1").first { it.type == EmailActionTypes.BOOKING_CREATE }
        assertTrue(booking.primary)
        assertEquals("2026-10-14", booking.prefill.text("date"))
        assertEquals("09:30", booking.prefill.text("time"))
        assertEquals("svc-1", booking.prefill.text("serviceId"))
        assertEquals("+351 912 345 678", booking.prefill.text("contactPhone"))
        assertTrue(build(email(insights = read), modules = all - DashboardModules.BOOKINGS).none { it.type == EmailActionTypes.BOOKING_CREATE })
    }

    @Test
    fun `a supplier's bill becomes a payment to that supplier`() {
        val supplier = Supplier(tenantId = tenantId, number = "FOR-001", name = "Tintas Lda", phone = "+351 210 000 000", createdAt = now, updatedAt = now)
        val read = insights(EmailIntents.SUPPLIER_BILL) { copy(contact = EmailContact(company = "Tintas Lda"), amountCents = 23_000, dueDate = "2026-10-30", documentNumber = "FT 2026/123") }
        val bill = build(email(from = "faturas@tintas.pt", fromName = "Tintas", insights = read), supplier = supplier).first()
        assertEquals(EmailActionTypes.BILL_CREATE, bill.type)
        assertTrue(bill.primary)
        assertEquals(supplier.id.toHexString(), bill.prefill.text("supplierId"))
        assertEquals(23_000L, bill.prefill["amountCents"]!!.jsonPrimitive.long)
        assertEquals("2026-10-30", bill.prefill.text("dueDate"))
        assertEquals("FT 2026/123", bill.prefill.text("description"))

        val unread = build(email(from = "faturas@tintas.pt", pdf = true))
        assertTrue(unread.any { it.type == EmailActionTypes.BILL_CREATE && !it.primary }, "a PDF from someone not on file may be a bill")
    }

    @Test
    fun `machines' mail gets no person-to-person actions and modules decide the rest`() {
        assertTrue(build(email(automated = true)).isEmpty())
        assertEquals(listOf(EmailActionTypes.CLIENT_CREATE), build(email(), modules = setOf(DashboardModules.CLIENTS, DashboardModules.EMAIL)).types())
        assertTrue(build(email(), modules = setOf(DashboardModules.QUOTES, DashboardModules.EMAIL)).none { it.type == EmailActionTypes.QUOTE_CREATE }, "a quote needs the client directory")
        assertTrue(build(email().copy(direction = EmailDirection.OUTBOUND)).isEmpty())
    }

    @Test
    fun `what the team did shows as done, and what they set aside goes`() {
        val clientId = ObjectId().toHexString()
        val quoteId = ObjectId().toHexString()
        val actions = listOf(
            EmailAction(EmailActionTypes.CLIENT_CREATE, EmailActionStatus.DONE, "client", clientId, "Maria Silva", "ana@example.pt", now),
            EmailAction(EmailActionTypes.QUOTE_CREATE, EmailActionStatus.DONE, "quote", quoteId, "ORC-009", "ana@example.pt", now),
            EmailAction(EmailActionTypes.TASK_CREATE, EmailActionStatus.DISMISSED, at = now),
        )
        val suggestions = build(email(actions = actions, insights = insights(EmailIntents.QUOTE_REQUEST)), client = client())
        assertTrue(suggestions.none { it.type == EmailActionTypes.TASK_CREATE })
        val quote = suggestions.single { it.type == EmailActionTypes.QUOTE_CREATE }
        assertEquals(EmailActionStatus.DONE, quote.status)
        assertEquals("ORC-009", quote.recordLabel)
        val added = suggestions.single { it.type == EmailActionTypes.CLIENT_CREATE }
        assertEquals(EmailActionStatus.DONE, added.status, "the sender is a client now, and the page still says who added them")
        assertEquals(clientId, added.recordId)
    }
}
