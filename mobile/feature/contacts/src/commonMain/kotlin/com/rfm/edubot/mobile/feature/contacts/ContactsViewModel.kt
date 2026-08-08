package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.data.Contact
import com.rfm.edubot.mobile.data.DashboardApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ContactsUiState(
    val contacts: List<Contact> = emptyList(),
    val loading: Boolean = false,
    val updatingId: String? = null,
    val error: String? = null,
)

class ContactsController(
    private val api: DashboardApi,
    private val token: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutableState = MutableStateFlow(ContactsUiState())
    val state: StateFlow<ContactsUiState> = mutableState.asStateFlow()

    fun load() = scope.launch {
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        try {
            mutableState.value = mutableState.value.copy(contacts = api.contacts(token), loading = false)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(loading = false, error = "load")
        }
    }

    fun toggleStatus(contact: Contact) = scope.launch {
        if (mutableState.value.updatingId != null) return@launch
        val next = if (contact.status == "BLOCKED") "ACTIVE" else "BLOCKED"
        mutableState.value = mutableState.value.copy(updatingId = contact.id, error = null)
        try {
            val updated = api.updateContactStatus(token, contact.id, next)
            mutableState.value = mutableState.value.copy(
                contacts = mutableState.value.contacts.map { if (it.id == updated.id) updated else it },
                updatingId = null,
            )
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(updatingId = null, error = "update")
        }
    }
}
