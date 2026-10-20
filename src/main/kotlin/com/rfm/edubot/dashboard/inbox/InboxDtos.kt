package com.rfm.edubot.dashboard.inbox

import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageAuthor
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.User
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.dashboard.InboxService
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.whatsapp.WhatsAppErrors
import com.rfm.edubot.whatsapp.WhatsAppTemplate
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable internal data class ContactStatusRequest(val status: String)
@Serializable internal data class ContactDto(val id: String, val waId: String, val channel: String, val displayName: String?, val status: String, val lastSeenAt: String)
@Serializable internal data class ConversationDto(
    val id: String,
    val waId: String,
    val channel: String,
    val displayName: String?,
    val state: String,
    val lastMessageAt: String,
    val messageCount: Int,
    val lastPreview: String? = null,
    val lastRole: String? = null,
    val waiting: Boolean = false,
    val autoReplyEnabled: Boolean = true,
    // No defaults below: the JSON config drops default values, and the inbox reads these every poll.
    val unreadCount: Int,
    val lastAuthor: String?,
    val lastKind: String?,
    val lastStatus: String?,
    val lastTracked: Boolean,
    val lastInboundAt: String?,
    /** WhatsApp only: free-form replies are allowed until then; templates after. */
    val windowExpiresAt: String?,
    val autoReplyPausedAt: String?,
    val autoReplyPausedBy: String?,
)
@Serializable internal data class AutoReplyRequest(val enabled: Boolean)
@Serializable internal data class OutboundMessageRequest(val text: String, val assetExternalId: String? = null)
@Serializable internal data class ThreadMessageDto(
    val id: String,
    val role: String,
    val text: String,
    val status: String,
    val createdAt: String,
    /** "customer", "ai", "agent" (a person in the inbox) or "automation" (an agent in the Agents module). */
    val author: String,
    /** "text", "template", "image", "audio", "document" or "video". */
    val kind: String,
    /** Has a WhatsApp id, so delivery ticks apply. */
    val tracked: Boolean,
    val agentName: String?,
    val agentUserId: String?,
    val templateName: String?,
    val fileName: String?,
    val statusAt: String?,
    val errorCode: Int?,
    val errorKey: String?,
    val errorText: String?,
)
@Serializable internal data class ThreadUpdatesDto(val cursor: String, val conversation: ConversationDto, val messages: List<ThreadMessageDto>)
@Serializable internal data class TemplateSendRequest(val name: String, val language: String, val params: Map<String, String> = emptyMap())
@Serializable internal data class StartConversationRequest(val phone: String, val name: String, val language: String, val params: Map<String, String> = emptyMap())
@Serializable internal data class StartedConversationDto(val conversation: ConversationDto, val message: ThreadMessageDto)
@Serializable internal data class InboxErrorDto(val error: String, val detail: String? = null)
@Serializable internal data class WhatsAppTemplateDto(
    val id: String?,
    val name: String,
    val language: String,
    val category: String?,
    val status: String,
    val rejectedReason: String?,
    val header: String?,
    val body: String,
    val footer: String?,
    val buttons: List<String>,
    val params: List<String>,
    val sendable: Boolean,
)
@Serializable internal data class TemplateDraftRequest(
    val name: String,
    val language: String,
    val category: String,
    val header: String? = null,
    val body: String,
    val examples: List<String> = emptyList(),
    val footer: String? = null,
    val quickReplies: List<String> = emptyList(),
)
@Serializable internal data class CreatedTemplateDto(val id: String?, val name: String, val language: String, val status: String, val category: String)

internal fun User.dto() = ContactDto(id.toHexString(), waId, channel.name, displayName, status.name, lastSeenAt.toString())

private fun Conversation.dto(
    displayName: String?,
    last: Message?,
    lastInbound: Instant?,
    windowExpiresAt: Instant?,
) = ConversationDto(
    id.toHexString(),
    waId,
    channel.name,
    displayName,
    state.name,
    lastMessageAt.toString(),
    messageCount,
    lastPreview = last?.previewText(),
    lastRole = last?.role?.name,
    waiting = needsTeamReply(last),
    autoReplyEnabled = autoReplyEnabled,
    unreadCount = unreadCount,
    lastAuthor = last?.authorLabel(),
    lastKind = last?.kind(),
    lastStatus = last?.status?.name,
    lastTracked = last?.waMessageId != null,
    lastInboundAt = lastInbound?.toString(),
    windowExpiresAt = windowExpiresAt?.toString(),
    autoReplyPausedAt = autoReplyPausedAt?.toString(),
    autoReplyPausedBy = autoReplyPausedBy,
)

private fun Message.previewText(): String {
    val text = displayText().trim()
    return if (text.length <= 120) text else text.take(117) + "…"
}

private fun Message.displayText(): String = when (val c = content) {
    is MessageContent.Text -> c.body
    is MessageContent.Template -> c.body
    is MessageContent.Image -> c.caption.orEmpty()
    is MessageContent.Audio -> c.transcription.orEmpty()
    is MessageContent.Video -> c.caption.orEmpty()
    is MessageContent.Document -> c.caption.orEmpty()
}

private fun Message.kind(): String = when (content) {
    is MessageContent.Text -> "text"
    is MessageContent.Template -> "template"
    is MessageContent.Image -> "image"
    is MessageContent.Audio -> "audio"
    is MessageContent.Document -> "document"
    is MessageContent.Video -> "video"
}

private fun Message.authorLabel(): String = when {
    role == UserRole.USER -> "customer"
    author == MessageAuthor.AGENT -> "agent"
    author == MessageAuthor.AUTOMATION -> "automation"
    else -> "ai"
}

internal fun Message.dto() = ThreadMessageDto(
    id = id.toHexString(),
    role = role.name,
    text = displayText(),
    status = status.name,
    createdAt = createdAt.toString(),
    author = authorLabel(),
    kind = kind(),
    tracked = waMessageId != null,
    agentName = agentName,
    agentUserId = agentUserId,
    templateName = (content as? MessageContent.Template)?.name,
    fileName = (content as? MessageContent.Document)?.fileName,
    statusAt = statusAt?.toString(),
    errorCode = errorCode,
    errorKey = errorCode?.let { WhatsAppErrors.key(it) },
    errorText = errorText,
)

internal fun WhatsAppTemplate.dto() =
    WhatsAppTemplateDto(id, name, language, category, status, rejectedReason, header, body, footer, buttons, params, sendable)

/** List rows for [conversations], with each WhatsApp conversation's 24-hour reply window. */
internal suspend fun inboxDtos(mongo: MongoModule, tenant: Tenant, inbox: InboxService, conversations: List<Conversation>): List<ConversationDto> {
    if (conversations.isEmpty()) return emptyList()
    val messages = MessageRepository(mongo, tenant.id)
    val displayNames = UserRepository(mongo, tenant.id).displayNamesByIds(conversations.map { it.userId })
    val lastMessages = messages.lastByConversationIds(conversations.map { it.id })
    val legacyInbound = messages.lastInboundByConversationIds(conversations.filter { it.lastInboundAt == null }.map { it.id })
    return conversations.map { convo ->
        val lastInbound = convo.lastInboundAt ?: legacyInbound[convo.id]
        convo.dto(displayNames[convo.userId], lastMessages[convo.id], lastInbound, inbox.windowExpiresAt(convo, lastInbound))
    }
}
