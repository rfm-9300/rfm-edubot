package com.rfm.edubot.channel

data class ChannelCapabilities(
    val supportsDocuments: Boolean = false,
)

/** A tappable reply under a message. Tapping it sends [title] back as the customer's message, with [payload]. */
data class QuickReply(val title: String, val payload: String)

interface OutboundClient {
    val capabilities: ChannelCapabilities

    suspend fun sendText(to: String, text: String)

    suspend fun sendDocument(to: String, bytes: ByteArray, filename: String, mimeType: String)

    /** [text] with [replies] under it. Only Instagram has quick replies; other channels refuse. */
    suspend fun sendQuickReplies(to: String, text: String, replies: List<QuickReply>) {
        throw OutboundDeliveryException("This channel can't send quick replies")
    }
}

interface ProfileLookupClient {
    suspend fun profileName(id: String): String?
}

open class OutboundDeliveryException(message: String) : RuntimeException(message)
