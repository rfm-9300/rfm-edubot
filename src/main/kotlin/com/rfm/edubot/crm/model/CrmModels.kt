package com.rfm.edubot.crm.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

data class Client(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val number: String,
    val name: String,
    val phone: String,
    val address: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    /** Who to talk to when the client is a company. */
    val contactPerson: String? = null,
    val email: String? = null,
    /** Tax number (NIF in Portugal); printed on quotes and invoices. */
    val taxId: String? = null,
    /** Staff-only notes. Never expose them to the customer-facing bot tools. */
    val notes: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Set on a client that has documents and was removed: hidden from lists and pickers, restorable. */
    val archivedAt: Instant? = null,
    /** Agents skip this client entirely: no reminders, no follow-ups, no automated changes. */
    val automationPaused: Boolean = false,
)

data class LineItem(
    val description: String,
    val quantity: Double,
    val unit: String,
    val unitPriceCents: Long,
    val totalCents: Long,
)

enum class QuoteStatus { PENDENTE, SENT, ACEITO }

enum class InvoiceStatus { PENDING, PAID, OVERDUE, CANCELLED }

enum class ClientServiceStatus { OPEN, INVOICED, CANCELLED }

enum class PaymentStatus { PENDING, PAID, OVERDUE, CANCELLED }

/** Vendor the tenant buys from. Payments attach to a supplier. */
data class Supplier(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val number: String,
    val name: String,
    val phone: String,
    val address: String? = null,
    /** Free text the tenant chooses (materials, subcontractor…); the form suggests the types already in use. */
    val type: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Set on a supplier that has payments and was removed: hidden from lists and pickers, restorable. */
    val archivedAt: Instant? = null,
    /** What the supplier usually does; the payment form offers these as lines once the supplier is picked. */
    val services: List<SupplierService> = emptyList(),
)

data class SupplierService(
    val description: String,
    val unit: String = "",
    /** The supplier's usual price per unit; null when it changes from job to job. */
    val unitPriceCents: Long? = null,
)

/** Person on the tenant's team: a payments payee, and with a sign-in of their own, the one registering the services they do. */
data class Employee(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val number: String,
    val name: String,
    val phone: String,
    val role: String? = null,
    val birthDate: LocalDate? = null,
    val address: String? = null,
    /** Tax number (NIF in Portugal). Staff-only, like the rest of the directory. */
    val taxId: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Set on an employee who has payments and was removed: hidden from lists and pickers, restorable. */
    val archivedAt: Instant? = null,
)

