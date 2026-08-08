package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Contact(
    val id: String,
    val waId: String,
    val channel: String,
    val displayName: String? = null,
    val status: String,
    val lastSeenAt: String,
)

@Serializable
data class Conversation(
    val id: String,
    val waId: String,
    val channel: String,
    val displayName: String? = null,
    val state: String,
    val lastMessageAt: String,
    val messageCount: Int,
)

@Serializable
data class ThreadMessage(
    val id: String,
    val role: String,
    val text: String,
    val status: String,
    val createdAt: String,
)
