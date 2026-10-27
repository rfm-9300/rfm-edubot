package com.rfm.edubot.dashboard

import kotlinx.serialization.Serializable

@Serializable
data class OverviewDto(
    val users: Long,
    val conversations: Long,
    val messages: Long,
    val messagesToday: Long,
    val quotes: Long,
    val invoices: Long,
    val instagramUnreplied: Long = 0,
    val generatedAt: String = "",
    val timezone: String = "Europe/Lisbon",
    val today: String = "",
    val attentionCount: Int = 0,
    val health: String = "ok",
    val highlights: List<OverviewHighlightDto> = emptyList(),
    val attention: List<OverviewAttentionItemDto> = emptyList(),
    val cash: OverviewCashDto? = null,
    val pipeline: OverviewPipelineDto? = null,
    val customers: OverviewCustomersDto? = null,
    val inbox: OverviewInboxDto? = null,
    val calendar: OverviewCalendarDto? = null,
    val social: OverviewSocialDto? = null,
    val email: OverviewEmailDto? = null,
    val catalog: OverviewCatalogDto? = null,
    val services: OverviewServicesDto? = null,
    val suppliers: OverviewSuppliersDto? = null,
    val employees: OverviewEmployeesDto? = null,
    val payments: OverviewPaymentsDto? = null,
    val assistant: OverviewAssistantDto? = null,
    val agents: OverviewAgentsDto? = null,
    val projects: OverviewProjectsDto? = null,
    val setup: List<OverviewSetupItemDto> = emptyList(),
    val hiddenCards: List<String> = emptyList(),
    /** Filled only for `GET /overview?extended=1` (the minimal Home layout); empty otherwise. */
    val cashFlow: List<OverviewMonthFlowDto> = emptyList(),
    val activity: List<OverviewDayCountDto> = emptyList(),
    val agenda: List<OverviewAgendaItemDto> = emptyList(),
    val recent: List<OverviewRecentItemDto> = emptyList(),
    val topClients: List<OverviewTopClientDto> = emptyList(),
    val serviceStats: OverviewServiceStatsDto? = null,
)

/** Money received (paid invoices) and spent (paid payments) in one tenant-local calendar month. */
@Serializable
data class OverviewMonthFlowDto(
    val month: String,
    val inCents: Long,
    val outCents: Long,
)

@Serializable
data class OverviewDayCountDto(
    val day: String,
    val count: Long,
)

@Serializable
data class OverviewAgendaItemDto(
    val id: String,
    val startAt: String,
    val endAt: String,
    val contactName: String,
    val service: String,
    val status: String,
)

/** One business event for the activity feed. Copy is composed client-side from [kind]. */
@Serializable
data class OverviewRecentItemDto(
    val kind: String,
    val tab: String,
    val id: String,
    val number: String? = null,
    val name: String? = null,
    val amountCents: Long? = null,
    val at: String,
)

@Serializable
data class OverviewTopClientDto(
    val id: String,
    val name: String,
    val billedCents: Long,
    val paidCents: Long,
    val invoiceCount: Int,
)

@Serializable
data class OverviewHighlightDto(
    val key: String,
    val module: String,
    val cents: Long? = null,
    val count: Long? = null,
    val pct: Int? = null,
    val deltaPct: Int? = null,
)

@Serializable
data class OverviewAttentionItemDto(
    val kind: String,
    val tab: String,
    val id: String? = null,
    val detail: String,
    val amountCents: Long? = null,
    val at: String? = null,
)

@Serializable
data class OverviewCashDto(
    val collectedThisMonthCents: Long,
    val collectedLastMonthCents: Long,
    val issuedThisMonthCents: Long,
    val outstandingCents: Long,
    val overdueCents: Long,
    val overdueCount: Int,
    val dueSoonCents: Long,
    val dueSoonCount: Int,
    val invoiceCount: Int,
    val paidCountThisMonth: Int,
    val agingCurrentCents: Long,
    val agingWeekCents: Long,
    val agingMonthCents: Long,
    val agingOldCents: Long,
    val topOverdue: List<OverviewNamedAmountDto> = emptyList(),
)

@Serializable
data class OverviewNamedAmountDto(
    val id: String,
    val name: String,
    val amountCents: Long,
    val number: String? = null,
)

