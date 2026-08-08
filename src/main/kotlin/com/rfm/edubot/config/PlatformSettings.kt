package com.rfm.edubot.config

/**
 * Catalog of platform settings that can be overridden in Mongo at runtime.
 * Bootstrap-critical keys (Mongo URI, listen port) stay env-only.
 */
enum class PlatformSettingKey(
    val key: String,
    val category: String,
    val secret: Boolean = false,
) {
    WA_VERIFY_TOKEN("WA_VERIFY_TOKEN", "whatsapp", secret = true),
    WA_APP_SECRET("WA_APP_SECRET", "whatsapp", secret = true),
    WA_PHONE_NUMBER_ID("WA_PHONE_NUMBER_ID", "whatsapp"),
    WA_ACCESS_TOKEN("WA_ACCESS_TOKEN", "whatsapp", secret = true),
    WA_API_VERSION("WA_API_VERSION", "whatsapp"),
    WHATSAPP_APP_ID("WHATSAPP_APP_ID", "whatsapp"),
    WA_ES_CONFIG_ID("WA_ES_CONFIG_ID", "whatsapp"),

    IG_APP_ID("IG_APP_ID", "instagram"),
    IG_APP_SECRET("IG_APP_SECRET", "instagram", secret = true),
    IG_OAUTH_REDIRECT("IG_OAUTH_REDIRECT", "instagram"),
    IG_GRAPH_VERSION("IG_GRAPH_VERSION", "instagram"),

    OPENROUTER_API_KEY("OPENROUTER_API_KEY", "openrouter", secret = true),
    OPENROUTER_PRIMARY_MODEL("OPENROUTER_PRIMARY_MODEL", "openrouter"),
    OPENROUTER_FALLBACK_MODEL("OPENROUTER_FALLBACK_MODEL", "openrouter"),
    OPENROUTER_MAX_TOKENS("OPENROUTER_MAX_TOKENS", "openrouter"),

    RATE_LIMIT_PER_HOUR("RATE_LIMIT_PER_HOUR", "ratelimit"),
    RATE_LIMIT_PER_DAY("RATE_LIMIT_PER_DAY", "ratelimit"),

    PDF_STORAGE_PATH("PDF_STORAGE_PATH", "pdf"),

    ADMIN_JWT_SECRET("ADMIN_JWT_SECRET", "admin", secret = true),
    ADMIN_JWT_EXPIRY_HOURS("ADMIN_JWT_EXPIRY_HOURS", "admin"),
    ADMIN_PASSWORD_HASH("ADMIN_PASSWORD_HASH", "admin", secret = true),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun fromKey(key: String): PlatformSettingKey? = byKey[key]
        val allKeys: List<String> = entries.map { it.key }
    }
}

