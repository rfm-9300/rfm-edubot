package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Booking(
    val id: String,
    val serviceId: String = "",
    val serviceName: String = "",
    val priceEur: Double? = null,
    val clientId: String? = null,
    val clientServiceId: String? = null,
    val contactName: String = "",
    val contactPhone: String = "",
    val startAt: String,
    val endAt: String = "",
    val status: String = BookingStatus.PENDING,
    val notes: String? = null,
    val source: String = "DASHBOARD",
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    val pending: Boolean get() = status == BookingStatus.PENDING

    val completed: Boolean get() = status == BookingStatus.COMPLETED

    /** A finished job that has not become a billable Serviços row yet. */
    val billable: Boolean get() = completed && clientServiceId == null
}

object BookingStatus {
    const val PENDING = "PENDING"
    const val CONFIRMED = "CONFIRMED"
    const val CANCELLED = "CANCELLED"
    const val COMPLETED = "COMPLETED"
    const val NO_SHOW = "NO_SHOW"

    /** The transitions the bookings screen offers, in the order the web drawer shows them. */
    val decisions: List<String> = listOf(CONFIRMED, COMPLETED, NO_SHOW, CANCELLED)
}

@Serializable
data class BookingService(
    val id: String,
    val name: String,
    val category: String = "",
    val unit: String = "",
    val durationMinutes: Int? = null,
    val priceEur: Double = 0.0,
    val active: Boolean = true,
)

@Serializable
data class AvailabilityRule(
    /** 1 = Monday through 7 = Sunday, matching the backend. */
    val dayOfWeek: Int,
    val startLocal: String,
    val endLocal: String,
)

@Serializable
data class TimeSlot(val startAt: String, val endAt: String)

@Serializable
data class CreateBooking(
    val serviceId: String,
    val startAt: String,
    val contactName: String = "",
    val contactPhone: String = "",
    val clientId: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val durationMinutes: Int? = null,
    val priceEur: Double? = null,
)

@Serializable
data class UpdateBooking(
    val serviceId: String? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val startAt: String? = null,
    val clientId: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val durationMinutes: Int? = null,
    val priceEur: Double? = null,
)
