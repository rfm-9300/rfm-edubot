package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Session(val token: String)

@Serializable
data class DashboardIdentity(
    val tenant: Tenant,
    val user: DashboardUser? = null,
    val modules: List<String>,
    val principalType: String,
)

@Serializable
data class Tenant(
    val id: String,
    val slug: String,
    val name: String,
    val locale: String,
    val channels: List<ChannelAsset> = emptyList(),
)

@Serializable
data class ChannelAsset(
    val platform: String,
    val externalId: String,
    val displayName: String? = null,
)

@Serializable
data class DashboardUser(
    val id: String,
    val email: String,
    val role: String,
    val status: String,
)

@Serializable
data class Overview(
    val users: Long,
    val conversations: Long,
    val messages: Long,
    val messagesToday: Long,
    val quotes: Long,
    val invoices: Long,
    val instagramUnreplied: Long = 0,
    val attentionCount: Int = 0,
    val health: String = "ok",
    val cash: OverviewCash? = null,
    val pipeline: OverviewPipeline? = null,
    val inbox: OverviewInbox? = null,
    val calendar: OverviewCalendar? = null,
    val customers: OverviewCustomers? = null,
)

@Serializable
data class OverviewCash(
    val collectedThisMonthCents: Long = 0,
    val outstandingCents: Long = 0,
    val overdueCents: Long = 0,
    val overdueCount: Int = 0,
)

@Serializable
data class OverviewPipeline(
    val openCents: Long = 0,
    val winRatePct: Int = 0,
    val quoteCount: Int = 0,
)

@Serializable
data class OverviewInbox(
    val waiting: Int = 0,
    val messagesToday: Long = 0,
    val contacts: Long = 0,
)

@Serializable
data class OverviewCalendar(
    val today: Int = 0,
    val pending: Int = 0,
)

@Serializable
data class OverviewCustomers(
    val total: Long = 0,
    val newThisMonth: Long = 0,
)
