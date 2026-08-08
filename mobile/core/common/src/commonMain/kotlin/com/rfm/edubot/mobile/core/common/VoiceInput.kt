package com.rfm.edubot.mobile.app

import kotlinx.coroutines.flow.StateFlow

enum class VoiceInputError {
    PERMISSION_DENIED,
    UNAVAILABLE,
    RECOGNITION_FAILED,
}

sealed interface VoiceInputState {
    data object Idle : VoiceInputState
    data object RequestingPermission : VoiceInputState
    data class Listening(val transcript: String = "") : VoiceInputState
    data class Finished(val transcript: String) : VoiceInputState
    data class Failed(val error: VoiceInputError) : VoiceInputState
}

interface VoiceInput {
    val state: StateFlow<VoiceInputState>

    fun start(locale: String)

    fun stop()

    fun cancel()
}
