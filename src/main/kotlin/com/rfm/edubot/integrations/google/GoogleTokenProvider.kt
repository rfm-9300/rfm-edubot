package com.rfm.edubot.integrations.google

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.integrations.ConnectionStatus
import com.rfm.edubot.integrations.IntegrationConnection
import com.rfm.edubot.integrations.IntegrationConnectionRepository
import com.rfm.edubot.integrations.TokenCipher
import com.rfm.edubot.notifications.NotificationKinds
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Hands out a working access token for a Google connection, refreshing it when under five minutes remain.
 * One refresh runs at a time per connection (the lock is in memory: a single app instance, like the OAuth
 * nonces). When Google stops honouring the grant, the connection needs a reconnect and its admins hear once.
 */
class GoogleTokenProvider(
    private val connections: IntegrationConnectionRepository,
    private val oauth: GoogleOAuthClient,
    private val cipher: TokenCipher?,
    private val notifications: NotificationRepository,
    private val clock: () -> Instant = SystemClock::now,
) {
    sealed interface Token {
        data class Ok(val accessToken: String) : Token

        data object NeedsReconnect : Token

        /** Google or the key is unavailable for now; the connection itself is fine. */
        data class Unavailable(val reason: String) : Token
    }

    private val log = LoggerFactory.getLogger("GoogleTokenProvider")
    private val locks = ConcurrentHashMap<ObjectId, Mutex>()

    suspend fun accessToken(connection: IntegrationConnection): Token {
        val cipher = cipher ?: return Token.Unavailable("not_configured")
        if (connection.status != ConnectionStatus.ACTIVE) return Token.NeedsReconnect
        usable(connection, cipher)?.let { return Token.Ok(it) }
        return locks.computeIfAbsent(connection.id) { Mutex() }.withLock {
            val current = connections.findById(connection.id) ?: return@withLock Token.NeedsReconnect
            if (current.status != ConnectionStatus.ACTIVE) return@withLock Token.NeedsReconnect
            usable(current, cipher)?.let { return@withLock Token.Ok(it) }
            val sealedRefresh = current.refreshToken
            val refreshToken = sealedRefresh?.let(cipher::open) ?: return@withLock needsReconnect(current, "token_unreadable")
            when (val refreshed = oauth.refresh(refreshToken)) {
                is GoogleOAuthClient.Refresh.Ok -> {
                    connections.saveAccessToken(
                        current.id,
                        cipher.seal(refreshed.accessToken),
                        clock() + refreshed.expiresInSeconds.seconds,
                        refreshToken = if (cipher.isStale(sealedRefresh)) cipher.seal(refreshToken) else null,
                    )
                    Token.Ok(refreshed.accessToken)
                }
                GoogleOAuthClient.Refresh.InvalidGrant -> needsReconnect(current, "invalid_grant")
                is GoogleOAuthClient.Refresh.Failed -> Token.Unavailable(refreshed.reason)
            }
        }
    }

    private fun usable(connection: IntegrationConnection, cipher: TokenCipher): String? {
        val expiresAt = connection.accessTokenExpiresAt ?: return null
        if (expiresAt - clock() < REFRESH_MARGIN) return null
        return connection.accessToken?.let(cipher::open)
    }

    /** The connection needs a person to reconnect it; its admins hear about it once. */
    suspend fun needsReconnect(connection: IntegrationConnection, reason: String): Token {
        if (connections.markNeedsReconnect(connection.id, reason)) {
            log.warn("Google connection needs a reconnect: tenant={} connection={} reason={}", connection.tenantId, connection.id, reason)
            runCatching {
                notifications.notify(
                    tenantId = connection.tenantId,
                    kind = NotificationKinds.INTEGRATION_RECONNECT,
                    params = mapOf("integration" to GMAIL, "account" to connection.accountEmail),
                    link = DashboardModules.SETTINGS,
                )
            }.onFailure { log.warn("Could not notify about the Google reconnect: {}", it.message) }
        }
        return Token.NeedsReconnect
    }

    companion object {
        val REFRESH_MARGIN = 5.minutes

        /** The integration name the dashboard translates (`app.agents.integrations.GMAIL`). */
        const val GMAIL = "GMAIL"
    }
}
