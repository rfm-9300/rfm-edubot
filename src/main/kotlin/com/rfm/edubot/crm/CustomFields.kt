package com.rfm.edubot.crm

import com.rfm.edubot.tenant.model.CustomField
import com.rfm.edubot.tenant.model.CustomFieldType
import com.rfm.edubot.tenant.model.DirectoryFields
import com.rfm.edubot.tenant.model.FieldDirectory
import com.rfm.edubot.tenant.model.Tenant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.random.Random

/** The Clients directory's standard fields, and what a tenant may change about them. */
object ClientFields {
    /** Lists show the name, and bookings and the bot find a client by phone, so these stay required. */
    val ALWAYS_REQUIRED = listOf("name", "phone")

    /** Standard fields a tenant may make required, in form order, each with the error a missing one answers. */
    val REQUIRABLE: Map<String, String> = linkedMapOf(
        "taxId" to "tax_id_required",
        "email" to "email_required",
        "contactPerson" to "contact_person_required",
        "address" to "address_required",
        "postalCode" to "postal_code_required",
        "city" to "city_required",
    )

    /** What staff had to fill in before tenants could choose. */
    val DEFAULT = DirectoryFields(required = setOf("taxId", "address"))

    fun of(tenant: Tenant): DirectoryFields = tenant.directoryFields[FieldDirectory.CLIENTS] ?: DEFAULT

    /** The tenant's own fields whose values a directory search also matches. */
    fun searchable(fields: DirectoryFields): List<String> =
        fields.custom.filter { it.type == CustomFieldType.TEXT || it.type == CustomFieldType.SELECT }.map { it.key }
}

/** A tenant's own fields: checking a change to their definitions, and the values staff type in. */
object CustomFields {
    const val MAX_FIELDS = 20
    const val MAX_LABEL = 60
    const val MAX_OPTIONS = 30
    const val MAX_OPTION = 60
    const val MAX_TEXT = 500

    /** Keys are made here, and end up in Mongo field paths, so anything else is refused. */
    private val KEY = Regex("^cf_[a-z0-9]{8}$")

    fun isKey(key: String): Boolean = KEY.matches(key)

    fun newKey(): String = "cf_" + (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")

    /** One field as the settings form sends it; [key] is null for a new field. */
    data class Draft(
        val key: String?,
        val label: String,
        val type: String,
        val required: Boolean = false,
        val showInList: Boolean = false,
        val options: List<String> = emptyList(),
    )

    sealed interface Change {
        data class Valid(val fields: DirectoryFields) : Change
        data class Invalid(val error: String) : Change
    }

    /**
     * The settings replacing [current]: [required] keeps only the standard fields [requirable] lists, and
     * [drafts] become the custom fields, in their order. An existing field keeps its key and its type, so
     * the values already stored stay readable; a new one gets a fresh key. A yes/no field is never required.
     */
    fun change(
        current: DirectoryFields,
        requirable: Collection<String>,
        required: Collection<String>,
        drafts: List<Draft>,
        newKey: () -> String = ::newKey,
    ): Change {
        if (drafts.size > MAX_FIELDS) return Change.Invalid("too_many_fields")
        val existing = current.custom.associateBy { it.key }
        val keys = existing.keys.toMutableSet()
        val kept = mutableSetOf<String>()
        val labels = mutableSetOf<String>()
        val fields = drafts.map { draft ->
            val stored = draft.key?.takeIf { kept.add(it) }?.let { existing[it] }
            val type = stored?.type ?: CustomFieldType.parse(draft.type) ?: return Change.Invalid("invalid_type")
            val label = draft.label.trim()
            when {
                label.isEmpty() -> return Change.Invalid("label_required")
                label.length > MAX_LABEL -> return Change.Invalid("label_too_long")
                !labels.add(label.lowercase()) -> return Change.Invalid("duplicate_label")
            }
            val options = if (type == CustomFieldType.SELECT) draft.options.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() } else emptyList()
            if (type == CustomFieldType.SELECT) {
                when {
                    options.isEmpty() -> return Change.Invalid("options_required")
                    options.size > MAX_OPTIONS -> return Change.Invalid("too_many_options")
                    options.any { it.length > MAX_OPTION } -> return Change.Invalid("option_too_long")
                }
            }
            CustomField(
                key = stored?.key ?: generateSequence { newKey() }.first { isKey(it) && keys.add(it) },
                label = label,
                type = type,
                required = draft.required && type != CustomFieldType.CHECKBOX,
                showInList = draft.showInList,
                options = options,
            )
        }
        return Change.Valid(DirectoryFields(required = requirable.filter { it in required }.toSet(), custom = fields))
    }

    sealed interface Values {
        /** [changes] maps a field's key to its new value, or to null to clear it. */
        data class Valid(val changes: Map<String, JsonPrimitive?>) : Values
        data class Invalid(val error: String, val field: String) : Values
    }

    /**
     * The changes [input] makes to [stored] for [fields]. Keys of no field are ignored, and so are fields
     * [input] leaves out; null, a blank string or an unticked box clears a value. A value sent back as it is
     * stored is kept even when it no longer fits (a choice since removed). Once the changes apply, every
     * required field must have a value.
     */
    fun values(fields: List<CustomField>, input: Map<String, JsonElement>?, stored: Map<String, JsonPrimitive>): Values {
        val changes = linkedMapOf<String, JsonPrimitive?>()
        for (field in fields) {
            val raw = input?.get(field.key) ?: continue
            val current = stored[field.key]
            if (raw is JsonPrimitive && current != null && raw.isString == current.isString && raw.content == current.content) continue
            val next = when (val parsed = parse(field, raw)) {
                is Parsed.Value -> parsed.value
                is Parsed.Bad -> return Values.Invalid(parsed.error, field.key)
            }
            if (next != current) changes[field.key] = next
        }
        fields.firstOrNull { it.required && (if (it.key in changes) changes[it.key] else stored[it.key]) == null }
            ?.let { return Values.Invalid("custom_field_required", it.key) }
        return Values.Valid(changes)
    }

    private sealed interface Parsed {
        data class Value(val value: JsonPrimitive?) : Parsed
        data class Bad(val error: String) : Parsed
    }

    private fun parse(field: CustomField, raw: JsonElement): Parsed {
        if (raw is JsonNull) return Parsed.Value(null)
        val primitive = raw as? JsonPrimitive ?: return Parsed.Bad("custom_field_invalid")
        val text = primitive.content.trim()
        if (primitive.isString && text.isEmpty()) return Parsed.Value(null)
        val invalid = Parsed.Bad("custom_field_invalid")
        return when (field.type) {
            CustomFieldType.TEXT ->
                if (text.length > MAX_TEXT) Parsed.Bad("custom_field_too_long") else Parsed.Value(JsonPrimitive(text))
            CustomFieldType.NUMBER -> {
                val number = if (primitive.isString) text.replace(',', '.').toDoubleOrNull() else primitive.doubleOrNull
                number?.takeIf { it.isFinite() }?.let { Parsed.Value(JsonPrimitive(it)) } ?: invalid
            }
            CustomFieldType.DATE ->
                runCatching { LocalDate.parse(text) }.getOrNull()?.let { Parsed.Value(JsonPrimitive(it.toString())) } ?: invalid
            CustomFieldType.SELECT ->
                field.options.firstOrNull { it == text }?.let { Parsed.Value(JsonPrimitive(it)) } ?: invalid
            CustomFieldType.CHECKBOX -> when (primitive.booleanOrNull) {
                true -> Parsed.Value(JsonPrimitive(true))
                false -> Parsed.Value(null)
                null -> invalid
            }
        }
    }
}
