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
) {
    val blocked: Boolean get() = status == "BLOCKED"
}

@Serializable
data class Conversation(
    val id: String,
    val waId: String,
    val channel: String,
    val displayName: String? = null,
    val state: String,
    val lastMessageAt: String,
    val messageCount: Int = 0,
    val lastPreview: String? = null,
    val lastRole: String? = null,
    /** The customer wrote last and nobody has answered. Drives the "needs reply" filter. */
    val waiting: Boolean = false,
    val autoReplyEnabled: Boolean = true,
    val unreadCount: Int = 0,
    val lastAuthor: String? = null,
    val lastKind: String? = null,
    val lastStatus: String? = null,
    val lastInboundAt: String? = null,
    /** When WhatsApp's 24-hour service window shuts; after it only a template can be sent. */
    val windowExpiresAt: String? = null,
    val autoReplyPausedAt: String? = null,
    val autoReplyPausedBy: String? = null,
) {
    val title: String get() = displayName?.takeIf { it.isNotBlank() } ?: waId
}

@Serializable
data class ThreadMessage(
    val id: String,
    val role: String,
    val text: String,
    val status: String,
    val createdAt: String,
    val author: String = AUTHOR_BOT,
    val kind: String = KIND_TEXT,
    val tracked: Boolean = false,
    val agentName: String? = null,
    val templateName: String? = null,
    val fileName: String? = null,
    val statusAt: String? = null,
    val errorCode: String? = null,
    val errorKey: String? = null,
    val errorText: String? = null,
) {
    val fromCustomer: Boolean get() = role.equals("USER", ignoreCase = true)

    /** Written by a person on the tenant's side rather than by the assistant. */
    val fromStaff: Boolean get() = !fromCustomer && author == AUTHOR_AGENT

    val failed: Boolean get() = status.equals("FAILED", ignoreCase = true)

    val hasAttachment: Boolean get() = kind != KIND_TEXT

    companion object {
        const val AUTHOR_BOT = "BOT"
        const val AUTHOR_AGENT = "AGENT"
        const val KIND_TEXT = "TEXT"
    }
}

/** `GET /app/api/conversations/{id}/updates?since=`: only what changed, plus the next cursor. */
@Serializable
data class ThreadUpdates(
    val cursor: String,
    val conversation: Conversation? = null,
    val messages: List<ThreadMessage> = emptyList(),
)

@Serializable
data class WhatsAppTemplate(
    val id: String? = null,
    val name: String,
    val language: String,
    val category: String? = null,
    val status: String,
    val header: String? = null,
    val body: String = "",
    val footer: String? = null,
    val params: List<String> = emptyList(),
    /** False while Meta has not approved it, so the composer can grey it out. */
    val sendable: Boolean = false,
)

@Serializable
data class StartedConversation(
    val conversation: Conversation,
    val message: ThreadMessage? = null,
)
