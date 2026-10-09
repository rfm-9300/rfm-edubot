package com.rfm.edubot.agents.actions

import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.model.OutboundStatus
import com.rfm.edubot.agents.registry.ActionCategory
import com.rfm.edubot.agents.registry.ActionResult
import com.rfm.edubot.agents.registry.AgentAction
import com.rfm.edubot.agents.registry.IntegrationKind
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SideEffect
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.agents.runtime.RunContext
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.integrations.email.EmailAddresses
import com.rfm.edubot.integrations.email.EmailAttachment
import com.rfm.edubot.integrations.email.EmailDirection
import com.rfm.edubot.integrations.email.EmailMessage
import com.rfm.edubot.integrations.email.EmailMessageRepository
import com.rfm.edubot.integrations.email.EmailSendResult
import com.rfm.edubot.integrations.email.EmailService
import com.rfm.edubot.integrations.email.OutgoingEmail
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import kotlin.time.Duration.Companion.days

/**
 * An email from the company's connected Gmail account to the record's client, a team member or an
 * address, with copies, a reply-to and the quote or invoice PDF. The email service claims the send
 * under the step's key, so a retried or resumed step never emails twice.
 */
object EmailSendAction : AgentAction {
    override val key = "email.send"
    override val category = ActionCategory.MESSAGE
    override val sideEffect = SideEffect.EXTERNAL_MESSAGE
    override val requiredIntegration = IntegrationKind.GMAIL
    override val recipientFields = setOf("to", "email", "cc", "bcc")
    override val toolDescription = "Send an email from the company's Gmail account to the client of the record the agent works on (or a team member, or an address), optionally with the quote or invoice PDF."
    override val inputSchema = Schema.obj(
        "to" to Schema.string(enum = listOf("client", "user", "email"), default = "client"),
        "userId" to Schema.string(widget = "user"),
        "email" to Schema.string(widget = "template", maxLength = 254),
        "subject" to Schema.string(widget = "template", maxLength = 300, description = "the subject line"),
        "text" to Schema.string(widget = "template", maxLength = 20_000, description = "the email, as plain text"),
        "attachPdf" to Schema.string(enum = listOf("none", "auto", "quote", "invoice"), default = "none"),
        "cc" to Schema.string(widget = "template", maxLength = 1000, description = "addresses separated by commas"),
        "bcc" to Schema.string(widget = "template", maxLength = 1000, description = "addresses separated by commas"),
        "replyTo" to Schema.string(widget = "template", maxLength = 254),
        required = listOf("subject", "text"),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext): ActionPreview {
        val to = recipient(input, ctx)
        val cc = addresses(input.string("cc"))
        val bcc = addresses(input.string("bcc"))
        val pdf = input.string("attachPdf")?.takeIf { it != "none" }?.let { AgentDocuments.forRun(ctx, it) }
        return ActionPreview(
            kind = "email",
            channel = "email",
            recipients = listOfNotNull(to),
            subject = input.string("subject"),
            body = input.string("text"),
            attachments = listOfNotNull(pdf?.filename),
            fields = buildMap {
                if (cc.isNotEmpty()) put("cc", cc.joinToString(", "))
                if (bcc.isNotEmpty()) put("bcc", bcc.joinToString(", "))
                input.string("replyTo")?.let { put("replyTo", it) }
            },
            editable = listOf("subject", "text"),
            warnings = buildList {
                if (to == null) add("no_email")
                if (ctx.services.email?.isAvailable(ctx.tenant) != true) add("no_email_account")
                if (pdf != null && to != null && documentJustSent(ctx, to)) add(ALREADY_EMAILED)
            },
        )
    }

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val subject = input.string("subject") ?: return ActionResult.Skipped("empty_subject")
        val text = input.string("text") ?: return ActionResult.Skipped("empty_message")
        val sender = ctx.services.email ?: return ActionResult.Failed(EmailService.NO_ACCOUNT)
        val to = recipient(input, ctx) ?: return ActionResult.Skipped("no_email")
        AgentMessaging.capped(ctx, EmailService.CHANNEL, to)?.let { return it }
        val pdf = input.string("attachPdf")?.takeIf { it != "none" }?.let { AgentDocuments.forRun(ctx, it) }
        if (pdf != null && documentJustSent(ctx, to)) return ActionResult.Skipped(ALREADY_EMAILED)
        val email = OutgoingEmail(
            to = listOf(to),
            subject = subject,
            text = text,
            cc = addresses(input.string("cc")),
            bcc = addresses(input.string("bcc")),
            replyTo = input.string("replyTo"),
            attachments = listOfNotNull(pdf?.let { EmailAttachment(it.filename, "application/pdf", it.bytes) }),
            clientId = ctx.run.clientId ?: AgentMessaging.variable(ctx, "client.id")?.let { runCatching { ObjectId(it) }.getOrNull() },
            record = ctx.run.subject,
        )
        return when (val sent = sender.send(ctx.tenant, email, ctx.idempotencyKey)) {
            is EmailSendResult.Sent -> ActionResult.Done(
                buildJsonObject {
                    put("channel", EmailService.CHANNEL)
                    put("to", to)
                    if (sent.messageId.isNotEmpty()) put("messageId", sent.messageId)
                    sent.threadId?.let { put("threadId", it) }
                },
                note = if (sent.alreadySent) "already_sent" else null,
            )
            is EmailSendResult.Failed -> if (sent.key == EmailService.DAILY_LIMIT) nextDay(ctx) else ActionResult.Failed(sent.key, retryable = sent.retryable)
        }
    }

    /**
     * Someone else (a person with "Send by email", another agent) emailed this record's document to [to] in the
     * last day. This step's own send doesn't count, so a retried step still reports it as sent.
     */
    private suspend fun documentJustSent(ctx: RunContext, to: String): Boolean {
        val record = ctx.run.subject ?: return false
        if (ctx.services.outboundLog.find(ctx.idempotencyKey)?.status == OutboundStatus.SENT) return false
        return EmailMessageRepository(ctx.services.mongo).documentSent(ctx.tenant.id, record, to, ctx.now - 1.days)
    }

    /** The account's allowance comes back at the company's midnight: the step waits for it once, then gives up. */
    internal fun nextDay(ctx: RunContext): ActionResult {
        val attempts = ctx.run.steps.firstOrNull { it.stepId == ctx.step.id }?.attempts ?: 0
        if (attempts >= 1) return ActionResult.Failed(EmailService.DAILY_LIMIT)
        val tomorrow = ctx.now.toLocalDateTime(ctx.zone).date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(ctx.zone)
        return ActionResult.Wait(tomorrow, note = EmailService.DAILY_LIMIT, retrySameStep = true)
    }

    /** The address to write to: the record's client, a team member of the company, or the one given. */
    internal suspend fun recipient(input: JsonObject, ctx: RunContext): String? = when (input.string("to") ?: "client") {
        "user" -> input.string("userId")
            ?.let { id -> runCatching { ObjectId(id) }.getOrNull() }
            ?.let { ctx.services.dashboardUsers.findById(it) }
            ?.takeIf { it.tenantId == ctx.tenant.primaryTenantId }
            ?.email
        "email" -> input.string("email")
        else -> AgentMessaging.variable(ctx, "client.email")
    }?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The record's client and team members are on file; typed addresses, copies and the reply-to must be
     * a client's, a team member's or the company's own.
     */
    override suspend fun reachesOnlyKnownContacts(input: JsonObject, ctx: RunContext): Boolean {
        val typed = buildList {
            if ((input.string("to") ?: "client") == "email") input.string("email")?.let(::add)
            addAll(addresses(input.string("cc")))
            addAll(addresses(input.string("bcc")))
            input.string("replyTo")?.let(::add)
        }
        if (typed.isEmpty()) return true
        val clients = ClientRepository(ctx.services.mongo, ctx.tenant.id)
        val company = AgentMessaging.variable(ctx, "company.email")
        return typed.all { address ->
            address.equals(company, ignoreCase = true) ||
                clients.findByEmail(address) != null ||
                ctx.services.dashboardUsers.findByEmail(address)?.tenantId == ctx.tenant.primaryTenantId
        }
    }

    private fun addresses(raw: String?): List<String> =
        raw.orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    const val ALREADY_EMAILED = "already_emailed"
}

