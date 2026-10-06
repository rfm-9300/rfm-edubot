package com.rfm.edubot.crm

import com.rfm.edubot.crm.InvoiceInstallments.Outcome
import com.rfm.edubot.crm.InvoiceInstallments.Part
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceInstallment
import com.rfm.edubot.crm.model.InvoiceStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Splitting an invoice into installments and receiving them, without Mongo. */
class InvoiceInstallmentsTest {
    private val created = Instant.parse("2026-10-06T09:00:00Z")
    private val now = Instant.parse("2026-10-06T10:00:00Z")
    private val oct6 = LocalDate(2026, 10, 6)
    private val nov6 = LocalDate(2026, 11, 6)
    private val dec6 = LocalDate(2026, 12, 6)

    private fun invoice(
        totalCents: Long = 400_00,
        installments: List<InvoiceInstallment> = emptyList(),
        status: InvoiceStatus = InvoiceStatus.PENDING,
        dueDate: LocalDate = LocalDate(2026, 10, 20),
    ) = Invoice(
        tenantId = ObjectId(),
        number = "FAT-001",
        clientId = ObjectId(),
        items = listOf(lineItem("Pintura", unitPriceEur = totalCents / 100.0)),
        status = status,
        dueDate = dueDate,
        totalCents = totalCents,
        createdAt = created,
        updatedAt = created,
        installments = installments,
    )

    private fun Invoice.apply(outcome: Outcome): Invoice {
        assertIs<Outcome.Ready>(outcome)
        return copy(installments = outcome.installments, status = outcome.status, dueDate = outcome.dueDate, paidAt = outcome.paidAt)
    }

    private fun refused(outcome: Outcome): String = assertIs<Outcome.Refused>(outcome).reason

    @Test
    fun `half now and half later splits the invoice and moves its due date to the first half`() {
        val split = invoice().apply(InvoiceInstallments.plan(invoice(), listOf(Part(200_00, nov6), Part(200_00, oct6))))

        assertEquals(listOf(oct6, nov6), split.installments.map { it.dueDate }, "parts are kept in date order")
        assertEquals(oct6, split.dueDate)
        assertEquals(InvoiceStatus.PENDING, split.status)
        assertEquals(0, split.paidCents)
        assertEquals(400_00, split.outstandingCents)
    }

    @Test
    fun `parts must add up to the invoice and be worth something`() {
        assertEquals("installments_total_mismatch", refused(InvoiceInstallments.plan(invoice(), listOf(Part(200_00, oct6), Part(150_00, nov6)))))
        assertEquals("installment_amount_invalid", refused(InvoiceInstallments.plan(invoice(), listOf(Part(400_00, oct6), Part(0, nov6)))))
        assertEquals("installments_required", refused(InvoiceInstallments.plan(invoice(), emptyList())))
        val tooMany = (1..InvoiceInstallments.MAX_PARTS + 1).map { Part(1, oct6) }
        assertEquals("too_many_installments", refused(InvoiceInstallments.plan(invoice(totalCents = tooMany.size.toLong()), tooMany)))
    }

    @Test
    fun `a paid or cancelled invoice can't be split`() {
        assertEquals("invoice_closed", refused(InvoiceInstallments.plan(invoice(status = InvoiceStatus.PAID), listOf(Part(400_00, oct6)))))
        assertEquals("invoice_closed", refused(InvoiceInstallments.plan(invoice(status = InvoiceStatus.CANCELLED), listOf(Part(400_00, oct6)))))
    }

    @Test
    fun `a single part with nothing received pays the invoice in one go again`() {
        val split = invoice().apply(InvoiceInstallments.plan(invoice(), listOf(Part(200_00, oct6), Part(200_00, nov6))))
        val single = split.apply(InvoiceInstallments.plan(split, listOf(Part(400_00, dec6))))

        assertTrue(single.installments.isEmpty())
        assertEquals(dec6, single.dueDate)
    }

