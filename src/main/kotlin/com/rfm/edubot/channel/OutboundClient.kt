package com.rfm.edubot.channel

data class ChannelCapabilities(
    val supportsDocuments: Boolean = false,
    /** The most options one message can carry as taps; 0 when the channel has none, so they go out as numbered lines. */
    val quickReplies: Int = 0,
    /** The most options that go out as buttons without a list (WhatsApp's 3); the rest need a list label. */
    val buttonReplies: Int = quickReplies,
    val quickReplyTitleChars: Int = 20,
    /** The longest text a message with taps can have, in UTF-8 bytes. */
    val quickReplyBodyBytes: Int = 1000,
) {
    companion object {
        val WHATSAPP = ChannelCapabilities(supportsDocuments = true, quickReplies = 10, buttonReplies = 3, quickReplyTitleChars = 20, quickReplyBodyBytes = 1000)
        val INSTAGRAM = ChannelCapabilities(supportsDocuments = false, quickReplies = 13)
        /** The website's chat has no taps: options go out as numbered lines. */
        val WEB = ChannelCapabilities()
    }
}

/** A tappable reply under a message. Tapping it sends [title] back as the customer's message, with [payload]. */
data class QuickReply(val title: String, val payload: String)

interface OutboundClient {
    val capabilities: ChannelCapabilities

    suspend fun sendText(to: String, text: String)

    suspend fun sendDocument(to: String, bytes: ByteArray, filename: String, mimeType: String)

    /**
     * [text] with [replies] under it as taps. Only channels whose [capabilities] list `quickReplies` have them; others
     * refuse. [listLabel] names the button that opens a long list (WhatsApp), when [replies] won't fit as buttons.
     */
    suspend fun sendQuickReplies(to: String, text: String, replies: List<QuickReply>, listLabel: String? = null) {
        throw OutboundDeliveryException("This channel can't send quick replies")
    }
}

interface ProfileLookupClient {
    suspend fun profileName(id: String): String?
}

open class OutboundDeliveryException(message: String) : RuntimeException(message)
