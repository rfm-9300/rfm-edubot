package com.rfm.edubot.mobile

import android.content.Context
import com.rfm.edubot.mobile.core.common.SnapshotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cached list and summary responses, so the app opens with content rather than a spinner.
 *
 * Plain preferences, unlike [AndroidTokenStore]: these are the tenant's own data as the backend
 * already sent it, not a credential, and encrypting them would buy nothing while costing a key
 * unwrap on every read. Reads and writes hop to IO because the store is called from suspend code.
 */
class AndroidSnapshotStore(context: Context) : SnapshotStore {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        preferences.getString(key, null)
    }

    override suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) {
        preferences.edit().putString(key, value).apply()
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        preferences.edit().remove(key).apply()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val FILE = "edubot_mobile_snapshots"
    }
}
