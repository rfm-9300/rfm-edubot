package com.rfm.edubot.mobile.core.common

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lists used to print `2026-09-30T10:01:11.284Z` verbatim. These cover the formatting that replaced
 * it, including the timezone — a tenant in Lisbon must not see UTC clock times in July.
 */
class TimesTest {
    private val lisbonSummer = TenantClock("Europe/Lisbon") { Instant.parse("2026-07-15T12:00:00Z") }
    private val lisbonWinter = TenantClock("Europe/Lisbon") { Instant.parse("2026-01-15T12:00:00Z") }

    @Test
    fun `clock times are in the tenant's zone rather than UTC`() {
        // Lisbon is UTC+1 in July.
        assertEquals("13:05", lisbonSummer.timeOfDay("2026-07-15T12:05:00Z"))
        // And UTC+0 in January.
        assertEquals("12:05", lisbonWinter.timeOfDay("2026-01-15T12:05:00Z"))
    }

    @Test
    fun `an unknown timezone falls back to UTC rather than failing`() {
        val clock = TenantClock("Mars/Olympus") { Instant.parse("2026-01-15T12:00:00Z") }
        assertEquals(TimeZone.UTC, clock.zone)
        assertEquals("12:00", clock.timeOfDay("2026-01-15T12:00:00Z"))
    }

    @Test
    fun `a list stamp is a time today and a date otherwise`() {
        assertEquals("09:30", lisbonWinter.listStamp("2026-01-15T09:30:00Z"))
        assertEquals("14/01", lisbonWinter.listStamp("2026-01-14T09:30:00Z"))
    }

    @Test
    fun `a value that is not an instant is passed through rather than blanked`() {
        assertEquals("not a date", lisbonWinter.listStamp("not a date"))
        assertNull(lisbonWinter.timeOfDay("not a date"))
    }

    @Test
    fun `dates pad single digits so columns line up`() {
        assertEquals("05/02", lisbonWinter.dayAndMonth("2026-02-05T09:30:00Z"))
        assertEquals("05/02/2026", lisbonWinter.fullDate("2026-02-05T09:30:00Z"))
        assertEquals("09:05", lisbonWinter.timeOfDay("2026-01-15T09:05:00Z"))
    }

    @Test
    fun `days since counts whole days and is negative in the future`() {
        assertEquals(3, lisbonWinter.daysSince("2026-01-12T12:00:00Z"))
        assertEquals(-3, lisbonWinter.daysSince("2026-01-18T12:00:00Z"))
        assertEquals(0, lisbonWinter.daysSince("2026-01-15T06:00:00Z"))
    }

    @Test
    fun `a due date before today is overdue`() {
        assertTrue(lisbonWinter.isOverdue("2026-01-14"))
        assertFalse(lisbonWinter.isOverdue("2026-01-15"), "due today is not yet late")
        assertFalse(lisbonWinter.isOverdue("2026-01-16"))
    }

    @Test
    fun `an overdue check accepts both a plain date and an instant`() {
        assertTrue(lisbonWinter.isOverdue("2026-01-14T23:59:00Z"))
        assertTrue(lisbonWinter.isOverdue("2026-01-14"))
    }

    @Test
    fun `a missing or unparseable due date is not treated as overdue`() {
        assertFalse(lisbonWinter.isOverdue(""))
        assertFalse(lisbonWinter.isOverdue("soon"))
    }
}

class MoneyTest {
    @Test
    fun `cents render with European grouping and a comma`() {
        assertEquals("0,00", formatCents(0))
        assertEquals("0,05", formatCents(5))
        assertEquals("1,00", formatCents(100))
        assertEquals("12,34", formatCents(1234))
        assertEquals("1.234,56", formatCents(123_456))
        assertEquals("1.234.567,89", formatCents(123_456_789))
    }

    @Test
    fun `a negative amount keeps one sign at the front`() {
        assertEquals("-1.234,56", formatCents(-123_456))
        assertEquals("-0,05", formatCents(-5))
    }

    @Test
    fun `euro doubles round half-up on the cent`() {
        assertEquals("1.250,00", formatEuros(1250.0))
        assertEquals("0,10", formatEuros(0.1))
        assertEquals("12,35", formatEuros(12.345))
        assertEquals("99,99", formatEuros(99.99))
    }

    @Test
    fun `a negative euro amount keeps its sign`() {
        assertEquals("-12,35", formatEuros(-12.345))
    }

    @Test
    fun `a price with repeating binary fraction still reads exactly`() {
        // 0.07 and 1.005 are the classic cases where naive rounding drops a cent.
        assertEquals("0,07", formatEuros(0.07))
        assertEquals("8,15", formatEuros(8.15))
    }
}
