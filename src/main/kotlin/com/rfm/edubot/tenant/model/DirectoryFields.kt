package com.rfm.edubot.tenant.model

/** The directories whose fields a tenant can shape, by the key their settings are stored under. */
enum class FieldDirectory(val key: String) {
    CLIENTS("clients"),
}

/**
 * How a tenant shaped one directory: which standard fields its staff must fill in, and the fields it
 * added of its own. The rules apply to staff saving a record in the dashboard; the bot, bookings and
 * agents still create records from what they know (a client's name and phone).
 */
data class DirectoryFields(
    /** Standard fields staff must fill in, besides the ones the directory always requires. */
    val required: Set<String>,
    val custom: List<CustomField> = emptyList(),
)

/** A field a tenant added to a directory. [key] never changes, so renaming [label] keeps the stored values. */
data class CustomField(
    val key: String,
    val label: String,
    val type: CustomFieldType,
    val required: Boolean = false,
    /** Shown as a column in the directory list. */
    val showInList: Boolean = false,
    /** The choices of a [CustomFieldType.SELECT] field, in order. */
    val options: List<String> = emptyList(),
)

/** How a custom value is typed and stored: text (also a date as `yyyy-MM-dd`, or a choice), a number, or true. */
enum class CustomFieldType {
    TEXT,
    NUMBER,
    DATE,
    SELECT,
    CHECKBOX,
    ;

    companion object {
        fun parse(value: String?): CustomFieldType? = entries.firstOrNull { it.name == value?.trim()?.uppercase() }
    }
}
