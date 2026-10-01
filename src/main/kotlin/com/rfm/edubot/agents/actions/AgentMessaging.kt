package com.rfm.edubot.agents.actions

import com.rfm.edubot.agents.model.OutboundLogEntry
import com.rfm.edubot.agents.model.OutboundStatus
import com.rfm.edubot.agents.registry.ActionResult
import com.rfm.edubot.agents.runtime.Guardrails
import com.rfm.edubot.agents.runtime.RunContext
import com.rfm.edubot.agents.runtime.TemplateRenderer
import com.rfm.edubot.agents.store.OutboundLogRepository
import com.rfm.edubot.agents.templates.AgentCopy
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageAuthor
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.MessageStatus
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.dashboard.InboxService
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.integrations.email.EmailAttachment
import com.rfm.edubot.integrations.email.EmailSendResult
import com.rfm.edubot.integrations.email.OutgoingEmail
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.TenantLocales
import com.rfm.edubot.whatsapp.WhatsAppApiException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import kotlin.time.Duration.Companion.days

/** A quote or invoice PDF, generated fresh with the company's document template. */
internal data class AgentPdf(val bytes: ByteArray, val filename: String, val number: String, val kind: String)

internal object AgentDocuments {
    /** The PDF for [kind] (`quote`, `invoice` or `auto` for the run's own record); an invoice's quote works too. */
    suspend fun forRun(ctx: RunContext, kind: String?): AgentPdf? {
        val subject = ctx.run.subject ?: return null
        val subjectId = runCatching { ObjectId(subject.id) }.getOrNull() ?: return null
        val wanted = kind?.takeIf { it != "auto" && it != "none" } ?: subject.type
        val template = ctx.tenant.documentTemplate.withCompanyFallback(ctx.tenant.name)
        val clients = ClientRepository(ctx.services.mongo, ctx.tenant.id)
        return when (wanted) {
            SubjectTypes.INVOICE -> {
                val invoice = if (subject.type == SubjectTypes.INVOICE) InvoiceRepository(ctx.services.mongo, ctx.tenant.id).findById(subjectId) else null
                invoice?.let { found ->
                    val client = clients.findById(found.clientId) ?: return null
                    AgentPdf(ctx.services.pdf.generateInvoice(found, client, template), "${found.number}.pdf", found.number, SubjectTypes.INVOICE)
                }
            }
            SubjectTypes.QUOTE -> {
                val quoteId = when (subject.type) {
                    SubjectTypes.QUOTE -> subjectId
                    SubjectTypes.INVOICE -> InvoiceRepository(ctx.services.mongo, ctx.tenant.id).findById(subjectId)?.quoteId
                    else -> null
                } ?: return null
                val quote = QuoteRepository(ctx.services.mongo, ctx.tenant.id).findById(quoteId) ?: return null
                val client = clients.findById(quote.clientId) ?: return null
                AgentPdf(ctx.services.pdf.generateQuote(quote, client, template), "${quote.number}.pdf", quote.number, SubjectTypes.QUOTE)
            }
            else -> null
        }
    }
}

internal sealed class Delivery {
    data class Sent(val providerMessageId: String?) : Delivery()
    data object AlreadySent : Delivery()
    data object InDoubt : Delivery()
    data class Failed(val key: String, val retryable: Boolean) : Delivery()
}

/** Delivery plumbing shared by the message actions: the outbound log, caps, conversation history, fallbacks. */
internal object AgentMessaging {
    /** Sends once per run step (or [key]), recording the attempt before the provider call. */
    suspend fun deliver(ctx: RunContext, channel: String, recipient: String, key: String = ctx.idempotencyKey, send: suspend () -> String?): Delivery {
        val entry = OutboundLogEntry(
            tenantId = ctx.tenant.id,
            idempotencyKey = key,
            channel = channel,
            recipient = OutboundLogRepository.normalizeRecipient(channel, recipient),
            status = OutboundStatus.SENDING,
            at = ctx.now,
            runId = ctx.run.id,
            agentId = ctx.run.agentId,
        )
        when (ctx.services.outboundLog.acquire(entry)) {
            OutboundLogRepository.Acquire.ALREADY_SENT -> return Delivery.AlreadySent
            OutboundLogRepository.Acquire.IN_DOUBT -> return Delivery.InDoubt
            OutboundLogRepository.Acquire.STARTED -> Unit
        }
        return try {
            val id = send()
            ctx.services.outboundLog.markSent(key, id)
            Delivery.Sent(id)
        } catch (e: WhatsAppApiException) {
            ctx.services.outboundLog.markFailed(key, e.key)
            Delivery.Failed(e.key, retryable = e.key == "rate_limited")
        } catch (e: Exception) {
            ctx.services.outboundLog.markFailed(key, e.message ?: "send_failed")
            Delivery.Failed("send_failed", retryable = true)
        }
    }

    /** Frequency caps: the send waits a day once, then the step is skipped. */
    suspend fun capped(ctx: RunContext, channel: String, recipient: String): ActionResult? {
        if (!Guardrails.recipientCapReached(ctx.services.outboundLog, ctx.tenant.id, channel, recipient, ctx.settings.company, ctx.now)) return null
        val attempts = ctx.run.steps.firstOrNull { it.stepId == ctx.step.id }?.attempts ?: 0
        return if (attempts < 1) ActionResult.Wait(ctx.now + 1.days, note = "recipient_cap", retrySameStep = true) else ActionResult.Skipped("recipient_cap")
    }

