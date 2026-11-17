package com.rfm.edubot.flows

import com.rfm.edubot.channel.ChannelCapabilities
import com.rfm.edubot.channel.QuickReply
import kotlin.test.Test
import kotlin.test.assertEquals

class FlowRenderTest {
    private fun message(count: Int, display: OptionsDisplay = OptionsDisplay.AUTO, label: String? = null, text: String = "Escolha:", title: (Int) -> String = { "Opção ${it + 1}" }) =
        FlowMessage(text, List(count) { FlowOption(title(it), "flow:f:s$it:$it") }, display, label, "flow:f:menu")

    @Test
    fun `Instagram gets every option as a tap, up to its thirteen`() {
        val out = message(13).fitTo(ChannelCapabilities.INSTAGRAM)
        assertEquals(13, out.replies.size)
        assertEquals("Escolha:", out.text)
        assertEquals(QuickReply("Opção 1", "flow:f:s0:0"), out.replies.first())
        assertEquals(14, message(14).fitTo(ChannelCapabilities.INSTAGRAM).text.lines().count { it.matches(Regex("\\d+\\. .*")) }, "one too many: all numbered")
    }

    @Test
    fun `WhatsApp gets up to three as buttons, and more as a list only when the step has a label`() {
        assertEquals(3, message(3).fitTo(ChannelCapabilities.WHATSAPP).replies.size)
        val list = message(7, label = "Escolher").fitTo(ChannelCapabilities.WHATSAPP)
        assertEquals(7, list.replies.size)
        assertEquals("Escolher", list.listLabel)
        val noLabel = message(7).fitTo(ChannelCapabilities.WHATSAPP)
        assertEquals(emptyList(), noLabel.replies)
        assertEquals("Escolha:\n\n1. Opção 1\n2. Opção 2\n3. Opção 3\n4. Opção 4\n5. Opção 5\n6. Opção 6\n7. Opção 7", noLabel.text)
        assertEquals(emptyList(), message(11, label = "Escolher").fitTo(ChannelCapabilities.WHATSAPP).replies, "a list holds ten")
        assertEquals(null, message(3, label = "Escolher").fitTo(ChannelCapabilities.WHATSAPP).listLabel, "buttons need no label")
    }

    @Test
    fun `the website has no taps, so options are numbered lines the customer answers by typing`() {
        val out = message(2).fitTo(ChannelCapabilities.WEB)
        assertEquals(emptyList(), out.replies)
        assertEquals("Escolha:\n\n1. Opção 1\n2. Opção 2", out.text)
    }

    @Test
    fun `a title or text too long for a tap is numbered instead`() {
        assertEquals(emptyList(), message(2) { "Um título bem comprido demais" }.fitTo(ChannelCapabilities.INSTAGRAM).replies)
        assertEquals(emptyList(), message(2, text = "ã".repeat(501)).fitTo(ChannelCapabilities.INSTAGRAM).replies)
    }

    @Test
    fun `numbered always numbers, and hidden leaves the author's own words`() {
        assertEquals(emptyList(), message(2, OptionsDisplay.NUMBERED).fitTo(ChannelCapabilities.INSTAGRAM).replies)
        assertEquals("Escolha:\n\n1. Opção 1\n2. Opção 2", message(2, OptionsDisplay.NUMBERED).fitTo(ChannelCapabilities.INSTAGRAM).text)
        val hidden = message(2, OptionsDisplay.HIDDEN, text = "Responda 1 para unhas ou 2 para pestanas").fitTo(ChannelCapabilities.WHATSAPP)
        assertEquals(FlowOutgoing("Responda 1 para unhas ou 2 para pestanas", emptyList(), null), hidden)
    }

    @Test
    fun `a message with no options goes out as it is`() {
        assertEquals(FlowOutgoing("Obrigado!", emptyList(), null), message(0, text = "Obrigado!").fitTo(ChannelCapabilities.WHATSAPP))
    }

    @Test
    fun `answers fill their placeholders, and an unknown one is left empty`() {
        assertEquals("Olá Maria, marcado para 2026-11-18 às .", fillAnswers("Olá {name}, marcado para {date} às {time}.", mapOf("name" to "Maria", "date" to "2026-11-18")))
        assertEquals("sem chaves {Name}", fillAnswers("sem chaves {Name}", emptyMap()))
    }

    @Test
    fun `an option's payload carries its position, so two options to one step differ`() {
        assertEquals("flow:f:s:2", FlowPayload.of("f", "s", 2))
        assertEquals("f" to "s", FlowPayload.parse("flow:f:s:2"))
        assertEquals("f" to "s", FlowPayload.parse("flow:f:s"))
        assertEquals(null, FlowPayload.parse("flow:f:s:x"))
        assertEquals(null, FlowPayload.parse("flow:f:"))
    }
}
