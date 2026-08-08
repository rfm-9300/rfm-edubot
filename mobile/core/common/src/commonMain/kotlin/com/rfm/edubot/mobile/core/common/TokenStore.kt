package com.rfm.edubot.mobile.core.common

interface TokenStore {
    suspend fun read(): String?
    suspend fun write(token: String)
    suspend fun clear()
}

class InMemoryTokenStore : TokenStore {
    private var token: String? = null

    override suspend fun read(): String? = token

    override suspend fun write(token: String) {
        this.token = token
    }

    override suspend fun clear() {
        token = null
    }
}
