package com.rfm.edubot.mobile.feature.crm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.model.SaveClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ClientField { Name, Phone, TaxId, Address, Email }

data class ClientFormState(
    val name: String = "",
    val phone: String = "",
    val taxId: String = "",
    val address: String = "",
    val postalCode: String = "",
    val city: String = "",
    val email: String = "",
    val loading: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: AppError? = null,
    /** Fields the backend named in its rejection, so the form can mark them. */
    val invalidFields: Set<ClientField> = emptySet(),
) {
    val canSave: Boolean
        get() = !saving && name.isNotBlank() && phone.isNotBlank() && taxId.isNotBlank() && address.isNotBlank()
}

class ClientFormViewModel(
    private val repository: CrmRepository,
    private val clientId: String?,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(ClientFormState())
    val state: StateFlow<ClientFormState> = mutable.asStateFlow()

    fun load() = scope.launch {
        val id = clientId ?: return@launch
        mutable.update { it.copy(loading = true, error = null) }
        when (val client = repository.client(id)) {
            is Outcome.Success -> mutable.value = ClientFormState(
                name = client.value.name,
                phone = client.value.phone,
                taxId = client.value.taxId.orEmpty(),
                address = client.value.address.orEmpty(),
                postalCode = client.value.postalCode.orEmpty(),
                city = client.value.city.orEmpty(),
                email = client.value.email.orEmpty(),
            )
            is Outcome.Failure -> mutable.update { it.copy(loading = false, error = client.error) }
        }
    }

    fun setName(value: String) = edit(ClientField.Name) { copy(name = value) }

    fun setPhone(value: String) = edit(ClientField.Phone) { copy(phone = value) }

    fun setTaxId(value: String) = edit(ClientField.TaxId) { copy(taxId = value) }

    fun setAddress(value: String) = edit(ClientField.Address) { copy(address = value) }

    fun setPostalCode(value: String) = edit(null) { copy(postalCode = value) }

    fun setCity(value: String) = edit(null) { copy(city = value) }

    fun setEmail(value: String) = edit(ClientField.Email) { copy(email = value) }

    fun save() = scope.launch {
        val current = mutable.value
        if (!current.canSave) return@launch
        mutable.value = current.copy(saving = true, error = null, invalidFields = emptySet())
        val request = SaveClient(
            name = current.name.trim(),
            phone = current.phone.trim(),
            taxId = current.taxId.trim(),
            address = current.address.trim(),
            postalCode = current.postalCode.trim().ifBlank { null },
            city = current.city.trim().ifBlank { null },
            email = current.email.trim().ifBlank { null },
        )
        when (val saved = repository.saveClient(request, clientId)) {
            is Outcome.Success -> mutable.update { it.copy(saving = false, saved = true) }
            is Outcome.Failure -> mutable.update {
                it.copy(saving = false, error = saved.error, invalidFields = fieldsFor(saved.error))
            }
        }
    }

    private fun edit(field: ClientField?, transform: ClientFormState.() -> ClientFormState) {
        mutable.update { current ->
            current.transform().copy(
                error = null,
                invalidFields = if (field == null) current.invalidFields else current.invalidFields - field,
            )
        }
    }

    /** Maps the backend's stable error codes onto the fields they are about. */
    private fun fieldsFor(error: AppError): Set<ClientField> = when {
        error !is AppError.Rejected -> emptySet()
        error.code == "tax_id_required" || error.code == "tax_id_too_long" -> setOf(ClientField.TaxId)
        error.code == "address_required" || error.code == "address_too_long" -> setOf(ClientField.Address)
        error.code == "invalid_email" -> setOf(ClientField.Email)
        error.code == "phone_taken" -> setOf(ClientField.Phone)
        else -> emptySet()
    }
}
