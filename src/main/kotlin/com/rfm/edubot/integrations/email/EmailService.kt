package com.rfm.edubot.integrations.email

import com.rfm.edubot.agents.EmailAvailability
import com.rfm.edubot.agents.model.OutboundLogEntry
import com.rfm.edubot.agents.model.OutboundStatus
import com.rfm.edubot.agents.store.AgentSettingsRepository
import com.rfm.edubot.agents.store.OutboundLogRepository
import com.rfm.edubot.events.Actor
import com.rfm.edubot.events.ActorType
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.events.currentActor
import com.rfm.edubot.integrations.ConnectionStatus
import com.rfm.edubot.integrations.IntegrationConnection
import com.rfm.edubot.integrations.IntegrationProviders
import com.rfm.edubot.integrations.google.GmailClient
import com.rfm.edubot.integrations.google.GoogleIntegration
import com.rfm.edubot.integrations.google.GoogleScopes
import com.rfm.edubot.integrations.google.GoogleTokenProvider
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory

/**
 * Sends a company's email from its default Google account, in the company's branded layout with the
 * account's sender name, reply-to and signature. Each send is claimed in the outbound log before
 * Gmail is called, so one key never goes out twice; each account has a daily allowance; what went
 * out is kept in `email_messages` and announced as `email.sent`.
 */
