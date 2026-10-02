package com.rfm.edubot.mobile.core.network

import com.rfm.edubot.mobile.core.common.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place that knows the bearer token.
 *
 * Nothing above this class handles tokens: screens and repositories call the dashboard and the HTTP
 * layer attaches the header. When the backend answers 401 the HTTP layer calls [invalidate], which
 * clears storage and emits on [expired] so the session layer can drop to the sign-in screen —
 * rather than leaving one screen showing an error while the rest of the app pretends to be signed in.
 */
class SessionTokens(private val store: TokenStore) {
    private val guard = Mutex()
    private var cached: String? = null
    private var loaded = false
    private val expirations = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    /** Emits once each time a token stops being accepted. */
    val expired: Flow<Unit> = expirations.asSharedFlow()

    suspend fun current(): String? = guard.withLock {
        if (!loaded) {
            cached = store.read()
            loaded = true
        }
        cached
    }

    /** Stores a token from sign-in or a company switch. */
    suspend fun adopt(token: String) = guard.withLock {
        cached = token
        loaded = true
        store.write(token)
    }

    /** The backend rejected the token. Clears it and signals the session to sign out. */
    suspend fun invalidate() {
        val had = guard.withLock {
            val previous = cached
            cached = null
            loaded = true
            store.clear()
            previous
        }
        if (had != null) expirations.emit(Unit)
    }

    /** Deliberate sign-out: clears the token without reporting an expiry. */
    suspend fun forget() = guard.withLock {
        cached = null
        loaded = true
        store.clear()
    }
}
