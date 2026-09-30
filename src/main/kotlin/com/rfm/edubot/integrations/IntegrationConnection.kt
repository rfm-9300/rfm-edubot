package com.rfm.edubot.integrations

import kotlinx.datetime.Instant
import org.bson.types.ObjectId

object IntegrationProviders {
    const val GOOGLE = "google"
}

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
 * A company's account at an outside provider (`integration_connections`), one document per account.
 * The tokens are sealed with [TokenCipher] and never leave the server.
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
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Emails sent from the account on [day] (the company's date, `yyyy-mm-dd`). */
    fun sentOn(day: String): Int = dailySends?.takeIf { it.day == day }?.count ?: 0
}

/** How many emails went out from the account on [day], for the company's daily cap. */
data class DailySends(val day: String, val count: Int)