/** Outgoing bill. Exactly one of [supplierId] or [employeeId] is set. */
data class Payment(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val number: String,
    val supplierId: ObjectId? = null,
    val employeeId: ObjectId? = null,
    /** Client the expense was for, if any; the client record and Financeiro count it as spent on that client. */
    val clientId: ObjectId? = null,
    val items: List<LineItem>,
    val notes: String? = null,
    val status: PaymentStatus = PaymentStatus.PENDING,
    val dueDate: LocalDate,
    val paidAt: Instant? = null,
    val totalCents: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Work sold or performed for a client. Open rows can be grouped into one invoice.
 *
 * A row with [items] totals their sum, and its [quantity], [unit] and [unitPriceCents] summarize them
 * (one line's own values, or 1 × the sum for several), so readers that predate items still add up.
 * Rows without items (older rows, bookings) are a single line made of those fields and [name].
 */
data class ClientService(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val clientId: ObjectId,
    val name: String,
    val notes: String? = null,
    val quantity: Double = 1.0,
    val unit: String = "",
    val unitPriceCents: Long,
    val totalCents: Long,
    val items: List<LineItem> = emptyList(),
    val status: ClientServiceStatus = ClientServiceStatus.OPEN,
    val invoiceId: ObjectId? = null,
    val bookingServiceId: ObjectId? = null,
    val catalogItemId: String? = null,
    val bookingId: ObjectId? = null,
    val performedAt: LocalDate? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Who did the work, on a row made by approving an employee's registered service. */
    val employeeId: ObjectId? = null,
)

enum class ServiceSubmissionStatus { PENDING, APPROVED, REJECTED }

/**
 * Work an employee registered from their own sign-in. Staff approve it, which creates the Serviços row
 * [serviceId] from its content, or reject it with an optional [rejectionReason]. Only a pending one changes.
 * [adjusted] says the approver changed the content first; the submission then holds what was approved.
 */
data class ServiceSubmission(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val employeeId: ObjectId,
    val clientId: ObjectId,
    val name: String,
    val notes: String? = null,
    val items: List<LineItem>,
    val totalCents: Long,
    val catalogItemId: String? = null,
    val performedAt: LocalDate,
    val status: ServiceSubmissionStatus = ServiceSubmissionStatus.PENDING,
    val serviceId: ObjectId? = null,
    val reviewedBy: String? = null,
    val reviewedAt: Instant? = null,
    val rejectionReason: String? = null,
    val adjusted: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Quote(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val number: String,
    val clientId: ObjectId,
    val items: List<LineItem>,
    val notes: String? = null,
    val status: QuoteStatus = QuoteStatus.PENDENTE,
    val totalCents: Long,
    val validUntil: LocalDate? = null,
    val pdfPath: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** When the quote was first marked sent / accepted; follow-up agents count days from these. */
    val sentAt: Instant? = null,
    val acceptedAt: Instant? = null,
)

data class Invoice(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val number: String,
    val clientId: ObjectId,
    val quoteId: ObjectId? = null,
    val items: List<LineItem>,
    val status: InvoiceStatus = InvoiceStatus.PENDING,
    /**
     * With [installments], the next unpaid one's due date (the last one's once all are paid), so reminders,
     * overdue flags and Home follow the plan without knowing about it.
     */
    val dueDate: LocalDate,
    val paidAt: Instant? = null,
    val totalCents: Long,
    val pdfPath: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** The code the tax office gave this invoice (the ATCUD in Portugal), printed on the PDF. */
    val taxOfficeCode: String? = null,
    /** Empty when the invoice is paid in one go; otherwise parts adding up to [totalCents], paid ones first. */
    val installments: List<InvoiceInstallment> = emptyList(),
) {
    /** Received so far: the whole total once paid, otherwise the installments already paid. */
    val paidCents: Long
        get() = if (status == InvoiceStatus.PAID) totalCents else installments.filter { it.paidAt != null }.sumOf { it.amountCents }

    /** Still to receive; nothing on a paid or cancelled invoice. */
    val outstandingCents: Long
        get() = if (status == InvoiceStatus.PAID || status == InvoiceStatus.CANCELLED) 0 else totalCents - paidCents

    /**
     * What the client owes by [today] or by the due date, whichever is later: the unpaid installments due by
     * then (every overdue one on an overdue invoice), or everything outstanding when it is paid in one go.
     */
    fun amountDueCents(today: LocalDate): Long {
        if (outstandingCents == 0L) return 0
        val unpaid = installments.filter { it.paidAt == null }
        if (unpaid.isEmpty()) return outstandingCents
        val by = maxOf(today, dueDate)
        return unpaid.filter { it.dueDate <= by }.sumOf { it.amountCents }
    }

    /** Money received and when: each paid installment, or the total on the day it was paid in one go. */
    fun receipts(): List<Pair<Instant, Long>> {
        val parts = installments.filter { it.paidAt != null || status == InvoiceStatus.PAID }
        if (parts.isNotEmpty()) return parts.mapNotNull { part -> (part.paidAt ?: paidAt)?.let { it to part.amountCents } }
        return if (status == InvoiceStatus.PAID) listOf((paidAt ?: updatedAt) to totalCents) else emptyList()
    }
}

/** One part of an invoice paid in installments. */
data class InvoiceInstallment(
    val amountCents: Long,
    val dueDate: LocalDate,
    val paidAt: Instant? = null,
)
