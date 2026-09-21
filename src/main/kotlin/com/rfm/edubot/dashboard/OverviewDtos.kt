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
    val catalog: OverviewCatalogDto? = null,
    val assistant: OverviewAssistantDto? = null,
    val setup: List<OverviewSetupItemDto> = emptyList(),
    val hiddenCards: List<String> = emptyList(),
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

@Serializable
data class OverviewCatalogDto(
    val items: Long,
)

@Serializable
data class OverviewAssistantDto(
    val pendingActions: Int,
)

@Serializable
data class OverviewSetupItemDto(
    val kind: String,
    val tab: String,
    val section: String? = null,
)
