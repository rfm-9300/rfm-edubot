package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.tenant.model.DirectoryFields
import kotlinx.serialization.json.JsonPrimitive
import org.bson.types.ObjectId

/**
 * A company's clients and the rules for changing them. [create] and [update] apply the client form's rules in its
 * order: a name and a phone, what the company requires ([fields]), valid details, then its own fields. Clients may
 * share a phone. The Clients page, the backoffice and the dashboard assistant write through them; the bot's
 * `create_client` uses [createOrRestore], which needs only a name and a phone.
 */
class ClientDirectory(private val clients: ClientRepository, private val fields: DirectoryFields = ClientFields.DEFAULT) {

    sealed interface Outcome {
        data class Saved(val client: Client) : Outcome

        /** [error] is a stable code; [field] is the key of the company's own field it is about, if it is about one. */
        data class Invalid(val error: String, val field: String? = null) : Outcome

        data object NotFound : Outcome
    }

    /** Why [draft] can't be saved, over [existing] for an update, or null when it can. */
    fun check(draft: ClientDraft, existing: Client? = null): Outcome.Invalid? = checked(draft, existing).invalid

    suspend fun create(draft: ClientDraft): Outcome {
        val checked = checked(draft, existing = null)
        checked.invalid?.let { return it }
        return saved {
            clients.create(
                draft.name, draft.phone, draft.address, draft.email, draft.taxId, draft.notes,
                draft.postalCode, draft.city, draft.contactPerson,
                customFields = checked.custom.mapNotNull { (key, value) -> value?.let { key to it } }.toMap(),
            )
        }
    }

    suspend fun update(id: ObjectId, draft: ClientDraft): Outcome {
        if (draft.name.isBlank() || draft.phone.isBlank()) return Outcome.Invalid(NAME_AND_PHONE_REQUIRED)
        val existing = clients.findById(id) ?: return Outcome.NotFound
        val checked = checked(draft, existing)
        checked.invalid?.let { return it }
        return saved {
            clients.update(
                id, draft.name, draft.phone, draft.address, draft.email, draft.taxId, draft.notes,
                draft.postalCode, draft.city, draft.contactPerson, customFieldChanges = checked.custom,
            )
        }
    }

    /** The bot's create: a client who comes back with a phone they had is restored rather than duplicated. */
    suspend fun createOrRestore(name: String, phone: String, address: String?): Client {
        val archived = clients.findByPhone(phone)?.takeIf { it.archivedAt != null }
        return archived?.let { clients.setArchived(it.id, archived = false) } ?: clients.create(name, phone, address)
    }

    /** [invalid], or the changes to the company's own fields that saving makes. */
    private class Checked(val invalid: Outcome.Invalid?, val custom: Map<String, JsonPrimitive?> = emptyMap())

    private fun checked(draft: ClientDraft, existing: Client?): Checked {
        if (draft.name.isBlank() || draft.phone.isBlank()) return Checked(Outcome.Invalid(NAME_AND_PHONE_REQUIRED))
        (draft.requiredError(existing, fields) ?: draft.detailsError())?.let { return Checked(Outcome.Invalid(it)) }
        return when (val values = CustomFields.values(fields.custom, draft.customFields, existing?.customFields.orEmpty())) {
            is CustomFields.Values.Valid -> Checked(invalid = null, custom = values.changes)
            is CustomFields.Values.Invalid -> Checked(Outcome.Invalid(values.error, values.field))
        }
    }

    private suspend fun saved(save: suspend () -> Client?): Outcome = save()?.let { Outcome.Saved(it) } ?: Outcome.NotFound

    companion object {
        const val NAME_AND_PHONE_REQUIRED = "name and phone are required"
    }
}
