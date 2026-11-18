package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.PaymentRepeat
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentRecurrenceTest {

    private fun days(first: String, every: PaymentRepeat, times: Int) =
        PaymentRecurrence.dueDates(LocalDate.parse(first), every, times).map { it.toString() }

    @Test
    fun `the first payment is due on the date given and weekly ones follow seven days apart`() {
        assertEquals(listOf("2026-11-18", "2026-11-25", "2026-12-02"), days("2026-11-18", PaymentRepeat.WEEKLY, 3))
    }

    @Test
    fun `monthly keeps the day and a bill due on the 31st goes back to it after a short month`() {
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30"), days("2026-01-31", PaymentRepeat.MONTHLY, 4))
        assertEquals(listOf("2026-11-15", "2026-12-15", "2027-01-15"), days("2026-11-15", PaymentRepeat.MONTHLY, 3))
    }

    @Test
    fun `quarterly and yearly count from the first date, not from the one before`() {
        assertEquals(listOf("2026-11-30", "2027-02-28", "2027-05-30", "2027-08-30"), days("2026-11-30", PaymentRepeat.QUARTERLY, 4))
        assertEquals(listOf("2028-02-29", "2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29"), days("2028-02-29", PaymentRepeat.YEARLY, 5))
    }

    @Test
    fun `how often reads in any case and anything else is no repeat`() {
        assertEquals(PaymentRepeat.MONTHLY, PaymentRecurrence.parse("monthly"))
        assertEquals(PaymentRepeat.QUARTERLY, PaymentRecurrence.parse(" Quarterly "))
        assertNull(PaymentRecurrence.parse("daily"))
        assertNull(PaymentRecurrence.parse(null))
    }

    @Test
    fun `a bill repeats between 2 and 24 times`() {
        assertFalse(PaymentRecurrence.validTimes(null))
        assertFalse(PaymentRecurrence.validTimes(1))
        assertTrue(PaymentRecurrence.validTimes(2))
        assertTrue(PaymentRecurrence.validTimes(24))
        assertFalse(PaymentRecurrence.validTimes(25))
    }
}
