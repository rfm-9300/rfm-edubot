package com.rfm.edubot.flows

import com.rfm.edubot.channel.QuickReply
import com.rfm.edubot.tenant.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatFlowsTest {
    private val flow = ChatFlow(
        id = "servicos",
        name = "Menu de serviços",
        channels = setOf(Platform.INSTAGRAM),
        keywords = listOf("Olá", "Bom dia", "Oi", "Quanto custa a limpeza de pele?", "Formação"),
        steps = listOf(
            FlowStep("menu", "Olá! Qual dos serviços lhe interessa?", listOf(FlowButton("Pele/Laser", "pele"), FlowButton("Sobrancelha/Formação", "sobrancelha"))),
            FlowStep("pele", "Selecione a especialidade", listOf(FlowButton("Limpeza de Pele", "contacto"), FlowButton("Depil. Laser", "contacto"))),
            FlowStep("sobrancelha", "Selecione a especialidade", listOf(FlowButton("Microblading", "contacto"))),
            FlowStep("contacto", "Fale com a nossa equipa: https://example.com/contacto"),
        ),
    )
    private val flows = ChatFlows(listOf(flow))

    private fun stepFor(text: String, payload: String? = null, platform: Platform = Platform.INSTAGRAM) =
        flows.replyTo(platform, text, payload)?.step?.id

    @Test
    fun `a keyword starts the flow at its first step, with quick replies that say where they lead`() {
        val reply = flows.replyTo(Platform.INSTAGRAM, "Olá, bom dia!", null)!!

        assertEquals("menu", reply.step.id)
        assertEquals(
            listOf(QuickReply("Pele/Laser", "flow:servicos:pele"), QuickReply("Sobrancelha/Formação", "flow:servicos:sobrancelha")),
            reply.quickReplies,
        )
    }

    @Test
    fun `keywords match whole words, ignoring case, accents and punctuation`() {
        assertEquals("menu", stepFor("OLA!!"))
        assertEquals("menu", stepFor("oi, tudo bem?"))
        assertEquals("menu", stepFor("Boa tarde. Quanto custa a limpeza de pele"))
        assertEquals("menu", stepFor("Têm formacao online?"))
        assertNull(stepFor("Depois falo convosco"))
        assertNull(stepFor("Têm vaga no sábado?"))
    }

    @Test
    fun `a tapped reply goes to its step even when its title holds a keyword`() {
        assertEquals("sobrancelha", stepFor("Sobrancelha/Formação", payload = "flow:servicos:sobrancelha"))
        assertEquals("contacto", stepFor("Microblading", payload = "flow:servicos:contacto"))
    }

    @Test
    fun `a payload whose step is gone falls back to the keywords`() {
        assertEquals("menu", stepFor("Sobrancelha/Formação", payload = "flow:servicos:gone"))
        assertNull(stepFor("Microblading", payload = "flow:servicos:gone"))
        assertNull(stepFor("Microblading", payload = "something-else"))
    }

    @Test
    fun `a disabled flow, or one on another channel, never answers`() {
        assertNull(ChatFlows(listOf(flow.copy(enabled = false))).replyTo(Platform.INSTAGRAM, "Olá", null))
        assertNull(stepFor("Olá", platform = Platform.WHATSAPP))
        assertNull(stepFor("Pele/Laser", payload = "flow:servicos:pele", platform = Platform.WHATSAPP))
    }

    @Test
    fun `a flow within Instagram's limits has no problems`() {
        assertEquals(emptyList(), flow.problems())
    }

    @Test
    fun `problems name what Instagram would refuse and buttons that lead nowhere`() {
        val menu = flow.steps.first()
        fun withMenu(step: FlowStep) = flow.copy(steps = listOf(step) + flow.steps.drop(1)).problems()

        assertEquals(listOf("title:menu"), withMenu(menu.copy(buttons = listOf(FlowButton("Sobrancelha e Formação", "pele")))))
        assertEquals(listOf("buttons:menu"), withMenu(menu.copy(buttons = List(14) { FlowButton("Opção $it", "pele") })))
        assertEquals(listOf("next:menu"), withMenu(menu.copy(buttons = listOf(FlowButton("Pele", "nowhere")))))
        assertEquals(listOf("text:menu"), withMenu(menu.copy(text = "\u00e3".repeat(501))))
        assertEquals(listOf("channels"), flow.copy(channels = setOf(Platform.WHATSAPP)).problems())
        assertEquals(listOf("id"), flow.copy(id = "Serviços").problems())
        assertEquals(listOf("keywords"), flow.copy(keywords = listOf("?!")).problems())
        assertEquals(listOf("duplicate_step"), flow.copy(steps = flow.steps + flow.steps.last()).problems())
    }
}
