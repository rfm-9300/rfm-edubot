package com.rfm.edubot.channel

data class ChannelCapabilities(
    val supportsDocuments: Boolean = false,
)

interface OutboundClient {
    val capabilities: ChannelCapabilities

    suspend fun sendText(to: String, text: String)

    suspend fun sendDocument(to: String, bytes: ByteArray, filename: String, mimeType: String)
}
