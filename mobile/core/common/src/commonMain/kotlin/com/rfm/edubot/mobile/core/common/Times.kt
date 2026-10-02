package com.rfm.edubot.mobile.core.common

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Renders the ISO instants the dashboard returns in the tenant's own timezone. Screens used to show
 * `2026-09-30T10:01:11.284Z` verbatim; everything user-facing should go through here.
 *
 * Month and weekday names are not in here on purpose: those are copy, and belong in the string
 * catalogs so they translate.
 */
class TenantClock(
    private val zoneId: String,
    private val now: () -> Instant,
) {
    val zone: TimeZone = runCatching { TimeZone.of(zoneId) }.getOrDefault(TimeZone.UTC)

    fun instant(): Instant = now()

    fun today(): LocalDate = now().toLocalDateTime(zone).date

    fun parse(iso: String): Instant? = runCatching { Instant.parse(iso) }.getOrNull()

    fun localDateTime(iso: String): LocalDateTime? = parse(iso)?.toLocalDateTime(zone)

    /** `14:05`, or null when [iso] is not an instant. */
    fun timeOfDay(iso: String): String? = localDateTime(iso)?.let { "${it.hour.pad()}:${it.minute.pad()}" }

    /** `30/09`, dropping the year because lists are nearly always recent. */
    fun dayAndMonth(iso: String): String? = localDateTime(iso)?.let { "${it.dayOfMonth.pad()}/${it.monthNumber.pad()}" }

    /** `30/09/2026`. Used for due dates, where the year matters. */
    fun fullDate(iso: String): String? = localDateTime(iso)?.let {
        "${it.dayOfMonth.pad()}/${it.monthNumber.pad()}/${it.year}"
    }

    /**
     * A clock time for today, a date otherwise — the rule chat and inbox lists use everywhere.
     * Returns [iso] unchanged when it cannot be parsed, so a format change upstream degrades to the
     * raw value instead of a blank cell.
     */
    fun listStamp(iso: String): String {
        val moment = localDateTime(iso) ?: return iso
        return if (moment.date == today()) timeOfDay(iso) ?: iso else dayAndMonth(iso) ?: iso
    }

    /** Whole days from [iso] until now; negative while [iso] is in the future. */
    fun daysSince(iso: String): Int? {
        val then = parse(iso) ?: return null
        return ((now().toEpochMilliseconds() - then.toEpochMilliseconds()) / MILLIS_PER_DAY).toInt()
    }

    /** True when [iso] is a date or instant strictly before today. */
    fun isOverdue(iso: String): Boolean {
        val date = localDateTime(iso)?.date ?: runCatching { LocalDate.parse(iso) }.getOrNull() ?: return false
        return date < today()
    }

    private fun Int.pad(): String = if (this < 10) "0$this" else toString()

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

/**
 * Cents to `1.234,56` — the European grouping the quotes and invoices already print.
 *
 * The sign is taken from the input rather than from the whole-euro part, because an amount between
 * -99 and -1 cents has a whole part of zero and would otherwise lose its minus.
 */
fun formatCents(cents: Long): String {
    val negative = cents < 0
    val absolute = if (negative) -cents else cents
    val digits = (absolute / 100).toString()
    val grouped = StringBuilder(if (negative) "-" else "")
    digits.forEachIndexed { index, digit ->
        if (index > 0 && (digits.length - index) % 3 == 0) grouped.append('.')
        grouped.append(digit)
    }
    val fraction = (absolute % 100).toInt()
    return "$grouped,${if (fraction < 10) "0$fraction" else fraction.toString()}"
}

/** Euro doubles to the same shape, rounding half-up on the cent. */
fun formatEuros(amount: Double): String {
    val negative = amount < 0
    val absoluteCents = ((if (negative) -amount else amount) * 100.0 + 0.5).toLong()
    return formatCents(if (negative) -absoluteCents else absoluteCents)
}
