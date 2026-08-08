package com.rfm.edubot.bookings.model

import kotlinx.datetime.Instant
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

data class BookingService(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val name: String,
    val durationMinutes: Int,
    val active: Boolean = true,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Weekly availability window in the tenant timezone. dayOfWeek is ISO: 1=Monday … 7=Sunday. */
data class AvailabilityRule(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val dayOfWeek: Int,
    val startLocal: String,
    val endLocal: String,
)

enum class BookingStatus { PENDING, CONFIRMED, CANCELLED, COMPLETED }

enum class BookingSource { DASHBOARD, ADMIN, WHATSAPP, ASSISTANT }

data class Booking(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val serviceId: ObjectId,
    val clientId: ObjectId? = null,
    val contactName: String,
    val contactPhone: String,
    val startAt: Instant,
    val endAt: Instant,
    val status: BookingStatus = BookingStatus.PENDING,
    val notes: String? = null,
    val source: BookingSource = BookingSource.DASHBOARD,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TimeSlot(
    val startAt: Instant,
    val endAt: Instant,
)
