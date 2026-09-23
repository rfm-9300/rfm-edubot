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
    val createdAt: Instant,
    val updatedAt: Instant,
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
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Person on the tenant's team. Payments still attach only to a supplier; this directory is the payee list a later payments change can use. */
data class Employee(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val number: String,
    val name: String,
    val phone: String,
    val role: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Outgoing bill. Exactly one of [supplierId] or [employeeId] is set. */
data class Payment(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val number: String,
    val supplierId: ObjectId? = null,
    val employeeId: ObjectId? = null,
    val items: List<LineItem>,
    val notes: String? = null,
    val status: PaymentStatus = PaymentStatus.PENDING,
    val dueDate: LocalDate,
    val paidAt: Instant? = null,
    val totalCents: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Work sold or performed for a client. Open rows can be grouped into one invoice. */
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
    val status: ClientServiceStatus = ClientServiceStatus.OPEN,
    val invoiceId: ObjectId? = null,
    val bookingServiceId: ObjectId? = null,
    val catalogItemId: String? = null,
    val bookingId: ObjectId? = null,
    val performedAt: LocalDate? = null,
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
