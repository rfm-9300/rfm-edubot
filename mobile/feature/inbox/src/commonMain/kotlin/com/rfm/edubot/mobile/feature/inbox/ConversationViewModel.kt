package com.rfm.edubot.mobile.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.data.InboxRepository
import com.rfm.edubot.mobile.core.data.ThreadSnapshot
import com.rfm.edubot.mobile.core.model.ChannelAsset
import com.rfm.edubot.mobile.core.model.Conversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ConversationUiState(
    val snapshot: ThreadSnapshot? = null,
    val loading: Boolean = false,
    val sending: Boolean = false,
    val draft: String = "",
    val error: AppError? = null,
)

/**
 * One open conversation.
 *
 * Polls `/updates?since=` on the web dashboard's four-second cadence, so a reply typed on a laptop
 * appears on the phone. The old screen fetched the thread once when it opened and never again.
 */
class ConversationViewModel(
    private val repository: InboxRepository,
    private val conversationId: String,
    private val channels: List<ChannelAsset>,
    private val pollMillis: Long = THREAD_POLL_MILLIS,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = mutable.asStateFlow()

    private var poller: Job? = null

    fun open() = scope.launch {
        val conversation = repository.conversations.state.value.value?.firstOrNull { it.id == conversationId }
            ?: run {
                mutable.value = mutable.value.copy(error = AppError.NotFound)
                return@launch
            }
        mutable.value = mutable.value.copy(loading = true, error = null)
        when (val opened = repository.openThread(conversation)) {
            is Outcome.Success -> {
                mutable.value = mutable.value.copy(snapshot = opened.value, loading = false)
                startPolling()
            }
            is Outcome.Failure -> mutable.value = mutable.value.copy(loading = false, error = opened.error)
        }
    }

    fun updateDraft(text: String) {
        mutable.update { it.copy(draft = text) }
    }

    fun send() = scope.launch {
        val current = mutable.value
        val snapshot = current.snapshot ?: return@launch
        val text = current.draft.trim()
        if (text.isBlank() || current.sending) return@launch
        mutable.value = current.copy(sending = true, error = null, draft = "")
        when (val sent = repository.send(snapshot.conversation, text, channels)) {
            is Outcome.Success -> mutable.update { live ->
                val base = live.snapshot ?: snapshot
                live.copy(
                    snapshot = base.copy(messages = base.messages + sent.value),
                    sending = false,
                )
            }
            is Outcome.Failure -> mutable.update {
                // Give the text back rather than losing what somebody typed.
                it.copy(sending = false, error = sent.error, draft = text)
            }
        }
    }

    /** Resends a message WhatsApp rejected, which the app previously had no way to do. */
    fun retry(messageId: String) = scope.launch {
        val snapshot = mutable.value.snapshot ?: return@launch
        when (val retried = repository.retry(snapshot.conversation, messageId)) {
            is Outcome.Success -> mutable.update { live ->
                val base = live.snapshot ?: snapshot
                live.copy(
                    snapshot = base.copy(
                        messages = base.messages.map { if (it.id == messageId) retried.value else it },
                    ),
                )
            }
            is Outcome.Failure -> mutable.update { it.copy(error = retried.error) }
        }
    }

    /** Hands the conversation to a person, or back to the assistant. */
    fun setAutoReply(enabled: Boolean) = scope.launch {
        val snapshot = mutable.value.snapshot ?: return@launch
        when (val updated = repository.setAutoReply(snapshot.conversation, enabled)) {
            is Outcome.Success -> mutable.update { live ->
                live.copy(snapshot = (live.snapshot ?: snapshot).copy(conversation = updated.value))
            }
            is Outcome.Failure -> mutable.update { it.copy(error = updated.error) }
        }
    }

    fun dismissError() {
        mutable.update { it.copy(error = null) }
    }

    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            while (isActive) {
                delay(pollMillis)
                val snapshot = mutable.value.snapshot ?: continue
                if (mutable.value.sending) continue
                val polled = repository.pollThread(snapshot)
                if (polled is Outcome.Success) {
                    mutable.update { live ->
                        // Only adopt the poll when the thread has not moved underneath it.
                        if (live.snapshot?.conversation?.id == polled.value.conversation.id) {
                            live.copy(snapshot = polled.value)
                        } else {
                            live
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        poller?.cancel()
    }

    companion object {
        const val THREAD_POLL_MILLIS = 4_000L
    }
}

/** True while WhatsApp's 24-hour service window has closed, so only a template may be sent. */
internal fun Conversation.windowClosed(nowIso: String?): Boolean {
    val expires = windowExpiresAt ?: return false
    val now = nowIso ?: return false
    return expires < now
}
