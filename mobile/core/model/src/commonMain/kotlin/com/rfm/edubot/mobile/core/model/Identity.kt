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
)
