package com.rfm.edubot.config

import at.favre.lib.crypto.bcrypt.BCrypt
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

class PlatformSettingsService(
    private val repository: PlatformSettingsRepository,
    private val runtime: RuntimeConfig,
) {
    private val log = LoggerFactory.getLogger("PlatformSettingsService")

    suspend fun initialize() {
        val doc = repository.load()
        runtime.applyOverrides(doc.overrides)
        log.info("Platform settings loaded: {} overrides", doc.overrides.size)
    }

    suspend fun list(): PlatformSettingsResponse {
        val overrides = runtime.overrides()
        val base = runtime.base
        val settings = PlatformSettingKey.entries.map { key ->
            val effective = PlatformSettingsMerger.effectiveValue(base, overrides, key)
            val overridden = overrides.containsKey(key.key)
            PlatformSettingDto(
                key = key.key,
                category = key.category,
                secret = key.secret,
                value = if (key.secret) PlatformSettingsMerger.mask(effective) else effective,
                hasValue = effective.isNotBlank(),
                source = if (overridden) "override" else "env",
            )
        }
        return PlatformSettingsResponse(
            settings = settings,
            updatedAt = repository.load().updatedAt?.toString(),
        )
    }

    suspend fun reveal(keyName: String): RevealResponse {
        val key = PlatformSettingKey.fromKey(keyName) ?: throw IllegalArgumentException("unknown key")
        if (!key.secret) throw IllegalArgumentException("key is not secret")
        val value = PlatformSettingsMerger.effectiveValue(runtime.base, runtime.overrides(), key)
        return RevealResponse(key = key.key, value = value)
    }

    suspend fun update(request: PlatformSettingsUpdateRequest): PlatformSettingsResponse {
        val current = runtime.overrides().toMutableMap()
        request.clear.orEmpty().forEach { keyName ->
            if (PlatformSettingKey.fromKey(keyName) == null) throw IllegalArgumentException("unknown key: $keyName")
            current.remove(keyName)
        }
        request.updates.orEmpty().forEach { (keyName, rawValue) ->
            val key = PlatformSettingKey.fromKey(keyName) ?: throw IllegalArgumentException("unknown key: $keyName")
            val value = rawValue.trim()
            if (value.isBlank()) {
                // Blank secret/field means "leave unchanged"
                return@forEach
            }
            if (key.secret && looksMasked(value)) {
                return@forEach
            }
            current[key.key] = normalizeValue(key, value)
        }
        val saved = repository.save(current)
        runtime.applyOverrides(saved.overrides)
        log.info("Platform settings updated: {} overrides", saved.overrides.size)
        return list()
    }

    suspend fun reload(): PlatformSettingsResponse {
        initialize()
        return list()
    }

    private fun normalizeValue(key: PlatformSettingKey, value: String): String {
        if (key == PlatformSettingKey.ADMIN_PASSWORD_HASH && !value.startsWith("\$2")) {
            return BCrypt.withDefaults().hashToString(12, value.toCharArray())
        }
        if (key == PlatformSettingKey.OPENROUTER_MAX_TOKENS ||
            key == PlatformSettingKey.RATE_LIMIT_PER_HOUR ||
            key == PlatformSettingKey.RATE_LIMIT_PER_DAY ||
            key == PlatformSettingKey.ADMIN_JWT_EXPIRY_HOURS
        ) {
            value.toIntOrNull() ?: throw IllegalArgumentException("${key.key} must be an integer")
        }
        return value
    }

    private fun looksMasked(value: String): Boolean = value.contains('•') || value.contains('*') && value.length <= 16
}

@Serializable
data class PlatformSettingDto(
    val key: String,
    val category: String,
    val secret: Boolean,
    val value: String,
    val hasValue: Boolean,
    val source: String,
)

@Serializable
data class PlatformSettingsResponse(
    val settings: List<PlatformSettingDto>,
    val updatedAt: String? = null,
)

@Serializable
data class PlatformSettingsUpdateRequest(
    val updates: Map<String, String>? = null,
    val clear: List<String>? = null,
)

@Serializable
data class RevealResponse(
    val key: String,
    val value: String,
)
