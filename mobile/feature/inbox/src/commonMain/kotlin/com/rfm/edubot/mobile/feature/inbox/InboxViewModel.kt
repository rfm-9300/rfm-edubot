package com.rfm.edubot.mobile.feature.inbox

import com.rfm.edubot.mobile.core.model.ChannelAsset
import com.rfm.edubot.mobile.core.model.Conversation
import com.rfm.edubot.mobile.core.model.ThreadMessage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.network.DashboardApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InboxUiState(
    val conversations: List<Conversation> = emptyList(),
    val selectedConversation: Conversation? = null,
    val messages: List<ThreadMessage> = emptyList(),
    val loading: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
)

class InboxViewModel(
    private val api: DashboardApi,
    private val token: String,
    private val channels: List<ChannelAsset>,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutableState = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = mutableState.asStateFlow()

    fun load() = scope.launch {
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        try {
            mutableState.value = mutableState.value.copy(conversations = api.conversations(token), loading = false)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(loading = false, error = "load")
        }
    }

    fun select(conversation: Conversation) = scope.launch {
        mutableState.value = mutableState.value.copy(selectedConversation = conversation, messages = emptyList(), loading = true, error = null)
        try {
            mutableState.value = mutableState.value.copy(messages = api.messages(token, conversation.id), loading = false)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(loading = false, error = "thread")
        }
    }

    fun clearSelection() {
        mutableState.value = mutableState.value.copy(selectedConversation = null, messages = emptyList(), error = null)
    }

    fun send(text: String) = scope.launch {
        val current = mutableState.value
        val conversation = current.selectedConversation ?: return@launch
        val asset = channels.firstOrNull { it.platform == conversation.channel } ?: return@launch
        if (text.isBlank() || current.sending) return@launch
        mutableState.value = current.copy(sending = true, error = null)
        try {
            val sent = api.sendMessage(token, conversation.id, text.trim(), asset.externalId)
            mutableState.value = mutableState.value.copy(messages = mutableState.value.messages + sent, sending = false)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(sending = false, error = "send")
        }
    }
}
