package com.rfm.edubot.mobile.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.data.InboxRepository
import com.rfm.edubot.mobile.core.data.ResourceState
import com.rfm.edubot.mobile.core.model.Conversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class InboxFilter { All, NeedsReply, Unread, Paused }

class InboxViewModel(
    private val repository: InboxRepository,
    private val pollMillis: Long = LIST_POLL_MILLIS,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope

    val conversations: StateFlow<ResourceState<List<Conversation>>> = repository.conversations.state

    private val mutableFilter = MutableStateFlow(InboxFilter.All)
    val filter: StateFlow<InboxFilter> = mutableFilter.asStateFlow()

    private var polling = false

    /**
     * Loads and then keeps the list fresh on the web dashboard's own 15-second cadence. Without
     * this the app's inbox was only as current as the last manual pull.
     */
    fun start() {
        if (polling) return
        polling = true
        scope.launch {
            repository.conversations.load()
            while (isActive) {
                delay(pollMillis)
                repository.conversations.refresh()
            }
        }
    }

    fun refresh() = scope.launch { repository.conversations.refresh() }

    fun setFilter(value: InboxFilter) {
        mutableFilter.value = value
    }

    fun visible(all: List<Conversation>, filter: InboxFilter): List<Conversation> = when (filter) {
        InboxFilter.All -> all
        InboxFilter.NeedsReply -> all.filter { it.waiting }
        InboxFilter.Unread -> all.filter { it.unreadCount > 0 }
        InboxFilter.Paused -> all.filterNot { it.autoReplyEnabled }
    }.sortedByDescending { it.lastMessageAt }

    fun counts(all: List<Conversation>): Map<InboxFilter, Int> = mapOf(
        InboxFilter.All to all.size,
        InboxFilter.NeedsReply to all.count { it.waiting },
        InboxFilter.Unread to all.count { it.unreadCount > 0 },
        InboxFilter.Paused to all.count { !it.autoReplyEnabled },
    )

    companion object {
        const val LIST_POLL_MILLIS = 15_000L
    }
}
