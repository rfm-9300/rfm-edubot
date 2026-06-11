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
) {
    data class WhatsAppConfig(
        val verifyToken: String,
        val appSecret: String,
        val phoneNumberId: String,
        val accessToken: String,
        val apiVersion: String = "v21.0",
    )

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
        val graphVersion: String = "v21.0",
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
        val adminPasswordHash: String,
    )

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
            )

            val instagramConfig = InstagramConfig(
                appId = getOptional(config, "app.instagram.appId"),
                appSecret = getOptional(config, "app.instagram.appSecret"),
                redirectUri = getOptional(config, "app.instagram.redirectUri"),
                graphVersion = if (config.hasPath("app.instagram.graphVersion")) config.getString("app.instagram.graphVersion") else "v21.0",
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
                adminPasswordHash = getRequired(config, "app.admin.adminPasswordHash"),
            )

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

        private fun logStartupKeys(config: Config) {
            val keys = listOf(
                "ktor.deployment.port",
                "app.whatsapp.verifyToken",
                "app.whatsapp.appSecret",
                "app.whatsapp.phoneNumberId",
                "app.whatsapp.accessToken",
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
                "app.pdf.storagePath",
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
