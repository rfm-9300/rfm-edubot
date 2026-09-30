package com.rfm.edubot.integrations.google

import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.integrations.IntegrationConnection
import com.rfm.edubot.integrations.IntegrationProviders
import com.rfm.edubot.integrations.email.EmailAddresses
import com.rfm.edubot.integrations.email.EmailDirection
import com.rfm.edubot.integrations.email.EmailMessage
import com.rfm.edubot.integrations.email.EmailMessageRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.shared.jobs.PeriodicJob
import com.rfm.edubot.shared.jobs.SchedulerLease
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Reads the inboxes companies use in automations: Gmail's history after each account's cursor (the
 * first time, the inbox since inbox sync was turned on), each new message stored once in
 * `email_messages`, matched to a client by the sender's address and announced as `email.received`.
 * The company's own mail, spam and strangers' newsletters are left alone; automatic replies are
 * kept but announce nothing, so two mailboxes can't answer each other forever.
 */
class GmailSyncWorker(
    private val google: GoogleIntegration,
    private val messages: EmailMessageRepository,
    private val events: DomainEventLog,
    private val mongo: MongoModule,
    private val tenants: suspend (ObjectId) -> Tenant?,
    private val clock: () -> Instant = SystemClock::now,
) {
    private val log = LoggerFactory.getLogger("GmailSyncWorker")

    fun job(lease: SchedulerLease?, interval: Duration): PeriodicJob = PeriodicJob("gmail-sync", interval, lease) { syncAll() }

    /** Reads every inbox once; returns how many new emails were kept. */
    suspend fun syncAll(): Int {
        if (!google.inboxAvailable) return 0
        var kept = 0
        for (connection in google.connections.inboxesToSync()) {
            kept += try {
                sync(connection)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Inbox sync failed: tenant={} connection={} error={}", connection.tenantId, connection.id, e.message)
                google.connections.inboxFailed(connection.id, SYNC_FAILED)
                0
            }
        }
        return kept
    }

    /** Reads [connection]'s new mail; returns how many emails were kept. */
    suspend fun sync(connection: IntegrationConnection): Int {
        if (!GoogleScopes.canRead(connection.scopes)) {
            if (connection.inbox.lastError != MISSING_SCOPE) google.connections.inboxFailed(connection.id, MISSING_SCOPE)
            return 0
        }
        val tenant = tenants(connection.tenantId)?.takeIf { it.status == TenantStatus.ACTIVE } ?: return 0
        val pass = Pass(tenant, connection, ownAddresses(tenant, connection))
        val cursor = connection.inbox.historyId
        val outcome = if (cursor == null) pass.catchUp(connection.inbox.enabledAt ?: clock()) else pass.follow(cursor)
        outcome.cursor?.takeIf { outcome.error == null || it != cursor }?.let { google.connections.inboxSynced(connection.id, it) }
        when (outcome.error) {
            null, TOKEN_REFUSED -> Unit
            // Reading was refused but sending may still work (a Workspace admin can block one scope): only the inbox waits.
            GmailClient.NEEDS_RECONNECT -> google.connections.inboxFailed(connection.id, MISSING_SCOPE)
            else -> google.connections.inboxFailed(connection.id, outcome.error)
        }
        if (pass.kept > 0) log.info("Inbox sync: tenant={} connection={} kept={}", tenant.slug, connection.id, pass.kept)
        return pass.kept
    }

    /** Where reading got to ([cursor], null when it must start over) and why it stopped early, if it did. */
    private data class Outcome(val cursor: String?, val error: String? = null)

    private sealed interface Step {
        data object Next : Step
        data object OutOfBudget : Step
        data class Failed(val key: String) : Step
    }

    /** One sync of one account: its token (renewed once if Gmail refuses it), and what it kept. */
    private inner class Pass(private val tenant: Tenant, private var connection: IntegrationConnection, private val own: Set<String>) {
        var kept = 0
        private var fetches = 0
        private var token: String? = null
        private var renewed = false
        private var refused: GmailClient.Read.Failed = GmailClient.Read.Failed(TOKEN_REFUSED, retryable = false)

        /** Follows the history after [cursor]; a cursor Gmail no longer keeps means catching up instead. */
        suspend fun follow(cursor: String): Outcome {
            var position = cursor
            var pageToken: String? = null
            repeat(MAX_PAGES) {
                val page = when (val read = read { google.gmail.history(it, cursor, pageToken) }) {
                    is GmailClient.Read.Ok -> read.value
                    is GmailClient.Read.Failed -> return if (read.key == GmailClient.NOT_FOUND && pageToken == null) resync() else Outcome(position, read.key)
                }
                for (record in page.records) {
                    for (id in record.messageIds) {
                        when (val step = take(id)) {
                            Step.Next -> Unit
                            Step.OutOfBudget -> return Outcome(position)
                            is Step.Failed -> return Outcome(position, step.key)
                        }
                    }
                    position = record.id
                }
                pageToken = page.nextPageToken ?: return Outcome(page.historyId ?: position)
            }
            return Outcome(position)
        }

        /**
         * Reads the inbox since [since], the newest [MAX_FETCHES] messages, and starts following the history
         * from the mailbox's position before listing: what arrives meanwhile comes with the next sync.
         */
        suspend fun catchUp(since: Instant): Outcome {
            val start = when (val read = read { google.gmail.mailbox(it) }) {
                is GmailClient.Read.Ok -> read.value.historyId ?: return Outcome(null, GmailClient.READ_FAILED)
                is GmailClient.Read.Failed -> return Outcome(null, read.key)
            }
            val ids = mutableListOf<String>()
            var pageToken: String? = null
            do {
                val page = when (val read = read { google.gmail.inbox(it, since, pageToken) }) {
                    is GmailClient.Read.Ok -> read.value
                    is GmailClient.Read.Failed -> return Outcome(null, read.key)
                }
                ids += page.ids
                pageToken = page.nextPageToken
            } while (pageToken != null && ids.size < MAX_FETCHES)
            for (id in ids.distinct().take(MAX_FETCHES).asReversed()) {
                val step = take(id)
                if (step is Step.Failed) return Outcome(null, step.key)
            }
            return Outcome(start)
        }

        /** Gmail keeps about a week of history: after a longer pause, the missed mail of the last days is read instead. */
        private suspend fun resync(): Outcome {
            val since = listOfNotNull(connection.inbox.enabledAt, connection.inbox.lastSyncedAt?.minus(RESYNC_MARGIN), clock() - RESYNC_WINDOW).max()
            log.info("Inbox history expired, catching up: connection={} since={}", connection.id, since)
            return catchUp(since)
        }

        private suspend fun take(id: String): Step {
            if (messages.findByProviderId(tenant.id, connection.id, id) != null) return Step.Next
            if (fetches >= MAX_FETCHES) return Step.OutOfBudget
            fetches++
            val message = when (val read = read { google.gmail.message(it, id) }) {
                is GmailClient.Read.Ok -> read.value
                is GmailClient.Read.Failed -> return if (read.key == GmailClient.NOT_FOUND) Step.Next else Step.Failed(read.key)
            }
            if (keep(message)) kept++
            return Step.Next
        }

        /** Stores [message] when it is someone else's mail in the inbox; announces it unless it is an automatic reply. */
        private suspend fun keep(message: GmailMessage): Boolean {
            if (message.labelIds.any { it in SKIPPED_LABELS }) return false
            val from = message.from ?: return false
            if (from.email in own) return false
            val replyTo = message.replyTo?.email?.takeIf { it != from.email && it !in own }
            val clients = ClientRepository(mongo, tenant.id)
            val client = clients.findByEmail(from.email) ?: replyTo?.let { clients.findByEmail(it) }
            if (client == null && message.labelIds.any { it in BULK_CATEGORIES }) return false
            val now = clock()
            val email = EmailMessage(
                tenantId = tenant.id,
                connectionId = connection.id,
                providerMessageId = message.id,
                threadId = message.threadId,
                messageIdHeader = message.messageIdHeader,
                direction = EmailDirection.INBOUND,
                from = from.email,
                fromName = from.name?.take(MAX_NAME),
                replyTo = replyTo,
                to = message.to.take(MAX_RECIPIENTS),
                cc = message.cc.take(MAX_RECIPIENTS),
                subject = message.subject.take(EmailMessage.MAX_SUBJECT),
                snippet = message.snippet,
                bodyText = message.text,
                attachments = message.attachments.take(MAX_ATTACHMENTS),
                automated = message.automated,
                clientId = client?.id,
                date = message.date ?: now,
                createdAt = now,
            )
            val stored = messages.insert(email)
            if (stored.id != email.id) return false
            if (!message.autoReply) announce(stored, client)
            return true
        }

        /** The event carries no text: triggers that look into the body read it from the stored email. */
        private suspend fun announce(email: EmailMessage, client: Client?) {
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

        private suspend fun <T> read(call: suspend (String) -> GmailClient.Read<T>): GmailClient.Read<T> {
            val first = call(accessToken() ?: return refused)
            if (first !is GmailClient.Read.Failed || first.key != GmailClient.UNAUTHORIZED || renewed) return first
            renewed = true
            google.connections.expireAccessToken(connection.id)
            connection = google.connections.findById(connection.id) ?: return refused
            token = null
            return call(accessToken() ?: return refused)
        }

        private suspend fun accessToken(): String? {
            token?.let { return it }
            return when (val result = google.tokens.accessToken(connection)) {
                is GoogleTokenProvider.Token.Ok -> result.accessToken.also { token = it }
                GoogleTokenProvider.Token.NeedsReconnect -> null.also { refused = GmailClient.Read.Failed(TOKEN_REFUSED, retryable = false) }
                is GoogleTokenProvider.Token.Unavailable -> null.also { refused = GmailClient.Read.Failed(TOKEN_UNAVAILABLE, retryable = true) }
            }
        }
    }

    /** The account itself, the company's other accounts and the address on its documents. */
    private suspend fun ownAddresses(tenant: Tenant, connection: IntegrationConnection): Set<String> = buildSet {
        add(connection.accountEmail)
        google.connections.list(tenant.id, IntegrationProviders.GOOGLE).forEach { add(it.accountEmail) }
        EmailAddresses.normalize(tenant.documentTemplate.email)?.let(::add)
    }

    companion object {
        /** Messages read per account per sync; the rest wait for the next one. */
        const val MAX_FETCHES = 50

        /** The account has to allow inbox reading (again) before it can be read. */
        const val MISSING_SCOPE = "missing_scope"
        const val SYNC_FAILED = "sync_failed"

        /** The token provider already asked the company to reconnect the account. */
        private const val TOKEN_REFUSED = "token_refused"
        private const val TOKEN_UNAVAILABLE = "token_unavailable"
        private const val MAX_PAGES = 20
        private const val MAX_NAME = 200
        private const val MAX_RECIPIENTS = 50
        private const val MAX_ATTACHMENTS = 30
        private val RESYNC_WINDOW = 7.days
        private val RESYNC_MARGIN = 1.hours
        private val SKIPPED_LABELS = setOf("SPAM", "TRASH", "DRAFT", "SENT", "CHAT")
        private val BULK_CATEGORIES = setOf("CATEGORY_PROMOTIONS", "CATEGORY_SOCIAL")
    }
}
