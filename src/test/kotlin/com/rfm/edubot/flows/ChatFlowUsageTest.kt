package com.rfm.edubot.flows

import com.rfm.edubot.conversation.OriginCount
import kotlinx.datetime.Instant
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class ChatFlowUsageTest {
    private val ana = ObjectId()
    private val rui = ObjectId()
    private val eva = ObjectId()
    private fun at(minute: Int) = Instant.parse("2026-10-14T10:00:00Z") + minute.minutes

    @Test
    fun `a flow's messages add up per flow and per step, and each conversation is one customer`() {
        val usage = ChatFlowUsage.of(
            listOf(
                OriginCount("flow:servicos:menu", ana, delivered = 2, lastDeliveredAt = at(1)),
                OriginCount("flow:servicos:contacto", ana, delivered = 1, lastDeliveredAt = at(5)),
                OriginCount("flow:servicos:menu", rui, delivered = 1, lastDeliveredAt = at(3)),
                OriginCount("flow:promo:oferta", rui, delivered = 4, lastDeliveredAt = at(2)),
            ),
        )

        assertEquals(FlowUsage(sent = 4, failed = 0, customers = 2, lastSentAt = at(5), steps = mapOf("menu" to 3, "contacto" to 1)), usage["servicos"])
        assertEquals(FlowUsage(sent = 4, failed = 0, customers = 1, lastSentAt = at(2), steps = mapOf("oferta" to 4)), usage["promo"])
    }

    @Test
    fun `messages the channel refused are counted apart and reach no customer`() {
        val usage = ChatFlowUsage.of(
            listOf(
                OriginCount("flow:servicos:menu", ana, delivered = 1, failed = 2, lastDeliveredAt = at(1)),
                OriginCount("flow:servicos:menu", eva, delivered = 0, failed = 3, lastDeliveredAt = null),
                OriginCount("flow:promo:oferta", eva, delivered = 0, failed = 1, lastDeliveredAt = null),
            ),
        )

        assertEquals(FlowUsage(sent = 1, failed = 5, customers = 1, lastSentAt = at(1), steps = mapOf("menu" to 1)), usage["servicos"])
        assertEquals(FlowUsage(sent = 0, failed = 1, customers = 0, lastSentAt = null, steps = emptyMap()), usage["promo"])
    }

    @Test
    fun `messages stored before they carried their step count for the flow only, and other origins are ignored`() {
        val usage = ChatFlowUsage.of(
            listOf(
                OriginCount("flow:servicos", ana, delivered = 3, lastDeliveredAt = at(1)),
                OriginCount("flow:servicos:menu", rui, delivered = 1, lastDeliveredAt = at(2)),
                OriginCount("flow:", ana, delivered = 1, lastDeliveredAt = at(3)),
                OriginCount("flow:a:b:c", ana, delivered = 1, lastDeliveredAt = at(3)),
                OriginCount("agent:6a1f", ana, delivered = 9, lastDeliveredAt = at(4)),
            ),
        )

        assertEquals(setOf("servicos"), usage.keys)
        assertEquals(FlowUsage(sent = 4, failed = 0, customers = 2, lastSentAt = at(2), steps = mapOf("menu" to 1)), usage["servicos"])
    }
}
