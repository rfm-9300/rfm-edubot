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
    val dueDate: LocalDate,
    val paidAt: Instant? = null,
    val totalCents: Long,
    val pdfPath: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)
