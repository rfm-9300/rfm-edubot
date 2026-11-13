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

    @Test
    fun `saving checks the editor's limits, then Instagram's, and says which menu and message`() {
        fun problem(vararg menus: ChatFlow) = ChatFlows(menus.toList()).saveProblem()
        val longTitle = flow.copy(steps = listOf(flow.steps[0].copy(buttons = listOf(FlowButton("x".repeat(21), "pele")))) + flow.steps.drop(1))
        val leadsNowhere = flow.copy(id = "outro", steps = flow.steps.mapIndexed { i, s -> if (i == 2) s.copy(buttons = listOf(FlowButton("Microblading", "nowhere"))) else s })

        assertNull(flows.saveProblem())
        assertEquals(FlowProblem("name", 0, limit = ChatFlowLimits.MAX_NAME_CHARS), problem(flow.copy(name = " ")))
        assertEquals(FlowProblem("keywords", 0), problem(flow.copy(keywords = emptyList())))
        assertEquals(FlowProblem("keywords_limit", 0, limit = ChatFlowLimits.MAX_KEYWORDS), problem(flow.copy(keywords = List(51) { "palavra $it" })))
        assertEquals(FlowProblem("keyword", 0, limit = ChatFlowLimits.MAX_KEYWORD_CHARS), problem(flow.copy(keywords = listOf("x".repeat(61)))))
        assertEquals(FlowProblem("title", 0, 0, ChatFlow.MAX_TITLE), problem(longTitle))
        assertEquals(FlowProblem("next", 1, 2), problem(flow, leadsNowhere))
        assertEquals(FlowProblem("menus", limit = ChatFlowLimits.MAX_FLOWS), problem(*Array(11) { flow.copy(id = "m$it") }))
        assertEquals(FlowProblem("invalid"), problem(flow, flow))
        assertEquals(FlowProblem("invalid", 0), problem(flow.copy(id = "Serviços")))
    }

    @Test
    fun `a start gate opens on a start word as whole words, ignoring case, accents and punctuation`() {
        val gate = StartGate(words = listOf("Quero marcar", "Orçamento", "oi"))

        assertEquals(true, gate.opens("Oi!"))
        assertEquals(true, gate.opens("Boa tarde, queria um ORCAMENTO, por favor"))
        assertEquals(true, gate.opens("Quero   marcar uma consulta"))
        assertEquals(false, gate.opens("Depois falo convosco"))
        assertEquals(false, gate.opens("Quero ver, marcar fica para depois"))
        assertEquals(false, gate.opens("Olá"), "menu keywords are a different list: the gate only looks at its own words")
        assertEquals(false, gate.opens(""))
    }

    @Test
    fun `a channel has a gate only when it is on and has a word to look for`() {
        fun gates(gate: StartGate) = ChatFlows(startGates = mapOf(Platform.INSTAGRAM to gate))

        assertEquals(StartGate(words = listOf("oi")), gates(StartGate(words = listOf("oi"))).startGate(Platform.INSTAGRAM))
        assertNull(gates(StartGate(enabled = false, words = listOf("oi"))).startGate(Platform.INSTAGRAM))
        assertNull(gates(StartGate(words = listOf("?!", " "))).startGate(Platform.INSTAGRAM), "a gate nothing can open is skipped, not obeyed")
        assertNull(gates(StartGate(words = listOf("oi"))).startGate(Platform.WHATSAPP), "another channel has no gate")
        assertNull(ChatFlows().startGate(Platform.INSTAGRAM))
    }

    @Test
    fun `saving checks start gates by channel`() {
        fun problem(platform: Platform, gate: StartGate) = ChatFlows(flows = listOf(flow), startGates = mapOf(platform to gate)).saveProblem()

        assertNull(problem(Platform.WHATSAPP, StartGate(words = listOf("oi"))))
        assertNull(problem(Platform.INSTAGRAM, StartGate(enabled = false)), "a gate that is off needs no words")
        assertEquals(FlowProblem("start_words", channel = "INSTAGRAM"), problem(Platform.INSTAGRAM, StartGate(words = emptyList())))
        assertEquals(FlowProblem("start_words", channel = "WHATSAPP"), problem(Platform.WHATSAPP, StartGate(words = listOf("?!"))))
        assertEquals(
            FlowProblem("start_words_limit", channel = "INSTAGRAM", limit = ChatFlowLimits.MAX_START_WORDS),
            problem(Platform.INSTAGRAM, StartGate(words = List(51) { "palavra $it" })),
        )
        assertEquals(
            FlowProblem("start_word", channel = "WHATSAPP", limit = ChatFlowLimits.MAX_KEYWORD_CHARS),
            problem(Platform.WHATSAPP, StartGate(words = listOf("x".repeat(61)))),
        )
        assertEquals(FlowProblem("invalid", channel = "WEB"), problem(Platform.WEB, StartGate(words = listOf("oi"))), "the website's visitors would wait at a silent widget")
    }
}