@Serializable
data class OverviewPipelineDto(
    val openCents: Long,
    val pendingCount: Int,
    val sentCount: Int,
    val acceptedCount: Int,
    val acceptedThisMonthCents: Long,
    val acceptedThisMonthCount: Int,
    val winRatePct: Int,
    val expiringSoonCount: Int,
    val quoteCount: Int,
)

@Serializable
data class OverviewCustomersDto(
    val total: Long,
    val newThisMonth: Long,
    val newLastMonth: Long,
)

@Serializable
data class OverviewInboxDto(
    val waiting: Int,
    val conversations: Long,
    val messagesToday: Long,
    val messagesThisWeek: Long,
    val messagesLastWeek: Long,
    val contacts: Long,
    val newContactsThisWeek: Long,
    val autoReplyPaused: Int,
)

@Serializable
data class OverviewCalendarDto(
    val today: Int,
    val thisWeek: Int,
    val pending: Int,
    val next: OverviewCalendarNextDto? = null,
)

@Serializable
data class OverviewCalendarNextDto(
    val id: String,
    val contactName: String,
    val startAt: String,
)

@Serializable
data class OverviewSocialDto(
    val unreplied: Int,
    val connected: Boolean,
    val commentsEnabled: Boolean,
)

/** The Email page's figure: threads with received mail nobody on the team has opened. */
@Serializable
data class OverviewEmailDto(
    val unread: Int,
)

@Serializable
data class OverviewCatalogDto(
    val items: Long,
)

@Serializable
data class OverviewServicesDto(
    val openCount: Int,
    val openCents: Long,
    val invoicedThisMonthCount: Int,
    val invoicedThisMonthCents: Long,
)

/**
 * Home's Services card (extended only): the work done, read like the employee record does. A row is to do
 * while it is open and dated today or later; every other row that isn't cancelled is done. A row's day is
 * its `performedAt`, or the tenant-local day it was created.
 */
@Serializable
data class OverviewServiceStatsDto(
    val doneThisWeek: Int,
    val doneThisMonth: Int,
    val doneThisMonthCents: Long,
    val doneLastMonth: Int,
    val doneLastMonthCents: Long,
    val doneThisYear: Int,
    val doneThisYearCents: Long,
    val toDo: Int,
    val toDoToday: Int,
    /** The last [OverviewMath.TREND_MONTHS] months, oldest first. */
    val months: List<OverviewServiceMonthDto>,
    /** The lines done most often over the last [OverviewMath.TOP_CLIENT_MONTHS] months, by catalog title. */
    val top: List<OverviewServiceTopDto>,
    /** Who did the most this month; empty without the Employees module. */
    val employees: List<OverviewServiceEmployeeDto> = emptyList(),
)

@Serializable
data class OverviewServiceMonthDto(
    val month: String,
    val count: Int,
    val cents: Long,
)

@Serializable
data class OverviewServiceTopDto(
    val name: String,
    val count: Int,
    val cents: Long,
)

@Serializable
data class OverviewServiceEmployeeDto(
    val id: String,
    val name: String,
    val count: Int,
    val cents: Long,
)

/**
 * Home's Service insights card, `GET /overview/services?months=N`: the work done in the last [months] months
 * ([from] to [to], both included) against the [months] before, read the way [OverviewServiceStatsDto] is.
 * Lists are capped; the page ranks [services] and [clients] by times or by value, so each holds the top by both.
 */
@Serializable
data class ServiceInsightsDto(
    val months: Int,
    val from: String,
    val to: String,
    val current: ServicePeriodDto,
    val previous: ServicePeriodDto,
    /** Calendar months ending with this one, oldest first: at least [OverviewMath.TREND_MONTHS], or [months]. */
    val trend: List<OverviewServiceMonthDto>,
    val services: List<ServiceInsightItemDto>,
    val clients: List<ServiceInsightClientDto>,
    val matrix: ServiceMatrixDto,
    val dueBack: List<ServiceDueBackDto>,
    /** Services done in the period by weekday, Monday first. */
    val weekdays: List<Int>,
    /** Empty without the Employees module. */
    val employees: List<OverviewServiceEmployeeDto> = emptyList(),
    /** True when the company has more rows than one read takes, so the figures miss the oldest ones. */
    val truncated: Boolean = false,
)

