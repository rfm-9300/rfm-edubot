package com.rfm.edubot.mobile.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.common.SessionError
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.data.SessionRepository
import com.rfm.edubot.mobile.core.localization.AppLocale
import com.rfm.edubot.mobile.core.localization.Localization
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.model.DashboardIdentity
import com.rfm.edubot.mobile.core.ui.ThemeChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

sealed interface SessionState {
    data object Restoring : SessionState

    data class SignedOut(val error: SessionError? = null, val busy: Boolean = false) : SessionState

    data class SignedIn(
        val identity: DashboardIdentity,
        val switchingCompany: Boolean = false,
    ) : SessionState
}

class DashboardSessionViewModel(
    private val session: SessionRepository,
    /** The device's language, used for sign-in copy before any tenant is known. */
    private val deviceLocale: String? = null,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = mutable.asStateFlow()

    private val themePreference = MutableStateFlow(ThemeChoice.System)
    val theme: StateFlow<ThemeChoice> = themePreference.asStateFlow()

    private val localeOverride = MutableStateFlow<String?>(null)

    init {
        // Any screen's call can hit a 401. One observer signs out, rather than each screen deciding.
        session.expired
            .onEach { signedOut(SessionError.SESSION_EXPIRED) }
            .launchIn(scope)
    }

    /**
     * Copy for whoever is looking: the tenant's language once signed in, the device's before that.
     * Sign-in errors used to always render in English regardless of either.
     */
    val strings: Strings
        get() {
            val signedIn = mutable.value as? SessionState.SignedIn
            val tag = localeOverride.value ?: signedIn?.identity?.tenant?.locale ?: deviceLocale
            return Localization.of(tag)
        }

    val locale: AppLocale get() = strings.locale

    /** Formats instants in the tenant's timezone, so lists stop showing raw ISO strings. */
    val clock: TenantClock
        get() = TenantClock(
            zoneId = (mutable.value as? SessionState.SignedIn)?.identity?.tenant?.timezone ?: "UTC",
            now = Clock.System::now,
        )

    fun restore() = scope.launch {
        if (!session.hasToken()) {
            mutable.value = SessionState.SignedOut()
            return@launch
        }
        when (val identity = session.identity()) {
            is Outcome.Success -> mutable.value = SessionState.SignedIn(identity.value)
            is Outcome.Failure -> {
                // A dead connection must not throw away a usable token; only a rejection does.
                if (identity.error == AppError.Unauthorized) signedOut(SessionError.SESSION_EXPIRED)
                else mutable.value = SessionState.SignedOut(SessionError.CONNECTION_FAILED)
            }
        }
    }

    fun signIn(email: String, password: String) = scope.launch {
        if (email.isBlank() || password.isBlank()) {
            mutable.value = SessionState.SignedOut(SessionError.MISSING_CREDENTIALS)
            return@launch
        }
        mutable.value = SessionState.SignedOut(busy = true)
        when (val identity = session.signIn(email, password)) {
            is Outcome.Success -> mutable.value = SessionState.SignedIn(identity.value)
            is Outcome.Failure -> mutable.value = SessionState.SignedOut(SessionError.ofLogin(identity.error))
        }
    }

    fun switchCompany(companyId: String) = scope.launch {
        val signedIn = mutable.value as? SessionState.SignedIn ?: return@launch
        if (signedIn.switchingCompany) return@launch
        mutable.value = signedIn.copy(switchingCompany = true)
        when (val identity = session.switchCompany(companyId)) {
            is Outcome.Success -> {
                localeOverride.value = null
                mutable.value = SessionState.SignedIn(identity.value)
            }
            is Outcome.Failure -> mutable.value = signedIn.copy(switchingCompany = false)
        }
    }

    /** Reflects a locale the settings screen just saved, without re-fetching the identity. */
    fun applyLocale(locale: String) {
        localeOverride.value = locale
        val signedIn = mutable.value as? SessionState.SignedIn ?: return
        mutable.value = signedIn.copy(
            identity = signedIn.identity.copy(tenant = signedIn.identity.tenant.copy(locale = locale)),
        )
    }

    fun applyTheme(choice: ThemeChoice) {
        themePreference.value = choice
    }

    fun signOut() = scope.launch { signedOut(null) }

    private suspend fun signedOut(error: SessionError?) {
        session.signOut()
        localeOverride.value = null
        mutable.value = SessionState.SignedOut(error)
    }
}
