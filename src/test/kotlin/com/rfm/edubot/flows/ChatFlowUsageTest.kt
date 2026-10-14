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
    private fun at(minute: Int) = Instant.parse("2026-10-14T10:00:00Z") + minute.minutes

    @Test
    fun `a flow's messages add up per flow and per step, and each conversation is one customer`() {
        val usage = ChatFlowUsage.of(
            listOf(
                OriginCount("flow:servicos:menu", ana, 2, at(1)),
                OriginCount("flow:servicos:contacto", ana, 1, at(5)),
                OriginCount("flow:servicos:menu", rui, 1, at(3)),
                OriginCount("flow:promo:oferta", rui, 4, at(2)),
            ),
        )

        val servicos = usage.getValue("servicos")
        assertEquals(4, servicos.sent)
        assertEquals(2, servicos.customers)
        assertEquals(at(5), servicos.lastSentAt)
        assertEquals(mapOf("menu" to 3, "contacto" to 1), servicos.steps)
        assertEquals(FlowUsage(4, 1, at(2), mapOf("oferta" to 4)), usage["promo"])
    }

    @Test
    fun `messages stored before they carried their step count for the flow only, and other origins are ignored`() {
        val usage = ChatFlowUsage.of(
            listOf(
                OriginCount("flow:servicos", ana, 3, at(1)),
                OriginCount("flow:servicos:menu", rui, 1, at(2)),
                OriginCount("flow:", ana, 1, at(3)),
                OriginCount("flow:a:b:c", ana, 1, at(3)),
                OriginCount("agent:6a1f", ana, 9, at(4)),
            ),
        )

        assertEquals(setOf("servicos"), usage.keys)
        assertEquals(FlowUsage(4, 2, at(2), mapOf("menu" to 1)), usage["servicos"])
    }
}
