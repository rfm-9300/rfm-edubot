package com.rfm.edubot.config

import java.util.concurrent.atomic.AtomicReference

/**
 * Env-loaded base config plus Mongo overrides. Call [get] wherever live values are needed.
 */
class RuntimeConfig(
    val base: AppConfig,
) {
    private val overridesRef = AtomicReference<Map<String, String>>(emptyMap())
    private val addedAdminEmailsRef = AtomicReference<Set<String>>(emptySet())
    private val currentRef = AtomicReference(base)

    fun get(): AppConfig = currentRef.get()

    fun overrides(): Map<String, String> = overridesRef.get()

    fun applyOverrides(overrides: Map<String, String>): AppConfig {
        val sanitized = overrides
            .filterKeys { PlatformSettingKey.fromKey(it) != null }
            .mapValues { it.value }
        overridesRef.set(sanitized)
        return recompute()
    }

    /** Backoffice sign-in emails added in the backoffice. They join the env's ADMIN_EMAILS, never replace them. */
    fun applyAddedAdminEmails(emails: Set<String>): AppConfig {
        addedAdminEmailsRef.set(emails)
        return recompute()
    }

    // Synchronized so a recompute always reads both inputs after the latest write to either.
    @Synchronized
    private fun recompute(): AppConfig {
        val merged = PlatformSettingsMerger.merge(base, overridesRef.get())
        val added = addedAdminEmailsRef.get()
        val google = merged.admin.googleSignIn
        val current = if (added.isEmpty()) merged else merged.copy(
            admin = merged.admin.copy(googleSignIn = google.copy(allowedEmails = google.allowedEmails + added)),
        )
        currentRef.set(current)
        return current
    }
}
