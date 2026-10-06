package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceInstallment
import com.rfm.edubot.crm.model.InvoiceStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/** Splitting an invoice into installments and receiving them, as pure rules the repository applies. */
object InvoiceInstallments {
    const val MAX_PARTS = 24

    /** What an invoice becomes after a change, or the stable error code refusing it. */
    sealed interface Outcome {
        data class Ready(
            val installments: List<InvoiceInstallment>,
            val status: InvoiceStatus,
            val dueDate: LocalDate,
            val paidAt: Instant?,
        ) : Outcome

        data class Refused(val reason: String) : Outcome
    }

    /** A part the client is still to pay, as asked for: its amount and its due date. */
    data class Part(val amountCents: Long, val dueDate: LocalDate)

    /**
     * Replaces the invoice's unpaid installments with [parts]; the paid ones stay as they were. The parts must
     * add up to what is still to receive. A single part with nothing paid yet is no plan at all: the invoice is
     * then paid in one go by that date.
     */
    fun plan(invoice: Invoice, parts: List<Part>): Outcome {
        if (invoice.status == InvoiceStatus.PAID || invoice.status == InvoiceStatus.CANCELLED) return Outcome.Refused("invoice_closed")
        val paid = invoice.installments.filter { it.paidAt != null }
        return when {
            parts.isEmpty() -> Outcome.Refused("installments_required")
            paid.size + parts.size > MAX_PARTS -> Outcome.Refused("too_many_installments")
            parts.any { it.amountCents <= 0 } -> Outcome.Refused("installment_amount_invalid")
            paid.sumOf { it.amountCents } + parts.sumOf { it.amountCents } != invoice.totalCents -> Outcome.Refused("installments_total_mismatch")
            else -> {
                val unpaid = parts.sortedBy { it.dueDate }.map { InvoiceInstallment(it.amountCents, it.dueDate) }
                val plan = if (paid.isEmpty() && unpaid.size == 1) emptyList() else paid + unpaid
                Outcome.Ready(plan, invoice.status, unpaid.first().dueDate, null)
            }
        }
    }

    /** Records installment [index] as received at [now]; the last one received pays the invoice. */
    fun receive(invoice: Invoice, index: Int, now: Instant): Outcome {
        if (invoice.status == InvoiceStatus.PAID || invoice.status == InvoiceStatus.CANCELLED) return Outcome.Refused("invoice_closed")
        val part = invoice.installments.getOrNull(index) ?: return Outcome.Refused("installment_not_found")
        if (part.paidAt != null) return Outcome.Refused("installment_paid")
        val plan = invoice.installments.mapIndexed { i, it -> if (i == index) it.copy(paidAt = now) else it }
        val next = plan.filter { it.paidAt == null }.minOfOrNull { it.dueDate }
        return if (next == null) {
            Outcome.Ready(plan, InvoiceStatus.PAID, invoice.dueDate, now)
        } else {
            Outcome.Ready(plan, invoice.status, next, null)
        }
    }

    /** Paying the whole invoice at [now] also receives every installment still open. */
    fun payAll(invoice: Invoice, now: Instant): List<InvoiceInstallment> =
        invoice.installments.map { if (it.paidAt == null) it.copy(paidAt = now) else it }
}
