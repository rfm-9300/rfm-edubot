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
    /** Home's Services card: the work done, filled with `extended=1`. */
    val serviceStats: OverviewServiceStats? = null,
    /** Home's Projects card, for a company with the Building category. */
    val projects: OverviewProjects? = null,
    /** Cards this company hid from Home on the web; the app hides them too. */
    val hiddenCards: List<String> = emptyList(),
)

/**
 * A row is to do while it is open and dated today or later; every other row that isn't cancelled is
 * done. Read like the employee record reads it.
 */
@Serializable
data class OverviewServiceStats(
    val doneThisWeek: Int = 0,
    val doneThisMonth: Int = 0,
    val doneThisMonthCents: Long = 0,
    val doneLastMonth: Int = 0,
    val doneLastMonthCents: Long = 0,
    val doneThisYear: Int = 0,
    val doneThisYearCents: Long = 0,
    val toDo: Int = 0,
    val toDoToday: Int = 0,
    /** The lines done most often lately, by catalog title. */
    val top: List<OverviewServiceTop> = emptyList(),
    /** Who did the most this month; empty without the Employees module. */
    val employees: List<OverviewServiceEmployee> = emptyList(),
)

@Serializable
data class OverviewServiceTop(val name: String, val count: Int = 0, val cents: Long = 0)

@Serializable
data class OverviewServiceEmployee(val id: String, val name: String, val count: Int = 0, val cents: Long = 0)

@Serializable
data class OverviewProjects(
    val active: Int = 0,
    val planning: Int = 0,
    val atRisk: Int = 0,
    val delayed: Int = 0,
    val openIssues: Int = 0,
    val overdueIssues: Int = 0,
    val diariesToday: Int = 0,
    val activeWithoutDiary: Int = 0,
    val pendingChanges: Int = 0,
    val pendingChangesCents: Long = 0,
    val toInvoiceCount: Int = 0,
    val toInvoiceCents: Long = 0,
    /** The active projects furthest behind their plan. */
    val behind: List<OverviewProjectRow> = emptyList(),
)

@Serializable
data class OverviewProjectRow(
    val id: String,
    val number: String = "",
    val name: String = "",
    val health: String = "",
    val progressPct: Int = 0,
    val plannedPct: Int? = null,
    val scheduleDays: Int? = null,
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
