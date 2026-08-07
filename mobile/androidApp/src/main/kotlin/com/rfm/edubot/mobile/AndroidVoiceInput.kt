package com.rfm.edubot.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.rfm.edubot.mobile.app.VoiceInput
import com.rfm.edubot.mobile.app.VoiceInputError
import com.rfm.edubot.mobile.app.VoiceInputState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class AndroidVoiceInput(private val activity: ComponentActivity) : VoiceInput, RecognitionListener {
    private val mutableState = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    override val state: StateFlow<VoiceInputState> = mutableState.asStateFlow()
    private var recognizer: SpeechRecognizer? = null
    private var pendingLocale: String? = null
    private var cancelling = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val locale = pendingLocale
        pendingLocale = null
        if (granted && locale != null) startRecognition(locale) else mutableState.value = VoiceInputState.Failed(VoiceInputError.PERMISSION_DENIED)
    }

    override fun start(locale: String) {
        onMain { startOnMain(locale) }
    }

    private fun startOnMain(locale: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            mutableState.value = VoiceInputState.Failed(VoiceInputError.UNAVAILABLE)
            return
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLocale = locale
            mutableState.value = VoiceInputState.RequestingPermission
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecognition(locale)
    }

    private fun startRecognition(locale: String) {
        cancelling = false
        val speechRecognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(activity).also {
            recognizer = it
            it.setRecognitionListener(this)
        }
        mutableState.value = VoiceInputState.Listening()
        speechRecognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }

    override fun stop() {
        onMain { recognizer?.stopListening() }
    }

    override fun cancel() {
        onMain {
            pendingLocale = null
            cancelling = true
            recognizer?.cancel()
            mutableState.value = VoiceInputState.Idle
        }
    }

    fun close() {
        onMain {
            pendingLocale = null
            cancelling = true
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            mutableState.value = VoiceInputState.Idle
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        mutableState.value = VoiceInputState.Listening(currentTranscript())
    }

    override fun onPartialResults(partialResults: Bundle?) {
        mutableState.value = VoiceInputState.Listening(transcript(partialResults))
    }

    override fun onResults(results: Bundle?) {
        mutableState.value = VoiceInputState.Finished(transcript(results))
    }

    override fun onError(error: Int) {
        if (cancelling) {
            cancelling = false
            return
        }
        mutableState.value = VoiceInputState.Failed(
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) VoiceInputError.PERMISSION_DENIED else VoiceInputError.RECOGNITION_FAILED,
        )
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun currentTranscript(): String = when (val current = mutableState.value) {
        is VoiceInputState.Listening -> current.transcript
        else -> ""
    }

    private fun transcript(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private inline fun onMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }
}
