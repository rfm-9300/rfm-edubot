package com.rfm.edubot.integrations.mailbox

import com.rfm.edubot.integrations.ConnectionStatus
import com.rfm.edubot.integrations.IntegrationConnection
import com.rfm.edubot.integrations.IntegrationProviders
import com.rfm.edubot.integrations.aws.S3Client
import com.rfm.edubot.integrations.email.EmailAddresses
import com.rfm.edubot.integrations.email.EmailMessage
import com.rfm.edubot.integrations.email.EmailMessageRepository
import com.rfm.edubot.integrations.email.ReceivedEmail
import com.rfm.edubot.integrations.email.ReceivedEmails
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Keeps the mail that reaches companies' addresses. SES's receipt rule stores each email in S3 and
 * announces it ([received], an SES "Received" notification): the email goes to the company whose
 * address it was sent to, threaded with the email it answers, matched to a client and announced as
 * `email.received` like inbox sync's ([ReceivedEmails]); the stored copy in S3 is then deleted.
 *
 * Mail SES found a virus or spam in, or that fails DMARC for a domain that asks receivers to reject it
 * (a forged sender), is dropped. An email someone at the company forwarded by hand is kept as from its
 * original sender. Gmail's request to confirm forwarding is kept on the address for the setup screen.
 */
