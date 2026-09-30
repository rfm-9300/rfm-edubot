package com.rfm.edubot.agents.actions

import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.registry.ActionCategory
import com.rfm.edubot.agents.registry.ActionResult
import com.rfm.edubot.agents.registry.AgentAction
import com.rfm.edubot.agents.registry.IntegrationKind
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SideEffect
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.agents.runtime.RunContext
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.dashboard.InboxService
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.tenant.model.Platform
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId

/**
 * A WhatsApp message to the record's client (or the chat's contact, or a number). Inside the 24-hour
 * window it's free text, optionally with the quote or invoice PDF; outside it an approved template
 * goes when one is mapped, else the step falls back to email or a task for a person.
 */
object WhatsAppSendAction : AgentAction {
    override val key = "whatsapp.send"
    override val category = ActionCategory.MESSAGE
    override val sideEffect = SideEffect.EXTERNAL_MESSAGE
    override val requiredIntegration = IntegrationKind.WHATSAPP
    override val recipientFields = setOf("to", "phone")
    override val toolDescription = "Send a WhatsApp message to the client of the record the agent works on. Outside WhatsApp's 24-hour window it falls back to email or a task."
    override val inputSchema = Schema.obj(
        "to" to Schema.string(enum = listOf("client", "contact", "phone"), default = "client"),
        "phone" to Schema.string(widget = "template", maxLength = 40),
        "text" to Schema.string(widget = "template", maxLength = 4096, description = "the message"),
        "attachPdf" to Schema.string(enum = listOf("none", "auto", "quote", "invoice"), default = "none"),
        "template" to Schema.string(widget = "wa-template", maxLength = 512),
        "templateLanguage" to Schema.string(maxLength = 10),
        "templateParams" to Schema.array(Schema.string(widget = "template", maxLength = 1024), maxItems = 10),
        "fallback" to Schema.string(enum = listOf("task", "email", "none"), default = "task"),
        required = listOf("text"),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext): ActionPreview {
        val phone = recipient(input, ctx)
        val open = phone != null && AgentMessaging.whatsAppWindowOpen(ctx, phone)
        val template = WhatsAppTemplateSending.mapping(input)
        return ActionPreview(
            kind = "message",
            channel = "whatsapp",
            recipients = listOfNotNull(phone),
            body = if (!open && template != null) WhatsAppTemplateSending.previewText(ctx, template) ?: input.string("text") else input.string("text"),
            attachments = listOfNotNull(input.string("attachPdf")?.takeIf { it != "none" }?.let { AgentDocuments.forRun(ctx, it)?.filename }),
            editable = if (!open && template != null) emptyList() else listOf("text"),
            warnings = buildList {
                if (phone == null) add("no_phone")
                else if (!open) add(if (template != null) "template_outside_window" else "window_closed")
            },
        )
    }

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val text = input.string("text") ?: return ActionResult.Skipped("empty_message")
        val fallback = input.string("fallback")
        val pdf = input.string("attachPdf")?.takeIf { it != "none" }?.let { AgentDocuments.forRun(ctx, it) }
        val phone = recipient(input, ctx) ?: return AgentMessaging.fallback(ctx, fallback, "WhatsApp", "no_phone", text, pdf)
        val client = ctx.services.whatsApp(ctx.tenant) ?: return AgentMessaging.fallback(ctx, fallback, "WhatsApp", "no_channel", text, pdf)
        AgentMessaging.capped(ctx, "whatsapp", phone)?.let { return it }

        if (!AgentMessaging.whatsAppWindowOpen(ctx, phone)) {
            val template = WhatsAppTemplateSending.mapping(input)
                ?: return AgentMessaging.fallback(ctx, fallback, "WhatsApp", "window_closed", text, pdf)
            return WhatsAppTemplateSending.send(ctx, client, phone, template)
        }
        val sent = AgentMessaging.deliver(ctx, "whatsapp", phone) { client.sendTextMessage(phone, text).id }
        when (sent) {
            is Delivery.Sent -> AgentMessaging.storeOutbound(ctx, Platform.WHATSAPP, phone, MessageContent.Text(text), sent.providerMessageId)
            Delivery.AlreadySent -> return ActionResult.Done(buildJsonObject { put("channel", "whatsapp") }, note = "already_sent")
            Delivery.InDoubt -> return ActionResult.Failed("send_in_doubt")
            is Delivery.Failed -> return if (sent.key == "window_closed") {
                AgentMessaging.fallback(ctx, fallback, "WhatsApp", "window_closed", text, pdf)
            } else {
                ActionResult.Failed(sent.key, retryable = sent.retryable)
            }
        }
        if (pdf != null) {
            val attached = AgentMessaging.deliver(ctx, "whatsapp", phone, key = "${ctx.idempotencyKey}:pdf") {
                client.sendDocument(phone, pdf.bytes, pdf.filename, "application/pdf")
                null
            }
            if (attached is Delivery.Failed) return ActionResult.Done(output(phone, sent), note = "pdf_failed:${attached.key}")
        }
        return ActionResult.Done(output(phone, sent))
    }

    private fun output(phone: String, sent: Delivery) = buildJsonObject {
        put("channel", "whatsapp")
        put("to", phone)
        (sent as? Delivery.Sent)?.providerMessageId?.let { put("messageId", it) }
    }

    /** The record's client or contact is on file; a typed number must be a client's or someone who wrote to the company. */
    override suspend fun reachesOnlyKnownContacts(input: JsonObject, ctx: RunContext): Boolean {
        if ((input.string("to") ?: "client") != "phone") return true
        val phone = input.string("phone") ?: return true
        if (ClientRepository(ctx.services.mongo, ctx.tenant.id).findByPhone(phone) != null) return true
        val waId = AgentMessaging.normalizePhone(phone, ctx.tenant.locale) ?: return false
        return UserRepository(ctx.services.mongo, ctx.tenant.id).findByWaId(waId, Platform.WHATSAPP) != null
    }

    /** The WhatsApp number to write to, with its country code. */
    internal suspend fun recipient(input: JsonObject, ctx: RunContext): String? {
        val subject = ctx.run.subject
        val chatWaId = if (subject?.type == SubjectTypes.CONVERSATION) {
            runCatching { ObjectId(subject.id) }.getOrNull()
                ?.let { ConversationRepository(ctx.services.mongo, ctx.tenant.id).findById(it) }
                ?.takeIf { it.channel == Platform.WHATSAPP }?.waId
        } else {
            null
        }
        val raw = when (input.string("to") ?: "client") {
            "phone" -> input.string("phone")
            "contact" -> chatWaId ?: AgentMessaging.variable(ctx, "contact.waId")
            else -> chatWaId ?: AgentMessaging.variable(ctx, "client.phone") ?: AgentMessaging.variable(ctx, "booking.contactPhone")
        }
        return AgentMessaging.normalizePhone(raw, ctx.tenant.locale)
    }
}

