package com.rfm.edubot.integrations.email

object EmailAddresses {
    // Deliberately loose (no quoted local parts) but header-safe: no spaces, line breaks, brackets or separators.
    private val pattern = Regex("^[^\\s@<>(),;:\"\\[\\]\\\\]+@[^\\s@<>(),;:\"\\[\\]\\\\.]+(\\.[^\\s@<>(),;:\"\\[\\]\\\\.]+)+$")

    /** The address trimmed and lower-cased, or null when it isn't one. */
    fun normalize(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it.length <= 254 && pattern.matches(it) }
}
