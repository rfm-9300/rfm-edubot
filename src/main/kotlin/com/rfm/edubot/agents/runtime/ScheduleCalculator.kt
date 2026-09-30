package com.rfm.edubot.agents.runtime

import com.rfm.edubot.agents.registry.int
import com.rfm.edubot.agents.registry.ints
import com.rfm.edubot.agents.registry.string
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject

/**
 * Next fire times of schedule triggers in the company's timezone. Local wall-clock times go through
 * the timezone, so "every day at 09:00" stays at 09:00 across daylight-saving changes.
 */
object ScheduleCalculator {
    fun nextFire(config: JsonObject, after: Instant, zone: TimeZone): Instant? {
        val time = parseTime(config.string("time")) ?: LocalTime(9, 0)
        val weekdays = config.ints("weekdays").filter { it in 1..7 }.toSet()
        return when (config.string("frequency") ?: "daily") {
            "hourly" -> nextHourly(config.int("everyHours")?.coerceIn(1, 24) ?: 1, time.minute, weekdays, after, zone)
            "weekly" -> nextDaily(time, weekdays.ifEmpty { setOf(1) }, after, zone)
            "monthly" -> nextMonthly(config.int("dayOfMonth") ?: 1, time, after, zone)
            else -> nextDaily(time, weekdays, after, zone)
        }
    }

    private fun nextDaily(time: LocalTime, weekdays: Set<Int>, after: Instant, zone: TimeZone): Instant? {
        var date = after.toLocalDateTime(zone).date
        repeat(400) {
            if (weekdays.isEmpty() || date.dayOfWeek.isoDayNumber in weekdays) {
                val candidate = LocalDateTime(date, time).toInstant(zone)
                if (candidate > after) return candidate
            }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return null
    }

    private fun nextHourly(everyHours: Int, minute: Int, weekdays: Set<Int>, after: Instant, zone: TimeZone): Instant? {
        val start = after.toLocalDateTime(zone)
        var date = start.date
        repeat(400) {
            if (weekdays.isEmpty() || date.dayOfWeek.isoDayNumber in weekdays) {
                for (hour in 0 until 24 step everyHours) {
                    val candidate = LocalDateTime(date, LocalTime(hour, minute)).toInstant(zone)
                    if (candidate > after) return candidate
                }
            }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return null
    }

    /** [dayOfMonth] past the month's end (or -1) means the month's last day. */
    private fun nextMonthly(dayOfMonth: Int, time: LocalTime, after: Instant, zone: TimeZone): Instant? {
        val today = after.toLocalDateTime(zone).date
        var month = LocalDate(today.year, today.monthNumber, 1)
        repeat(24) {
            val last = month.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).dayOfMonth
            val day = if (dayOfMonth <= 0 || dayOfMonth > last) last else dayOfMonth
            val candidate = LocalDateTime(LocalDate(month.year, month.monthNumber, day), time).toInstant(zone)
            if (candidate > after) return candidate
            month = month.plus(1, DateTimeUnit.MONTH)
        }
        return null
    }

    fun parseTime(value: String?): LocalTime? = value?.let { raw ->
        val parts = raw.trim().split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) null else LocalTime(hour, minute)
    }
}
