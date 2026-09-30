package com.rfm.edubot.dashboard

import com.rfm.edubot.channel.OutboundClient
import com.rfm.edubot.channel.OutboundDeliveryException
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageAuthor
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.MessageStatus
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.whatsapp.CreatedTemplate
import com.rfm.edubot.whatsapp.MediaFile
import com.rfm.edubot.whatsapp.MediaTooLargeException
import com.rfm.edubot.whatsapp.SentMessage
import com.rfm.edubot.whatsapp.TemplateDraft
import com.rfm.edubot.whatsapp.WhatsAppApiException
import com.rfm.edubot.whatsapp.WhatsAppClient
import com.rfm.edubot.whatsapp.WhatsAppTemplate
import kotlinx.datetime.Instant
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** A dashboard inbox action that could not be done; [key] is translated by the dashboard (`inboxErr_<key>`). */
internal class InboxError(val key: String, val detail: String? = null) : RuntimeException(key)

/**
 * Messages a person sends from the dashboard inbox. Agent replies pause the AI for that conversation,
 * so the bot doesn't answer over a human; the inbox shows who paused it and offers to resume.
 */
internal class InboxService(
    private val mongo: MongoModule,
    private val whatsApp: (Tenant) -> WhatsAppClient?,
    private val outbound: (Tenant, Platform) -> OutboundClient,
    private val clock: () -> Instant = { SystemClock.now() },
) {
    data class Agent(val userId: String?, val name: String?)
    data class TemplateRequest(val name: String, val language: String, val params: Map<String, String>)

    private val log = LoggerFactory.getLogger("InboxService")
    private val templateCache = ConcurrentHashMap<ObjectId, Pair<Instant, List<WhatsAppTemplate>>>()

    fun windowExpiresAt(conversation: Conversation, lastInboundFallback: Instant? = null): Instant? {
        if (conversation.channel != Platform.WHATSAPP) return null
        return (conversation.lastInboundAt ?: lastInboundFallback)?.plus(WINDOW)
    }

    suspend fun sendText(tenant: Tenant, conversation: Conversation, rawText: String, agent: Agent): Message {
        val text = rawText.trim()
        if (text.isEmpty() || text.length > MAX_TEXT_LENGTH) throw InboxError("invalid_text")
        return when (conversation.channel) {
            Platform.WHATSAPP -> {
                requireWindowOpen(tenant, conversation)
                val client = whatsApp(tenant) ?: throw InboxError("no_channel")
                val sent = send { client.sendTextMessage(conversation.waId, text) }
                store(tenant, conversation, MessageContent.Text(text), agent, sent.id)
            }
            Platform.INSTAGRAM -> {
                send { outbound(tenant, Platform.INSTAGRAM).sendText(conversation.waId, text); SentMessage(null, null) }
                store(tenant, conversation, MessageContent.Text(text), agent, null)
            }
            Platform.WEB -> throw InboxError("web_read_only")
        }
    }

    suspend fun sendTemplate(tenant: Tenant, conversation: Conversation, request: TemplateRequest, agent: Agent): Message {
        if (conversation.channel != Platform.WHATSAPP) throw InboxError("templates_whatsapp_only")
        val client = whatsApp(tenant) ?: throw InboxError("no_channel")
        val template = findTemplate(tenant, client, request)
        val values = templateValues(template, request.params)
        val sent = send { client.sendTemplate(conversation.waId, template.name, template.language, template.bodyParameters(values)) }
        return store(tenant, conversation, MessageContent.Template(template.name, template.language, template.render(values)), agent, sent.id)
    }

    /** Opens a WhatsApp conversation with a template, which is the only thing Meta lets a business send first. */
    suspend fun startConversation(tenant: Tenant, rawPhone: String, request: TemplateRequest, agent: Agent): Pair<Conversation, Message> {
        val phone = normalizePhone(rawPhone) ?: throw InboxError("invalid_phone")
        val client = whatsApp(tenant) ?: throw InboxError("no_channel")
        val template = findTemplate(tenant, client, request)
        val values = templateValues(template, request.params)
        // Send before creating anything, so a rejected number doesn't leave an empty conversation behind.
        val sent = send { client.sendTemplate(phone, template.name, template.language, template.bodyParameters(values)) }
        val waId = sent.waId?.takeIf { it.isNotBlank() } ?: phone
        val users = UserRepository(mongo, tenant.id)
        val user = users.findByWaId(waId, Platform.WHATSAPP) ?: users.findOrCreate(waId, null, Platform.WHATSAPP)
        val conversation = ConversationRepository(mongo, tenant.id).findOrCreate(user.id, waId, Platform.WHATSAPP)
        val message = store(tenant, conversation, MessageContent.Template(template.name, template.language, template.render(values)), agent, sent.id)
        val updated = ConversationRepository(mongo, tenant.id).findById(conversation.id) ?: conversation
        return updated to message
    }

    /** Sends a failed text reply again. Failed templates are resent from the template picker instead. */
    suspend fun retry(tenant: Tenant, conversation: Conversation, messageId: ObjectId): Message {
        val messages = MessageRepository(mongo, tenant.id)
        val message = messages.findById(messageId)?.takeIf { it.conversationId == conversation.id } ?: throw InboxError("not_found")
        val text = (message.content as? MessageContent.Text)?.body
        if (message.status != MessageStatus.FAILED || message.author != MessageAuthor.AGENT || text == null) throw InboxError("not_retryable")
        if (conversation.channel != Platform.WHATSAPP) throw InboxError("not_retryable")
        requireWindowOpen(tenant, conversation)
        val client = whatsApp(tenant) ?: throw InboxError("no_channel")
        val sent = send { client.sendTextMessage(conversation.waId, text) }
        return messages.markResent(message.id, sent.id) ?: throw InboxError("not_retryable")
    }

    /** Templates that can be sent right now: approved by Meta. */
    suspend fun templates(tenant: Tenant, refresh: Boolean = false): List<WhatsAppTemplate> =
        allTemplates(tenant, refresh).filter { it.approved }

    /** Every template with its review status, for managing them. */
    suspend fun allTemplates(tenant: Tenant, refresh: Boolean = false): List<WhatsAppTemplate> {
        val client = whatsApp(tenant) ?: throw InboxError("no_channel")
        return loadTemplates(tenant, client, refresh)
    }

    suspend fun createTemplate(tenant: Tenant, draft: TemplateDraft): CreatedTemplate {
        draft.problem()?.let { throw InboxError(it) }
        val client = whatsApp(tenant) ?: throw InboxError("no_channel")
        val wabaId = wabaId(tenant)
        val created = try {
            client.createTemplate(wabaId, draft)
        } catch (e: WhatsAppApiException) {
            log.warn("Meta rejected a template: tenant={} name={} code={} trace={}", tenant.slug, draft.name, e.code, e.traceId)
            throw InboxError(if (e.key == "send_failed") "template_create_failed" else e.key, e.detail)
        } catch (e: Exception) {
            log.warn("Template submission failed: tenant={} error={}", tenant.slug, e.message)
            throw InboxError("template_create_failed")
        }
        templateCache.remove(tenant.id)
        log.info("Template submitted: tenant={} name={} language={} status={}", tenant.slug, draft.name, draft.language, created.status)
        return created
    }

    suspend fun deleteTemplate(tenant: Tenant, name: String, templateId: String?) {
        val client = whatsApp(tenant) ?: throw InboxError("no_channel")
        try {
            client.deleteTemplate(wabaId(tenant), name, templateId)
        } catch (e: WhatsAppApiException) {
            throw InboxError(if (e.key == "send_failed") "template_delete_failed" else e.key, e.detail)
        } catch (e: Exception) {
            throw InboxError("template_delete_failed")
        }
        templateCache.remove(tenant.id)
    }

    /** A customer's photo, voice note, video or document, fetched from Meta for the dashboard. */
    suspend fun media(tenant: Tenant, conversation: Conversation, messageId: ObjectId): Pair<MediaFile, String?> {
        val message = MessageRepository(mongo, tenant.id).findById(messageId)?.takeIf { it.conversationId == conversation.id }
            ?: throw InboxError("not_found")
        val (mediaId, fileName) = when (val content = message.content) {
            is MessageContent.Image -> content.mediaId to null
            is MessageContent.Audio -> content.mediaId to null
            is MessageContent.Video -> content.mediaId to null
            is MessageContent.Document -> content.mediaId to content.fileName
            else -> throw InboxError("not_found")
        }
        if (conversation.channel != Platform.WHATSAPP) throw InboxError("media_unavailable")
        val client = whatsApp(tenant) ?: throw InboxError("no_channel")
        val file = try {
            client.downloadMedia(mediaId, MAX_MEDIA_BYTES)
        } catch (e: MediaTooLargeException) {
            throw InboxError("media_too_large")
        } catch (e: WhatsAppApiException) {
            throw InboxError("media_unavailable", e.detail)
        } catch (e: Exception) {
            log.warn("Media download failed: tenant={} message={} error={}", tenant.slug, messageId, e.message)
            throw InboxError("media_unavailable")
        }
        return file to fileName
    }

    private fun wabaId(tenant: Tenant): String =
        tenant.binding(Platform.WHATSAPP)?.wabaId?.takeIf { it.isNotBlank() } ?: throw InboxError("no_waba")

    private suspend fun loadTemplates(tenant: Tenant, client: WhatsAppClient, refresh: Boolean): List<WhatsAppTemplate> {
        val wabaId = wabaId(tenant)
        val cached = templateCache[tenant.id]
        if (!refresh && cached != null && clock() - cached.first < TEMPLATE_CACHE) return cached.second
        val templates = try {
            client.templates(wabaId)
        } catch (e: WhatsAppApiException) {
            throw InboxError(e.key, e.detail)
        } catch (e: Exception) {
            log.warn("Failed to load WhatsApp templates: tenant={} error={}", tenant.slug, e.message)
            throw InboxError("templates_failed")
        }
        templateCache[tenant.id] = clock() to templates
        return templates
    }

    private suspend fun findTemplate(tenant: Tenant, client: WhatsAppClient, request: TemplateRequest): WhatsAppTemplate {
        val template = loadTemplates(tenant, client, refresh = false)
            .firstOrNull { it.name == request.name && it.language == request.language && it.approved }
            ?: throw InboxError("template_not_found")
        if (!template.sendable) throw InboxError("template_unsupported")
        return template
    }

    private fun templateValues(template: WhatsAppTemplate, params: Map<String, String>): Map<String, String> =
        template.params.associateWith { key ->
            val value = params[key]?.trim().orEmpty()
            if (value.isEmpty() || value.length > MAX_TEMPLATE_PARAM_LENGTH) throw InboxError("template_params")
            value
        }

    private suspend fun requireWindowOpen(tenant: Tenant, conversation: Conversation) {
        val fallback = if (conversation.lastInboundAt == null) MessageRepository(mongo, tenant.id).lastInboundAt(conversation.id) else null
        val expiresAt = windowExpiresAt(conversation, fallback)
        if (expiresAt == null || clock() >= expiresAt) throw InboxError("window_closed")
    }

    private suspend fun send(block: suspend () -> SentMessage): SentMessage = try {
        block()
    } catch (e: WhatsAppApiException) {
        log.warn("WhatsApp rejected an inbox message: code={} key={} trace={}", e.code, e.key, e.traceId)
        throw InboxError(e.key, e.detail)
    } catch (e: OutboundDeliveryException) {
        throw InboxError("send_failed", e.message)
    } catch (e: InboxError) {
        throw e
    } catch (e: Exception) {
        log.warn("Inbox message delivery failed: {}", e.message)
        throw InboxError("send_failed")
    }

    private suspend fun store(tenant: Tenant, conversation: Conversation, content: MessageContent, agent: Agent, waMessageId: String?): Message {
        val now = clock()
        val message = Message(
            tenantId = tenant.id,
            conversationId = conversation.id,
            channel = conversation.channel,
            waId = conversation.waId,
            role = UserRole.ASSISTANT,
            waMessageId = waMessageId,
            content = content,
            // Only messages with a WhatsApp id get status webhooks; the rest are done once the send call succeeds.
            status = if (waMessageId != null) MessageStatus.SENT else MessageStatus.DELIVERED,
            createdAt = now,
            author = MessageAuthor.AGENT,
            agentUserId = agent.userId,
            agentName = agent.name,
            statusAt = now,
        )
        MessageRepository(mongo, tenant.id).insert(message)
        val conversations = ConversationRepository(mongo, tenant.id)
        conversations.bumpActivity(conversation.id)
        conversations.markRead(conversation.id)
        if (conversation.autoReplyEnabled) conversations.setAutoReplyEnabled(conversation.id, false, agent.name)
        return message
    }

    companion object {
        val WINDOW = 24.hours
        /** WhatsApp's limit for a text message body. */
        const val MAX_TEXT_LENGTH = 4096
        const val MAX_TEMPLATE_PARAM_LENGTH = 1024
        /** WhatsApp caps videos and voice notes at 16 MB; bigger documents open in WhatsApp instead. */
        const val MAX_MEDIA_BYTES = 20L * 1024 * 1024
        private val TEMPLATE_CACHE = 60.seconds

        /** Digits with the country code, as WhatsApp expects; null unless 8 to 15 digits remain. */
        fun normalizePhone(raw: String): String? {
            val digits = raw.filter { it.isDigit() }.let { if (it.startsWith("00")) it.drop(2) else it }
            return digits.takeIf { it.length in 8..15 }
        }
    }
}