class EmailService(
    private val google: GoogleIntegration,
    private val messages: EmailMessageRepository,
    private val outboundLog: OutboundLogRepository,
    private val events: DomainEventLog,
    private val agentSettings: AgentSettingsRepository,
    private val clock: () -> Instant = SystemClock::now,
) : EmailSender {
    private val log = LoggerFactory.getLogger("EmailService")

    /** The platform has a Google OAuth client and a token key. */
    val configured: Boolean get() = google.configured

    /** The account email goes out from when none is named. */
    suspend fun sender(tenant: Tenant): IntegrationConnection? =
        google.connections.defaultFor(tenant.id, IntegrationProviders.GOOGLE)

    override suspend fun isAvailable(tenant: Tenant): Boolean =
        google.configured && sender(tenant)?.let(::canSend) == true

    /**
     * What automations may use. Sending still counts while the account only needs a reconnect: runs
     * then stop with `needs_reconnect`, which its admins were already asked to fix. The inbox counts
     * once an account has inbox sync on with the read scopes.
     */
    suspend fun availability(tenant: Tenant): EmailAvailability {
        if (!google.configured) return EmailAvailability()
        val accounts = google.connections.list(tenant.id, IntegrationProviders.GOOGLE).filter { it.status != ConnectionStatus.REVOKED }
        return EmailAvailability(
            send = accounts.any { GoogleScopes.canSend(it.scopes) },
            inbox = google.inboxAvailable && accounts.any { it.settings.inboxSync && GoogleScopes.canRead(it.scopes) },
        )
    }

    /** How many emails each of the company's accounts may send a day. */
    suspend fun dailyLimit(tenant: Tenant): Int = agentSettings.get(tenant.id).platform.emailSendsPerDay

    /** The company's date (`yyyy-mm-dd`), which the daily allowance counts by. */
    fun today(tenant: Tenant): String =
        clock().toLocalDateTime(TimeZone.of(TenantTimeZones.normalize(tenant.timezone))).date.toString()

    override suspend fun send(tenant: Tenant, email: OutgoingEmail, idempotencyKey: String?): EmailSendResult {
        val connection = sender(tenant) ?: return EmailSendResult.Failed(NO_ACCOUNT)
        return sendFrom(tenant, connection, email, idempotencyKey)
    }

    /** A short email from [connection] to [to] in the company's language, to check how its clients see it. */
    suspend fun sendTest(tenant: Tenant, connection: IntegrationConnection, to: String): EmailSendResult {
        val company = tenant.documentTemplate.withCompanyFallback(tenant.name).companyName
        val test = OutgoingEmail(
            to = listOf(to),
            subject = EmailCopy.t(tenant.locale, "test.subject", "company" to company),
            text = EmailCopy.t(tenant.locale, "test.body", "company" to company, "account" to connection.accountEmail),
        )
        return sendFrom(tenant, connection, test)
    }

    /** Disconnecting an account forgets the mail kept from it. */
    suspend fun forget(connection: IntegrationConnection): Long = messages.deleteForConnection(connection.tenantId, connection.id)

    /** Sends from [connection], one of [tenant]'s accounts. Without [idempotencyKey] every call is a new email. */
    suspend fun sendFrom(tenant: Tenant, connection: IntegrationConnection, email: OutgoingEmail, idempotencyKey: String? = null): EmailSendResult {
        if (!google.configured || connection.tenantId != tenant.id) return EmailSendResult.Failed(NO_ACCOUNT)
        if (!canSend(connection)) return EmailSendResult.Failed(NEEDS_RECONNECT)
        val prepared = when (val result = prepare(tenant, connection, email)) {
            is Prepared.Ready -> result
            is Prepared.Invalid -> return EmailSendResult.Failed(result.key)
        }
        val key = idempotencyKey ?: "email:${ObjectId().toHexString()}"
        val actor = currentActor().actor
        val agent = actor.takeIf { it.type == ActorType.AGENT }
        val entry = OutboundLogEntry(
            tenantId = tenant.id,
            idempotencyKey = key,
            channel = CHANNEL,
            recipient = prepared.to.first(),
            status = OutboundStatus.SENDING,
            at = clock(),
            runId = agent?.runId?.let(::objectId),
            agentId = agent?.id?.let(::objectId),
        )
        when (outboundLog.acquire(entry)) {
            OutboundLogRepository.Acquire.ALREADY_SENT -> return alreadySent(tenant, connection, key)
            OutboundLogRepository.Acquire.IN_DOUBT -> return EmailSendResult.Failed(IN_DOUBT)
            OutboundLogRepository.Acquire.STARTED -> Unit
        }
        val day = today(tenant)
        if (!google.connections.claimSend(connection.id, day, dailyLimit(tenant))) {
            outboundLog.markFailed(key, DAILY_LIMIT)
            return EmailSendResult.Failed(DAILY_LIMIT)
        }
        val result = try {
            transmit(connection, prepared.raw, email.threadId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Email send crashed: tenant={} connection={} error={}", tenant.id, connection.id, e.message)
            GmailClient.Send.Failed(SEND_FAILED, retryable = true)
        }
        return when (result) {
            is GmailClient.Send.Sent -> {
                outboundLog.markSent(key, result.id)
                record(tenant, connection, email, prepared, result, actor)
                EmailSendResult.Sent(result.id, result.threadId, connection.accountEmail)
            }
            is GmailClient.Send.Failed -> {
                // Gmail took the message without saying so: the log stays SENDING so nobody sends it twice.
                if (result.key == IN_DOUBT) return EmailSendResult.Failed(IN_DOUBT)
                google.connections.releaseSend(connection.id, day)
                outboundLog.markFailed(key, result.key)
                EmailSendResult.Failed(result.key, retryable = result.retryable)
            }
        }
    }

    private sealed interface Prepared {
        data class Ready(
            val to: List<String>,
            val cc: List<String>,
            val bcc: List<String>,
            val fromName: String,
            val subject: String,
            val text: String,
            val messageId: String,
            val raw: ByteArray,
            val date: Instant,
        ) : Prepared

        data class Invalid(val key: String) : Prepared
    }

    /** Checks the addresses and builds the message; nothing is claimed yet, so a bad email costs nothing. */
    private fun prepare(tenant: Tenant, connection: IntegrationConnection, email: OutgoingEmail): Prepared {
        val to = addresses(email.to) ?: return Prepared.Invalid(INVALID_RECIPIENT)
        if (to.isEmpty()) return Prepared.Invalid(INVALID_RECIPIENT)
        val cc = (addresses(email.cc) ?: return Prepared.Invalid(INVALID_RECIPIENT)) - to.toSet()
        val bcc = (addresses(email.bcc) ?: return Prepared.Invalid(INVALID_RECIPIENT)) - (to + cc).toSet()
        if (to.size + cc.size + bcc.size > MAX_RECIPIENTS) return Prepared.Invalid(TOO_MANY_RECIPIENTS)
        val replyTo = when (val explicit = email.replyTo?.takeIf { it.isNotBlank() }) {
            null -> EmailAddresses.normalize(connection.settings.replyTo)
            else -> EmailAddresses.normalize(explicit) ?: return Prepared.Invalid(INVALID_RECIPIENT)
        }?.takeIf { it != connection.accountEmail }
        val template = tenant.documentTemplate.withCompanyFallback(tenant.name)
        val fromName = connection.settings.senderName?.trim()?.takeIf { it.isNotEmpty() }
            ?: template.companyName.trim().ifEmpty { tenant.name }
        val subject = email.subject.trim()
        val rendered = EmailLayout.render(subject, email.text, template, connection.settings.signature, tenant.locale)
        val messageId = MimeMessageBuilder.newMessageId(connection.accountEmail)
        val date = clock()
        val raw = try {
            MimeMessageBuilder.build(
                MimeMessageBuilder.Message(
                    from = MimeMessageBuilder.Address(connection.accountEmail, fromName),
                    to = to.map { MimeMessageBuilder.Address(it) },
                    cc = cc.map { MimeMessageBuilder.Address(it) },
                    bcc = bcc.map { MimeMessageBuilder.Address(it) },
                    replyTo = replyTo?.let { MimeMessageBuilder.Address(it) },
                    subject = subject,
                    text = rendered.text,
                    html = rendered.html,
                    attachments = email.attachments,
                    messageId = messageId,
                    inReplyTo = email.inReplyTo?.takeIf { it.isNotBlank() },
                    date = date,
                ),
            )
        } catch (e: IllegalArgumentException) {
            return Prepared.Invalid(INVALID_MESSAGE)
        }
        if (raw.size > GmailClient.MAX_RAW_BYTES) return Prepared.Invalid(TOO_LARGE)
        return Prepared.Ready(to, cc, bcc, fromName, subject, rendered.text, messageId, raw, date)
    }

    /** Sends with the account's token; a token Gmail refuses before it expires is renewed and the send tried once more. */
    private suspend fun transmit(connection: IntegrationConnection, raw: ByteArray, threadId: String?): GmailClient.Send {
        val first = attempt(connection, raw, threadId)
        if (first !is GmailClient.Send.Failed || first.key != UNAUTHORIZED) return first
        google.connections.expireAccessToken(connection.id)
        val fresh = google.connections.findById(connection.id) ?: return GmailClient.Send.Failed(NEEDS_RECONNECT, retryable = false)
        val second = attempt(fresh, raw, threadId)
        return if (second is GmailClient.Send.Failed && second.key == UNAUTHORIZED) GmailClient.Send.Failed(SEND_FAILED, retryable = true, second.status) else second
    }

    private suspend fun attempt(connection: IntegrationConnection, raw: ByteArray, threadId: String?): GmailClient.Send {
        val token = when (val token = google.tokens.accessToken(connection)) {
            is GoogleTokenProvider.Token.Ok -> token.accessToken
            GoogleTokenProvider.Token.NeedsReconnect -> return GmailClient.Send.Failed(NEEDS_RECONNECT, retryable = false)
            is GoogleTokenProvider.Token.Unavailable -> return GmailClient.Send.Failed(SEND_FAILED, retryable = true)
        }
        val result = google.gmail.send(token, raw, threadId)
        if (result is GmailClient.Send.Failed && result.key == NEEDS_RECONNECT) google.tokens.needsReconnect(connection, "insufficient_permissions")
        return result
    }

    /** Keeps what went out and announces it; the email is already sent, so a failure here is only logged. */
    private suspend fun record(tenant: Tenant, connection: IntegrationConnection, email: OutgoingEmail, prepared: Prepared.Ready, sent: GmailClient.Send.Sent, actor: Actor) {
        val stored = try {
            messages.insert(
                EmailMessage(
                    tenantId = tenant.id,
                    connectionId = connection.id,
                    providerMessageId = sent.id,
                    threadId = sent.threadId,
                    messageIdHeader = prepared.messageId,
                    direction = EmailDirection.OUTBOUND,
                    from = connection.accountEmail,
                    fromName = prepared.fromName,
                    to = prepared.to,
                    cc = prepared.cc,
                    bcc = prepared.bcc,
                    subject = prepared.subject,
                    snippet = EmailMessage.snippetOf(email.text),
                    bodyText = prepared.text,
                    attachments = email.attachments.map { EmailAttachmentInfo(it.filename, it.mimeType, it.bytes.size) },
                    clientId = email.clientId,
                    record = email.record,
                    sentByType = actor.type.name,
                    sentById = actor.id,
                    sentByName = actor.name,
                    runId = actor.runId,
                    date = prepared.date,
                    createdAt = clock(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not store the sent email: tenant={} message={} error={}", tenant.id, sent.id, e.message)
            return
        }
        events.append(
            tenantId = tenant.id,
            type = DomainEventTypes.EMAIL_SENT,
            subject = SubjectRef.of(SubjectTypes.EMAIL, stored.id),
            payload = buildJsonObject {
                put("from", connection.accountEmail)
                put("to", prepared.to.joinToString(", "))
                put("subject", prepared.subject)
                put("snippet", stored.snippet)
                put("hasPdf", email.attachments.any { it.mimeType == PDF })
                sent.threadId?.let { put("threadId", it) }
                email.clientId?.let { put("clientId", it.toHexString()) }
            },
            related = listOfNotNull(email.clientId?.let { SubjectRef.of(SubjectTypes.CLIENT, it) }, email.record),
        )
    }

    private suspend fun alreadySent(tenant: Tenant, connection: IntegrationConnection, key: String): EmailSendResult {
        val providerId = outboundLog.find(key)?.providerMessageId.orEmpty()
        val stored = providerId.takeIf { it.isNotEmpty() }?.let { messages.findByProviderId(tenant.id, connection.id, it) }
        return EmailSendResult.Sent(providerId, stored?.threadId, stored?.from ?: connection.accountEmail, alreadySent = true)
    }

    private fun canSend(connection: IntegrationConnection): Boolean =
        connection.status == ConnectionStatus.ACTIVE && GoogleScopes.canSend(connection.scopes)

    /** Normalised and deduplicated; null when one of them isn't an address. */
    private fun addresses(raw: List<String>): List<String>? =
        raw.map { it.trim() }.filter { it.isNotEmpty() }.map { EmailAddresses.normalize(it) ?: return null }.distinct()

    private fun objectId(hex: String): ObjectId? = runCatching { ObjectId(hex) }.getOrNull()

    companion object {
        const val CHANNEL = "email"
        const val MAX_RECIPIENTS = 20
        const val NO_ACCOUNT = "no_email_account"
        const val NEEDS_RECONNECT = "needs_reconnect"
        const val INVALID_RECIPIENT = "invalid_recipient"
        const val TOO_MANY_RECIPIENTS = "too_many_recipients"
        const val INVALID_MESSAGE = "invalid_message"
        const val TOO_LARGE = "message_too_large"
        const val DAILY_LIMIT = "daily_send_limit"
        const val IN_DOUBT = "send_in_doubt"
        const val SEND_FAILED = "send_failed"
        private const val UNAUTHORIZED = "unauthorized"
        private const val PDF = "application/pdf"
    }
}
