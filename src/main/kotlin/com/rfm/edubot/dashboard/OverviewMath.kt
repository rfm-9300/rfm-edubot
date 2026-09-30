package com.rfm.edubot.dashboard

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

object OverviewMath {
    const val HEALTH_OK = "ok"
    const val HEALTH_WATCH = "watch"
    const val HEALTH_URGENT = "urgent"

    const val KIND_WAITING_CHAT = "waiting_chat"
    const val KIND_OVERDUE_INVOICE = "overdue_invoice"
    const val KIND_DUE_SOON_INVOICE = "due_soon_invoice"
    const val KIND_OVERDUE_PAYMENT = "overdue_payment"
    const val KIND_DUE_SOON_PAYMENT = "due_soon_payment"
    const val KIND_PENDING_BOOKING = "pending_booking"
    const val KIND_INSTAGRAM_COMMENT = "instagram_comment"
    const val KIND_QUOTE_EXPIRING = "quote_expiring"
    const val KIND_ASSISTANT_ACTION = "assistant_action"
    const val KIND_AGENT_APPROVAL = "agent_approval"
    const val KIND_AGENT_TASK_DUE = "agent_task_due"
    const val KIND_AGENT_FAILED = "agent_failed"
    const val KIND_INTEGRATION_RECONNECT = "integration_reconnect"

    const val HIGHLIGHT_COLLECTED = "collected_month"
    const val HIGHLIGHT_OUTSTANDING = "outstanding"
    const val HIGHLIGHT_OVERDUE = "overdue"
    const val HIGHLIGHT_PIPELINE = "pipeline_open"
    const val HIGHLIGHT_WIN_RATE = "win_rate"
    const val HIGHLIGHT_WAITING = "waiting"
    const val HIGHLIGHT_MESSAGES = "messages_today"
    const val HIGHLIGHT_BOOKINGS_TODAY = "bookings_today"
    const val HIGHLIGHT_CLIENTS = "clients"
    const val HIGHLIGHT_CONTACTS = "contacts"
    const val HIGHLIGHT_UNREPLIED = "instagram_unreplied"

    const val RECENT_INVOICE_PAID = "invoice_paid"
    const val RECENT_INVOICE_ISSUED = "invoice_issued"
    const val RECENT_PAYMENT_PAID = "payment_paid"
    const val RECENT_QUOTE_ACCEPTED = "quote_accepted"
    const val RECENT_QUOTE_CREATED = "quote_created"
    const val RECENT_CLIENT_CREATED = "client_created"
    const val RECENT_BOOKING_CREATED = "booking_created"

    const val TREND_MONTHS = 6
    const val TREND_DAYS = 14
    const val TOP_CLIENT_MONTHS = 12
    const val TOP_CLIENTS = 5
    const val RECENT_LIMIT = 8
    const val AGENDA_LIMIT = 8
    /** Approvals, due tasks and failed runs each list at most this many rows in "Needs you". */
    const val AGENT_ITEMS = 3
    /** Failed runs can't be dismissed, so "Needs you" only lists recent ones; the card counts the week. */
    const val AGENT_FAILED_DAYS = 3

    data class Window(
        val zone: TimeZone,
        val now: Instant,
        val today: LocalDate,
        val todayStart: Instant,
        val tomorrowStart: Instant,
        val weekStart: Instant,
        val nextWeekStart: Instant,
        val lastWeekStart: Instant,
        val monthStart: Instant,
        val lastMonthStart: Instant,
        val nextMonthStart: Instant,
        val dueSoonEnd: LocalDate,
        val staleBefore: Instant,
    )

