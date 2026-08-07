package com.rfm.edubot.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.rfm.edubot.mobile.app.DashboardApp
import com.rfm.edubot.mobile.data.KtorDashboardApi

class MainActivity : ComponentActivity() {
    private lateinit var voiceInput: AndroidVoiceInput

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceInput = AndroidVoiceInput(this)
        setContent {
            DashboardApp(
                api = KtorDashboardApi(baseUrl = BuildConfig.API_BASE_URL),
                tokenStore = AndroidTokenStore(applicationContext),
                voiceInput = voiceInput,
                initialEmail = BuildConfig.DEBUG_LOGIN_EMAIL,
                initialPassword = BuildConfig.DEBUG_LOGIN_PASSWORD,
            )
        }
    }

    override fun onDestroy() {
        voiceInput.close()
        super.onDestroy()
    }
}
