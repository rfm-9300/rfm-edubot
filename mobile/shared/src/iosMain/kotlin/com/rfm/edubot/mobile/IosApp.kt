package com.rfm.edubot.mobile

import androidx.compose.ui.window.ComposeUIViewController
import com.rfm.edubot.mobile.app.DashboardApp
import com.rfm.edubot.mobile.app.MobileGraph
import com.rfm.edubot.mobile.core.common.SnapshotStore
import com.rfm.edubot.mobile.core.common.TokenStore
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController

/**
 * [deviceLocale] comes from Swift (`Locale.current.identifier`) so the sign-in screen is in the
 * reader's language before any tenant is known.
 */
fun MainViewController(deviceLocale: String?): UIViewController {
    val graph = MobileGraph(
        baseUrl = BASE_URL,
        tokenStore = IosTokenStore(),
        snapshotStore = IosSnapshotStore(),
        voiceInput = IosVoiceInput(),
    )
    return ComposeUIViewController {
        DashboardApp(graph = graph, deviceLocale = deviceLocale)
    }
}

private const val BASE_URL = "https://thebotslab.pt"

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

/**
 * Cached list and summary responses. Not secrets — unlike the token — so plain user defaults is the
 * right store; the keys are namespaced so [clear] cannot reach anything else.
 */
private class IosSnapshotStore : SnapshotStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun read(key: String): String? = defaults.stringForKey(PREFIX + key)

    override suspend fun write(key: String, value: String) {
        defaults.setObject(value, forKey = PREFIX + key)
        trackKey(key)
    }

    override suspend fun remove(key: String) {
        defaults.removeObjectForKey(PREFIX + key)
        defaults.setObject(knownKeys().minus(key).joinToString(","), forKey = INDEX_KEY)
    }

    override suspend fun clear() {
        knownKeys().forEach { defaults.removeObjectForKey(PREFIX + it) }
        defaults.removeObjectForKey(INDEX_KEY)
    }

    private fun knownKeys(): Set<String> =
        defaults.stringForKey(INDEX_KEY)?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    private fun trackKey(key: String) {
        val keys = knownKeys()
        if (key in keys) return
        defaults.setObject((keys + key).joinToString(","), forKey = INDEX_KEY)
    }

    private companion object {
        const val PREFIX = "edubot.snapshot."
        const val INDEX_KEY = "edubot.snapshot.index"
    }
}
