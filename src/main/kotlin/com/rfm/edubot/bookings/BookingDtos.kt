package com.rfm.edubot.bookings

import com.rfm.edubot.bookings.model.AvailabilityRule
import com.rfm.edubot.bookings.model.Booking
import com.rfm.edubot.bookings.model.BookingService
import com.rfm.edubot.bookings.model.TimeSlot
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

@Serializable
data class BookingServiceDto(
    val id: String,
    val name: String,
    val durationMinutes: Int,
    val active: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CreateBookingServiceRequest(
    val name: String,
    val durationMinutes: Int = 30,
)

@Serializable
data class UpdateBookingServiceRequest(
    val name: String? = null,
    val durationMinutes: Int? = null,
    val active: Boolean? = null,
)

@Serializable
data class AvailabilityRuleDto(
    val dayOfWeek: Int,
    val startLocal: String,
    val endLocal: String,
)

@Serializable
data class ReplaceAvailabilityRequest(
    val rules: List<AvailabilityRuleDto>,
)

@Serializable
data class BookingDto(
    val id: String,
    val serviceId: String,
    val clientId: String? = null,
    val contactName: String,
    val contactPhone: String,
    val startAt: String,
    val endAt: String,
    val status: String,
    val notes: String? = null,
    val source: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CreateBookingRequest(
    val serviceId: String,
    val contactName: String,
    val contactPhone: String,
    val startAt: String,
    val clientId: String? = null,
    val notes: String? = null,
    val status: String? = null,
)

@Serializable
data class UpdateBookingRequest(
    val serviceId: String? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val startAt: String? = null,
    val clientId: String? = null,
    val clearClientId: Boolean = false,
    val notes: String? = null,
    val status: String? = null,
)

@Serializable
data class TimeSlotDto(
    val startAt: String,
    val endAt: String,
)

fun BookingService.dto() = BookingServiceDto(
    id = id.toHexString(),
    name = name,
    durationMinutes = durationMinutes,
    active = active,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

fun AvailabilityRule.dto() = AvailabilityRuleDto(dayOfWeek = dayOfWeek, startLocal = startLocal, endLocal = endLocal)

fun Booking.dto() = BookingDto(
    id = id.toHexString(),
    serviceId = serviceId.toHexString(),
    clientId = clientId?.toHexString(),
    contactName = contactName,
    contactPhone = contactPhone,
    startAt = startAt.toString(),
    endAt = endAt.toString(),
    status = status.name,
    notes = notes,
    source = source.name,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

fun TimeSlot.dto() = TimeSlotDto(startAt = startAt.toString(), endAt = endAt.toString())

fun AvailabilityRuleDto.toRule(tenantId: ObjectId) = AvailabilityRule(
    tenantId = tenantId,
    dayOfWeek = dayOfWeek,
    startLocal = startLocal,
    endLocal = endLocal,
)
