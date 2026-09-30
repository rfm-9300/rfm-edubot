package com.rfm.edubot.messaging

import com.rfm.edubot.tenant.model.Platform
import kotlinx.coroutines.channels.Channel
import org.bson.types.ObjectId

data class InboundMessage(
    val tenantId: ObjectId,
    val phoneNumberId: String,
    val platform: Platform = Platform.WHATSAPP,
    val channelExternalId: String = phoneNumberId,
    val waId: String,
    val waMessageId: String,
    val profileName: String? = null,
    val messageText: String,
    val timestamp: String,
    val eventId: String,
    val registerOnly: Boolean = false,
    /** Set for photos, voice notes, videos, documents and stickers; [messageText] is then the caption. */
    val media: InboundMedia? = null,
)

/** [kind] is "image", "audio", "video", "document" or "sticker"; [mediaId] is Meta's id for downloading it. */
data class InboundMedia(val kind: String, val mediaId: String, val mimeType: String? = null, val fileName: String? = null)

class MessageQueue(capacity: Int = Channel.UNLIMITED) {
    private val channel = Channel<InboundMessage>(capacity)

    suspend fun enqueue(message: InboundMessage) {
        channel.send(message)
    }

    fun tryEnqueue(message: InboundMessage): Boolean {
        return channel.trySend(message).isSuccess
    }

    fun receiveChannel() = channel
}