    /** Writes an agent's message into the customer's conversation, so the reply bot sees it when they answer. */
    suspend fun storeOutbound(ctx: RunContext, platform: Platform, waId: String, content: MessageContent, providerMessageId: String?) {
        val mongo = ctx.services.mongo
        val users = UserRepository(mongo, ctx.tenant.id)
        val user = users.findByWaId(waId, platform) ?: users.findOrCreate(waId, null, platform)
        val conversations = ConversationRepository(mongo, ctx.tenant.id)
        val conversation = conversations.findOrCreate(user.id, waId, platform)
        MessageRepository(mongo, ctx.tenant.id).insert(
            Message(
                tenantId = ctx.tenant.id,
                conversationId = conversation.id,
                channel = platform,
                waId = waId,
                role = UserRole.ASSISTANT,
                waMessageId = providerMessageId,
                content = content,
                status = if (providerMessageId != null) MessageStatus.SENT else MessageStatus.DELIVERED,
                createdAt = ctx.now,
                author = MessageAuthor.AUTOMATION,
                agentName = ctx.run.agentName,
                statusAt = ctx.now,
                origin = "agent:${ctx.run.agentId.toHexString()}",
            ),
        )
        conversations.bumpActivity(conversation.id)
    }

    /** WhatsApp lets a business write freely only within 24 hours of the customer's last message. */
    suspend fun whatsAppWindowOpen(ctx: RunContext, waId: String): Boolean {
        val conversations = ConversationRepository(ctx.services.mongo, ctx.tenant.id)
        val conversation = conversations.findByWaId(waId, Platform.WHATSAPP) ?: return false
        val last = conversation.lastInboundAt ?: MessageRepository(ctx.services.mongo, ctx.tenant.id).lastInboundAt(conversation.id) ?: return false
        return ctx.now < last + InboxService.WINDOW
    }

    /** WhatsApp wants digits with the country code; a local 9-digit number gets the company's country code. */
    fun normalizePhone(raw: String?, locale: String): String? {
        val digits = raw?.filter { it.isDigit() }?.let { if (it.startsWith("00")) it.drop(2) else it } ?: return null
        val withCountry = if (digits.length == 9) {
            when (TenantLocales.normalize(locale)) {
                "pt-PT" -> "351$digits"
                "es" -> "34$digits"
                else -> digits
            }
        } else {
            digits
        }
        return InboxService.normalizePhone(withCountry)
    }

    fun variable(ctx: RunContext, path: String): String? =
        (TemplateRenderer.lookup(ctx.variables, path) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** What happens when a message can't go out on its channel: email, a task for a person, or nothing. */
    suspend fun fallback(ctx: RunContext, strategy: String?, channel: String, reason: String, text: String, pdf: AgentPdf? = null): ActionResult {
        val name = variable(ctx, "client.name") ?: variable(ctx, "conversation.contactName") ?: ctx.run.subjectLabel.orEmpty()
        when (strategy ?: "task") {
            "none" -> return ActionResult.Skipped(reason)
            "email" -> {
                val email = variable(ctx, "client.email")
                val sender = ctx.services.email
                if (email != null && sender != null && sender.isAvailable(ctx.tenant)) {
                    val company = variable(ctx, "company.name") ?: ctx.tenant.name
                    val outgoing = OutgoingEmail(
                        to = listOf(email),
                        subject = AgentCopy.t(ctx.locale, "fallback.email.subject", "company" to company),
                        text = text,
                        attachments = listOfNotNull(pdf?.let { EmailAttachment(it.filename, "application/pdf", it.bytes) }),
                        clientId = variable(ctx, "client.id")?.let { runCatching { ObjectId(it) }.getOrNull() },
                        record = ctx.run.subject,
                    )
                    return when (val sent = sender.send(ctx.tenant, outgoing, "${ctx.idempotencyKey}:email")) {
                        is EmailSendResult.Sent -> ActionResult.Done(buildJsonObject { put("channel", "email"); put("messageId", sent.messageId) }, note = "fallback_email")
                        is EmailSendResult.Failed -> taskFallback(ctx, name, channel, reason, text)
                    }
                }
                return taskFallback(ctx, name, channel, reason, text)
            }
            else -> return taskFallback(ctx, name, channel, reason, text)
        }
    }

    private suspend fun taskFallback(ctx: RunContext, name: String, channel: String, reason: String, text: String): ActionResult {
        val input = buildJsonObject {
            put("title", AgentCopy.t(ctx.locale, "fallback.task.title", "name" to name))
            put("detail", AgentCopy.t(ctx.locale, "fallback.task.detail", "channel" to channel, "reason" to AgentCopy.t(ctx.locale, "reason.$reason"), "text" to text))
            put("dueInDays", 0)
        }
        val result = CreateTaskAction.execute(input, ctx)
        return if (result is ActionResult.Done) ActionResult.Done(result.output, note = "fallback_task:$reason") else result
    }
}
