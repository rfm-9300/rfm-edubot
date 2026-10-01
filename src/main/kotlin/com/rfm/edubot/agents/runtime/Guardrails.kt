package com.rfm.edubot.agents.runtime

import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.CompanyAgentSettings
import com.rfm.edubot.agents.model.QuietHours
import com.rfm.edubot.agents.store.OutboundLogRepository
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId
import kotlin.time.Duration.Companion.days

/** Limits on when and how often agents may contact people outside the company. */
object Guardrails {
    /** The agent's quiet hours, else the company's. */
    fun quietHours(definition: AgentDefinition, company: CompanyAgentSettings): QuietHours? =
        definition.policy.quietHours ?: company.quietHours

    fun businessDaysOnly(definition: AgentDefinition, company: CompanyAgentSettings): Boolean =
        definition.policy.businessDaysOnly ?: company.businessDaysOnly

    /**
     * When a message may go out if [now] is not a good moment: the end of quiet hours, or Monday when
     * only business days are allowed. Null means it can go now.
     */
    fun nextAllowed(now: Instant, zone: TimeZone, quietHours: QuietHours?, businessDaysOnly: Boolean): Instant? {
        var candidate = now
        repeat(8) {
            val local = candidate.toLocalDateTime(zone)
            if (businessDaysOnly && local.date.dayOfWeek.isoDayNumber >= 6) {
                val daysToMonday = 8 - local.date.dayOfWeek.isoDayNumber
                val morning = quietHours?.let { ScheduleCalculator.parseTime(it.end) } ?: LocalTime(9, 0)
                candidate = LocalDateTime(local.date.plus(daysToMonday, DateTimeUnit.DAY), morning).toInstant(zone)
                return@repeat
            }
            val quietEnd = quietHours?.let { quietEndIfInside(local, it, zone) }
            if (quietEnd != null) {
                candidate = quietEnd
                return@repeat
            }
            return if (candidate == now) null else candidate
        }
        return candidate.takeIf { it != now }
    }

    private fun quietEndIfInside(local: LocalDateTime, quiet: QuietHours, zone: TimeZone): Instant? {
        val start = ScheduleCalculator.parseTime(quiet.start) ?: return null
        val end = ScheduleCalculator.parseTime(quiet.end) ?: return null
        if (start == end) return null
        val time = local.time
        return if (start < end) {
            if (time >= start && time < end) LocalDateTime(local.date, end).toInstant(zone) else null
        } else {
            when {
                time >= start -> LocalDateTime(local.date.plus(1, DateTimeUnit.DAY), end).toInstant(zone)
                time < end -> LocalDateTime(local.date, end).toInstant(zone)
                else -> null
            }
        }
    }

    /** True when [recipient] already got as many automated messages as the company allows today or this week. */
    suspend fun recipientCapReached(
        log: OutboundLogRepository,
        tenantId: ObjectId,
        channel: String,
        recipient: String,
        company: CompanyAgentSettings,
        now: Instant,
    ): Boolean {
        val normalized = OutboundLogRepository.normalizeRecipient(channel, recipient)
        if (normalized.isBlank()) return false
        val today = log.countForRecipient(tenantId, normalized, now - 1.days)
        if (company.perRecipientDailyCap > 0 && today >= company.perRecipientDailyCap) return true
        val week = log.countForRecipient(tenantId, normalized, now - 7.days)
        return company.perRecipientWeeklyCap > 0 && week >= company.perRecipientWeeklyCap
    }
}
