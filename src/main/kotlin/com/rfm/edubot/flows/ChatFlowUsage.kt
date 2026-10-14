package com.rfm.edubot.flows

import com.rfm.edubot.conversation.OriginCount
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

/**
 * What a flow sent lately, read back from the messages it stored: [sent] messages to [customers]
 * conversations, and per step id in [steps]. Messages stored before they carried their step only count
 * towards the flow.
 */
data class FlowUsage(val sent: Int, val customers: Int, val lastSentAt: Instant, val steps: Map<String, Int>)

/** The Menus tab's usage figures. */
object ChatFlowUsage {
    const val DAYS = 30

    /** Every message a flow sends has an `origin` that starts with this. */
    const val ORIGIN_PREFIX = FlowPayload.PREFIX

    fun since(now: Instant): Instant = now - DAYS.days

    /** Per flow id, from the per-conversation [counts] of messages whose origin is `flow:<flow>` or `flow:<flow>:<step>`. */
    fun of(counts: List<OriginCount>): Map<String, FlowUsage> =
        counts.mapNotNull { count -> parse(count.origin)?.let { (flow, step) -> Triple(flow, step, count) } }
            .groupBy({ it.first }) { it.second to it.third }
            .mapValues { (_, rows) ->
                FlowUsage(
                    sent = rows.sumOf { it.second.count },
                    customers = rows.map { it.second.conversationId }.toSet().size,
                    lastSentAt = rows.maxOf { it.second.lastAt },
                    steps = rows.filter { it.first != null }.groupBy({ it.first!! }) { it.second.count }.mapValues { it.value.sum() },
                )
            }

    private fun parse(origin: String): Pair<String, String?>? {
        if (!origin.startsWith(ORIGIN_PREFIX)) return null
        val parts = origin.removePrefix(ORIGIN_PREFIX).split(':')
        if (parts.size > 2 || parts.any { it.isEmpty() }) return null
        return parts[0] to parts.getOrNull(1)
    }
}
