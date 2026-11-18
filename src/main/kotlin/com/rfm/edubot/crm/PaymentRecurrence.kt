package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.PaymentRepeat
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** When the payments of a repeating bill fall due, as a pure rule the repository applies. */
object PaymentRecurrence {
    const val MIN_TIMES = 2
    const val MAX_TIMES = 24

    fun parse(raw: String?): PaymentRepeat? = PaymentRepeat.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }

    fun validTimes(times: Int?): Boolean = times != null && times in MIN_TIMES..MAX_TIMES

    /**
     * The [times] due dates, the first being [first]. Each is counted from [first], not from the one before, so a
     * bill due on the 31st falls on the last day of a shorter month and goes back to the 31st after it.
     */
    fun dueDates(first: LocalDate, every: PaymentRepeat, times: Int): List<LocalDate> = List(times) { n ->
        when (every) {
            PaymentRepeat.WEEKLY -> first.plus(n * 7, DateTimeUnit.DAY)
            PaymentRepeat.MONTHLY -> first.plus(n, DateTimeUnit.MONTH)
            PaymentRepeat.QUARTERLY -> first.plus(n * 3, DateTimeUnit.MONTH)
            PaymentRepeat.YEARLY -> first.plus(n, DateTimeUnit.YEAR)
        }
    }
}
