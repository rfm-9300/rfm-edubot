package com.rfm.edubot.agents.briefings

import com.rfm.edubot.agents.registry.DefinitionProblem
import kotlinx.serialization.Serializable

/** `GET /app/api/briefings`: the company's briefings, with how today went for each. */
@Serializable
data class BriefingListDto(val today: String, val canManage: Boolean, val briefings: List<BriefingDto>)

/** A briefing: an agent that runs on a schedule once per employee. */
@Serializable
data class BriefingDto(
    val id: String,
    val name: String,
    /** The agent's status: DRAFT, ACTIVE, PAUSED. */
    val status: String,
    val icon: String? = null,
    val templateKey: String? = null,
    val schedule: BriefingScheduleDto? = null,
    /** The next time the schedule fires, while the briefing is on. */
    val nextRunAt: String? = null,
    /** The WhatsApp template its message step sends, once picked. */
    val template: String? = null,
    val templateLanguage: String? = null,
    /** What of each person's day it reads: `shifts`, `services`, `issues`. */
    val groups: List<String> = emptyList(),
    /** Its message waits for a person's approval ("Ask me first"), so a send starts approvals, not messages. */
    val asksFirst: Boolean = false,
    /** Why it can't be switched on (`app.agents.problems.<code>`, as the Agents page words them), none when it can. */
    val problems: List<DefinitionProblem> = emptyList(),
    val today: BriefingCountsDto,
)

@Serializable
data class BriefingScheduleDto(val frequency: String, val time: String? = null, val weekdays: List<Int> = emptyList())

/** Today's runs by outcome, one per person (their latest). */
@Serializable
data class BriefingCountsDto(
    val people: Int = 0,
    /** Sent, delivered or read. */
    val sent: Int = 0,
    val read: Int = 0,
    val failed: Int = 0,
    /** Still going out: queued, sending, or held for quiet hours. */
    val waiting: Int = 0,
    val approval: Int = 0,
)

/** `GET /app/api/briefings/{id}?day=`: one row per member of the team for that day. */
@Serializable
data class BriefingDayDto(
    val briefing: BriefingDto,
    val day: String,
    val today: String,
    /** When the schedule fires on [day], if it does. */
    val scheduledAt: String? = null,
    val people: List<BriefingPersonDto>,
    val canManage: Boolean,
)

/**
 * One person on one day. [state] is one of [BriefingStates]; [detail] says why (a reason code, or WhatsApp's own
 * error text); [message] is what they got, or would get today.
 */
@Serializable
data class BriefingPersonDto(
    val employeeId: String,
    val name: String,
    val phone: String,
    val role: String? = null,
    val state: String,
    val detail: String? = null,
    val message: String? = null,
    /** The preview's warnings (`app.agents.warnings.<code>`), such as no template picked yet. */
    val warnings: List<String> = emptyList(),
    /** When [state] happened, or happens (the schedule's time, the end of quiet hours). */
    val at: String? = null,
    val runId: String? = null,
    val approvalId: String? = null,
    /** How many times the briefing ran for them that day (a resend makes two). */
    val sends: Int = 0,
)

@Serializable
data class BriefingSendRequest(val employeeIds: List<String>? = null, val again: Boolean = false)

@Serializable
data class BriefingSendDto(val started: Int, val skipped: List<BriefingSkipDto>)

/** [reason]: opted_out, no_phone, already_sent, nothing_today, not_found, or why the run didn't start (AgentRunStarter). */
@Serializable
data class BriefingSkipDto(val employeeId: String, val reason: String)

@Serializable
data class BriefingApproveRequest(val employeeIds: List<String>? = null)

@Serializable
data class BriefingApproveDto(val approved: Int)

/** What a person's row says. */
object BriefingStates {
    /** Tagged `no-briefing`: never written to. */
    const val OPTED_OUT = "OPTED_OUT"
    const val NO_PHONE = "NO_PHONE"

    /** The record isn't one the briefing's conditions pick. */
    const val NOT_INCLUDED = "NOT_INCLUDED"

    /** Their day has nothing for it (no shift, no service): nothing goes. */
    const val NOTHING_TODAY = "NOTHING_TODAY"

    /** Nothing sent yet; today's schedule will send it at `at`. */
    const val SCHEDULED = "SCHEDULED"

    /** Nothing sent yet and the schedule won't today (its time passed, it's off, or not on this weekday): send it by hand. */
    const val READY = "READY"

    /** An earlier day without a message. */
    const val NOT_SENT = "NOT_SENT"

    const val SENDING = "SENDING"

    /** Held, as `detail` says (quiet hours, a retry): goes at `at`. */
    const val WAITING = "WAITING"
    const val AWAITING_APPROVAL = "AWAITING_APPROVAL"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val READ = "READ"
    const val FAILED = "FAILED"

    /** WhatsApp couldn't take it, so a task went to the team instead (`detail`: why). */
    const val FALLBACK = "FALLBACK"

    /** The step was skipped, as `detail` says (a cap, for one). */
    const val SKIPPED = "SKIPPED"

    /** Its step only drafts ("Only draft it"): the message was written, never sent. */
    const val DRAFTED = "DRAFTED"

    /** Rejected, expired unapproved or cancelled (`detail`). */
    const val CANCELLED = "CANCELLED"
}
