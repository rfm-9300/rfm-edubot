package com.rfm.edubot.agents.runtime

import com.rfm.edubot.agents.model.QuietHours
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduleCalculatorTest {
    private val lisbon = TimeZone.of("Europe/Lisbon")
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant = LocalDateTime(year, month, day, hour, minute).toInstant(lisbon)
    private fun local(instant: Instant) = instant.toLocalDateTime(lisbon)

    @Test
    fun `a daily schedule keeps its local time across the daylight-saving change`() {
        val config = buildJsonObject { put("frequency", "daily"); put("time", "09:00") }
        // Clocks go back at 02:00 on 25 October 2026 in Lisbon.
        val saturday = ScheduleCalculator.nextFire(config, at(2026, 10, 24, 8), lisbon)!!
        assertEquals(LocalDateTime(2026, 10, 24, 9, 0), local(saturday))
        val sunday = ScheduleCalculator.nextFire(config, saturday, lisbon)!!
        assertEquals(LocalDateTime(2026, 10, 25, 9, 0), local(sunday))
        assertEquals(25L, (sunday - saturday).inWholeHours, "the night of the change lasts an hour longer")
        assertEquals(LocalDateTime(2026, 10, 26, 9, 0), local(ScheduleCalculator.nextFire(config, sunday, lisbon)!!))
    }

    @Test
    fun `weekday schedules skip the weekend`() {
        val config = buildJsonObject {
            put("frequency", "daily")
            put("time", "08:00")
            put("weekdays", buildJsonArray { (1..5).forEach { add(JsonPrimitive(it)) } })
        }
        // Friday 2 October 2026, after 08:00 → Monday.
        assertEquals(LocalDateTime(2026, 10, 5, 8, 0), local(ScheduleCalculator.nextFire(config, at(2026, 10, 2, 9), lisbon)!!))
    }

    @Test
    fun `weekly and monthly schedules, including the last day of the month`() {
        val weekly = buildJsonObject { put("frequency", "weekly"); put("time", "08:00"); put("weekdays", buildJsonArray { add(JsonPrimitive(1)) }) }
        assertEquals(LocalDateTime(2026, 10, 5, 8, 0), local(ScheduleCalculator.nextFire(weekly, at(2026, 10, 1, 12), lisbon)!!))
        val lastDay = buildJsonObject { put("frequency", "monthly"); put("time", "17:00"); put("dayOfMonth", -1) }
        assertEquals(LocalDateTime(2027, 2, 28, 17, 0), local(ScheduleCalculator.nextFire(lastDay, at(2027, 2, 1, 9), lisbon)!!))
        val the31st = buildJsonObject { put("frequency", "monthly"); put("time", "09:00"); put("dayOfMonth", 31) }
        assertEquals(LocalDateTime(2026, 11, 30, 9, 0), local(ScheduleCalculator.nextFire(the31st, at(2026, 11, 1, 9), lisbon)!!))
    }

    @Test
    fun `hourly schedules step through the day`() {
        val config = buildJsonObject { put("frequency", "hourly"); put("everyHours", 6); put("time", "00:15") }
        assertEquals(LocalDateTime(2026, 10, 1, 12, 15), local(ScheduleCalculator.nextFire(config, at(2026, 10, 1, 7), lisbon)!!))
    }

    @Test
    fun `quiet hours across midnight hold messages until the morning`() {
        val quiet = QuietHours("21:00", "08:00")
        assertEquals(LocalDateTime(2026, 10, 2, 8, 0), local(Guardrails.nextAllowed(at(2026, 10, 1, 22), lisbon, quiet, false)!!))
        assertEquals(LocalDateTime(2026, 10, 2, 8, 0), local(Guardrails.nextAllowed(at(2026, 10, 2, 6), lisbon, quiet, false)!!))
        assertNull(Guardrails.nextAllowed(at(2026, 10, 2, 10), lisbon, quiet, false))
    }

    @Test
    fun `business days only moves a weekend message to Monday morning`() {
        val quiet = QuietHours("21:00", "08:00")
        // Saturday 3 October 2026 at noon.
        assertEquals(LocalDateTime(2026, 10, 5, 8, 0), local(Guardrails.nextAllowed(at(2026, 10, 3, 12), lisbon, quiet, true)!!))
        assertNull(Guardrails.nextAllowed(at(2026, 10, 5, 9), lisbon, quiet, true))
    }

    @Test
    fun `date offsets look at today and yesterday once their time has passed`() {
        val nine = LocalTime(9, 0)
        assertEquals(listOf(LocalDate(2026, 10, 1)), AgentScheduler.fireDates(at(2026, 10, 2, 8), lisbon, nine))
        assertEquals(listOf(LocalDate(2026, 10, 2)), AgentScheduler.fireDates(at(2026, 10, 2, 12), lisbon, nine))
    }
}
