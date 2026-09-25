package com.rfm.edubot.messaging

import com.rfm.edubot.tenant.model.Platform
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageQueueTest {
    private fun message(eventId: String = "evt-1") = InboundMessage(
        tenantId = ObjectId(),
        phoneNumberId = "123",
        platform = Platform.WHATSAPP,
        waId = "5511999999999",
        waMessageId = "wamid.1",
        messageText = "hello",
        timestamp = "0",
        eventId = eventId,
    )

    @Test
    fun `enqueued message is received in order`() = runBlocking {
        val queue = MessageQueue()
        queue.enqueue(message("evt-1"))
        queue.enqueue(message("evt-2"))

        val first = queue.receiveChannel().receive()
        val second = queue.receiveChannel().receive()

        assertEquals("evt-1", first.eventId)
        assertEquals("evt-2", second.eventId)
    }

    @Test
    fun `tryEnqueue succeeds when capacity is available`() {
        val queue = MessageQueue(capacity = 1)
        assertTrue(queue.tryEnqueue(message("evt-1")))
    }

    @Test
    fun `tryEnqueue fails once a bounded queue is full`() {
        val queue = MessageQueue(capacity = 1)
        assertTrue(queue.tryEnqueue(message("evt-1")))
        assertFalse(queue.tryEnqueue(message("evt-2")))
    }

    @Test
    fun `unlimited queue accepts many tryEnqueue calls without blocking`() {
        val queue = MessageQueue(capacity = Channel.UNLIMITED)
        repeat(1000) { i -> assertTrue(queue.tryEnqueue(message("evt-$i"))) }
    }
}
