package com.rfm.edubot.mobile

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.rfm.edubot.mobile.app.DashboardApp
import com.rfm.edubot.mobile.app.MobileGraph
import java.util.Locale

/** A [FragmentActivity] because BiometricPrompt shows itself as a fragment. */
class MainActivity : FragmentActivity() {
    private lateinit var voiceInput: AndroidVoiceInput
    private lateinit var graph: MobileGraph

    /** Set by the shell while a screen is on the stack; cleared at a module root. */
    private var popBackStack: (() -> Boolean)? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (popBackStack?.invoke() != true) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        voiceInput = AndroidVoiceInput(this)
        graph = MobileGraph(
            baseUrl = BuildConfig.API_BASE_URL,
            tokenStore = AndroidTokenStore(applicationContext),
            snapshotStore = AndroidSnapshotStore(applicationContext),
            voiceInput = voiceInput,
            location = AndroidLocationProvider(this),
            signer = AndroidDeviceSigner(this),
        )
        onBackPressedDispatcher.addCallback(this, backCallback)
        setContent {
            DashboardApp(
                graph = graph,
                deviceLocale = Locale.getDefault().toLanguageTag(),
                initialEmail = BuildConfig.DEBUG_LOGIN_EMAIL,
                initialPassword = BuildConfig.DEBUG_LOGIN_PASSWORD,
                onBackHandlerChanged = { handler ->
                    popBackStack = handler
                    backCallback.isEnabled = handler != null
                },
            )
        }
    }

    override fun onDestroy() {
        voiceInput.close()
        graph.close()
        super.onDestroy()
    }
}
