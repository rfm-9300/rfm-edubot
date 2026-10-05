package com.rfm.edubot.mobile.feature.persona

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.data.PersonaRepository
import com.rfm.edubot.mobile.core.data.ResourceState
import com.rfm.edubot.mobile.core.model.Persona
import com.rfm.edubot.mobile.core.model.PersonaTestMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PersonaEditorState(
    val busy: Boolean = false,
    val error: AppError? = null,
    val noteDraft: String = "",
    val testDraft: String = "",
    val testReply: String? = null,
    val testing: Boolean = false,
)

class PersonaViewModel(
    private val repository: PersonaRepository,
    private val pollMillis: Long = COMPILE_POLL_MILLIS,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope

    val persona: StateFlow<ResourceState<Persona>> = repository.persona.state

    private val mutable = MutableStateFlow(PersonaEditorState())
    val editor: StateFlow<PersonaEditorState> = mutable.asStateFlow()

    private var compilePoller: Job? = null

    fun load() = scope.launch {
        repository.persona.load()
        watchCompilation()
    }

    fun refresh() = scope.launch { repository.persona.refresh() }

    fun save(instructions: String) = act { repository.save(instructions) }

    fun rebuild() = act { repository.rebuild() }

    fun setNoteDraft(value: String) = mutable.update { it.copy(noteDraft = value, error = null) }

    fun addNote() {
        val content = mutable.value.noteDraft.trim()
        if (content.isBlank()) return
        act(clearNote = true) { repository.addSource(content) }
    }

    fun deleteSource(id: String) = act { repository.deleteSource(id) }

    fun setTestDraft(value: String) = mutable.update { it.copy(testDraft = value, error = null) }

    /** Sends a trial message through the compiled prompt, as the web's persona test chat does. */
    fun test() = scope.launch {
        val content = mutable.value.testDraft.trim()
        if (content.isBlank() || mutable.value.testing) return@launch
        mutable.update { it.copy(testing = true, error = null, testReply = null) }
        when (val reply = repository.test(listOf(PersonaTestMessage("user", content)))) {
            is Outcome.Success -> mutable.update { it.copy(testing = false, testReply = reply.value) }
            is Outcome.Failure -> mutable.update { it.copy(testing = false, error = reply.error) }
        }
    }

    private fun act(clearNote: Boolean = false, block: suspend () -> Outcome<*>) = scope.launch {
        if (mutable.value.busy) return@launch
        mutable.update { it.copy(busy = true, error = null) }
        val result = block()
        mutable.update {
            it.copy(
                busy = false,
                error = (result as? Outcome.Failure)?.error,
                noteDraft = if (clearNote && result is Outcome.Success) "" else it.noteDraft,
            )
        }
        watchCompilation()
    }

    /** A rebuild runs on the backend, so the screen polls while the prompt is still compiling. */
    private fun watchCompilation() {
        if (persona.value.value?.compiling != true) return
        if (compilePoller?.isActive == true) return
        compilePoller = scope.launch {
            while (isActive && repository.persona.state.value.value?.compiling == true) {
                delay(pollMillis)
                repository.persona.refresh()
            }
        }
    }

    override fun onCleared() {
        compilePoller?.cancel()
    }

    companion object {
        const val COMPILE_POLL_MILLIS = 3_000L
    }
}