/** A public reply to an Instagram comment, or a DM in an Instagram conversation inside its 24-hour window. */
object InstagramReplyAction : AgentAction {
    override val key = "instagram.reply"
    override val category = ActionCategory.MESSAGE
    override val sideEffect = SideEffect.EXTERNAL_MESSAGE
    override val requiredIntegration = IntegrationKind.INSTAGRAM
    override val subjectTypes = setOf(SubjectTypes.INSTAGRAM_COMMENT, SubjectTypes.CONVERSATION)
    override val toolDescription = "Reply on Instagram: publicly to the comment the agent works on, or in the Instagram chat within 24 hours of the customer's last message."
    override val inputSchema = Schema.obj(
        "text" to Schema.string(widget = "template", maxLength = 1000, description = "the reply"),
        required = listOf("text"),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(
        kind = "message",
        channel = "instagram",
        recipients = listOfNotNull(AgentMessaging.variable(ctx, "comment.fromUsername")?.let { "@$it" } ?: AgentMessaging.variable(ctx, "conversation.contactName")),
        body = input.string("text"),
        editable = listOf("text"),
    )

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val text = input.string("text") ?: return ActionResult.Skipped("empty_message")
        val subject = ctx.run.subject ?: return ActionResult.Skipped("no_record")
        return when (subject.type) {
            SubjectTypes.INSTAGRAM_COMMENT -> {
                val social = ctx.services.instagramSocial ?: return ActionResult.Failed("no_channel")
                var failure: String? = null
                val sent = AgentMessaging.deliver(ctx, "instagram", subject.id) {
                    val (status, _) = social.reply(ctx.tenant, subject.id, text)
                    if (status != HttpStatusCode.OK && status != HttpStatusCode.Created) {
                        failure = status.value.toString()
                        error("instagram_reply_failed")
                    }
                    null
                }
                when (sent) {
                    is Delivery.Sent, Delivery.AlreadySent -> ActionResult.Done(buildJsonObject { put("channel", "instagram"); put("commentId", subject.id) })
                    Delivery.InDoubt -> ActionResult.Failed("send_in_doubt")
                    is Delivery.Failed -> ActionResult.Failed("instagram_reply_failed:${failure ?: sent.key}")
                }
            }
            SubjectTypes.CONVERSATION -> {
                val id = runCatching { ObjectId(subject.id) }.getOrNull() ?: return ActionResult.Skipped("no_record")
                val conversation = ConversationRepository(ctx.services.mongo, ctx.tenant.id).findById(id)
                    ?.takeIf { it.channel == Platform.INSTAGRAM } ?: return ActionResult.Skipped("not_instagram")
                val last = conversation.lastInboundAt ?: MessageRepository(ctx.services.mongo, ctx.tenant.id).lastInboundAt(conversation.id)
                if (last == null || ctx.now >= last + InboxService.WINDOW) return ActionResult.Skipped("window_closed")
                val outbound = ctx.services.outbound(ctx.tenant, Platform.INSTAGRAM) ?: return ActionResult.Failed("no_channel")
                when (val sent = AgentMessaging.deliver(ctx, "instagram", conversation.waId) { outbound.sendText(conversation.waId, text); null }) {
                    is Delivery.Sent -> {
                        AgentMessaging.storeOutbound(ctx, Platform.INSTAGRAM, conversation.waId, MessageContent.Text(text), null)
                        ActionResult.Done(buildJsonObject { put("channel", "instagram"); put("to", conversation.waId) })
                    }
                    Delivery.AlreadySent -> ActionResult.Done(buildJsonObject { put("channel", "instagram") }, note = "already_sent")
                    Delivery.InDoubt -> ActionResult.Failed("send_in_doubt")
                    is Delivery.Failed -> ActionResult.Failed(sent.key, sent.retryable)
                }
            }
            else -> ActionResult.Skipped("not_instagram")
        }
    }
}
