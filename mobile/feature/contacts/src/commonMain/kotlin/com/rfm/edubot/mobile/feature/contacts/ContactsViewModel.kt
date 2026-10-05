package com.rfm.edubot.mobile.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.data.InboxRepository
import com.rfm.edubot.mobile.core.data.ResourceState
import com.rfm.edubot.mobile.core.model.Contact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ContactsViewModel(
    private val repository: InboxRepository,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope

    val contacts: StateFlow<ResourceState<List<Contact>>> = repository.contacts.state

    /** The contact whose block state is in flight, so its row can stop offering the action twice. */
    private val mutablePending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = mutablePending.asStateFlow()

    /** A failed block, kept apart from a failed list load so one does not hide the other. */
    private val mutableFailure = MutableStateFlow<AppError?>(null)
    val failure: StateFlow<AppError?> = mutableFailure.asStateFlow()

    fun load() = scope.launch { repository.contacts.load() }

    fun refresh() = scope.launch {
        mutableFailure.value = null
        repository.contacts.refresh()
    }

    fun toggleBlocked(contact: Contact) = scope.launch {
        if (mutablePending.value != null) return@launch
        mutablePending.value = contact.id
        mutableFailure.value = null
        val result = repository.setContactStatus(contact, blocked = !contact.blocked)
        mutablePending.value = null
        if (result is Outcome.Failure) mutableFailure.value = result.error
    }
}
