package com.rfm.edubot.bookings.model

import kotlinx.datetime.Instant
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

/** A catalog service (`crm.standard_items`, type service) as Bookings sees it. */
data class BookableService(
    val id: String,
    val name: String,
    val category: String,
    val unit: String,
    val durationMinutes: Int?,
    val priceCents: Long,
    /** Offered for booking: the catalog item is flagged bookable and has a duration. */
    val active: Boolean,
)

/** Weekly availability window in the tenant timezone. dayOfWeek is ISO: 1=Monday … 7=Sunday. */
data class AvailabilityRule(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val dayOfWeek: Int,
    val startLocal: String,
    val endLocal: String,
)

enum class BookingStatus {
    PENDING, CONFIRMED, CANCELLED, COMPLETED, NO_SHOW;

    /** Holds its time against overlapping bookings. A no-show frees the slot for a walk-in. */
    val blocksSlot: Boolean get() = this == PENDING || this == CONFIRMED || this == COMPLETED
}

enum class BookingSource {
    DASHBOARD, ADMIN, WHATSAPP, INSTAGRAM, WEB, ASSISTANT;

    /** Made by a customer in a conversation rather than by staff, so opening hours and "not in the past" are enforced. */
    val isCustomer: Boolean get() = this == WHATSAPP || this == INSTAGRAM || this == WEB
}

data class Booking(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    /** Catalog item id (`crm.standard_items.id`) of the booked service. */
    val serviceId: String,
    /** Service name when booked, so history still reads right after the catalog changes. */
    val serviceName: String = "",
    /** Agreed price; this is what gets billed when the booking is completed. */
    val priceCents: Long? = null,
    val clientId: ObjectId? = null,
    /** The Serviços row (`crm.client_services`) created when the booking was completed. */
    val clientServiceId: ObjectId? = null,
    val contactName: String,
    val contactPhone: String,
    val startAt: Instant,
    val endAt: Instant,
    val status: BookingStatus = BookingStatus.PENDING,
    val notes: String? = null,
    val source: BookingSource = BookingSource.DASHBOARD,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** `bookings.services` id on rows written before booking services moved into the catalog. */
    val legacyServiceId: ObjectId? = null,
)

data class TimeSlot(
    val startAt: Instant,
    val endAt: Instant,
)
