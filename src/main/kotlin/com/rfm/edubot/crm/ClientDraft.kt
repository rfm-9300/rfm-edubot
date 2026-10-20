package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.tenant.model.DirectoryFields
import kotlinx.serialization.json.JsonElement

/**
 * A client as staff fill one in, from the Clients page, the backoffice or the dashboard assistant. On an update,
 * omitted details other than [address] keep what is stored and an empty string clears them.
 */
data class ClientDraft(
    val name: String,
    val phone: String,
    val address: String? = null,
    val email: String? = null,
    val taxId: String? = null,
    val notes: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val contactPerson: String? = null,
    /** Values of the company's own fields by key; checked by [CustomFields.values]. */
    val customFields: Map<String, JsonElement>? = null,
) {
    /**
     * Stable error code for the first standard field [fields] requires that staff left empty, or null.
     * On an update an omitted detail keeps [existing]'s (the address is always sent). Bookings and the bot
     * still create clients from a name and phone.
     */
    fun requiredError(existing: Client? = null, fields: DirectoryFields = ClientFields.DEFAULT): String? {
        val values = mapOf(
            "taxId" to (taxId ?: existing?.taxId),
            "email" to (email ?: existing?.email),
            "contactPerson" to (contactPerson ?: existing?.contactPerson),
            "address" to address,
            "postalCode" to (postalCode ?: existing?.postalCode),
            "city" to (city ?: existing?.city),
        )
        return ClientFields.REQUIRABLE.entries.firstOrNull { (key, _) -> key in fields.required && values[key].isNullOrBlank() }?.value
    }

    /** Stable error code for the first invalid optional field, or null. */
    fun detailsError(): String? {
        val mail = email?.trim().orEmpty()
        return when {
            mail.length > MAX_EMAIL || (mail.isNotEmpty() && !EMAIL_SHAPE.matches(mail)) -> "invalid_email"
            (taxId?.trim()?.length ?: 0) > MAX_TAX_ID -> "tax_id_too_long"
            (notes?.trim()?.length ?: 0) > MAX_NOTES -> "notes_too_long"
            (address?.trim()?.length ?: 0) > MAX_ADDRESS -> "address_too_long"
            (postalCode?.trim()?.length ?: 0) > MAX_POSTAL_CODE -> "postal_code_too_long"
            (city?.trim()?.length ?: 0) > MAX_CITY -> "city_too_long"
            (contactPerson?.trim()?.length ?: 0) > MAX_CONTACT_PERSON -> "contact_person_too_long"
            else -> null
        }
    }

    companion object {
        const val MAX_EMAIL = 254
        const val MAX_TAX_ID = 32
        const val MAX_NOTES = 4000
        const val MAX_ADDRESS = 300
        const val MAX_POSTAL_CODE = 20
        const val MAX_CITY = 100
        const val MAX_CONTACT_PERSON = 120
        private val EMAIL_SHAPE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
