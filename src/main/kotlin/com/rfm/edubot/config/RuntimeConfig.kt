package com.rfm.edubot.config

import java.util.concurrent.atomic.AtomicReference

/**
 * Env-loaded base config plus Mongo overrides. Call [get] wherever live values are needed.
 */
class RuntimeConfig(
    val base: AppConfig,
) {
    private val overridesRef = AtomicReference<Map<String, String>>(emptyMap())
    private val currentRef = AtomicReference(base)

    fun get(): AppConfig = currentRef.get()

    fun overrides(): Map<String, String> = overridesRef.get()

    fun applyOverrides(overrides: Map<String, String>): AppConfig {
        val sanitized = overrides
            .filterKeys { PlatformSettingKey.fromKey(it) != null }
            .mapValues { it.value }
        overridesRef.set(sanitized)
        val merged = PlatformSettingsMerger.merge(base, sanitized)
        currentRef.set(merged)
        return merged
    }
}