object PlatformSettingsMerger {
    fun merge(base: AppConfig, overrides: Map<String, String>): AppConfig {
        fun o(key: PlatformSettingKey): String? = overrides[key.key]?.takeIf { it.isNotBlank() }

        return base.copy(
            whatsapp = base.whatsapp.copy(
                verifyToken = o(PlatformSettingKey.WA_VERIFY_TOKEN) ?: base.whatsapp.verifyToken,
                appSecret = o(PlatformSettingKey.WA_APP_SECRET) ?: base.whatsapp.appSecret,
                phoneNumberId = o(PlatformSettingKey.WA_PHONE_NUMBER_ID) ?: base.whatsapp.phoneNumberId,
                accessToken = o(PlatformSettingKey.WA_ACCESS_TOKEN) ?: base.whatsapp.accessToken,
                apiVersion = o(PlatformSettingKey.WA_API_VERSION) ?: base.whatsapp.apiVersion,
                embeddedSignup = base.whatsapp.embeddedSignup.copy(
                    appId = o(PlatformSettingKey.WHATSAPP_APP_ID) ?: base.whatsapp.embeddedSignup.appId,
                    configId = o(PlatformSettingKey.WA_ES_CONFIG_ID) ?: base.whatsapp.embeddedSignup.configId,
                ),
            ),
            instagram = base.instagram.copy(
                appId = o(PlatformSettingKey.IG_APP_ID) ?: base.instagram.appId,
                appSecret = o(PlatformSettingKey.IG_APP_SECRET) ?: base.instagram.appSecret,
                redirectUri = o(PlatformSettingKey.IG_OAUTH_REDIRECT) ?: base.instagram.redirectUri,
                graphVersion = o(PlatformSettingKey.IG_GRAPH_VERSION) ?: base.instagram.graphVersion,
            ),
            openrouter = base.openrouter.copy(
                apiKey = o(PlatformSettingKey.OPENROUTER_API_KEY) ?: base.openrouter.apiKey,
                primaryModel = o(PlatformSettingKey.OPENROUTER_PRIMARY_MODEL) ?: base.openrouter.primaryModel,
                fallbackModel = o(PlatformSettingKey.OPENROUTER_FALLBACK_MODEL) ?: base.openrouter.fallbackModel,
                maxTokens = o(PlatformSettingKey.OPENROUTER_MAX_TOKENS)?.toIntOrNull() ?: base.openrouter.maxTokens,
            ),
            rateLimit = base.rateLimit.copy(
                perUserPerHour = o(PlatformSettingKey.RATE_LIMIT_PER_HOUR)?.toIntOrNull() ?: base.rateLimit.perUserPerHour,
                perUserPerDay = o(PlatformSettingKey.RATE_LIMIT_PER_DAY)?.toIntOrNull() ?: base.rateLimit.perUserPerDay,
            ),
            pdfStoragePath = o(PlatformSettingKey.PDF_STORAGE_PATH) ?: base.pdfStoragePath,
            admin = base.admin.copy(
                jwtSecret = o(PlatformSettingKey.ADMIN_JWT_SECRET) ?: base.admin.jwtSecret,
                jwtExpiryHours = o(PlatformSettingKey.ADMIN_JWT_EXPIRY_HOURS)?.toIntOrNull() ?: base.admin.jwtExpiryHours,
                adminPasswordHash = o(PlatformSettingKey.ADMIN_PASSWORD_HASH) ?: base.admin.adminPasswordHash,
            ),
        )
    }

    fun effectiveValue(base: AppConfig, overrides: Map<String, String>, key: PlatformSettingKey): String {
        overrides[key.key]?.takeIf { it.isNotBlank() }?.let { return it }
        return readBase(base, key)
    }

    fun readBase(base: AppConfig, key: PlatformSettingKey): String = when (key) {
        PlatformSettingKey.WA_VERIFY_TOKEN -> base.whatsapp.verifyToken
        PlatformSettingKey.WA_APP_SECRET -> base.whatsapp.appSecret
        PlatformSettingKey.WA_PHONE_NUMBER_ID -> base.whatsapp.phoneNumberId
        PlatformSettingKey.WA_ACCESS_TOKEN -> base.whatsapp.accessToken
        PlatformSettingKey.WA_API_VERSION -> base.whatsapp.apiVersion
        PlatformSettingKey.WHATSAPP_APP_ID -> base.whatsapp.embeddedSignup.appId
        PlatformSettingKey.WA_ES_CONFIG_ID -> base.whatsapp.embeddedSignup.configId
        PlatformSettingKey.IG_APP_ID -> base.instagram.appId
        PlatformSettingKey.IG_APP_SECRET -> base.instagram.appSecret
        PlatformSettingKey.IG_OAUTH_REDIRECT -> base.instagram.redirectUri
        PlatformSettingKey.IG_GRAPH_VERSION -> base.instagram.graphVersion
        PlatformSettingKey.OPENROUTER_API_KEY -> base.openrouter.apiKey
        PlatformSettingKey.OPENROUTER_PRIMARY_MODEL -> base.openrouter.primaryModel
        PlatformSettingKey.OPENROUTER_FALLBACK_MODEL -> base.openrouter.fallbackModel
        PlatformSettingKey.OPENROUTER_MAX_TOKENS -> base.openrouter.maxTokens.toString()
        PlatformSettingKey.RATE_LIMIT_PER_HOUR -> base.rateLimit.perUserPerHour.toString()
        PlatformSettingKey.RATE_LIMIT_PER_DAY -> base.rateLimit.perUserPerDay.toString()
        PlatformSettingKey.PDF_STORAGE_PATH -> base.pdfStoragePath
        PlatformSettingKey.ADMIN_JWT_SECRET -> base.admin.jwtSecret
        PlatformSettingKey.ADMIN_JWT_EXPIRY_HOURS -> base.admin.jwtExpiryHours.toString()
        PlatformSettingKey.ADMIN_PASSWORD_HASH -> base.admin.adminPasswordHash
    }

    fun mask(value: String): String {
        if (value.isBlank()) return ""
        if (value.length <= 4) return "••••"
        return "••••••••" + value.takeLast(4)
    }
}
