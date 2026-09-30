package com.rfm.edubot.integrations

import kotlinx.datetime.Instant
import org.bson.types.ObjectId

object IntegrationProviders {
    const val GOOGLE = "google"
}

enum class ConnectionStatus { ACTIVE, NEEDS_RECONNECT, REVOKED }

/** How email from this account reads: the name shown as the sender, where replies go, and the sign-off. */
data class EmailSettings(
    val senderName: String? = null,
    val replyTo: String? = null,
    val signature: String? = null,
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
    val createdAt: Instant,
    val updatedAt: Instant,
)
