package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer

/**
 * What a screen knows about one resource right now.
 *
 * [fromCache] is the field that matters for honesty: it is true while [value] is the copy from disk
 * and the backend has not confirmed it, so the UI can say "showing your last snapshot" only when
 * that is actually what is happening.
 */
data class ResourceState<T>(
    val value: T? = null,
    val loading: Boolean = false,
    val error: AppError? = null,
    val fromCache: Boolean = false,
) {
    val hasValue: Boolean get() = value != null

    /** Nothing to show and nothing on the way — the only time an empty state belongs on screen. */
    val isEmpty: Boolean get() = value == null && !loading
}

/**
 * One dashboard resource, cached in memory for the session and on disk between launches.
 *
 * [load] hands over the disk copy first and then refreshes, so opening a screen on a slow
 * connection shows the previous data immediately instead of a spinner. A failed refresh keeps the
 * cached value and reports the error beside it rather than blanking the screen.
 */
class CachedResource<T : Any>(
    private val key: String,
    private val serializer: KSerializer<T>,
    private val cache: SnapshotCache,
    private val fetch: suspend () -> T,
) {
    private val mutable = MutableStateFlow(ResourceState<T>())
    val state: StateFlow<ResourceState<T>> = mutable.asStateFlow()

    /** Serves the cached copy, then refreshes. A no-op refresh when [force] is false and we are fresh. */
    suspend fun load(force: Boolean = false): Outcome<T> {
        val current = mutable.value
        if (!force && current.hasValue && !current.fromCache) return Outcome.Success(current.value!!)
        if (!current.hasValue) {
            cache.read(key, serializer)?.let { stored ->
                mutable.value = ResourceState(value = stored, loading = true, fromCache = true)
            } ?: run { mutable.value = current.copy(loading = true, error = null) }
        } else {
            mutable.value = current.copy(loading = true, error = null)
        }
        return refresh()
    }

    suspend fun refresh(): Outcome<T> {
        mutable.value = mutable.value.copy(loading = true, error = null)
        val outcome = apiCall { fetch() }
        when (outcome) {
            is Outcome.Success -> {
                mutable.value = ResourceState(value = outcome.value, loading = false, fromCache = false)
                cache.write(key, serializer, outcome.value)
            }
            is Outcome.Failure -> mutable.value = mutable.value.copy(loading = false, error = outcome.error)
        }
        return outcome
    }

    /**
     * Replaces the held value after a mutation whose response is the new state, so the screen does
     * not have to re-fetch the whole list to show one changed row.
     */
    suspend fun put(value: T) {
        mutable.value = ResourceState(value = value, loading = false, fromCache = false)
        cache.write(key, serializer, value)
    }

    /** Edits the held value in place, when a mutation returns only the row that changed. */
    suspend fun mutate(transform: (T) -> T) {
        val current = mutable.value.value ?: return
        put(transform(current))
    }

    fun reset() {
        mutable.value = ResourceState()
    }
}
