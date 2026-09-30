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
import com.rfm.edubot.integrations.email.EmailAttachment
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
        return ActionPreview(
            kind = "email",
            channel = "email",
            recipients = listOfNotNull(to),
            subject = input.string("subject"),
            body = input.string("text"),
            attachments = listOfNotNull(input.string("attachPdf")?.takeIf { it != "none" }?.let { AgentDocuments.forRun(ctx, it)?.filename }),
            fields = buildMap {
                if (cc.isNotEmpty()) put("cc", cc.joinToString(", "))
                if (bcc.isNotEmpty()) put("bcc", bcc.joinToString(", "))
                input.string("replyTo")?.let { put("replyTo", it) }
            },
            editable = listOf("subject", "text"),
            warnings = buildList {
                if (to == null) add("no_email")
                if (ctx.services.email?.isAvailable(ctx.tenant) != true) add("no_email_account")
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

    /** The account's allowance comes back at the company's midnight: the step waits for it once, then gives up. */
    private fun nextDay(ctx: RunContext): ActionResult {
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

    private fun addresses(raw: String?): List<String> =
        raw.orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
}