/**
 * A reply to the email the agent works on, in its thread, from the account it came in to, to its
 * Reply-To or sender. Mail sent by machines (notifications, newsletters, no-reply senders) gets none:
 * nobody reads the answer, and two auto-responders could answer each other forever.
 */
object EmailReplyAction : AgentAction {
    override val key = "email.reply"
    override val category = ActionCategory.MESSAGE
    override val sideEffect = SideEffect.EXTERNAL_MESSAGE
    override val requiredIntegration = IntegrationKind.GMAIL_INBOX
    override val subjectTypes = setOf(SubjectTypes.EMAIL)
    override val toolDescription = "Reply, in its thread, to the email the agent works on."
    override val inputSchema = Schema.obj(
        "text" to Schema.string(widget = "template", maxLength = 20_000, description = "the reply, as plain text"),
        required = listOf("text"),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext): ActionPreview {
        val email = inbound(ctx)
        return ActionPreview(
            kind = "email",
            channel = "email",
            recipients = listOfNotNull(email?.let(::recipient)),
            subject = email?.let { subject(it.subject) },
            body = input.string("text"),
            editable = listOf("text"),
            warnings = buildList {
                if (email == null) add("no_record") else if (email.automated) add(AUTOMATED)
                if (ctx.services.email?.isAvailable(ctx.tenant) != true) add("no_email_account")
            },
        )
    }

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val text = input.string("text") ?: return ActionResult.Skipped("empty_message")
        val sender = ctx.services.email ?: return ActionResult.Failed(EmailService.NO_ACCOUNT)
        val email = inbound(ctx) ?: return ActionResult.Skipped("no_record")
        if (email.automated) return ActionResult.Skipped(AUTOMATED)
        val to = recipient(email)
        AgentMessaging.capped(ctx, EmailService.CHANNEL, to)?.let { return it }
        val reply = OutgoingEmail(
            to = listOf(to),
            subject = subject(email.subject),
            text = text,
            threadId = email.threadId,
            inReplyTo = email.messageIdHeader,
            clientId = email.clientId ?: ctx.run.clientId,
            record = ctx.run.subject,
            autoReplied = true,
        )
        return when (val sent = sender.sendFrom(ctx.tenant, email.connectionId, reply, ctx.idempotencyKey)) {
            is EmailSendResult.Sent -> ActionResult.Done(
                buildJsonObject {
                    put("channel", EmailService.CHANNEL)
                    put("to", to)
                    if (sent.messageId.isNotEmpty()) put("messageId", sent.messageId)
                    sent.threadId?.let { put("threadId", it) }
                },
                note = if (sent.alreadySent) "already_sent" else null,
            )
            is EmailSendResult.Failed ->
                if (sent.key == EmailService.DAILY_LIMIT) EmailSendAction.nextDay(ctx) else ActionResult.Failed(sent.key, retryable = sent.retryable)
        }
    }

    /** The email the run is about, when someone wrote it to the company. */
    private suspend fun inbound(ctx: RunContext): EmailMessage? {
        val subject = ctx.run.subject?.takeIf { it.type == SubjectTypes.EMAIL } ?: return null
        val id = runCatching { ObjectId(subject.id) }.getOrNull() ?: return null
        return EmailMessageRepository(ctx.services.mongo).find(ctx.tenant.id, id)?.takeIf { it.direction == EmailDirection.INBOUND }
    }

    private fun recipient(email: EmailMessage): String = EmailAddresses.normalize(email.replyTo) ?: email.from

    internal fun subject(original: String): String = EmailMessage.replySubject(original)

    const val AUTOMATED = "automated_sender"
}
