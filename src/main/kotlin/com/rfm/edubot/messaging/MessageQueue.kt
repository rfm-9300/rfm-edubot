package com.rfm.edubot.messaging

import kotlinx.coroutines.channels.Channel

data class InboundMessage(
    val waId: String,
    val waMessageId: String,
    val profileName: String? = null,
    val messageText: String,
    val timestamp: String,
    val eventId: String,
)

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
