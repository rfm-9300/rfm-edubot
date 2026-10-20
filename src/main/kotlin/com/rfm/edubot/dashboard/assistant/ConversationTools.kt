package com.rfm.edubot.dashboard.assistant

import com.rfm.edubot.agents.ai.UntrustedContent
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageAuthor
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.InboxError
import com.rfm.edubot.dashboard.InboxService
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Platform
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Customer chats: `list_conversations`, `get_conversation` and `reply_to_conversation`, which needs the inbox's
 * sender. What customers wrote reaches the model wrapped as [UntrustedContent].
 */
internal class ConversationTools(private val s: AssistantToolSession) : AssistantToolGroup {
    override val spec: ToolGroupSpec get() = Companion

    override suspend fun execute(name: String, args: JsonObject): JsonObject = when (name) {
        LIST_CONVERSATIONS -> listConversations(args)
        GET_CONVERSATION -> getConversation(args)
        REPLY -> reply(args)
        else -> throw ToolProblem("unknown_tool", "Unknown tool.")
    }

    override suspend fun describe(name: String, args: JsonObject): JsonObject? = when (name) {
        REPLY -> {
            val conversation = s.resolveConversation(args.text("conversation_id") ?: return null)
            buildJsonObject {
                put("contact", contactName(conversation))
                put("channel", conversation.channel.name)
                args.text("text")?.let { put("text", it) }
                if (conversation.channel == Platform.WHATSAPP) put("window_open", windowOpen(conversation))
            }
        }
        else -> null
    }

    override suspend fun check(name: String, args: JsonObject) {
        if (name == REPLY) replyTarget(args)
    }

    private suspend fun listConversations(args: JsonObject): JsonObject {
        val term = args.text("query")
        val users = UserRepository(s.mongo, s.tenant.id)
        val matches = term?.let { q -> users.list(q).map { it.id } }.orEmpty()
        val channel = args.text("channel")?.uppercase()?.let { c -> Platform.entries.firstOrNull { it.name == c } ?: throw ToolProblem("invalid_channel", "Use WHATSAPP, INSTAGRAM or WEB.") }
        val waitingOnly = args.bool("waiting_only") == true
        val found = ConversationRepository(s.mongo, s.tenant.id).list(term, limit = 100, userIds = matches)
            .filter { channel == null || it.channel == channel }
        val messages = MessageRepository(s.mongo, s.tenant.id)
        val last = messages.lastByConversationIds(found.map { it.id })
        val names = users.displayNamesByIds(found.map { it.userId })
        val rows = found.filter { !waitingOnly || last[it.id]?.role == UserRole.USER }
        val shown = rows.take(s.limit(args))
        val whatsApp = shown.filter { it.channel == Platform.WHATSAPP }
        val legacyInbound = messages.lastInboundByConversationIds(whatsApp.filter { it.lastInboundAt == null }.map { it.id })
        val windows = whatsApp.associate { it.id to windowOpen(it, it.lastInboundAt ?: legacyInbound[it.id]) }
        return buildJsonObject {
            putJsonArray("conversations") {
                shown.forEach { c ->
                    addJsonObject {
                        put("id", c.id.toHexString())
                        put("contact", UntrustedContent.wrap("conversation.contactName", names[c.userId]?.takeIf { it.isNotBlank() } ?: c.waId))
                        put("channel", c.channel.name)
                        if (c.channel == Platform.WHATSAPP) put("phone", "+${c.waId}")
                        put("waiting", last[c.id]?.role == UserRole.USER)
                        put("unread", c.unreadCount)
                        last[c.id]?.let { put("last_message", UntrustedContent.wrap("conversation.lastMessage", preview(it).take(160))) }
                        put("last_at", s.localTime(c.lastMessageAt))
                        if (!c.autoReplyEnabled) put("bot_paused", true)
                        windows[c.id]?.let { put("reply_window_open", it) }
                    }
                }
            }
            put("count", rows.size)
        }
    }

    private suspend fun getConversation(args: JsonObject): JsonObject {
        val conversation = s.resolveConversation(args.text("conversation_id") ?: throw missing("conversation_id"))
        val messages = MessageRepository(s.mongo, s.tenant.id).threadByConversation(conversation.id, limit = (args.int("limit") ?: 20).coerceIn(1, 50))
        return buildJsonObject {
            put("id", conversation.id.toHexString())
            put("contact", UntrustedContent.wrap("conversation.contactName", contactName(conversation)))
            put("channel", conversation.channel.name)
            if (!conversation.autoReplyEnabled) put("bot_paused", true)
            if (conversation.channel == Platform.WHATSAPP) put("reply_window_open", windowOpen(conversation))
            putJsonArray("messages") {
                messages.forEach { m ->
                    addJsonObject {
                        val from = when {
                            m.role == UserRole.USER -> "customer"
                            m.author == MessageAuthor.AGENT -> "team"
                            m.author == MessageAuthor.AUTOMATION || m.origin?.startsWith("agent:") == true -> "automation"
                            else -> "bot"
                        }
                        put("from", from)
                        put("at", s.localTime(m.createdAt))
                        val text = preview(m)
                        put("text", if (from == "customer") UntrustedContent.wrap("event.text", text) else text)
                    }
                }
            }
        }
    }

