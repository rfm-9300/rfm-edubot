package com.rfm.edubot.mobile

import androidx.compose.ui.window.ComposeUIViewController
import com.rfm.edubot.mobile.app.DashboardApp
import com.rfm.edubot.mobile.core.common.TokenStore
import com.rfm.edubot.mobile.core.network.KtorDashboardApi
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    DashboardApp(
        api = KtorDashboardApi(baseUrl = "https://thebotslab.pt"),
        tokenStore = IosTokenStore(),
        voiceInput = IosVoiceInput(),
    )
}

private class IosTokenStore : TokenStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun read(): String? = defaults.stringForKey(TOKEN_KEY)

    override suspend fun write(token: String) {
        defaults.setObject(token, forKey = TOKEN_KEY)
    }

    override suspend fun clear() {
        defaults.removeObjectForKey(TOKEN_KEY)
    }

    private companion object {
        const val TOKEN_KEY = "dashboard_access_token"
    }
}