    @Test
    fun `receiving installments moves the due date along and the last one pays the invoice`() {
        val split = invoice().apply(InvoiceInstallments.plan(invoice(), listOf(Part(100_00, oct6), Part(150_00, nov6), Part(150_00, dec6))))

        val first = split.apply(InvoiceInstallments.receive(split, 0, now))
        assertEquals(now, first.installments[0].paidAt)
        assertEquals(nov6, first.dueDate)
        assertEquals(InvoiceStatus.PENDING, first.status)
        assertNull(first.paidAt)
        assertEquals(100_00, first.paidCents)
        assertEquals(300_00, first.outstandingCents)

        val third = first.apply(InvoiceInstallments.receive(first, 2, now))
        assertEquals(nov6, third.dueDate, "the earliest part still open sets the due date")

        val last = third.apply(InvoiceInstallments.receive(third, 1, now))
        assertEquals(InvoiceStatus.PAID, last.status)
        assertEquals(now, last.paidAt)
        assertEquals(400_00, last.paidCents)
        assertEquals(0, last.outstandingCents)
    }

    @Test
    fun `receiving refuses a part already received, a missing one and a closed invoice`() {
        val split = invoice().apply(InvoiceInstallments.plan(invoice(), listOf(Part(200_00, oct6), Part(200_00, nov6))))
        val first = split.apply(InvoiceInstallments.receive(split, 0, now))

        assertEquals("installment_paid", refused(InvoiceInstallments.receive(first, 0, now)))
        assertEquals("installment_not_found", refused(InvoiceInstallments.receive(first, 5, now)))
        assertEquals("invoice_closed", refused(InvoiceInstallments.receive(first.copy(status = InvoiceStatus.CANCELLED), 1, now)))
    }

    @Test
    fun `changing the plan keeps the parts already received and splits only what is left`() {
        val split = invoice().apply(InvoiceInstallments.plan(invoice(), listOf(Part(200_00, oct6), Part(200_00, nov6))))
        val first = split.apply(InvoiceInstallments.receive(split, 0, now))

        assertEquals("installments_total_mismatch", refused(InvoiceInstallments.plan(first, listOf(Part(400_00, dec6)))))
        val replanned = first.apply(InvoiceInstallments.plan(first, listOf(Part(100_00, nov6), Part(100_00, dec6))))
        assertEquals(listOf(true, false, false), replanned.installments.map { it.paidAt != null })
        assertEquals(nov6, replanned.dueDate)

        val single = first.apply(InvoiceInstallments.plan(first, listOf(Part(200_00, dec6))))
        assertEquals(2, single.installments.size, "with a part received, one part left is still a plan")
    }

    @Test
    fun `the amount due asks for the next part, or every overdue one`() {
        val split = invoice().apply(InvoiceInstallments.plan(invoice(), listOf(Part(100_00, oct6), Part(150_00, nov6), Part(150_00, dec6))))

        assertEquals(100_00, split.amountDueCents(LocalDate(2026, 10, 1)), "before the first date: the first part")
        assertEquals(250_00, split.amountDueCents(LocalDate(2026, 11, 20)), "two parts overdue")
        assertEquals(400_00, invoice().amountDueCents(oct6), "paid in one go: everything")
        assertEquals(0, invoice(status = InvoiceStatus.PAID).amountDueCents(oct6))
    }

    @Test
    fun `receipts are each received part, or the total once paid in one go`() {
        val split = invoice().apply(InvoiceInstallments.plan(invoice(), listOf(Part(200_00, oct6), Part(200_00, nov6))))
        assertEquals(listOf(now to 200_00L), split.apply(InvoiceInstallments.receive(split, 0, now)).receipts())

        val later = Instant.parse("2026-11-06T10:00:00Z")
        val paidRest = split.copy(
            status = InvoiceStatus.PAID,
            paidAt = later,
            installments = InvoiceInstallments.payAll(split.apply(InvoiceInstallments.receive(split, 0, now)), later),
        )
        assertEquals(listOf(now to 200_00L, later to 200_00L), paidRest.receipts())

        assertEquals(listOf(later to 400_00L), invoice(status = InvoiceStatus.PAID).copy(paidAt = later).receipts())
        assertTrue(invoice().receipts().isEmpty())
    }
}
