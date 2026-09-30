package com.rfm.edubot.conversation.model

import com.rfm.edubot.tenant.model.Platform
import kotlinx.datetime.Instant
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

enum class UserRole {
    USER, ASSISTANT, SYSTEM
}

/**
 * Inbound messages stay RECEIVED. Outbound messages whose WhatsApp id we keep move
 * SENT → DELIVERED → READ, or to FAILED, as Meta's status webhooks arrive. Outbound rows without
 * a WhatsApp id (AI replies, Instagram) are DELIVERED, or FAILED when the send call itself fails.
 */
enum class MessageStatus {
    RECEIVED, PROCESSING, SENT, DELIVERED, READ, FAILED
}

/** Who wrote an outbound message. Null on customer messages and on rows stored before it existed. */
enum class MessageAuthor { AI, AGENT }

enum class UserStatus {
    ACTIVE, BLOCKED, RATE_LIMITED
}

enum class ConversationState {
    ACTIVE, ARCHIVED
}

data class User(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val channel: Platform = Platform.WHATSAPP,
    val waId: String,
    val displayName: String? = null,
    val locale: String = "pt_BR",
    val status: UserStatus = UserStatus.ACTIVE,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val metadata: Map<String, String> = emptyMap(),
)

data class Conversation(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val userId: ObjectId,
    val channel: Platform = Platform.WHATSAPP,
    val waId: String,
    val state: ConversationState = ConversationState.ACTIVE,
    val summary: String? = null,
    val summaryUpdatedAt: Instant? = null,
    val lastMessageAt: Instant,
    val messageCount: Int = 0,
    val systemPromptVersion: String = "v1",
    val autoReplyEnabled: Boolean = true,
    val createdAt: Instant,
    /** Last customer message; WhatsApp only allows free-form replies for 24 hours after it. */
    val lastInboundAt: Instant? = null,
    val unreadCount: Int = 0,
    val autoReplyPausedAt: Instant? = null,
    /** Dashboard user whose reply or click paused the AI. */
    val autoReplyPausedBy: String? = null,
)

data class Message(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val conversationId: ObjectId,
    val channel: Platform = Platform.WHATSAPP,
    val waId: String,
    val role: UserRole,
    val waMessageId: String? = null,
    val content: MessageContent,
    val tokens: TokenUsage? = null,
    val model: String? = null,
    val costUsd: Double = 0.0,
    val status: MessageStatus = MessageStatus.RECEIVED,
    val createdAt: Instant,
    val author: MessageAuthor? = null,
    val agentUserId: String? = null,
    val agentName: String? = null,
    val statusAt: Instant? = null,
    /** Meta's error code and text when [status] is FAILED. */
    val errorCode: Int? = null,
    val errorText: String? = null,
)

sealed class MessageContent {
    data class Text(val body: String) : MessageContent()
    /** An approved WhatsApp template; [body] is the text as sent, with its variables filled in. */
    data class Template(val name: String, val language: String, val body: String) : MessageContent()
    data class Image(val mediaId: String, val caption: String? = null) : MessageContent()
    data class Audio(val mediaId: String, val transcription: String? = null) : MessageContent()
    data class Document(val mediaId: String, val fileName: String? = null, val caption: String? = null) : MessageContent()
    data class Video(val mediaId: String, val caption: String? = null) : MessageContent()
}

data class TokenUsage(
    val prompt: Int = 0,
    val completion: Int = 0,
)
