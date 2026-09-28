package com.rfm.edubot.bookings

import com.rfm.edubot.bookings.model.AvailabilityRule
import com.rfm.edubot.bookings.model.Booking
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.bookings.model.BookingStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookingSchedulerTest {
    private val zone = TimeZone.of("Europe/Lisbon")
    private val tenantId = ObjectId()
    private val monday = at(10, 0, 0)
    private val tuesday = LocalDateTime(2026, 8, 11, 0, 0).toInstant(zone)
    private val mornings = listOf(AvailabilityRule(tenantId = tenantId, dayOfWeek = 1, startLocal = "09:00", endLocal = "12:00"))

    @Test
    fun `generates slots inside availability windows`() {
        val slots = BookingScheduler.generateSlots(60, mornings, emptyList(), monday, tuesday, zone)
        assertEquals(listOf(at(10, 9, 0), at(10, 10, 0), at(10, 11, 0)), slots.map { it.startAt })
    }

    @Test
    fun `skips slots that overlap existing bookings`() {
        val existing = listOf(booking(at(10, 10, 0), at(10, 11, 0), BookingStatus.CONFIRMED))
        val slots = BookingScheduler.generateSlots(60, mornings, existing, monday, tuesday, zone)
        assertEquals(listOf(at(10, 9, 0), at(10, 11, 0)), slots.map { it.startAt })
    }

    @Test
    fun `the next slot starts where a clashing booking ends`() {
        val existing = listOf(booking(at(10, 9, 0), at(10, 9, 30), BookingStatus.PENDING))
        val slots = BookingScheduler.generateSlots(60, mornings, existing, monday, tuesday, zone)
        assertEquals(listOf(at(10, 9, 30), at(10, 10, 30)), slots.map { it.startAt })
    }

    @Test
    fun `cancelled and no-show bookings leave the slot free`() {
        val existing = listOf(
            booking(at(10, 9, 0), at(10, 10, 0), BookingStatus.CANCELLED),
            booking(at(10, 10, 0), at(10, 11, 0), BookingStatus.NO_SHOW),
        )
        val slots = BookingScheduler.generateSlots(60, mornings, existing, monday, tuesday, zone)
        assertEquals(3, slots.size)
    }

    @Test
    fun `a window that has already started snaps to the next quarter hour`() {
        val slots = BookingScheduler.generateSlots(60, mornings, emptyList(), at(10, 9, 7), tuesday, zone)
        assertEquals(listOf(at(10, 9, 15), at(10, 10, 15)), slots.map { it.startAt })
    }

    @Test
    fun `a booking fits only when it sits inside one window of its weekday`() {
        assertTrue(BookingScheduler.fitsAvailability(at(10, 9, 0), at(10, 12, 0), mornings, zone))
        assertFalse(BookingScheduler.fitsAvailability(at(10, 11, 30), at(10, 12, 30), mornings, zone), "runs past closing")
        assertFalse(BookingScheduler.fitsAvailability(at(10, 8, 30), at(10, 9, 30), mornings, zone), "starts before opening")
        assertFalse(BookingScheduler.fitsAvailability(at(11, 9, 0), at(11, 10, 0), mornings, zone), "Tuesday is closed")
        assertFalse(BookingScheduler.fitsAvailability(at(10, 9, 0), at(10, 10, 0), emptyList(), zone), "no hours configured")
    }

    @Test
    fun `local times use the tenant timezone and ISO instants keep their offset`() {
        assertEquals(at(10, 10, 0), BookingScheduler.parseLocalDateTime("2026-08-10T10:00", "Europe/Lisbon"))
        assertEquals(at(10, 10, 0), parseBookingTime("2026-08-10T10:00", "Europe/Lisbon"))
        assertEquals(Instant.parse("2026-08-10T10:00:00Z"), parseBookingTime("2026-08-10T10:00:00Z", "Europe/Lisbon"))
        assertEquals(null, parseBookingTime("tomorrow", "Europe/Lisbon"))
    }

    private fun at(day: Int, hour: Int, minute: Int): Instant = LocalDateTime(2026, 8, day, hour, minute).toInstant(zone)

    private fun booking(start: Instant, end: Instant, status: BookingStatus) = Booking(
        tenantId = tenantId,
        serviceId = "srv-consult",
        contactName = "Ana",
        contactPhone = "+351",
        startAt = start,
        endAt = end,
        status = status,
        source = BookingSource.DASHBOARD,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
}
