package com.rfm.edubot.mobile.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * One record on screen: the value, whether it is loading or an action is running, the last failure,
 * and [gone] once an action removed it (the screen then steps back).
 */
data class RecordState<T>(
    val value: T? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: AppError? = null,
    val gone: Boolean = false,
)

/**
 * The view model every record screen shares: a quote, an invoice, a project, an issue. They differ
 * only in what they load and which actions they offer, so the screen passes those in instead of each
 * record writing the same loading, busy and error handling again.
 *
 * Only one action runs at a time: a double tap on "Mark paid" sends one request.
 */
open class RecordViewModel<T : Any>(
    private val loader: suspend () -> Outcome<T>,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    protected val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(RecordState<T>())
    val state: StateFlow<RecordState<T>> = mutable.asStateFlow()

    fun load() = scope.launch {
        mutable.update { it.copy(loading = true, error = null) }
        when (val loaded = loader()) {
            is Outcome.Success -> mutable.update { it.copy(value = loaded.value, loading = false) }
            is Outcome.Failure -> mutable.update { it.copy(loading = false, error = loaded.error) }
        }
    }

    /** Runs an action whose answer is the record as it is now. */
    fun act(action: suspend (T) -> Outcome<T>) = run { current ->
        when (val done = action(current)) {
            is Outcome.Success -> mutable.update { it.copy(value = done.value, busy = false) }
            is Outcome.Failure -> mutable.update { it.copy(busy = false, error = done.error) }
        }
    }

    /** Runs an action that changes something else (a quote's invoice), then reloads this record. */
    fun actThenReload(action: suspend (T) -> Outcome<*>) = run { current ->
        when (val done = action(current)) {
            is Outcome.Success -> when (val reloaded = loader()) {
                is Outcome.Success -> mutable.update { it.copy(value = reloaded.value, busy = false) }
                is Outcome.Failure -> mutable.update { it.copy(busy = false, error = reloaded.error) }
            }
            is Outcome.Failure -> mutable.update { it.copy(busy = false, error = done.error) }
        }
    }

    /** Runs an action that removes the record; [RecordState.gone] tells the screen to leave. */
    fun remove(action: suspend (T) -> Outcome<*>) = run { current ->
        when (val done = action(current)) {
            is Outcome.Success -> mutable.update { it.copy(busy = false, gone = true) }
            is Outcome.Failure -> mutable.update { it.copy(busy = false, error = done.error) }
        }
    }

    fun dismissError() = mutable.update { it.copy(error = null) }

    /**
     * Called as the screen steps back after [remove]: the store keeps this view model for the session,
     * so a [RecordState.gone] left set would send the next visit straight back.
     */
    fun consumeGone() = mutable.update { it.copy(gone = false) }

    private fun run(block: suspend (T) -> Unit) = scope.launch {
        val current = mutable.value
        val value = current.value ?: return@launch
        if (current.busy) return@launch
        mutable.update { it.copy(busy = true, error = null) }
        block(value)
    }
}

/**
 * A key for a form's view model that is new each time the form is opened and kept across a rotation.
 *
 * The signed-in view models live for the whole session. Keyed by a fixed name ("client-form:new"),
 * the second time someone opened a form they got the first one's view model, already `saved`, and
 * the form closed itself at once.
 */
@Composable
fun rememberFormKey(name: String): String {
    val instance = rememberSaveable { Random.nextLong().toULong().toString(36) }
    return "$name:$instance"
}
