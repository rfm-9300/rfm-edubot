package com.rfm.edubot.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformSettingsMergerTest {
    private val base = AppConfig(
        port = 8080,
        whatsapp = AppConfig.WhatsAppConfig(
            verifyToken = "env-verify",
            appSecret = "env-wa-secret",
            phoneNumberId = "111",
            accessToken = "env-wa-token",
        ),
        instagram = AppConfig.InstagramConfig(
            appId = "ig-app",
            appSecret = "env-ig-secret",
            redirectUri = "https://example.com/cb",
        ),
        openrouter = AppConfig.OpenRouterConfig(
            apiKey = "env-or-key",
            primaryModel = "model-a",
            fallbackModel = "model-b",
            maxTokens = 512,
        ),
        mongo = AppConfig.MongoConfig(uri = "mongodb://localhost:27017", database = "wabot"),
        rateLimit = AppConfig.RateLimitConfig(perUserPerHour = 30, perUserPerDay = 200),
        admin = AppConfig.AdminConfig(
            jwtSecret = "env-jwt",
            jwtExpiryHours = 24,
            adminPasswordHash = "\$2a\$12\$envhash",
        ),
        pdfStoragePath = "/tmp/pdfs",
    )

    @Test
    fun `merge applies only known non-blank overrides`() {
        val merged = PlatformSettingsMerger.merge(
            base,
            mapOf(
                "OPENROUTER_PRIMARY_MODEL" to "model-override",
                "OPENROUTER_MAX_TOKENS" to "2048",
                "WA_ACCESS_TOKEN" to "  ",
                "NOT_A_KEY" to "ignored",
            ),
        )
        assertEquals("model-override", merged.openrouter.primaryModel)
        assertEquals(2048, merged.openrouter.maxTokens)
        assertEquals("env-wa-token", merged.whatsapp.accessToken)
        assertEquals(8080, merged.port)
        assertEquals("mongodb://localhost:27017", merged.mongo.uri)
    }

    @Test
    fun `effective value prefers override then env`() {
        val overrides = mapOf("WA_VERIFY_TOKEN" to "mongo-verify")
        assertEquals(
            "mongo-verify",
            PlatformSettingsMerger.effectiveValue(base, overrides, PlatformSettingKey.WA_VERIFY_TOKEN),
        )
        assertEquals(
            "env-verify",
            PlatformSettingsMerger.effectiveValue(base, emptyMap(), PlatformSettingKey.WA_VERIFY_TOKEN),
        )
    }

    @Test
    fun `mask hides secret bodies and keeps a short suffix`() {
        assertEquals("", PlatformSettingsMerger.mask(""))
        assertEquals("••••", PlatformSettingsMerger.mask("ab"))
        val masked = PlatformSettingsMerger.mask("super-secret-token")
        assertTrue(masked.startsWith("••••••••"))
        assertTrue(masked.endsWith("oken"))
    }

    @Test
    fun `runtime config hot-applies overrides`() {
        val runtime = RuntimeConfig(base)
        runtime.applyOverrides(mapOf("RATE_LIMIT_PER_HOUR" to "9"))
        assertEquals(9, runtime.get().rateLimit.perUserPerHour)
        runtime.applyOverrides(emptyMap())
        assertEquals(30, runtime.get().rateLimit.perUserPerHour)
    }
}
