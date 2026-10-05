package com.rfm.edubot.mobile.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.data.NotificationsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The unread count on the bell.
 *
 * Polls on the same minute the web dashboard does rather than inventing a different cadence, so the
 * two surfaces agree about how fresh the count is.
 */
class NotificationsBadgeViewModel(
    private val repository: NotificationsRepository,
    private val pollMillis: Long = POLL_MILLIS,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(0L)
    val unread: StateFlow<Long> = mutable.asStateFlow()

    private var watching = false

    fun watch() {
        if (watching) return
        watching = true
        repository.notifications.state
            .map { it.value?.unread ?: 0L }
            .onEach { mutable.value = it }
            .launchIn(scope)
        scope.launch {
            repository.notifications.load()
            while (isActive) {
                delay(pollMillis)
                repository.notifications.refresh()
            }
        }
    }

    companion object {
        const val POLL_MILLIS = 60_000L
    }
}
