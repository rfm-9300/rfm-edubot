package com.rfm.edubot.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

data class AppConfig(
    val port: Int,
    val whatsapp: WhatsAppConfig,
    val instagram: InstagramConfig,
    val openrouter: OpenRouterConfig,
    val mongo: MongoConfig,
    val rateLimit: RateLimitConfig,
    val admin: AdminConfig,
    val pdfStoragePath: String,
    val backups: BackupConfig = BackupConfig(),
    val agents: AgentsConfig = AgentsConfig(),
    val google: GoogleConfig = GoogleConfig(),
    val integrations: IntegrationsConfig = IntegrationsConfig(),
) {
    /** The agents runtime: how often the scheduler ticks and how many runs work at once. */
    data class AgentsConfig(
        val tickSeconds: Int = 30,
        val lanes: Int = 4,
        val maxConcurrentRunsPerCompany: Int = 2,
    )

    /**
     * Google OAuth for connecting a company's Gmail account (a Web application client, separate from
     * the Firebase sign-in). Blank values turn the integration off; the app still boots.
     */
    data class GoogleConfig(
        val clientId: String = "",
        val clientSecret: String = "",
        val redirectUri: String = "",
        /** Pub/Sub topic for Gmail push notifications; blank keeps polling. */
        val pubsubTopic: String = "",
        /**
         * Companies may turn on "Use my inbox in automations", which asks for Gmail's *restricted* read
         * scopes. Off until the OAuth client passed Google's verification and CASA assessment for them.
         */
        val inboxEnabled: Boolean = false,
        /** How often connected inboxes are polled for new mail. */
        val syncSeconds: Int = 60,
    ) {
        val oauthEnabled: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank() && redirectUri.isNotBlank()
    }

    /** Base64 key (32 bytes) that encrypts integration tokens at rest. Env-only; required once Google OAuth is on. */
    data class IntegrationsConfig(val encryptionKey: String = "")

    /**
     * Where the host's backup archives and the backup runner's control files are mounted in the app
     * container (docker-compose.prod.yml). Blank = the backoffice says backups aren't set up here.
     */
    data class BackupConfig(
        val archiveDir: String = "",
        val controlDir: String = "",
    )

    data class WhatsAppConfig(
        val verifyToken: String,
        val appSecret: String,
        val phoneNumberId: String,
        val accessToken: String,
        val apiVersion: String = "v21.0",
        val embeddedSignup: EmbeddedSignupConfig = EmbeddedSignupConfig(),
    ) {
        data class EmbeddedSignupConfig(
            val appId: String = "",
            val configId: String = "",
        ) {
            val enabled: Boolean get() = appId.isNotBlank() && configId.isNotBlank()
        }
    }

    /**
     * Instagram-Login OAuth onboarding (see docs/plan-instagram-oauth-onboarding.md).
     * Uses a separate Instagram app id/secret (NOT the WhatsApp/Facebook app secret).
     * All fields optional: when [appId]/[appSecret]/[redirectUri] are blank the OAuth
     * onboarding feature is disabled (the connect route returns 503), but the app still boots.
     */
    data class InstagramConfig(
        val appId: String = "",
        val appSecret: String = "",
        val redirectUri: String = "",
        val graphVersion: String = "v25.0",
    ) {
        val oauthEnabled: Boolean get() = appId.isNotBlank() && appSecret.isNotBlank() && redirectUri.isNotBlank()
    }

    data class OpenRouterConfig(
        val apiKey: String,
        val primaryModel: String = "google/gemini-flash-1.5",
        val fallbackModel: String = "qwen/qwen-2.5-7b-instruct",
        val maxTokens: Int = 1024,
    )

    data class MongoConfig(
        val uri: String,
        val database: String = "wabot",
    )

    data class RateLimitConfig(
        val perUserPerHour: Int = 30,
        val perUserPerDay: Int = 200,
    )

    data class AdminConfig(
        val jwtSecret: String,
        val jwtIssuer: String = "wabot-platform",
        val jwtExpiryHours: Int = 24,
        /** Blank turns password login off. */
        val adminPasswordHash: String = "",
        val googleSignIn: GoogleSignInConfig = GoogleSignInConfig(),
    ) {
        val passwordLoginEnabled: Boolean get() = adminPasswordHash.isNotBlank()
    }

    /**
     * Sign-in with Google through Firebase Auth. The web values are public (they go to the browser);
     * access is decided server-side: [allowedEmails] for the backoffice, the user's own linked
     * account (or verified email) for the tenant dashboard.
     */
    data class GoogleSignInConfig(
        val firebaseProjectId: String = "",
        val webApiKey: String = "",
        val authDomain: String = "",
        val appId: String = "",
        val allowedEmails: Set<String> = emptySet(),
    ) {
        /** Backoffice sign-in: the Firebase web app plus an operator allowlist. */
        val enabled: Boolean get() = webConfigured && allowedEmails.isNotEmpty()

        /** Tenant dashboard sign-in only needs the Firebase web app; users are matched to their own accounts. */
        val webConfigured: Boolean get() = firebaseProjectId.isNotBlank() && webApiKey.isNotBlank()
    }

    companion object {
        private val log = LoggerFactory.getLogger("AppConfig")

        fun load(): AppConfig {
            val config = ConfigFactory.load()

            val port = config.getInt("ktor.deployment.port")

            val whatsAppConfig = WhatsAppConfig(
                verifyToken = getRequired(config, "app.whatsapp.verifyToken"),
                appSecret = getRequired(config, "app.whatsapp.appSecret"),
                phoneNumberId = getRequired(config, "app.whatsapp.phoneNumberId"),
                accessToken = getRequired(config, "app.whatsapp.accessToken"),
                apiVersion = config.getString("app.whatsapp.apiVersion"),
                embeddedSignup = AppConfig.WhatsAppConfig.EmbeddedSignupConfig(
                    appId = getOptional(config, "app.whatsapp.embeddedSignup.appId"),
                    configId = getOptional(config, "app.whatsapp.embeddedSignup.configId"),
                ),
            )

            val instagramConfig = InstagramConfig(
                appId = getOptional(config, "app.instagram.appId"),
                appSecret = getOptional(config, "app.instagram.appSecret"),
                redirectUri = getOptional(config, "app.instagram.redirectUri"),
                graphVersion = if (config.hasPath("app.instagram.graphVersion")) config.getString("app.instagram.graphVersion") else "v25.0",
            )

            val openRouterConfig = OpenRouterConfig(
                apiKey = getRequired(config, "app.openrouter.apiKey"),
                primaryModel = config.getString("app.openrouter.primaryModel"),
                fallbackModel = config.getString("app.openrouter.fallbackModel"),
                maxTokens = config.getInt("app.openrouter.maxTokens"),
            )

            val mongoConfig = MongoConfig(
                uri = getRequired(config, "app.mongo.uri"),
                database = config.getString("app.mongo.database"),
            )

            val rateLimitConfig = RateLimitConfig(
                perUserPerHour = config.getInt("app.ratelimit.perUserPerHour"),
                perUserPerDay = config.getInt("app.ratelimit.perUserPerDay"),
            )

            val adminConfig = AdminConfig(
                jwtSecret = getRequired(config, "app.admin.jwtSecret"),
                jwtIssuer = config.getString("app.admin.jwtIssuer"),
                jwtExpiryHours = config.getInt("app.admin.jwtExpiryHours"),
                adminPasswordHash = getOptional(config, "app.admin.adminPasswordHash"),
                googleSignIn = GoogleSignInConfig(
                    firebaseProjectId = getOptional(config, "app.admin.google.firebaseProjectId").trim(),
                    webApiKey = getOptional(config, "app.admin.google.webApiKey").trim(),
                    authDomain = getOptional(config, "app.admin.google.authDomain").trim(),
                    appId = getOptional(config, "app.admin.google.appId").trim(),
                    allowedEmails = parseEmails(getOptional(config, "app.admin.google.allowedEmails")),
                ),
            )
            if (!adminConfig.passwordLoginEnabled && !adminConfig.googleSignIn.enabled) {
                log.warn("No backoffice sign-in method is configured: set ADMIN_EMAILS and FIREBASE_* for Google, or ADMIN_PASSWORD_HASH")
            }

            logStartupKeys(config)

            return AppConfig(
                port = port,
                whatsapp = whatsAppConfig,
                instagram = instagramConfig,
                openrouter = openRouterConfig,
                mongo = mongoConfig,
                rateLimit = rateLimitConfig,
                admin = adminConfig,
                pdfStoragePath = config.getString("app.pdf.storagePath"),
                backups = BackupConfig(
                    archiveDir = getOptional(config, "app.backups.archiveDir").trim(),
                    controlDir = getOptional(config, "app.backups.controlDir").trim(),
                ),
                agents = AgentsConfig(
                    tickSeconds = getOptional(config, "app.agents.tickSeconds").trim().toIntOrNull()?.coerceIn(5, 600) ?: 30,
                    lanes = getOptional(config, "app.agents.lanes").trim().toIntOrNull()?.coerceIn(1, 32) ?: 4,
                    maxConcurrentRunsPerCompany = getOptional(config, "app.agents.maxConcurrentRunsPerCompany").trim().toIntOrNull()?.coerceIn(1, 16) ?: 2,
                ),
                google = GoogleConfig(
                    clientId = getOptional(config, "app.google.oauth.clientId").trim(),
                    clientSecret = getOptional(config, "app.google.oauth.clientSecret").trim(),
                    redirectUri = getOptional(config, "app.google.oauth.redirectUri").trim(),
                    pubsubTopic = getOptional(config, "app.google.gmail.pubsubTopic").trim(),
                    inboxEnabled = getOptional(config, "app.google.gmail.inboxEnabled").trim().lowercase() in setOf("true", "1", "yes"),
                    syncSeconds = getOptional(config, "app.google.gmail.syncSeconds").trim().toIntOrNull()?.coerceIn(15, 3600) ?: 60,
                ),
                integrations = IntegrationsConfig(encryptionKey = getOptional(config, "app.integrations.encryptionKey").trim()),
            )
        }

        private fun getRequired(config: Config, path: String): String {
            if (!config.hasPath(path) || config.getString(path).isBlank()) {
                log.error("Required config key missing or empty: $path")
                throw IllegalStateException("Missing required config: $path")
            }
            return config.getString(path)
        }

        private fun getOptional(config: Config, path: String): String =
            if (config.hasPath(path)) config.getString(path) else ""

        internal fun parseEmails(raw: String): Set<String> =
            raw.split(',', ';', ' ').map { it.trim().lowercase() }.filter { it.contains('@') }.toSet()

        private fun logStartupKeys(config: Config) {
            val keys = listOf(
                "ktor.deployment.port",
                "app.whatsapp.verifyToken",
                "app.whatsapp.appSecret",
                "app.whatsapp.phoneNumberId",
                "app.whatsapp.accessToken",
                "app.whatsapp.embeddedSignup.appId",
                "app.whatsapp.embeddedSignup.configId",
                "app.instagram.appId",
                "app.instagram.appSecret",
                "app.instagram.redirectUri",
                "app.openrouter.apiKey",
                "app.openrouter.primaryModel",
                "app.mongo.uri",
                "app.mongo.database",
                "app.ratelimit.perUserPerHour",
                "app.ratelimit.perUserPerDay",
                "app.admin.jwtSecret",
                "app.admin.jwtIssuer",
                "app.admin.jwtExpiryHours",
                "app.admin.adminPasswordHash",
                "app.admin.google.firebaseProjectId",
                "app.admin.google.webApiKey",
                "app.admin.google.authDomain",
                "app.admin.google.appId",
                "app.admin.google.allowedEmails",
                "app.pdf.storagePath",
                "app.agents.tickSeconds",
                "app.google.oauth.clientId",
                "app.google.oauth.clientSecret",
                "app.google.oauth.redirectUri",
                "app.integrations.encryptionKey",
            )

            val present = keys.filter { config.hasPath(it) }
            val missing = keys - present.toSet()

            log.info("Config loaded: {} keys present, {} missing", present.size, missing.size)
            if (missing.isNotEmpty()) {
                log.warn("Missing config keys: {}", missing)
            }
        }
    }
}
