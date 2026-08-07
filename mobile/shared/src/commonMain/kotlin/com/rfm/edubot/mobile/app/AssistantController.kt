package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.data.AssistantThread
import com.rfm.edubot.mobile.data.AssistantThreadDetail
import com.rfm.edubot.mobile.data.DashboardApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

data class AssistantUiState(
    val threads: List<AssistantThread> = emptyList(),
    val detail: AssistantThreadDetail? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: Boolean = false,
    val draft: String = "",
    val voiceState: VoiceInputState = VoiceInputState.Idle,
    val voiceError: VoiceInputError? = null,
)

class AssistantController(
    private val api: DashboardApi,
    private val token: String,
    private val locale: String,
    private val voiceInput: VoiceInput,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutableState = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = mutableState.asStateFlow()
    private var voiceDraftPrefix = ""

    init {
        scope.launch {
            voiceInput.state.collect { voiceState ->
                val transcript = when (voiceState) {
                    is VoiceInputState.Listening -> voiceState.transcript
                    is VoiceInputState.Finished -> voiceState.transcript
                    else -> null
                }
                mutableState.value = mutableState.value.copy(
                    draft = transcript?.let { mergeTranscript(voiceDraftPrefix, it) } ?: mutableState.value.draft,
                    voiceState = voiceState,
                    voiceError = (voiceState as? VoiceInputState.Failed)?.error,
                )
            }
        }
    }

    fun load() = scope.launch {
        mutableState.value = mutableState.value.copy(loading = true, error = false)
        try {
            val threads = api.assistantThreads(token)
            val detail = threads.firstOrNull()?.let { api.assistantThread(token, it.id) }
            mutableState.value = AssistantUiState(threads = threads, detail = detail)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(loading = false, error = true)
        }
    }

    fun createThread() = scope.launch {
        voiceInput.cancel()
        mutableState.value = mutableState.value.copy(busy = true)
        try {
            val thread = api.createAssistantThread(token, "New conversation")
            mutableState.value = mutableState.value.copy(
                threads = listOf(thread) + mutableState.value.threads,
                detail = AssistantThreadDetail(thread, emptyList()),
                busy = false,
                draft = "",
                voiceState = VoiceInputState.Idle,
                voiceError = null,
            )
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(busy = false, error = true)
        }
    }

    fun updateDraft(content: String) {
        if (mutableState.value.voiceState is VoiceInputState.Listening) voiceInput.cancel()
        mutableState.value = mutableState.value.copy(draft = content, voiceState = VoiceInputState.Idle, voiceError = null)
    }

    fun toggleVoice() {
        if (mutableState.value.busy) return
        if (mutableState.value.voiceState is VoiceInputState.Listening) {
            voiceInput.stop()
        } else {
            voiceDraftPrefix = mutableState.value.draft
            mutableState.value = mutableState.value.copy(voiceError = null)
            voiceInput.start(locale)
        }
    }

    fun send() = scope.launch {
        val detail = mutableState.value.detail ?: return@launch
        val content = mutableState.value.draft
        if (content.isBlank() || mutableState.value.busy) return@launch
        voiceInput.cancel()
        mutableState.value = mutableState.value.copy(busy = true)
        try {
            mutableState.value = mutableState.value.copy(
                detail = api.sendAssistantMessage(token, detail.thread.id, content.trim()),
                busy = false,
                draft = "",
                voiceState = VoiceInputState.Idle,
                voiceError = null,
            )
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(busy = false, error = true)
        }
    }

    fun decide(actionId: String, decision: String) = scope.launch {
        val detail = mutableState.value.detail ?: return@launch
        if (mutableState.value.busy) return@launch
        mutableState.value = mutableState.value.copy(busy = true)
        try {
            mutableState.value = mutableState.value.copy(detail = api.decideAssistantAction(token, detail.thread.id, actionId, decision), busy = false)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(busy = false, error = true)
        }
    }

    fun close() {
        voiceInput.cancel()
        scope.cancel()
    }
}

internal fun mergeTranscript(prefix: String, transcript: String): String = when {
    transcript.isBlank() -> prefix
    prefix.isBlank() -> transcript
    prefix.last().isWhitespace() -> prefix + transcript
    else -> "$prefix $transcript"
}
