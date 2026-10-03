package com.rfm.edubot.mobile.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.data.SettingsRepository
import com.rfm.edubot.mobile.core.model.Account
import com.rfm.edubot.mobile.core.model.WebWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val widget: WebWidget? = null,
    val account: Account? = null,
    val selectedLocale: String,
    val updatingLocale: Boolean = false,
    val error: AppError? = null,
)

class SettingsViewModel(
    private val repository: SettingsRepository,
    initialLocale: String,
    private val onLocaleUpdated: (String) -> Unit,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(SettingsUiState(selectedLocale = initialLocale))
    val state: StateFlow<SettingsUiState> = mutable.asStateFlow()

    fun load() = scope.launch {
        // Neither call is essential to the screen, so a failure shows beside the rest, not instead.
        repository.webWidget().valueOrNull?.let { widget -> mutable.update { it.copy(widget = widget) } }
        repository.account().valueOrNull?.let { account -> mutable.update { it.copy(account = account) } }
    }

    /**
     * Switches language optimistically so the screen redraws at once, then rolls back if the save
     * fails — the alternative is a tap that appears to do nothing until the round trip ends.
     */
    fun updateLocale(locale: String) = scope.launch {
        val previous = mutable.value.selectedLocale
        if (locale == previous || mutable.value.updatingLocale) return@launch
        mutable.update { it.copy(selectedLocale = locale, updatingLocale = true, error = null) }
        onLocaleUpdated(locale)
        when (val saved = repository.updateLocale(locale)) {
            is Outcome.Success -> {
                mutable.update { it.copy(selectedLocale = saved.value, updatingLocale = false) }
                onLocaleUpdated(saved.value)
            }
            is Outcome.Failure -> {
                mutable.update { it.copy(selectedLocale = previous, updatingLocale = false, error = saved.error) }
                onLocaleUpdated(previous)
            }
        }
    }
}
