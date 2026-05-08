package com.rfm.edubot.conversation.model

import kotlinx.datetime.Instant
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

enum class UserRole {
    USER, ASSISTANT, SYSTEM
}

enum class MessageStatus {
    RECEIVED, PROCESSING, DELIVERED, FAILED
}

enum class UserStatus {
    ACTIVE, BLOCKED, RATE_LIMITED
}

enum class ConversationState {
    ACTIVE, ARCHIVED
}

data class User(
    @BsonId val id: ObjectId = ObjectId(),
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
    val userId: ObjectId,
    val waId: String,
    val state: ConversationState = ConversationState.ACTIVE,
    val summary: String? = null,
    val summaryUpdatedAt: Instant? = null,
    val lastMessageAt: Instant,
    val messageCount: Int = 0,
    val systemPromptVersion: String = "v1",
    val createdAt: Instant,
)

data class Message(
    @BsonId val id: ObjectId = ObjectId(),
    val conversationId: ObjectId,
    val waId: String,
    val role: UserRole,
    val waMessageId: String? = null,
    val content: MessageContent,
    val tokens: TokenUsage? = null,
    val model: String? = null,
    val costUsd: Double = 0.0,
    val status: MessageStatus = MessageStatus.RECEIVED,
    val createdAt: Instant,
)

sealed class MessageContent {
    data class Text(val body: String) : MessageContent()
    data class Image(val mediaId: String, val caption: String? = null) : MessageContent()
    data class Audio(val mediaId: String, val transcription: String? = null) : MessageContent()
    data class Document(val mediaId: String) : MessageContent()
    data class Video(val mediaId: String) : MessageContent()
}

data class TokenUsage(
    val prompt: Int = 0,
    val completion: Int = 0,
)
