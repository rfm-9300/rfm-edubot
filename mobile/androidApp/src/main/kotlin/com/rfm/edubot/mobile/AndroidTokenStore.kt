package com.rfm.edubot.mobile

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rfm.edubot.mobile.data.TokenStore

class AndroidTokenStore(context: Context) : TokenStore {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "edubot_mobile_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override suspend fun read(): String? = preferences.getString(TOKEN_KEY, null)

    override suspend fun write(token: String) {
        preferences.edit().putString(TOKEN_KEY, token).apply()
    }

    override suspend fun clear() {
        preferences.edit().remove(TOKEN_KEY).apply()
    }

    private companion object {
        const val TOKEN_KEY = "dashboard_access_token"
    }
}
