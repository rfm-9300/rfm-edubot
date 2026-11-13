package com.rfm.edubot.mobile.feature.crm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.model.CrmClient
import com.rfm.edubot.mobile.core.model.CustomField
import com.rfm.edubot.mobile.core.model.DirectoryFields
import com.rfm.edubot.mobile.core.model.SaveClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Not one of the company's fields: staff-only notes, which every form offers. */
internal const val NOTES_KEY = "notes"

data class ClientFormState(
    /** The company's choice of fields. [DirectoryFields.DEFAULT] until its own arrive. */
    val fields: DirectoryFields = DirectoryFields.DEFAULT,
    /** Standard fields by key ([DirectoryFields.NAME]…), plus [NOTES_KEY]. */
    val values: Map<String, String> = emptyMap(),
    /** The company's own fields by key; a yes/no field holds `"true"` or nothing. */
    val custom: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: AppError? = null,
    /** Field keys (standard or custom) the backend named in its rejection. */
    val invalid: Set<String> = emptySet(),
) {
    fun value(key: String): String = values[key].orEmpty()

    fun customValue(key: String): String = custom[key].orEmpty()

    /** The standard fields after name and phone, in the company's order. */
    val detailFields: List<String>
        get() = fields.standard.map { it.key }.filterNot { it == DirectoryFields.NAME || it == DirectoryFields.PHONE }
            .ifEmpty { DirectoryFields.DEFAULT.standard.map { it.key }.drop(2) }

    /** What the company requires that is still empty. A yes/no field is never required (unticked is an answer). */
    val missing: Set<String>
        get() = buildSet {
            fields.standard.filter { it.required }.map { it.key }
                .plus(listOf(DirectoryFields.NAME, DirectoryFields.PHONE))
                .filter { value(it).isBlank() }
                .forEach(::add)
            fields.custom.filter { it.required && it.type != CustomField.CHECKBOX && customValue(it.key).isBlank() }
                .forEach { add(it.key) }
        }

    val canSave: Boolean get() = !saving && !loading && missing.isEmpty()
}

/**
 * Create or edit a client, with the fields the company asks for.
 *
 * Each company picks which standard fields are required and adds its own (`GET …/clients/fields`).
 * The form used to require NIF and address whatever the company chose, which blocked saves a company
 * had made optional and failed with `email_required` or `custom_field_required` where it had asked
 * for more.
 */
class ClientFormViewModel(
    private val repository: CrmRepository,
    private val clientId: String?,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(ClientFormState())
    val state: StateFlow<ClientFormState> = mutable.asStateFlow()

    fun load() = scope.launch {
        mutable.update { it.copy(loading = true, error = null) }
        repository.clientFields.load()
        val fields = repository.clientFields.state.value.value ?: DirectoryFields.DEFAULT
        val id = clientId
        if (id == null) {
            mutable.update { it.copy(fields = fields, loading = false) }
            return@launch
        }
        when (val client = repository.client(id)) {
            is Outcome.Success -> mutable.value = ClientFormState(
                fields = fields,
                values = client.value.standardValues(),
                custom = client.value.customFields.mapValues { (_, value) -> value.asText() },
            )
            is Outcome.Failure -> mutable.update { it.copy(fields = fields, loading = false, error = client.error) }
        }
    }

    fun set(key: String, value: String) = mutable.update {
        it.copy(values = it.values + (key to value), error = null, invalid = it.invalid - key)
    }

    fun setCustom(key: String, value: String) = mutable.update {
        it.copy(custom = it.custom + (key to value), error = null, invalid = it.invalid - key)
    }

    fun save() = scope.launch {
        val current = mutable.value
        if (!current.canSave) return@launch
        mutable.value = current.copy(saving = true, error = null, invalid = emptySet())
        when (val saved = repository.saveClient(current.toRequest(), clientId)) {
            is Outcome.Success -> mutable.update { it.copy(saving = false, saved = true) }
            is Outcome.Failure -> mutable.update {
                it.copy(saving = false, error = saved.error, invalid = fieldsFor(saved.error))
            }
        }
    }

    /** Maps the backend's stable error codes onto the fields they are about. */
    private fun fieldsFor(error: AppError): Set<String> {
        if (error !is AppError.Rejected) return emptySet()
        if (error.code.startsWith("custom_field_")) return setOfNotNull(error.details["detail"])
        return FIELD_ERRORS.entries.firstOrNull { (_, codes) -> error.code in codes }?.let { setOf(it.key) }.orEmpty()
    }

    private companion object {
        val FIELD_ERRORS = mapOf(
            DirectoryFields.NAME to setOf("name_required"),
            DirectoryFields.PHONE to setOf("phone_taken", "phone_required"),
            DirectoryFields.TAX_ID to setOf("tax_id_required", "tax_id_too_long"),
            DirectoryFields.EMAIL to setOf("email_required", "invalid_email"),
            DirectoryFields.CONTACT_PERSON to setOf("contact_person_required", "contact_person_too_long"),
            DirectoryFields.ADDRESS to setOf("address_required", "address_too_long"),
            DirectoryFields.POSTAL_CODE to setOf("postal_code_required", "postal_code_too_long"),
            DirectoryFields.CITY to setOf("city_required", "city_too_long"),
            NOTES_KEY to setOf("notes_too_long"),
        )
    }
}

private fun CrmClient.standardValues(): Map<String, String> = mapOf(
    DirectoryFields.NAME to name,
    DirectoryFields.PHONE to phone,
    DirectoryFields.TAX_ID to taxId.orEmpty(),
    DirectoryFields.EMAIL to email.orEmpty(),
    DirectoryFields.CONTACT_PERSON to contactPerson.orEmpty(),
    DirectoryFields.ADDRESS to address.orEmpty(),
    DirectoryFields.POSTAL_CODE to postalCode.orEmpty(),
    DirectoryFields.CITY to city.orEmpty(),
    NOTES_KEY to notes.orEmpty(),
)

/** A stored custom value as the form edits it. */
internal fun JsonPrimitive.asText(): String = when {
    isString -> content
    booleanOrNull == true -> "true"
    booleanOrNull == false -> ""
    else -> content.removeSuffix(".0")
}

/**
 * Every field the form shows is sent, an empty one as `""`: on an edit the backend keeps an omitted
 * field and clears an empty one, so leaving one out would keep a value the person just erased.
 */
internal fun ClientFormState.toRequest(): SaveClient = SaveClient(
    name = value(DirectoryFields.NAME).trim(),
    phone = value(DirectoryFields.PHONE).trim(),
    taxId = value(DirectoryFields.TAX_ID).trim(),
    address = value(DirectoryFields.ADDRESS).trim(),
    email = value(DirectoryFields.EMAIL).trim(),
    postalCode = value(DirectoryFields.POSTAL_CODE).trim(),
    city = value(DirectoryFields.CITY).trim(),
    contactPerson = value(DirectoryFields.CONTACT_PERSON).trim(),
    notes = value(NOTES_KEY).trim(),
    customFields = fields.custom.associate { field -> field.key to customJson(field, customValue(field.key)) }
        .takeIf { it.isNotEmpty() },
)

private fun customJson(field: CustomField, text: String): JsonElement = when (field.type) {
    CustomField.CHECKBOX -> JsonPrimitive(text == "true")
    // A number goes as typed: the backend reads a comma as the decimal point, as a Portuguese keyboard types it.
    else -> JsonPrimitive(text.trim())
}
