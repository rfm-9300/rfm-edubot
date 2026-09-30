package com.rfm.edubot.integrations.google

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.integrations.IntegrationConnectionRepository
import com.rfm.edubot.integrations.TokenCipher
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import io.ktor.client.HttpClient

/** The Google pieces the integration routes, the email service and the inbox sync share. */
class GoogleIntegration(
    private val configProvider: () -> AppConfig.GoogleConfig,
    val cipher: TokenCipher?,
    val oauth: GoogleOAuthClient,
    val connections: IntegrationConnectionRepository,
    val tokens: GoogleTokenProvider,
) {
    val config: AppConfig.GoogleConfig get() = configProvider()

    /** Connecting needs the OAuth client and the key that seals its tokens. */
    val configured: Boolean get() = config.oauthEnabled && cipher != null

    companion object {
        fun create(
            mongo: MongoModule,
            configProvider: () -> AppConfig.GoogleConfig,
            cipher: TokenCipher?,
            httpClient: HttpClient,
            notifications: NotificationRepository,
        ): GoogleIntegration {
            val connections = IntegrationConnectionRepository(mongo)
            val oauth = GoogleOAuthClient(configProvider, httpClient)
            return GoogleIntegration(configProvider, cipher, oauth, connections, GoogleTokenProvider(connections, oauth, cipher, notifications))
        }
    }
}
