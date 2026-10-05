package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.SnapshotStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Stores the last good copy of a response so a cold start shows content instead of a spinner.
 *
 * A snapshot that no longer parses is dropped rather than raised: it only means the app was updated
 * while a response from an older model version was on disk.
 */
class SnapshotCache(
    private val store: SnapshotStore,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun <T> read(key: String, serializer: KSerializer<T>): T? {
        val raw = store.read(key) ?: return null
        return runCatching { json.decodeFromString(serializer, raw) }.getOrElse {
            store.remove(key)
            null
        }
    }

    suspend fun <T> write(key: String, serializer: KSerializer<T>, value: T) {
        runCatching { json.encodeToString(serializer, value) }.onSuccess { store.write(key, it) }
    }

    /** Called on sign-out: the next account must not see the previous one's data. */
    suspend fun clear() = store.clear()
}
