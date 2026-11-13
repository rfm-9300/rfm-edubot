package com.rfm.edubot.tags

import org.bson.types.ObjectId

/**
 * The records a company can tag, each with the key its tags are stored under, the collection that holds the
 * record and how that collection stores the record's id. A record's tags live apart from the record (see
 * [RecordTagRepository]), so adding a kind here is all a new record type needs on the backend.
 */
enum class TagKind(val key: String, val collection: String, val idField: String = "_id") {
    CLIENT("clients", "crm.clients"),
    SUPPLIER("suppliers", "crm.suppliers"),
    EMPLOYEE("employees", "crm.employees"),
    SERVICE("services", "crm.client_services"),

    /** A catalog item is keyed by its own string id, not an ObjectId. */
    CATALOG_ITEM("catalog", "crm.standard_items", idField = "id"),
    QUOTE("quotes", "crm.quotes"),
    INVOICE("invoices", "crm.invoices"),
    PAYMENT("payments", "crm.payments"),
    PROJECT("projects", "building.projects"),
    ;

    /** [recordId] as the collection stores it, or null when it can't be an id of this kind. */
    fun storedId(recordId: String): Any? = when {
        idField != "_id" -> recordId.takeIf { it.isNotBlank() && it.length <= MAX_KEY_LENGTH }
        ObjectId.isValid(recordId) -> ObjectId(recordId)
        else -> null
    }

    private companion object {
        const val MAX_KEY_LENGTH = 100
    }
}

/** How a record's tags are cleaned up and limited. The same rules as the backoffice board's labels. */
object RecordTags {
    const val MAX_TAGS = 10
    const val MAX_LENGTH = 30

    const val TOO_MANY = "too_many_tags"
    const val TOO_LONG = "tag_too_long"

    sealed interface Cleaned {
        data class Valid(val tags: List<String>) : Cleaned
        data class Invalid(val error: String) : Cleaned
    }

    /**
     * [raw] trimmed, with inner whitespace collapsed, blanks dropped and repeats removed ignoring case (the first
     * spelling stays). More than [MAX_TAGS] or one longer than [MAX_LENGTH] is refused rather than cut, so
     * what staff typed is never silently changed.
     */
    fun clean(raw: List<String>): Cleaned {
        val tags = raw.map { it.trim().replace(WHITESPACE, " ") }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (tags.size > MAX_TAGS) return Cleaned.Invalid(TOO_MANY)
        if (tags.any { it.length > MAX_LENGTH }) return Cleaned.Invalid(TOO_LONG)
        return Cleaned.Valid(tags)
    }

    private val WHITESPACE = Regex("\\s+")
}
