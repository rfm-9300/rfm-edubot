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