    private suspend fun replyTarget(args: JsonObject): Pair<Conversation, String> {
        val conversation = s.resolveConversation(args.text("conversation_id") ?: throw missing("conversation_id"))
        val text = args.text("text") ?: throw missing("text")
        if (text.length > InboxService.MAX_TEXT_LENGTH) throw ToolProblem("invalid_text", INBOX_HINTS.getValue("invalid_text"))
        if (conversation.channel == Platform.WEB) throw ToolProblem("web_read_only", INBOX_HINTS.getValue("web_read_only"))
        if (conversation.channel == Platform.WHATSAPP && !windowOpen(conversation)) throw ToolProblem("window_closed", INBOX_HINTS.getValue("window_closed"))
        return conversation to text
    }

    private suspend fun reply(args: JsonObject): JsonObject {
        val service = s.inbox ?: throw ToolProblem("not_available", "Replies can't be sent here.")
        val (conversation, text) = replyTarget(args)
        val message = try {
            service.sendText(s.tenant, conversation, text, InboxService.Agent(s.ctx.user?.id?.toHexString(), s.ctx.user?.email))
        } catch (e: InboxError) {
            throw ToolProblem(e.key, INBOX_HINTS[e.key] ?: "The message couldn't be sent.")
        }
        return buildJsonObject {
            put("sent", true)
            put("type", "conversation")
            put("id", conversation.id.toHexString())
            put("message_id", message.id.toHexString())
            put("contact", contactName(conversation))
        }
    }

    private suspend fun contactName(conversation: Conversation): String =
        UserRepository(s.mongo, s.tenant.id).findById(conversation.userId)?.displayName?.takeIf { it.isNotBlank() } ?: conversation.waId

    private suspend fun windowOpen(conversation: Conversation): Boolean =
        windowOpen(conversation, conversation.lastInboundAt ?: MessageRepository(s.mongo, s.tenant.id).lastInboundAt(conversation.id))

    /** Whether WhatsApp's 24-hour window, which [lastInbound] opened, still allows a free-form reply. */
    private fun windowOpen(conversation: Conversation, lastInbound: Instant?): Boolean =
        s.inbox?.windowExpiresAt(conversation, lastInbound)?.let { SystemClock.now() < it } ?: (lastInbound != null && SystemClock.now() < lastInbound.plus(InboxService.WINDOW))

    private fun preview(message: Message): String = when (val content = message.content) {
        is MessageContent.Text -> content.body
        is MessageContent.Template -> content.body
        is MessageContent.Image -> "[photo] ${content.caption.orEmpty()}".trim()
        is MessageContent.Audio -> "[voice message] ${content.transcription.orEmpty()}".trim()
        is MessageContent.Video -> "[video] ${content.caption.orEmpty()}".trim()
        is MessageContent.Document -> "[document ${content.fileName.orEmpty()}] ${content.caption.orEmpty()}".trim()
    }

    companion object : ToolGroupSpec {
        const val LIST_CONVERSATIONS = "list_conversations"
        const val GET_CONVERSATION = "get_conversation"
        const val REPLY = "reply_to_conversation"

        private val INBOX_HINTS = mapOf(
            "window_closed" to "WhatsApp's 24-hour window is closed: only an approved template can be sent now, from the Inbox.",
            "web_read_only" to "Website chats can't be answered from the dashboard.",
            "no_channel" to "That channel isn't connected anymore.",
            "invalid_text" to "The message is empty or too long (4096 characters at most).",
        )

        override val definitions = listOf(
            tool(
                LIST_CONVERSATIONS,
                "Customer chats (WhatsApp, Instagram, website), most recent first: who, channel, whether they wait for a reply, the last message " +
                    "and whether WhatsApp's 24-hour reply window is open.",
                buildJsonObject {
                    put("query", prop("string", "Part of the contact's name or phone"))
                    put("waiting_only", prop("boolean", "Only chats whose last message is from the customer"))
                    put("channel", prop("string", null, listOf("WHATSAPP", "INSTAGRAM", "WEB")))
                    put("limit", prop("integer"))
                },
            ),
            tool(
                GET_CONVERSATION,
                "The latest messages of one customer chat, oldest first, to summarise it or prepare a reply.",
                buildJsonObject { put("conversation_id", prop("string", "From list_conversations")); put("limit", prop("integer", "Messages, 20 by default")) },
                listOf("conversation_id"),
            ),
            tool(
                REPLY,
                "Send a text reply to a customer chat, from the person you're helping. Write the exact message the customer will read.",
                buildJsonObject { put("conversation_id", prop("string", "From list_conversations")); put("text", prop("string")) },
                listOf("conversation_id", "text"),
            ),
        )
        override val moduleOf = mapOf(
            LIST_CONVERSATIONS to DashboardModules.CONVERSATIONS,
            GET_CONVERSATION to DashboardModules.CONVERSATIONS,
            REPLY to DashboardModules.CONVERSATIONS,
        )
        override val readOnly = setOf(LIST_CONVERSATIONS, GET_CONVERSATION)
    }
}
