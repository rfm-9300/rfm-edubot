package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.data.DashboardApi
import com.rfm.edubot.mobile.data.DashboardApiException
import com.rfm.edubot.mobile.data.DashboardIdentity
import com.rfm.edubot.mobile.data.Overview
import com.rfm.edubot.mobile.data.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DashboardSessionState {
    data object Restoring : DashboardSessionState
    data class SignedOut(val error: SessionError? = null) : DashboardSessionState
    data class SignedIn(
        val token: String,
        val identity: DashboardIdentity,
        val overview: Overview? = null,
        val loadingOverview: Boolean = false,
    ) : DashboardSessionState
}

enum class SessionError {
    MISSING_CREDENTIALS,
    INVALID_CREDENTIALS,
    SESSION_EXPIRED,
    CONNECTION_FAILED,
}

class DashboardSessionController(
    private val api: DashboardApi,
    private val tokenStore: TokenStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutableState = MutableStateFlow<DashboardSessionState>(DashboardSessionState.Restoring)
    val state: StateFlow<DashboardSessionState> = mutableState.asStateFlow()

    fun restore() = scope.launch {
        val token = tokenStore.read()
        if (token == null) {
            mutableState.value = DashboardSessionState.SignedOut()
            return@launch
        }
        loadIdentity(token, clearOnUnauthorized = true)
    }

    fun login(email: String, password: String) = scope.launch {
        if (email.isBlank() || password.isBlank()) {
            mutableState.value = DashboardSessionState.SignedOut(SessionError.MISSING_CREDENTIALS)
            return@launch
        }
        mutableState.value = DashboardSessionState.Restoring
        try {
            val token = api.login(email, password).token
            tokenStore.write(token)
            loadIdentity(token, clearOnUnauthorized = true)
        } catch (error: DashboardApiException) {
            tokenStore.clear()
            mutableState.value = DashboardSessionState.SignedOut(
                if (error.status == 401 || error.status == 403) SessionError.INVALID_CREDENTIALS else SessionError.CONNECTION_FAILED,
            )
        } catch (_: Exception) {
            mutableState.value = DashboardSessionState.SignedOut(SessionError.CONNECTION_FAILED)
        }
    }

    fun refreshOverview() = scope.launch {
        val signedIn = mutableState.value as? DashboardSessionState.SignedIn ?: return@launch
        mutableState.value = signedIn.copy(loadingOverview = true)
        try {
            mutableState.value = signedIn.copy(overview = api.overview(signedIn.token), loadingOverview = false)
        } catch (error: DashboardApiException) {
            if (error.status == 401) signOut()
            else mutableState.value = signedIn.copy(loadingOverview = false)
        } catch (_: Exception) {
            mutableState.value = signedIn.copy(loadingOverview = false)
        }
    }

    fun applyLocale(locale: String) {
        val signedIn = mutableState.value as? DashboardSessionState.SignedIn ?: return
        mutableState.value = signedIn.copy(
            identity = signedIn.identity.copy(tenant = signedIn.identity.tenant.copy(locale = locale)),
        )
    }

    fun signOut() = scope.launch {
        tokenStore.clear()
        mutableState.value = DashboardSessionState.SignedOut()
    }

    private suspend fun loadIdentity(token: String, clearOnUnauthorized: Boolean) {
        try {
            val identity = api.me(token)
            mutableState.value = DashboardSessionState.SignedIn(token, identity, loadingOverview = true)
            val overview = api.overview(token)
            mutableState.value = DashboardSessionState.SignedIn(token, identity, overview)
        } catch (error: DashboardApiException) {
            if (clearOnUnauthorized && error.status == 401) tokenStore.clear()
            mutableState.value = DashboardSessionState.SignedOut(SessionError.SESSION_EXPIRED)
        } catch (_: Exception) {
            mutableState.value = DashboardSessionState.SignedOut(SessionError.CONNECTION_FAILED)
        }
    }
}
