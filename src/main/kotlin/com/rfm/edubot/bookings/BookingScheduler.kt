package com.rfm.edubot.bookings

import com.rfm.edubot.bookings.model.AvailabilityRule
import com.rfm.edubot.bookings.model.Booking
import com.rfm.edubot.bookings.model.BookingService
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.bookings.model.BookingStatus
import com.rfm.edubot.bookings.model.TimeSlot
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.TenantTimeZones
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId

class BookingConflictException(message: String) : Exception(message)

class BookingScheduler(
    private val services: BookingServiceRepository,
    private val availability: AvailabilityRepository,
    private val bookings: BookingRepository,
    private val timezoneId: String,
) {
    private val zone: TimeZone = TimeZone.of(TenantTimeZones.normalize(timezoneId))

    suspend fun availableSlots(serviceId: ObjectId, from: Instant, to: Instant): List<TimeSlot> {
        val service = services.findById(serviceId) ?: return emptyList()
        if (!service.active) return emptyList()
        val rules = availability.list()
        if (rules.isEmpty()) return emptyList()
        val existing = bookings.list(from = from, to = to).filter { it.status != BookingStatus.CANCELLED }
        return generateSlots(service, rules, existing, from, to, zone)
    }

    suspend fun create(
        serviceId: ObjectId,
        contactName: String,
        contactPhone: String,
        startAt: Instant,
        clientId: ObjectId? = null,
        notes: String? = null,
        status: BookingStatus = BookingStatus.PENDING,
        source: BookingSource = BookingSource.DASHBOARD,
        endAtOverride: Instant? = null,
    ): Booking {
        val service = services.findById(serviceId) ?: throw IllegalArgumentException("service not found")
        if (!service.active) throw IllegalArgumentException("service is inactive")
        val endAt = endAtOverride ?: startAt.plus(service.durationMinutes, DateTimeUnit.MINUTE)
        if (endAt <= startAt) throw IllegalArgumentException("end must be after start")
        val conflicts = bookings.findOverlapping(startAt, endAt)
        if (conflicts.isNotEmpty()) throw BookingConflictException("time slot overlaps an existing booking")
        val now = SystemClock.now()
        return bookings.create(
            Booking(
                tenantId = service.tenantId,
                serviceId = serviceId,
                clientId = clientId,
                contactName = contactName.trim(),
                contactPhone = contactPhone.trim(),
                startAt = startAt,
                endAt = endAt,
                status = status,
                notes = notes?.trim()?.takeIf { it.isNotBlank() },
                source = source,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    suspend fun reschedule(id: ObjectId, startAt: Instant, serviceId: ObjectId? = null): Booking {
        val existing = bookings.findById(id) ?: throw IllegalArgumentException("booking not found")
        if (existing.status == BookingStatus.CANCELLED) throw IllegalArgumentException("cannot reschedule a cancelled booking")
        val service = services.findById(serviceId ?: existing.serviceId) ?: throw IllegalArgumentException("service not found")
        val endAt = startAt.plus(service.durationMinutes, DateTimeUnit.MINUTE)
        val conflicts = bookings.findOverlapping(startAt, endAt, excludeId = id)
        if (conflicts.isNotEmpty()) throw BookingConflictException("time slot overlaps an existing booking")
        return bookings.update(id, serviceId = service.id, startAt = startAt, endAt = endAt)
            ?: throw IllegalArgumentException("booking not found")
    }

    companion object {
        fun generateSlots(
            service: BookingService,
            rules: List<AvailabilityRule>,
            existing: List<Booking>,
            from: Instant,
            to: Instant,
            zone: TimeZone,
        ): List<TimeSlot> {
            if (to <= from || service.durationMinutes <= 0) return emptyList()
            val rulesByDay = rules.groupBy { it.dayOfWeek }
            val durationMs = service.durationMinutes * 60_000L
            val slots = mutableListOf<TimeSlot>()
            var day = from.toLocalDateTime(zone).date
            val endDay = to.toLocalDateTime(zone).date
            while (day <= endDay) {
                val dayRules = rulesByDay[day.dayOfWeek.ordinal + 1].orEmpty()
                for (rule in dayRules) {
                    val windowStart = localInstant(day, rule.startLocal, zone) ?: continue
                    val windowEnd = localInstant(day, rule.endLocal, zone) ?: continue
                    if (windowEnd <= windowStart) continue
                    var cursor = maxOf(windowStart, from)
                    val hardEnd = minOf(windowEnd, to)
                    while (cursor.plus(service.durationMinutes, DateTimeUnit.MINUTE) <= hardEnd) {
                        val slotEnd = cursor.plus(service.durationMinutes, DateTimeUnit.MINUTE)
                        val overlaps = existing.any { booking ->
                            booking.startAt < slotEnd && booking.endAt > cursor
                        }
                        if (!overlaps && cursor >= from) {
                            slots.add(TimeSlot(cursor, slotEnd))
                        }
                        cursor = Instant.fromEpochMilliseconds(cursor.toEpochMilliseconds() + durationMs)
                    }
                }
                day = day.plus(DatePeriod(days = 1))
            }
            return slots
        }

        fun parseLocalDateTime(value: String, timezoneId: String): Instant {
            val zone = TimeZone.of(TenantTimeZones.normalize(timezoneId))
            val normalized = value.trim().replace(' ', 'T')
            val local = when {
                normalized.length >= 19 -> LocalDateTime.parse(normalized.take(19))
                else -> LocalDateTime.parse(normalized)
            }
            return local.toInstant(zone)
        }

        private fun localInstant(day: LocalDate, hhmm: String, zone: TimeZone): Instant? {
            val parts = hhmm.trim().split(':')
            if (parts.size < 2) return null
            val hour = parts[0].toIntOrNull() ?: return null
            val minute = parts[1].toIntOrNull() ?: return null
            return LocalDateTime(day, LocalTime(hour, minute)).toInstant(zone)
        }
    }
}
