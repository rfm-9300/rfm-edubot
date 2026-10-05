package com.rfm.edubot.mobile.feature.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.common.VoiceInput
import com.rfm.edubot.mobile.core.common.VoiceInputError
import com.rfm.edubot.mobile.core.common.VoiceInputState
import com.rfm.edubot.mobile.core.data.AssistantRepository
import com.rfm.edubot.mobile.core.model.AssistantMessage
import com.rfm.edubot.mobile.core.model.AssistantThread
import com.rfm.edubot.mobile.core.model.AssistantThreadDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

data class AssistantUiState(
    val threads: List<AssistantThread> = emptyList(),
    val detail: AssistantThreadDetail? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: AppError? = null,
    val draft: String = "",
    val voiceState: VoiceInputState = VoiceInputState.Idle,
    val voiceError: VoiceInputError? = null,
)

class AssistantViewModel(
    private val repository: AssistantRepository,
    private val locale: String,
    private val voiceInput: VoiceInput,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutableState = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = mutableState.asStateFlow()
    private var voiceDraftPrefix = ""

    init {
        scope.launch {
            voiceInput.state.collect { voiceState ->
                mutableState.update { current ->
                    val transcript = when (voiceState) {
                        is VoiceInputState.Listening -> voiceState.transcript
                        is VoiceInputState.Finished -> voiceState.transcript
                        else -> null
                    }
                    current.copy(
                        draft = if (!current.busy && transcript != null) {
                            mergeTranscript(voiceDraftPrefix, transcript)
                        } else {
                            current.draft
                        },
                        voiceState = voiceState,
                        voiceError = (voiceState as? VoiceInputState.Failed)?.error,
                    )
                }
            }
        }
    }

    fun load() = scope.launch {
        mutableState.update { it.copy(loading = true, error = null) }
        when (val threads = repository.threads()) {
            is Outcome.Failure -> mutableState.update { it.copy(loading = false, error = threads.error) }
            is Outcome.Success -> {
                val first = threads.value.firstOrNull()
                val detail = first?.let { repository.thread(it.id).valueOrNull }
                mutableState.value = AssistantUiState(threads = threads.value, detail = detail)
            }
        }
    }

    fun selectThread(thread: AssistantThread) = scope.launch {
        if (mutableState.value.detail?.thread?.id == thread.id) return@launch
        voiceInput.cancel()
        mutableState.update { it.copy(loading = true, error = null, draft = "") }
        when (val detail = repository.thread(thread.id)) {
            is Outcome.Success -> mutableState.update { it.copy(detail = detail.value, loading = false) }
            is Outcome.Failure -> mutableState.update { it.copy(loading = false, error = detail.error) }
        }
    }

    fun createThread(title: String) = scope.launch {
        voiceInput.cancel()
        mutableState.update { it.copy(busy = true, error = null) }
        when (val thread = repository.createThread(title)) {
            is Outcome.Success -> mutableState.update {
                it.copy(
                    threads = listOf(thread.value) + it.threads,
                    detail = AssistantThreadDetail(thread.value, emptyList()),
                    busy = false,
                    draft = "",
                    voiceState = VoiceInputState.Idle,
                    voiceError = null,
                )
            }
            is Outcome.Failure -> mutableState.update { it.copy(busy = false, error = thread.error) }
        }
    }

    fun updateDraft(content: String) {
        if (mutableState.value.voiceState is VoiceInputState.Listening) voiceInput.cancel()
        mutableState.update { it.copy(draft = content, voiceState = VoiceInputState.Idle, voiceError = null) }
    }

    fun toggleVoice() {
        if (mutableState.value.busy) return
        if (mutableState.value.voiceState is VoiceInputState.Listening) {
            voiceInput.stop()
        } else {
            voiceDraftPrefix = mutableState.value.draft
            mutableState.update { it.copy(voiceError = null) }
            voiceInput.start(locale)
        }
    }

    fun send() = scope.launch {
        val detail = mutableState.value.detail ?: return@launch
        val content = mutableState.value.draft.trim()
        if (content.isBlank() || mutableState.value.busy) return@launch
        // Shown immediately so the question does not vanish while the model thinks.
        val optimistic = AssistantMessage(
            id = "local-${Random.nextLong()}",
            role = "user",
            content = content,
            createdAt = "",
        )
        mutableState.update {
            it.copy(
                busy = true,
                error = null,
                draft = "",
                voiceState = VoiceInputState.Idle,
                voiceError = null,
                detail = (it.detail ?: detail).let { live -> live.copy(messages = live.messages + optimistic) },
            )
        }
        voiceInput.cancel()
        when (val sent = repository.send(detail.thread.id, content)) {
            is Outcome.Success -> mutableState.update { it.copy(detail = sent.value, busy = false) }
            is Outcome.Failure -> mutableState.update {
                it.copy(detail = detail, draft = content, busy = false, error = sent.error)
            }
        }
    }

    fun decide(actionId: String, confirm: Boolean) = scope.launch {
        val detail = mutableState.value.detail ?: return@launch
        if (mutableState.value.busy) return@launch
        mutableState.update { it.copy(busy = true, error = null) }
        when (val decided = repository.decide(detail.thread.id, actionId, confirm)) {
            is Outcome.Success -> mutableState.update { it.copy(detail = decided.value, busy = false) }
            is Outcome.Failure -> mutableState.update { it.copy(busy = false, error = decided.error) }
        }
    }

    override fun onCleared() {
        voiceInput.cancel()
    }
}

internal fun mergeTranscript(prefix: String, transcript: String): String = when {
    transcript.isBlank() -> prefix
    prefix.isBlank() -> transcript
    prefix.last().isWhitespace() -> prefix + transcript
    else -> "$prefix $transcript"
}
