package com.rfm.edubot.integrations.email

import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.integrations.IntegrationConnection
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Tenant
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An address on a received email, decoded: lower-cased, with the name its sender gave. */
data class MailAddress(val email: String, val name: String? = null)

/** A received email as both inbox sources hand it over: Gmail's inbox sync and the mail forwarded to a company's address. */
data class ReceivedEmail(
    /** The source's own id for it (Gmail's message id, SES's), unique in its account. */
    val providerMessageId: String,
    val threadId: String?,
    val messageIdHeader: String?,
    val from: MailAddress,
    val replyTo: MailAddress?,
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    val text: String,
    val snippet: String,
    val attachments: List<EmailAttachmentInfo>,
    val automated: Boolean,
    val autoReply: Boolean,
    /** When it reached the company's mailbox. */
    val date: Instant?,
    /** Someone at the company forwarded it by hand: who, while [from] is who wrote it. */
    val forwardedBy: String? = null,
)

/**
 * Keeps a received email: once in `email_messages`, filed under the client its sender (or reply-to) is,
 * and announced as `email.received` unless it is an automatic reply.
 */
class ReceivedEmails(
    private val messages: EmailMessageRepository,
    private val events: DomainEventLog,
    private val mongo: MongoModule,
    private val clock: () -> Instant = SystemClock::now,
) {
    /**
     * Stores [email] in [connection] unless one of [own] (the company's own addresses) sent it, or it is
     * [bulk] mail from someone who isn't a client. Returns the stored email, or null when it was skipped
     * or kept before.
     */
    suspend fun keep(tenant: Tenant, connection: IntegrationConnection, email: ReceivedEmail, own: Set<String>, bulk: Boolean = false): EmailMessage? {
        if (email.from.email in own) return null
        val replyTo = email.replyTo?.email?.takeIf { it != email.from.email && it !in own }
        val clients = ClientRepository(mongo, tenant.id)
        val client = clients.findByEmail(email.from.email) ?: replyTo?.let { clients.findByEmail(it) }
        if (client == null && bulk) return null
        val now = clock()
        val message = EmailMessage(
            tenantId = tenant.id,
            connectionId = connection.id,
            providerMessageId = email.providerMessageId,
            threadId = email.threadId,
            messageIdHeader = email.messageIdHeader,
            direction = EmailDirection.INBOUND,
            from = email.from.email,
            fromName = email.from.name?.take(MAX_NAME),
            replyTo = replyTo,
            to = email.to.take(MAX_RECIPIENTS),
            cc = email.cc.take(MAX_RECIPIENTS),
            subject = email.subject.take(EmailMessage.MAX_SUBJECT),
            snippet = email.snippet,
            bodyText = email.text,
            attachments = email.attachments.take(MAX_ATTACHMENTS),
            automated = email.automated,
            clientId = client?.id,
            forwardedBy = email.forwardedBy,
            date = email.date ?: now,
            createdAt = now,
        )
        val stored = messages.insert(message)
        if (stored.id != message.id) return null
        if (!email.autoReply) announce(tenant, stored, client)
        return stored
    }

    /** The event carries no text: triggers that look into the body read it from the stored email. */
    private suspend fun announce(tenant: Tenant, email: EmailMessage, client: Client?) {
        events.append(
            tenantId = tenant.id,
            type = DomainEventTypes.EMAIL_RECEIVED,
            subject = SubjectRef.of(SubjectTypes.EMAIL, email.id),
            payload = buildJsonObject {
                put("from", email.from)
                email.fromName?.let { put("fromName", it) }
                put("subject", email.subject)
                put("hasAttachments", email.attachments.isNotEmpty())
                put("hasPdf", email.attachments.any { it.isPdf })
                put("automated", email.automated)
                put("connectionId", email.connectionId.toHexString())
                email.threadId?.let { put("threadId", it) }
                client?.let { put("clientId", it.id.toHexString()) }
            },
            related = listOfNotNull(client?.let { SubjectRef.of(SubjectTypes.CLIENT, it.id) }),
        )
    }

    companion object {
        private const val MAX_NAME = 200
        private const val MAX_RECIPIENTS = 50
        private const val MAX_ATTACHMENTS = 30
    }
}