@Serializable
data class ServicePeriodDto(
    val count: Int,
    val cents: Long,
    /** The services' lines, which [ServiceInsightItemDto] counts: their number and value. */
    val lines: Int,
    val lineCents: Long,
    val clients: Int,
    /** Clients with two services or more in the period. */
    val repeatClients: Int,
    /** Clients whose first service ever is in the period; only counted for the current period. */
    val newClients: Int = 0,
    /** The share of the value that the top fifth of clients brought, 0–100. */
    val topFifthSharePct: Int = 0,
)

@Serializable
data class ServiceInsightItemDto(
    val name: String,
    val count: Int,
    val cents: Long,
    val clients: Int,
    val previousCount: Int,
)

@Serializable
data class ServiceInsightClientDto(
    val id: String,
    val name: String,
    val count: Int,
    val cents: Long,
    val previousCount: Int,
    val lastDay: String,
    /** The line this client had done most in the period, and how often. */
    val topService: String,
    val topServiceCount: Int,
    /** How many different lines the client had done. */
    val serviceKinds: Int,
)

/** Who buys what: the top clients (rows) against the top services (columns), each cell that client's lines of that service. */
@Serializable
data class ServiceMatrixDto(
    val services: List<String>,
    val rows: List<ServiceMatrixRowDto>,
)

@Serializable
data class ServiceMatrixRowDto(
    val id: String,
    val name: String,
    val cells: List<ServiceMatrixCellDto>,
)

@Serializable
data class ServiceMatrixCellDto(
    val count: Int,
    val cents: Long,
)

/**
 * A regular client later than usual: the median gap between their visits over the last two years is [usualDays],
 * the last was on [lastDay], and they have nothing booked. [lateDays] counts from the day they were due.
 */
@Serializable
data class ServiceDueBackDto(
    val id: String,
    val name: String,
    val visits: Int,
    val usualDays: Int,
    val lastDay: String,
    val lateDays: Int,
    val cents: Long,
)

@Serializable
data class OverviewSuppliersDto(
    val total: Long,
    val newThisMonth: Long,
    val newLastMonth: Long,
)

@Serializable
data class OverviewEmployeesDto(
    val total: Long,
    val newThisMonth: Long,
    val newLastMonth: Long,
    /** Services employees registered that wait for someone to approve them (needs the services module). */
    val pendingSubmissions: Long = 0,
)

@Serializable
data class OverviewPaymentsDto(
    val paidThisMonthCents: Long,
    val outstandingCents: Long,
    val overdueCents: Long,
    val overdueCount: Int,
    val dueSoonCents: Long,
    val dueSoonCount: Int,
    val paymentCount: Int,
)

@Serializable
data class OverviewAssistantDto(
    val pendingActions: Int,
)

/** Agents module summary. Runs today counts the last 24 hours, as on the Agents screen. */
@Serializable
data class OverviewAgentsDto(
    val activeAgents: Long,
    val runsToday: Long,
    val pendingApprovals: Long,
    val openTasks: Long,
    val tasksDue: Long,
    val failedThisWeek: Long,
    val paused: Boolean,
)

/**
 * The Building category: projects by health, issues, today's diaries, changes waiting for the client and
 * measurements to invoice. [behind] holds the active projects furthest behind their plan. No defaults: the
 * JSON config would leave out the zeros the card reads.
 */
@Serializable
data class OverviewProjectsDto(
    val active: Int,
    val planning: Int,
    val atRisk: Int,
    val delayed: Int,
    val openIssues: Int,
    val overdueIssues: Int,
    val diariesToday: Int,
    val activeWithoutDiary: Int,
    val pendingChanges: Int,
    val pendingChangesCents: Long,
    val toInvoiceCount: Int,
    val toInvoiceCents: Long,
    val behind: List<OverviewProjectRowDto>,
)

@Serializable
data class OverviewProjectRowDto(
    val id: String,
    val number: String,
    val name: String,
    val health: String,
    val progressPct: Int,
    val plannedPct: Int?,
    val scheduleDays: Int?,
)

@Serializable
data class OverviewSetupItemDto(
    val kind: String,
    val tab: String,
    val section: String? = null,
)
