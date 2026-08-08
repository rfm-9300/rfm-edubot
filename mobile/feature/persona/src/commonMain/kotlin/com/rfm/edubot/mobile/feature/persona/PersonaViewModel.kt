package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.data.DashboardApi
import com.rfm.edubot.mobile.data.Persona
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PersonaUiState(
    val persona: Persona? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: Boolean = false,
)

class PersonaController(
    private val api: DashboardApi,
    private val token: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutableState = MutableStateFlow(PersonaUiState())
    val state: StateFlow<PersonaUiState> = mutableState.asStateFlow()

    fun load() = scope.launch {
        mutableState.value = mutableState.value.copy(loading = true, error = false)
        try {
            mutableState.value = PersonaUiState(persona = api.persona(token))
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(loading = false, error = true)
        }
    }

    fun save(instructions: String) = scope.launch {
        if (mutableState.value.saving) return@launch
        mutableState.value = mutableState.value.copy(saving = true, error = false)
        try {
            mutableState.value = PersonaUiState(persona = api.updatePersona(token, instructions))
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(saving = false, error = true)
        }
    }
}
