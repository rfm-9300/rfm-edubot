package com.rfm.edubot.mobile.core.testing

import com.rfm.edubot.mobile.core.common.VoiceInput
import com.rfm.edubot.mobile.core.common.VoiceInputState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeVoiceInput : VoiceInput {
    private val mutableState = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    override val state: StateFlow<VoiceInputState> = mutableState
    var startedLocale: String? = null

    override fun start(locale: String) {
        startedLocale = locale
        mutableState.value = VoiceInputState.Listening()
    }

    override fun stop() {
        val transcript = (mutableState.value as? VoiceInputState.Listening)?.transcript.orEmpty()
        mutableState.value = VoiceInputState.Finished(transcript)
    }

    override fun cancel() {
        mutableState.value = VoiceInputState.Idle
    }

    fun emit(state: VoiceInputState) {
        mutableState.value = state
    }
}
