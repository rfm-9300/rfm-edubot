package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.data.DashboardApi
import com.rfm.edubot.mobile.data.WebWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val widget: WebWidget? = null,
    val selectedLocale: String,
    val loading: Boolean = false,
    val updatingLocale: Boolean = false,
    val error: Boolean = false,
)

class SettingsController(
    private val api: DashboardApi,
    private val token: String,
    initialLocale: String,
    private val onLocaleUpdated: (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutableState = MutableStateFlow(SettingsUiState(selectedLocale = initialLocale))
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    fun load() = scope.launch {
        mutableState.value = mutableState.value.copy(loading = true, error = false)
        try {
            mutableState.value = mutableState.value.copy(widget = api.webWidget(token), loading = false)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(loading = false, error = true)
        }
    }

    fun updateLocale(locale: String) = scope.launch {
        val previousLocale = mutableState.value.selectedLocale
        if (locale == previousLocale || mutableState.value.updatingLocale) return@launch
        mutableState.value = mutableState.value.copy(selectedLocale = locale, updatingLocale = true, error = false)
        onLocaleUpdated(locale)
        try {
            val updatedLocale = api.updateLocale(token, locale)
            mutableState.value = mutableState.value.copy(selectedLocale = updatedLocale, updatingLocale = false)
            onLocaleUpdated(updatedLocale)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(selectedLocale = previousLocale, updatingLocale = false, error = true)
            onLocaleUpdated(previousLocale)
        }
    }
}
