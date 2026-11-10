package com.rfm.edubot.integrations

import kotlinx.datetime.Instant
import org.bson.types.ObjectId

object IntegrationProviders {
    const val GOOGLE = "google"

    /**
     * The company's own address on the platform's mail domain (Amazon SES): the mail the company forwards
     * there comes in, and the company's email goes out from it. No tokens; one per company.
     */
    const val MAILBOX = "mailbox"
}

/** A Gmail forwarding request that reached the company's address: the code to type in Gmail, its link, and the Gmail account asking. */
data class ForwardingConfirmation(val code: String?, val link: String?, val account: String?, val at: Instant)

/** What reached a company's address: when last, how much, from which mailboxes, and a Gmail confirmation still to enter. */
data class MailboxState(
    val lastReceivedAt: Instant? = null,
    val received: Int = 0,
    /** Mailboxes seen forwarding here (Gmail says which), most recent first. */
    val forwarders: List<String> = emptyList(),
    val confirmation: ForwardingConfirmation? = null,
)

enum class ConnectionStatus { ACTIVE, NEEDS_RECONNECT, REVOKED }

/**
 * How email from this account reads: the name shown as the sender, where replies go, and the sign-off;
 * and whether its inbox is used in automations.
 */
data class EmailSettings(
    val senderName: String? = null,
    val replyTo: String? = null,
    val signature: String? = null,
    /** "Use my inbox in automations": new mail is read, matched to clients and announced as `email.received`. */
    val inboxSync: Boolean = false,
)

/**
 * Where reading the inbox got to. [historyId] is Gmail's cursor; without one the next sync starts from
 * [enabledAt], when inbox sync was turned on, so older mail is never read.
 */
data class InboxState(
    val historyId: String? = null,
    val enabledAt: Instant? = null,
    val lastSyncedAt: Instant? = null,
    /** Why the last sync failed, e.g. `missing_scope`; cleared by the next one that works. */
    val lastError: String? = null,
)

/**
 * A company's email account (`integration_connections`), one document per account: a Google account it
 * connected, whose tokens are sealed with [TokenCipher] and never leave the server, or its own address
 * on the platform's mail domain ([IntegrationProviders.MAILBOX]).
 */
data class IntegrationConnection(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val provider: String,
    val accountEmail: String,
    val scopes: List<String>,
    val status: ConnectionStatus = ConnectionStatus.ACTIVE,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val accessTokenExpiresAt: Instant? = null,
    /** The dashboard user who consented last; the tokens act as their Google account. */
    val connectedByUserId: String? = null,
    val connectedByEmail: String? = null,
    val settings: EmailSettings = EmailSettings(),
    /** The company's sender when an email doesn't name an account. */
    val isDefault: Boolean = false,
    /** Why the connection stopped working, e.g. `invalid_grant`. */
    val lastError: String? = null,
    val dailySends: DailySends? = null,
    val inbox: InboxState = InboxState(),
    /** A company address's ([IntegrationProviders.MAILBOX]) received mail. */
    val mailbox: MailboxState = MailboxState(),
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Emails sent from the account on [day] (the company's date, `yyyy-mm-dd`). */
    fun sentOn(day: String): Int = dailySends?.takeIf { it.day == day }?.count ?: 0
}

/** How many emails went out from the account on [day], for the company's daily cap. */
data class DailySends(val day: String, val count: Int)
