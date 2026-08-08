@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rfm.edubot.mobile

import com.rfm.edubot.mobile.core.common.VoiceInput
import com.rfm.edubot.mobile.core.common.VoiceInputError
import com.rfm.edubot.mobile.core.common.VoiceInputState
import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.Foundation.NSLocale
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

internal class IosVoiceInput : VoiceInput {
    private val mutableState = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    override val state: StateFlow<VoiceInputState> = mutableState.asStateFlow()
    private val audioEngine = AVAudioEngine()
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest? = null
    private var recognitionTask: SFSpeechRecognitionTask? = null
    private var tapInstalled = false
    private var cancelling = false

    override fun start(locale: String) {
        dispatch_async(dispatch_get_main_queue()) { requestPermissionsAndStart(locale) }
    }

    private fun requestPermissionsAndStart(locale: String) {
        val recognizer = SFSpeechRecognizer(locale = NSLocale(localeIdentifier = locale))
        if (!recognizer.available) {
            mutableState.value = VoiceInputState.Failed(VoiceInputError.UNAVAILABLE)
            return
        }
        mutableState.value = VoiceInputState.RequestingPermission
        SFSpeechRecognizer.requestAuthorization { speechStatus ->
            if (speechStatus.value != 3L) {
                mutableState.value = VoiceInputState.Failed(VoiceInputError.PERMISSION_DENIED)
                return@requestAuthorization
            }
            val session = AVAudioSession.sharedInstance()
            if (session.recordPermission == AVAudioSessionRecordPermissionGranted) {
                dispatch_async(dispatch_get_main_queue()) { startRecording(recognizer) }
            } else {
                session.requestRecordPermission { granted ->
                    dispatch_async(dispatch_get_main_queue()) {
                        if (granted) startRecording(recognizer) else mutableState.value = VoiceInputState.Failed(VoiceInputError.PERMISSION_DENIED)
                    }
                }
            }
        }
    }

    @OptIn(BetaInteropApi::class)
    private fun startRecording(recognizer: SFSpeechRecognizer) {
        cancelResources()
        cancelling = false
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryRecord, mode = AVAudioSessionModeMeasurement, options = 0u, error = null)

        val request = SFSpeechAudioBufferRecognitionRequest().also { it.shouldReportPartialResults = true }
        recognitionRequest = request
        val inputNode = audioEngine.inputNode
        val format = inputNode.outputFormatForBus(0u)
        inputNode.installTapOnBus(0u, bufferSize = 1024u, format = format) { buffer, _ ->
            buffer?.let(request::appendAudioPCMBuffer)
        }
        tapInstalled = true
        audioEngine.prepare()
        if (!audioEngine.startAndReturnError(null)) {
            cancelResources()
            mutableState.value = VoiceInputState.Failed(VoiceInputError.UNAVAILABLE)
            return
        }
        mutableState.value = VoiceInputState.Listening()
        recognitionTask = recognizer.recognitionTaskWithRequest(request) { result, error ->
            dispatch_async(dispatch_get_main_queue()) {
                val transcript = result?.bestTranscription?.formattedString.orEmpty()
                when {
                    result?.final == true -> {
                        cancelResources()
                        mutableState.value = VoiceInputState.Finished(transcript)
                    }
                    error != null && !cancelling -> {
                        cancelResources()
                        mutableState.value = VoiceInputState.Failed(VoiceInputError.RECOGNITION_FAILED)
                    }
                    result != null -> mutableState.value = VoiceInputState.Listening(transcript)
                }
            }
        }
    }

    override fun stop() {
        dispatch_async(dispatch_get_main_queue()) {
            if (audioEngine.running) audioEngine.stop()
            removeTap()
            recognitionRequest?.endAudio()
        }
    }

    override fun cancel() {
        dispatch_async(dispatch_get_main_queue()) {
            cancelling = true
            recognitionTask?.cancel()
            cancelResources()
            mutableState.value = VoiceInputState.Idle
        }
    }

    private fun cancelResources() {
        if (audioEngine.running) audioEngine.stop()
        removeTap()
        recognitionRequest?.endAudio()
        recognitionTask = null
        recognitionRequest = null
    }

    private fun removeTap() {
        if (tapInstalled) {
            audioEngine.inputNode.removeTapOnBus(0u)
            tapInstalled = false
        }
    }
}
