package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.model.DashboardIdentity
import com.rfm.edubot.mobile.core.network.SessionApi
import com.rfm.edubot.mobile.core.network.SessionTokens
import kotlinx.coroutines.flow.Flow

/**
 * Sign-in, who is signed in, and signing out. The rest of the app never sees a token.
 *
 * [expired] relays the HTTP layer's 401: any screen's call can trip it, and the session observes it
 * once rather than every screen guessing whether its own failure means the session is over.
 */
class SessionRepository(
    private val api: SessionApi,
    private val tokens: SessionTokens,
    private val cache: SnapshotCache,
) {
    val expired: Flow<Unit> = tokens.expired

    suspend fun hasToken(): Boolean = tokens.current() != null

    suspend fun signIn(email: String, password: String): Outcome<DashboardIdentity> {
        val session = apiCall { api.login(email, password) }
        return when (session) {
            is Outcome.Failure -> session
            is Outcome.Success -> {
                // A new sign-in must not inherit the previous account's cached lists.
                cache.clear()
                tokens.adopt(session.value.token)
                identity()
            }
        }
    }

    suspend fun identity(): Outcome<DashboardIdentity> = apiCall { api.me() }

    /**
     * Moves to another company the account owns. The backend answers with a fresh token scoped to
     * it, so the cached lists of the previous company are dropped before the new identity loads.
     */
    suspend fun switchCompany(companyId: String): Outcome<DashboardIdentity> {
        val switched = apiCall { api.switchCompany(companyId) }
        return when (switched) {
            is Outcome.Failure -> switched
            is Outcome.Success -> {
                tokens.adopt(switched.value.token)
                cache.clear()
                identity()
            }
        }
    }

    suspend fun signOut() {
        tokens.forget()
        cache.clear()
    }
}