    fun window(now: Instant, timezoneId: String): Window {
        val zone = TimeZone.of(timezoneId)
        val today = now.toLocalDateTime(zone).date
        val todayStart = today.atStartOfDayIn(zone)
        val tomorrow = today.plus(DatePeriod(days = 1))
        val iso = today.dayOfWeek.isoDayNumber
        val weekStartDate = today.minus(DatePeriod(days = iso - 1))
        val nextWeekStartDate = weekStartDate.plus(DatePeriod(days = 7))
        val lastWeekStartDate = weekStartDate.minus(DatePeriod(days = 7))
        val monthStartDate = LocalDate(today.year, today.month, 1)
        val lastMonthEnd = monthStartDate.minus(DatePeriod(days = 1))
        val lastMonthStartDate = LocalDate(lastMonthEnd.year, lastMonthEnd.month, 1)
        val nextMonthStartDate = monthStartDate.plus(DatePeriod(months = 1))
        return Window(
            zone = zone,
            now = now,
            today = today,
            todayStart = todayStart,
            tomorrowStart = tomorrow.atStartOfDayIn(zone),
            weekStart = weekStartDate.atStartOfDayIn(zone),
            nextWeekStart = nextWeekStartDate.atStartOfDayIn(zone),
            lastWeekStart = lastWeekStartDate.atStartOfDayIn(zone),
            monthStart = monthStartDate.atStartOfDayIn(zone),
            lastMonthStart = lastMonthStartDate.atStartOfDayIn(zone),
            nextMonthStart = nextMonthStartDate.atStartOfDayIn(zone),
            dueSoonEnd = today.plus(DatePeriod(days = 7)),
            staleBefore = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - 14L * 24 * 60 * 60 * 1000),
        )
    }

    fun health(
        overdueCount: Int,
        waiting: Int,
        pendingBookings: Int,
        unreplied: Int,
        dueSoonCount: Int,
        expiringQuotes: Int,
        pendingAssistant: Int,
        agentAttention: Int = 0,
        reconnects: Int = 0,
    ): String {
        if (overdueCount > 0) return HEALTH_URGENT
        val watch = waiting + pendingBookings + unreplied + dueSoonCount + expiringQuotes + pendingAssistant + agentAttention + reconnects
        return if (watch > 0) HEALTH_WATCH else HEALTH_OK
    }

    fun winRatePct(accepted: Int, pending: Int, sent: Int): Int {
        val denom = accepted + pending + sent
        if (denom <= 0) return 0
        return (accepted * 100) / denom
    }

    fun deltaPct(current: Long, previous: Long): Int? {
        if (previous == 0L) return if (current == 0L) 0 else null
        return (((current - previous) * 100) / previous).toInt()
    }

    enum class AgingBucket { CURRENT, WEEK, MONTH, OLD }

    fun agingBucket(dueDate: LocalDate, today: LocalDate): AgingBucket {
        if (dueDate >= today) return AgingBucket.CURRENT
        val days = today.toEpochDays() - dueDate.toEpochDays()
        return when {
            days <= 7 -> AgingBucket.WEEK
            days <= 30 -> AgingBucket.MONTH
            else -> AgingBucket.OLD
        }
    }

    fun isEffectivelyOverdue(status: String, dueDate: LocalDate?, today: LocalDate): Boolean {
        if (status == "OVERDUE") return true
        return status == "PENDING" && dueDate != null && dueDate < today
    }

    fun monthKey(date: LocalDate): String = date.toString().substring(0, 7)

    fun monthKey(at: Instant, zone: TimeZone): String = monthKey(at.toLocalDateTime(zone).date)

    /** `YYYY-MM` for the [count] calendar months ending with the one containing [today], oldest first. */
    fun trendMonthKeys(today: LocalDate, count: Int = TREND_MONTHS): List<String> {
        val first = LocalDate(today.year, today.month, 1).minus(DatePeriod(months = count - 1))
        return (0 until count).map { monthKey(first.plus(DatePeriod(months = it))) }
    }

    /** `YYYY-MM-DD` for the [count] days ending with [today], oldest first. */
    fun trendDayKeys(today: LocalDate, count: Int = TREND_DAYS): List<String> =
        (count - 1 downTo 0).map { today.minus(DatePeriod(days = it)).toString() }

    /** Start of a trailing window of [months] calendar months that includes the current one. */
    fun monthsBackStart(today: LocalDate, zone: TimeZone, months: Int): Instant =
        LocalDate(today.year, today.month, 1).minus(DatePeriod(months = months - 1)).atStartOfDayIn(zone)

    fun daysBackStart(today: LocalDate, zone: TimeZone, days: Int): Instant =
        today.minus(DatePeriod(days = days - 1)).atStartOfDayIn(zone)

    fun monthFlow(keys: List<String>, inByMonth: Map<String, Long>, outByMonth: Map<String, Long>): List<OverviewMonthFlowDto> =
        keys.map { OverviewMonthFlowDto(it, inByMonth[it] ?: 0L, outByMonth[it] ?: 0L) }

    fun dayCounts(keys: List<String>, byDay: Map<String, Long>): List<OverviewDayCountDto> =
        keys.map { OverviewDayCountDto(it, byDay[it] ?: 0L) }

    fun setupItems(
        modules: Set<String>,
        hasWhatsApp: Boolean,
        hasInstagram: Boolean,
        hasWidget: Boolean,
        personaEmpty: Boolean,
    ): List<OverviewSetupItemDto> {
        val items = mutableListOf<OverviewSetupItemDto>()
        val settingsOn = DashboardModules.SETTINGS in modules
        if (settingsOn && DashboardModules.CONVERSATIONS in modules && !hasWhatsApp) {
            items += OverviewSetupItemDto("wa", DashboardModules.SETTINGS, "channels")
        }
        if (settingsOn && DashboardModules.INSTAGRAM in modules && !hasInstagram) {
            items += OverviewSetupItemDto("ig", DashboardModules.SETTINGS, "channels")
        }
        if (settingsOn && DashboardModules.CONVERSATIONS in modules && !hasWidget) {
            items += OverviewSetupItemDto("widget", DashboardModules.SETTINGS, "widget")
        }
        if (DashboardModules.PERSONA in modules && personaEmpty) {
            items += OverviewSetupItemDto("persona", DashboardModules.PERSONA, null)
        }
        return items
    }

    fun pickHighlights(
        modules: Set<String>,
        cash: OverviewCashDto?,
        pipeline: OverviewPipelineDto?,
        inbox: OverviewInboxDto?,
        calendar: OverviewCalendarDto?,
        customers: OverviewCustomersDto?,
        social: OverviewSocialDto?,
    ): List<OverviewHighlightDto> {
        val picked = mutableListOf<OverviewHighlightDto>()
        fun add(item: OverviewHighlightDto?) {
            if (item != null && picked.size < 4 && picked.none { it.key == item.key }) picked += item
        }

        if (DashboardModules.INVOICES in modules && cash != null) {
            add(
                OverviewHighlightDto(
                    key = HIGHLIGHT_COLLECTED,
                    module = DashboardModules.INVOICES,
                    cents = cash.collectedThisMonthCents,
                    deltaPct = deltaPct(cash.collectedThisMonthCents, cash.collectedLastMonthCents),
                ),
            )
            if (cash.overdueCount > 0) {
                add(
                    OverviewHighlightDto(
                        key = HIGHLIGHT_OVERDUE,
                        module = DashboardModules.INVOICES,
                        cents = cash.overdueCents,
                        count = cash.overdueCount.toLong(),
                    ),
                )
            }
            add(
                OverviewHighlightDto(
                    key = HIGHLIGHT_OUTSTANDING,
                    module = DashboardModules.INVOICES,
                    cents = cash.outstandingCents,
                ),
            )
        }
        if (DashboardModules.QUOTES in modules && pipeline != null) {
            add(
                OverviewHighlightDto(
                    key = HIGHLIGHT_PIPELINE,
                    module = DashboardModules.QUOTES,
                    cents = pipeline.openCents,
                    count = (pipeline.pendingCount + pipeline.sentCount).toLong(),
                ),
            )
            if (picked.size < 4) {
                add(
                    OverviewHighlightDto(
                        key = HIGHLIGHT_WIN_RATE,
                        module = DashboardModules.QUOTES,
                        pct = pipeline.winRatePct,
                    ),
                )
            }
        }
        if (DashboardModules.CONVERSATIONS in modules && inbox != null) {
            if (inbox.waiting > 0) {
                add(
                    OverviewHighlightDto(
                        key = HIGHLIGHT_WAITING,
                        module = DashboardModules.CONVERSATIONS,
                        count = inbox.waiting.toLong(),
                    ),
                )
            }
            add(
                OverviewHighlightDto(
                    key = HIGHLIGHT_MESSAGES,
                    module = DashboardModules.CONVERSATIONS,
                    count = inbox.messagesToday,
                    deltaPct = deltaPct(inbox.messagesThisWeek, inbox.messagesLastWeek),
                ),
            )
        }
        if (DashboardModules.BOOKINGS in modules && calendar != null) {
            add(
                OverviewHighlightDto(
                    key = HIGHLIGHT_BOOKINGS_TODAY,
                    module = DashboardModules.BOOKINGS,
                    count = calendar.today.toLong(),
                ),
            )
        }
        if (DashboardModules.CLIENTS in modules && customers != null) {
            add(
                OverviewHighlightDto(
                    key = HIGHLIGHT_CLIENTS,
                    module = DashboardModules.CLIENTS,
                    count = customers.total,
                    deltaPct = deltaPct(customers.newThisMonth, customers.newLastMonth),
                ),
            )
        }
        if (DashboardModules.INSTAGRAM in modules && social != null && social.unreplied > 0) {
            add(
                OverviewHighlightDto(
                    key = HIGHLIGHT_UNREPLIED,
                    module = DashboardModules.INSTAGRAM,
                    count = social.unreplied.toLong(),
                ),
            )
        }
        if (DashboardModules.CONTACTS in modules && inbox != null) {
            add(
                OverviewHighlightDto(
                    key = HIGHLIGHT_CONTACTS,
                    module = DashboardModules.CONTACTS,
                    count = inbox.contacts,
                ),
            )
        }
        return picked
    }
}
