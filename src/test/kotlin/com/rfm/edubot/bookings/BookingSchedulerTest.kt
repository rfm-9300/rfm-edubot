package com.rfm.edubot.bookings

import com.rfm.edubot.bookings.model.AvailabilityRule
import com.rfm.edubot.bookings.model.Booking
import com.rfm.edubot.bookings.model.BookingService
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.bookings.model.BookingStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookingSchedulerTest {
    private val zone = TimeZone.of("Europe/Lisbon")
    private val tenantId = ObjectId()
    private val service = BookingService(
        tenantId = tenantId,
        name = "Consult",
        durationMinutes = 60,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun `generates hourly slots inside availability windows`() {
        val monday = LocalDateTime(2026, 8, 10, 0, 0).toInstant(zone) // Monday
        val tuesday = LocalDateTime(2026, 8, 11, 0, 0).toInstant(zone)
        val rules = listOf(
            AvailabilityRule(tenantId = tenantId, dayOfWeek = 1, startLocal = "09:00", endLocal = "12:00"),
        )
        val slots = BookingScheduler.generateSlots(service, rules, emptyList(), monday, tuesday, zone)
        assertEquals(3, slots.size)
        assertEquals(LocalDateTime(2026, 8, 10, 9, 0).toInstant(zone), slots[0].startAt)
        assertEquals(LocalDateTime(2026, 8, 10, 11, 0).toInstant(zone), slots[2].startAt)
    }

    @Test
    fun `skips slots that overlap existing bookings`() {
        val monday = LocalDateTime(2026, 8, 10, 0, 0).toInstant(zone)
        val tuesday = LocalDateTime(2026, 8, 11, 0, 0).toInstant(zone)
        val rules = listOf(
            AvailabilityRule(tenantId = tenantId, dayOfWeek = 1, startLocal = "09:00", endLocal = "12:00"),
        )
        val existing = listOf(
            Booking(
                tenantId = tenantId,
                serviceId = service.id,
                contactName = "Ana",
                contactPhone = "+351",
                startAt = LocalDateTime(2026, 8, 10, 10, 0).toInstant(zone),
                endAt = LocalDateTime(2026, 8, 10, 11, 0).toInstant(zone),
                status = BookingStatus.CONFIRMED,
                source = BookingSource.DASHBOARD,
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
            )
        )
        val slots = BookingScheduler.generateSlots(service, rules, existing, monday, tuesday, zone)
        assertEquals(2, slots.size)
        assertTrue(slots.none { it.startAt == LocalDateTime(2026, 8, 10, 10, 0).toInstant(zone) })
    }

    @Test
    fun `parseLocalDateTime uses tenant timezone`() {
        val instant = BookingScheduler.parseLocalDateTime("2026-08-10T10:00", "Europe/Lisbon")
        assertEquals(LocalDateTime(2026, 8, 10, 10, 0).toInstant(zone), instant)
    }
}
