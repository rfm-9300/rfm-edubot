package com.rfm.edubot.crm

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.rfm.edubot.crm.InvoiceInstallments.Part
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.testing.TestMongo
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.bson.Document
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An invoice's tax office code, installments, cancelling and deleting, on Mongo. */
class InvoiceRepositoryTest {
    private val mongo = TestMongo.module("invoice_repository")
    private val tenantId = ObjectId()
    private val invoices = InvoiceRepository(mongo, tenantId)
    private val oct6 = LocalDate(2026, 10, 6)
    private val nov6 = LocalDate(2026, 11, 6)

    private suspend fun create(totalEur: Double = 400.0, code: String? = null) =
        invoices.create(ObjectId(), null, listOf(lineItem("Pintura", unitPriceEur = totalEur)), LocalDate(2026, 10, 20), code)

    private fun done(change: InvoiceChange) = assertIs<InvoiceChange.Done>(change).invoice

    private fun refused(change: InvoiceChange) = assertIs<InvoiceChange.Refused>(change).reason

    private suspend fun paidEvents(id: ObjectId): Int = mongo.database.getCollection<Document>("domain_events")
        .find(Filters.and(Filters.eq("type", DomainEventTypes.INVOICE_PAID), Filters.eq("subject.id", id.toHexString())))
        .toList().size

    @Test
    fun `the tax office code is stored on create, changed later and cleared by a blank one`() = runBlocking<Unit> {
        val invoice = create(code = "  JJ4XTRK3-12 ")
        assertEquals("JJ4XTRK3-12", invoices.findById(invoice.id)?.taxOfficeCode)

        assertEquals("ATCUD:ABCD1234-7", invoices.setTaxOfficeCode(invoice.id, "ATCUD:ABCD1234-7")?.taxOfficeCode)
        assertNull(invoices.setTaxOfficeCode(invoice.id, " ")?.taxOfficeCode)
        assertNull(InvoiceRepository(mongo, ObjectId()).setTaxOfficeCode(invoice.id, "X"), "another tenant can't touch it")
    }

    @Test
    fun `installments are received one by one and the last one pays the invoice once`() = runBlocking<Unit> {
        val invoice = create()
        val split = done(invoices.setInstallments(invoice.id, listOf(Part(200_00, oct6), Part(200_00, nov6))))
        assertEquals(oct6, split.dueDate)
        assertEquals(split, invoices.findById(invoice.id), "what comes back is what was stored")

        val first = done(invoices.receiveInstallment(invoice.id, 0))
        assertEquals(nov6, first.dueDate)
        assertEquals(200_00, first.paidCents)
        assertEquals("installment_paid", refused(invoices.receiveInstallment(invoice.id, 0)))
        assertEquals(0, paidEvents(invoice.id))

        val last = done(invoices.receiveInstallment(invoice.id, 1))
        assertEquals(InvoiceStatus.PAID, last.status)
        assertNotNull(last.paidAt)
        assertEquals(1, paidEvents(invoice.id))
        assertEquals("invoice_closed", refused(invoices.setInstallments(invoice.id, listOf(Part(400_00, nov6)))))
    }

    @Test
    fun `an invoice saved before installments existed can still be split`() = runBlocking<Unit> {
        val invoice = create()
        mongo.database.getCollection<Document>("crm.invoices")
            .updateOne(Filters.eq("_id", invoice.id), Updates.combine(Updates.unset("installments"), Updates.unset("taxOfficeCode")))

        assertTrue(invoices.findById(invoice.id)!!.installments.isEmpty())
        assertEquals(2, done(invoices.setInstallments(invoice.id, listOf(Part(100_00, oct6), Part(300_00, nov6)))).installments.size)
    }

    @Test
    fun `marking paid receives every open installment and does nothing twice`() = runBlocking<Unit> {
        val invoice = create()
        done(invoices.setInstallments(invoice.id, listOf(Part(200_00, oct6), Part(200_00, nov6))))
        val received = done(invoices.receiveInstallment(invoice.id, 0)).installments[0].paidAt

        val paid = invoices.markPaid(invoice.id)!!
        assertEquals(InvoiceStatus.PAID, paid.status)
        assertTrue(paid.installments.all { it.paidAt != null })
        assertEquals(received, paid.installments[0].paidAt, "a part received earlier keeps its date")
        assertEquals(paid.paidAt, invoices.markPaid(invoice.id)!!.paidAt, "marking paid again changes nothing")
        assertEquals(1, paidEvents(invoice.id))
    }

    @Test
    fun `only an invoice with no money received can be cancelled, and a cancelled one stays so`() = runBlocking<Unit> {
        val open = create()
        val cancelled = done(invoices.cancel(open.id))
        assertEquals(InvoiceStatus.CANCELLED, cancelled.status)
        assertEquals(0, cancelled.outstandingCents)
        assertEquals("invoice_cancelled", refused(invoices.cancel(open.id)))
        assertEquals(InvoiceStatus.CANCELLED, invoices.markPaid(open.id)?.status, "a cancelled invoice isn't marked paid")

        val partly = create()
        done(invoices.setInstallments(partly.id, listOf(Part(200_00, oct6), Part(200_00, nov6))))
        done(invoices.receiveInstallment(partly.id, 0))
        assertEquals("installments_paid", refused(invoices.cancel(partly.id)))

        val paid = create()
        invoices.markPaid(paid.id)
        assertEquals("invoice_paid", refused(invoices.cancel(paid.id)))
        assertIs<InvoiceChange.NotFound>(invoices.cancel(ObjectId()))
    }

    @Test
    fun `deleting removes the invoice, and the services it billed open again`() = runBlocking<Unit> {
        val services = ClientServiceRepository(mongo, tenantId)
        val clientId = ObjectId()
        val row = services.create(clientId, "Limpeza", null, 1.0, "", 80_00, null, null, null)
        val invoice = invoices.create(clientId, null, listOf(lineItem("Limpeza", unitPriceEur = 80.0)), oct6)
        services.markInvoiced(listOf(row.id), invoice.id)

        assertNull(InvoiceRepository(mongo, ObjectId()).delete(invoice.id), "another tenant can't delete it")
        assertEquals(invoice.id, invoices.delete(invoice.id)?.id)
        assertNull(invoices.findById(invoice.id))
        assertEquals(1, services.reopenInvoiced(invoice.id))
        val reopened = services.findById(row.id)!!
        assertEquals(ClientServiceStatus.OPEN, reopened.status)
        assertNull(reopened.invoiceId)
    }

    @Test
    fun `client totals leave cancelled invoices out and count installments received`() = runBlocking<Unit> {
        val clientId = ObjectId()
        mongo.database.getCollection<Document>("crm.clients").insertOne(
            Document("_id", clientId).append("tenantId", tenantId).append("number", "CLT-001").append("name", "Casa Martins"),
        )
        val split = invoices.create(clientId, null, listOf(lineItem("Obra", unitPriceEur = 400.0)), oct6)
        done(invoices.setInstallments(split.id, listOf(Part(150_00, oct6), Part(250_00, nov6))))
        done(invoices.receiveInstallment(split.id, 0))
        val cancelled = invoices.create(clientId, null, listOf(lineItem("Extra", unitPriceEur = 90.0)), oct6)
        done(invoices.cancel(cancelled.id))

        val total = invoices.sumByClient().single { it.clientId == clientId }
        assertEquals(400_00, total.totalCents)
        assertEquals(150_00, total.paidCents)
        assertEquals(1, total.invoiceCount)
    }
}
