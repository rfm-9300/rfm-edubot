package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /app/api/overview`. The backend sends a card per module the tenant has, so most of this is
 * nullable: a null section means "this tenant does not have that module", not "zero".
 */
@Serializable
data class Overview(
    val users: Long = 0,
    val conversations: Long = 0,
    val messages: Long = 0,
    val messagesToday: Long = 0,
    val quotes: Long = 0,
    val invoices: Long = 0,
    val instagramUnreplied: Long = 0,
    val generatedAt: String = "",
    val timezone: String = "",
    val today: String = "",
    val attentionCount: Int = 0,
    val health: String = "ok",
    val highlights: List<OverviewHighlight> = emptyList(),
    val attention: List<OverviewAttentionItem> = emptyList(),
    val cash: OverviewCash? = null,
    val pipeline: OverviewPipeline? = null,
    val customers: OverviewCustomers? = null,
    val inbox: OverviewInbox? = null,
    val calendar: OverviewCalendar? = null,
    val social: OverviewSocial? = null,
    val services: OverviewServices? = null,
    val payments: OverviewPayments? = null,
    val assistant: OverviewAssistant? = null,
    val agents: OverviewAgents? = null,
    val agenda: List<OverviewAgendaItem> = emptyList(),
    val recent: List<OverviewRecentItem> = emptyList(),
)

@Serializable
data class OverviewHighlight(
    val key: String,
    val module: String,
    val cents: Long? = null,
    val count: Long? = null,
    val pct: Int? = null,
    val deltaPct: Int? = null,
)

/** One row of the "needs you" queue. [tab] is the module to open; [id] the record inside it. */
@Serializable
data class OverviewAttentionItem(
    val kind: String,
    val tab: String,
    val id: String? = null,
    val detail: String = "",
    val amountCents: Long? = null,
    val at: String? = null,
)

@Serializable
data class OverviewCash(
    val collectedThisMonthCents: Long = 0,
    val collectedLastMonthCents: Long = 0,
    val issuedThisMonthCents: Long = 0,
    val outstandingCents: Long = 0,
    val overdueCents: Long = 0,
    val overdueCount: Int = 0,
    val dueSoonCents: Long = 0,
    val dueSoonCount: Int = 0,
    val invoiceCount: Int = 0,
    val paidCountThisMonth: Int = 0,
)

@Serializable
data class OverviewPipeline(
    val openCents: Long = 0,
    val pendingCount: Int = 0,
    val sentCount: Int = 0,
    val acceptedCount: Int = 0,
    val acceptedThisMonthCents: Long = 0,
    val winRatePct: Int = 0,
    val expiringSoonCount: Int = 0,
    val quoteCount: Int = 0,
)

@Serializable
data class OverviewCustomers(
    val total: Long = 0,
    val newThisMonth: Long = 0,
    val newLastMonth: Long = 0,
)

@Serializable
data class OverviewInbox(
    val waiting: Int = 0,
    val conversations: Long = 0,
    val messagesToday: Long = 0,
    val messagesThisWeek: Long = 0,
    val contacts: Long = 0,
    val newContactsThisWeek: Long = 0,
    val autoReplyPaused: Int = 0,
)

@Serializable
data class OverviewCalendar(
    val today: Int = 0,
    val thisWeek: Int = 0,
    val pending: Int = 0,
    val next: OverviewCalendarNext? = null,
)

@Serializable
data class OverviewCalendarNext(
    val id: String,
    val contactName: String = "",
    val startAt: String = "",
)

@Serializable
data class OverviewSocial(
    val unreplied: Int = 0,
    val connected: Boolean = false,
    val commentsEnabled: Boolean = false,
)

@Serializable
data class OverviewServices(
    val openCount: Int = 0,
    val openCents: Long = 0,
    val invoicedThisMonthCount: Int = 0,
    val invoicedThisMonthCents: Long = 0,
)

@Serializable
data class OverviewPayments(
    val paidThisMonthCents: Long = 0,
    val outstandingCents: Long = 0,
    val overdueCents: Long = 0,
    val overdueCount: Int = 0,
    val dueSoonCents: Long = 0,
    val dueSoonCount: Int = 0,
    val paymentCount: Int = 0,
)

@Serializable
data class OverviewAssistant(val pendingActions: Int = 0)

@Serializable
data class OverviewAgents(
    val activeAgents: Long = 0,
    val runsToday: Long = 0,
    val pendingApprovals: Long = 0,
    val openTasks: Long = 0,
    val tasksDue: Long = 0,
    val failedThisWeek: Long = 0,
    val paused: Boolean = false,
)

@Serializable
data class OverviewAgendaItem(
    val id: String,
    val startAt: String,
    val endAt: String = "",
    val contactName: String = "",
    val service: String = "",
    val status: String = "",
)

@Serializable
data class OverviewRecentItem(
    val kind: String,
    val tab: String,
    val id: String,
    val number: String? = null,
    val name: String? = null,
    val amountCents: Long? = null,
    val at: String = "",
)
