package com.rfm.edubot.mobile.core.localization

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.SessionError
import com.rfm.edubot.mobile.core.common.VoiceInputError

enum class AppLocale(val tag: String) {
    English("en"),
    Portuguese("pt-PT"),
    Spanish("es"),
    ;

    companion object {
        /** The tenant locale and the device locale both arrive as tags like `pt-BR` or `es_ES`. */
        fun of(tag: String?): AppLocale = when (tag?.lowercase()?.replace('_', '-')?.substringBefore('-')) {
            "pt" -> Portuguese
            "es" -> Spanish
            else -> English
        }
    }
}

/**
 * Looked-up copy for one locale.
 *
 * Keys are the constants in [Txt], so a call site cannot invent one that no catalog has. A key the
 * current locale is missing falls back to English rather than rendering blank, and a key no catalog
 * has renders as itself — loud enough to be caught in review instead of shipping as empty space.
 */
class Strings internal constructor(
    val locale: AppLocale,
    private val values: Map<String, String>,
    private val fallback: Map<String, String>,
) {
    operator fun get(key: String): String = values[key] ?: fallback[key] ?: key

    /** Replaces `{name}` placeholders, the same convention the web catalogs use. */
    fun format(key: String, vararg args: Pair<String, Any?>): String =
        args.fold(get(key)) { text, (name, value) -> text.replace("{$name}", value?.toString().orEmpty()) }

    /** Picks `<key>.one` or `<key>.other` and substitutes `{count}`. */
    fun plural(key: String, count: Int): String =
        format(if (count == 1) "$key.one" else "$key.other", "count" to count)

    /** The module's name in the navigation; an unknown id falls back to the id itself. */
    fun module(id: String): String = get("module.$id")

    fun moduleSubtitle(id: String): String = get("module.$id.subtitle")

    /**
     * The bottom bar's label, which has about ten characters before it elides. Falls back to the
     * full name where that already fits — only the languages that need a shorter word define one.
     */
    fun moduleShort(id: String): String {
        val key = "module.$id.short"
        return values[key] ?: fallback[key] ?: module(id)
    }

    fun error(error: SessionError): String = get(
        when (error) {
            SessionError.MISSING_CREDENTIALS -> Txt.LOGIN_ERROR_MISSING
            SessionError.INVALID_CREDENTIALS -> Txt.LOGIN_ERROR_INVALID
            SessionError.SESSION_EXPIRED -> Txt.LOGIN_ERROR_EXPIRED
            SessionError.ACCOUNT_INACTIVE -> Txt.LOGIN_ERROR_INACTIVE
            SessionError.CONNECTION_FAILED -> Txt.LOGIN_ERROR_CONNECTION
        },
    )

    /**
     * What to tell someone about a failed call. [AppError.Rejected] prefers a message for the
     * backend's own error code, so `tax_id_required` reads as a missing NIF rather than
     * "something went wrong".
     */
    fun error(error: AppError): String = when (error) {
        AppError.Unauthorized -> get(Txt.ERROR_UNAUTHORIZED)
        AppError.Forbidden -> get(Txt.ERROR_FORBIDDEN)
        AppError.NotFound -> get(Txt.ERROR_NOT_FOUND)
        is AppError.Offline -> get(Txt.ERROR_OFFLINE)
        is AppError.Unavailable -> get(Txt.ERROR_SERVER)
        is AppError.Rejected -> rejection(error.code)
    }

    private fun rejection(code: String): String {
        if (code.isBlank()) return get(Txt.ERROR_REJECTED)
        val key = "error.code.$code"
        return values[key] ?: fallback[key] ?: get(Txt.ERROR_REJECTED)
    }

    fun voiceError(error: VoiceInputError): String = get(
        when (error) {
            VoiceInputError.PERMISSION_DENIED -> Txt.VOICE_PERMISSION_DENIED
            VoiceInputError.UNAVAILABLE -> Txt.VOICE_UNAVAILABLE
            VoiceInputError.RECOGNITION_FAILED -> Txt.VOICE_FAILED
        },
    )

    /** A shift's alert (`OUTSIDE_SITE`, `UNVERIFIED`…); a code with no copy shows as itself. */
    fun timeFlag(code: String): String = values["time.flag.$code"] ?: fallback["time.flag.$code"] ?: code

    /** Status enums arrive from the backend as names; a status with no copy shows title-cased. */
    fun status(status: String): String {
        val key = "status.${status.uppercase()}"
        return values[key] ?: fallback[key] ?: status.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** Monday-first weekday name for `AvailabilityRule.dayOfWeek` (1..7). */
    fun weekday(dayOfWeek: Int): String = get("weekday.${dayOfWeek.coerceIn(1, 7)}")

    /**
     * Why a Home queue row needs a person, keyed by the `kind` the overview sends. An unknown kind
     * falls back to the module's name, which is still useful.
     */
    fun attention(kind: String, moduleId: String): String {
        val key = "attention.$kind"
        return values[key] ?: fallback[key] ?: module(moduleId)
    }

    /** Notification copy, keyed by the `kind` the backend sends instead of a prewritten sentence. */
    fun notification(kind: String, params: Map<String, String>): String {
        val key = "notification.$kind"
        val template = values[key] ?: fallback[key] ?: return get(Txt.NOTIFICATIONS_GENERIC)
        return params.entries.fold(template) { text, (name, value) -> text.replace("{$name}", value) }
    }

    internal val keys: Set<String> get() = values.keys
}

object Localization {
    private val catalogs: Map<AppLocale, Map<String, String>> = mapOf(
        AppLocale.English to englishCatalog,
        AppLocale.Portuguese to portugueseCatalog,
        AppLocale.Spanish to spanishCatalog,
    )

    fun of(locale: AppLocale): Strings = Strings(locale, catalogs.getValue(locale), englishCatalog)

    fun of(tag: String?): Strings = of(AppLocale.of(tag))

    /** Exposed so the locale-parity test can compare catalogs without reaching into internals. */
    internal fun catalog(locale: AppLocale): Map<String, String> = catalogs.getValue(locale)
}
