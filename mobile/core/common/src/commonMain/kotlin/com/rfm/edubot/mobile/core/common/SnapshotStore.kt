package com.rfm.edubot.mobile.core.common

/**
 * Key/value storage for the last good response of a list or summary, so the app opens with content
 * instead of a spinner and keeps working on a dead connection.
 *
 * Values are serialized JSON. Platform implementations live next to [TokenStore]; unlike the token
 * these are not secrets, so they do not need encrypted storage.
 */
interface SnapshotStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun remove(key: String)

    /** Drops every snapshot. Called on sign-out so one user's data never greets the next. */
    suspend fun clear()
}

class InMemorySnapshotStore : SnapshotStore {
    private val entries = mutableMapOf<String, String>()

    override suspend fun read(key: String): String? = entries[key]

    override suspend fun write(key: String, value: String) {
        entries[key] = value
    }

    override suspend fun remove(key: String) {
        entries.remove(key)
    }

    override suspend fun clear() {
        entries.clear()
    }
}