class MailboxInbound(
    private val mailbox: MailboxIntegration,
    private val messages: EmailMessageRepository,
    private val received: ReceivedEmails,
    private val tenants: suspend (ObjectId) -> Tenant?,
    /** The team's sign-in addresses, whose hand-forwarded mail is unwrapped. */
    private val team: suspend (ObjectId) -> Set<String> = { emptySet() },
    private val clock: () -> Instant = SystemClock::now,
) {
    private val log = LoggerFactory.getLogger("MailboxInbound")

    sealed interface Outcome {
        /** [count] emails were kept (0 when they all had been already). */
        data class Kept(val count: Int) : Outcome

        /** Nothing to keep, and nothing to try again: [reason] says why. */
        data class Ignored(val reason: String) : Outcome

        /** Not done; SNS delivers the notification again. */
        data class Retry(val reason: String) : Outcome
    }

    suspend fun received(notification: JsonObject): Outcome {
        if (notification.string("notificationType") != RECEIVED) return Outcome.Ignored("not_received")
        val mail = notification["mail"] as? JsonObject ?: return Outcome.Ignored("invalid")
        val receipt = notification["receipt"] as? JsonObject ?: return Outcome.Ignored("invalid")
        val sesId = mail.string("messageId")?.takeIf { it.isNotBlank() } ?: return Outcome.Ignored("invalid")
        val at = (mail.string("timestamp") ?: receipt.string("timestamp"))?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: clock()
        val source = locate(notification, receipt, sesId) ?: return Outcome.Ignored("no_content")

        val connections = (receipt["recipients"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(EmailAddresses::normalize) }
            .filter(mailbox::owns).distinct()
            .mapNotNull { mailbox.connections.findMailbox(it) }
            .filter { it.provider == IntegrationProviders.MAILBOX && it.status == ConnectionStatus.ACTIVE }
        if (connections.isEmpty()) {
            discard(source)
            return Outcome.Ignored("unknown_recipient")
        }
        val verdict = { name: String -> (receipt[name] as? JsonObject)?.string("status")?.uppercase() }
        val dropped = when {
            verdict("virusVerdict") == FAIL -> "virus"
            verdict("spamVerdict") == FAIL -> "spam"
            verdict("dmarcVerdict") == FAIL && receipt.string("dmarcPolicy")?.lowercase() == "reject" -> "dmarc_reject"
            else -> null
        }
        if (dropped != null) {
            log.info("Mailbox email dropped: reason={} connections={}", dropped, connections.map { it.id })
            discard(source)
            return Outcome.Ignored(dropped)
        }

        val pending = connections.mapNotNull { connection ->
            val tenant = tenants(connection.tenantId)?.takeIf { it.status == TenantStatus.ACTIVE } ?: return@mapNotNull null
            tenant to connection
        }.filter { (tenant, connection) -> messages.findByProviderId(tenant.id, connection.id, sesId) == null }
        if (pending.isEmpty()) {
            discard(source)
            return Outcome.Kept(0)
        }
        val raw = when (val read = read(source, sesId)) {
            is Read.Bytes -> read.bytes
            Read.Missing -> return Outcome.Ignored("object_missing")
            is Read.Failed -> return if (read.retryable) Outcome.Retry("s3_unavailable") else Outcome.Ignored("s3_refused")
        }
        val parsed = InboundMime.parse(raw) ?: run {
            discard(source)
            return Outcome.Ignored("unreadable")
        }
        val verified = verdict("dkimVerdict") == PASS || verdict("dmarcVerdict") == PASS
        var kept = 0
        for ((tenant, connection) in pending) {
            if (keep(tenant, connection, parsed, sesId, at, mail.string("source"), verified) != null) kept++
        }
        discard(source)
        return Outcome.Kept(kept)
    }

    /** Files [mail] in [connection]; null when it wasn't kept (the company's own, or kept before). */
    private suspend fun keep(tenant: Tenant, connection: IntegrationConnection, mail: InboundMail, sesId: String, at: Instant, envelope: String?, verified: Boolean): EmailMessage? {
        val forwarder = ForwardedMail.forwarder(mail.headers, envelope, connection.accountEmail)
        mailbox.connections.mailboxReceived(connection.id, at, forwarder)
        ForwardedMail.gmailConfirmation(mail.from?.email, mail.subject, mail.text, verified, connection.accountEmail, at)?.let { confirmation ->
            mailbox.connections.setForwardingConfirmation(connection.id, confirmation)
            log.info("Gmail forwarding confirmation received: tenant={} connection={}", tenant.slug, connection.id)
        }

        val own = ownAddresses(tenant, connection) + connection.mailbox.forwarders + listOfNotNull(forwarder)
        val company = own + team(tenant.id)
        val email = unwrap(mail, sesId, at, own, company) ?: return null
        email.messageIdHeader?.let { header ->
            if (messages.findReceived(tenant.id, connection.id, header) != null) return null
        }
        val threadId = if (email.forwardedBy != null) newThreadId() else thread(tenant, connection, mail) ?: newThreadId()
        val stored = received.keep(tenant, connection, email.copy(threadId = threadId), own) ?: return null
        log.info("Mailbox email kept: tenant={} connection={} automated={}", tenant.slug, connection.id, stored.automated)
        return stored
    }

    /**
     * The email as the company sees it. Mail its own people forwarded by hand ([company] sent it) is
     * kept as from whoever wrote the forwarded email: the email attached whole, else the sender named in
     * the forward's header block. Anything else [own] sent is the company's own mail and isn't kept.
     */
    private fun unwrap(mail: InboundMail, sesId: String, at: Instant, own: Set<String>, company: Set<String>): ReceivedEmail? {
        val from = mail.from ?: return null
        val gmailConfirmation = from.email == ForwardedMail.GMAIL_FORWARDING
        val base = ReceivedEmail(
            providerMessageId = sesId,
            threadId = null,
            messageIdHeader = mail.messageIdHeader,
            from = from,
            replyTo = mail.replyTo,
            to = mail.to,
            cc = mail.cc,
            subject = mail.subject,
            text = mail.text,
            snippet = EmailMessage.snippetOf(mail.text),
            attachments = mail.attachments,
            automated = mail.automated || gmailConfirmation,
            autoReply = mail.autoReply || gmailConfirmation,
            date = at,
        )
        if (from.email !in company) return base
        val attached = mail.attached?.takeIf { inner -> inner.from != null && inner.from.email !in own }
        if (attached != null) {
            return base.copy(
                messageIdHeader = attached.messageIdHeader ?: mail.messageIdHeader,
                from = attached.from!!,
                replyTo = attached.replyTo,
                to = attached.to,
                cc = attached.cc,
                subject = attached.subject.ifBlank { ForwardedMail.withoutForwardPrefix(mail.subject) },
                text = listOf(mail.text, attached.text).filter { it.isNotBlank() }.joinToString("\n\n").take(EmailMessage.MAX_BODY),
                snippet = EmailMessage.snippetOf(attached.text.ifBlank { mail.text }),
                attachments = (attached.attachments + mail.attachments).distinctBy { it.filename },
                automated = attached.automated,
                autoReply = attached.autoReply,
                forwardedBy = from.email,
            )
        }
        val original = ForwardedMail.original(mail.text, mail.subject)?.takeIf { it.from.email !in own }
        if (original != null) {
            return base.copy(
                from = original.from,
                replyTo = null,
                subject = original.subject ?: ForwardedMail.withoutForwardPrefix(mail.subject),
                automated = false,
                autoReply = false,
                forwardedBy = from.email,
            )
        }
        // A teammate writing in is kept; the company's own mail (sent from its address, the forwarding mailbox) isn't.
        return if (from.email in own) null else base
    }

    /** The thread the email answers: an email of [connection] it names in In-Reply-To or References. */
    private suspend fun thread(tenant: Tenant, connection: IntegrationConnection, mail: InboundMail): String? {
        val ids = (mail.inReplyTo + mail.references.asReversed()).distinct()
        val sesIds = ids.mapNotNull { id -> SES_MESSAGE_ID.find(id)?.groupValues?.get(1) }
        return messages.threadAnswered(tenant.id, connection.id, ids, sesIds)
    }

    /** The address itself, the company's other email accounts, where its replies go and the address on its documents. */
    private suspend fun ownAddresses(tenant: Tenant, connection: IntegrationConnection): Set<String> = buildSet {
        add(connection.accountEmail)
        mailbox.connections.list(tenant.id).forEach { add(it.accountEmail) }
        EmailAddresses.normalize(connection.settings.replyTo)?.let(::add)
        EmailAddresses.normalize(tenant.documentTemplate.email)?.let(::add)
    }

    private sealed interface Source {
        data class Stored(val bucket: String, val key: String) : Source
        class Inline(val bytes: ByteArray) : Source
    }

    private sealed interface Read {
        class Bytes(val bytes: ByteArray) : Read
        data object Missing : Read
        data class Failed(val retryable: Boolean) : Read
    }

    /** Where the email is: the S3 object SES wrote (only in the platform's bucket), or inside the notification. */
    private fun locate(notification: JsonObject, receipt: JsonObject, sesId: String): Source? {
        val action = receipt["action"] as? JsonObject ?: return null
        return when (action.string("type")?.uppercase()) {
            "S3" -> {
                val bucket = action.string("bucketName")?.takeIf { it == mailbox.config.bucket } ?: return null.also {
                    log.warn("Mailbox notification for a bucket that isn't the platform's ignored")
                }
                Source.Stored(bucket, action.string("objectKey") ?: "${action.string("objectKeyPrefix").orEmpty()}$sesId")
            }
            "SNS" -> notification.string("content")?.let { content -> Source.Inline(inline(content)) }
            else -> null
        }
    }

    /** An SNS action's content: the raw email as UTF-8, or Base64 when the action was set to encode it. */
    private fun inline(content: String): ByteArray {
        val head = content.take(200)
        if (':' !in head || !head.any { it == '\n' || it == ' ' }) {
            runCatching { Base64.getMimeDecoder().decode(content) }.getOrNull()?.let { return it }
        }
        return content.toByteArray(Charsets.UTF_8)
    }

    private suspend fun read(source: Source, sesId: String): Read = when (source) {
        is Source.Inline -> Read.Bytes(source.bytes)
        is Source.Stored -> when (val got = mailbox.s3.get(source.bucket, source.key)) {
            is S3Client.Get.Ok -> Read.Bytes(got.bytes)
            S3Client.Get.NotFound -> {
                // A key named without its prefix: the bare SES id is the other place SES writes it.
                if (source.key != sesId) {
                    when (val bare = mailbox.s3.get(source.bucket, sesId)) {
                        is S3Client.Get.Ok -> Read.Bytes(bare.bytes)
                        else -> Read.Missing
                    }
                } else Read.Missing
            }
            is S3Client.Get.Failed -> Read.Failed(got.retryable)
        }
    }

    /** The copy SES stored isn't needed once kept (or refused); the bucket's lifecycle rule catches what this misses. */
    private suspend fun discard(source: Source) {
        if (source is Source.Stored) mailbox.s3.delete(source.bucket, source.key)
    }

    private fun newThreadId(): String = "mb-${ObjectId().toHexString()}"

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val RECEIVED = "Received"
        private const val FAIL = "FAIL"
        private const val PASS = "PASS"

        /** A Message-ID SES wrote for mail it sent: `<{SES id}@{region}.amazonses.com>`. */
        private val SES_MESSAGE_ID = Regex("^<([^@<>\\s]+)@(?:[a-z0-9-]+\\.)?amazonses\\.com>$", RegexOption.IGNORE_CASE)
    }
}
