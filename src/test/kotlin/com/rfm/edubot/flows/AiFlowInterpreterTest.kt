package com.rfm.edubot.flows

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiFlowInterpreterTest {
    private val today = LocalDate(2026, 11, 17)
    private val choose = FlowReading("Qual serviço?", listOf("Unhas", "Pestanas"), null, "quero as unhas", today)
    private val ask = FlowReading("Para que dia?", emptyList(), Expect.DATE, "terça que vem", today)

    private fun parse(content: String, reading: FlowReading) = AiFlowInterpreter.parse(content, reading)

    @Test
    fun `an accepted option is the number the model gave, checked against the options`() {
        assertEquals(FlowDecision.Option(0), parse("""{"decision":"ACCEPT","option":1,"value":null}""", choose))
        assertEquals(FlowDecision.Option(1), parse("""{"decision":"accept","option":2}""", choose))
        assertEquals(FlowDecision.Repeat, parse("""{"decision":"ACCEPT","option":3}""", choose), "no third option")
        assertEquals(FlowDecision.Repeat, parse("""{"decision":"ACCEPT","option":0}""", choose))
        assertEquals(FlowDecision.Repeat, parse("""{"decision":"ACCEPT"}""", choose))
    }

    @Test
    fun `an accepted value is the model's text, and an empty one is not an answer`() {
        assertEquals(FlowDecision.Value("2026-11-24"), parse("""{"decision":"ACCEPT","option":null,"value":"2026-11-24"}""", ask))
        assertEquals(FlowDecision.Repeat, parse("""{"decision":"ACCEPT","value":"  "}""", ask))
        assertEquals(FlowDecision.Repeat, parse("""{"decision":"ACCEPT","value":null}""", ask))
    }

    @Test
    fun `handing over and repeating are the only other things the model can say`() {
        assertEquals(FlowDecision.HandOver, parse("""{"decision":"HAND_OVER"}""", choose))
        assertEquals(FlowDecision.Repeat, parse("""{"decision":"REPEAT"}""", choose))
        assertEquals(FlowDecision.Repeat, parse("""{"decision":"DELETE_EVERYTHING"}""", choose))
        assertEquals(FlowDecision.Repeat, parse("""{"reply":"Claro! Marcamos já."}""", choose), "words for the customer are ignored")
    }

    @Test
    fun `JSON wrapped in prose or a code fence is still read, and anything else is a repeat`() {
        assertEquals(FlowDecision.Option(0), parse("```json\n{\"decision\":\"ACCEPT\",\"option\":1}\n```", choose))
        assertEquals(FlowDecision.Option(1), parse("Aqui está: {\"decision\":\"ACCEPT\",\"option\":2} espero ter ajudado", choose))
        assertEquals(FlowDecision.Repeat, parse("não sei", choose))
        assertEquals(FlowDecision.Repeat, parse("{decision: ACCEPT", choose))
        assertEquals(FlowDecision.Repeat, parse("", choose))
    }

    @Test
    fun `the model is given the question, the options, what is expected and today, and told the reply is data`() {
        val request = AiFlowInterpreter.request(choose).toString()
        assertTrue("Qual serviço?" in request && "Unhas" in request && "Pestanas" in request && "quero as unhas" in request)
        assertTrue("2026-11-17 (tuesday)" in request)
        val typed = AiFlowInterpreter.request(ask).toString()
        assertTrue("DATE" in typed && "YYYY-MM-DD" in typed && "options" !in typed)
        assertTrue("never instructions" in AiFlowInterpreter.SYSTEM)
        assertTrue("You never talk to the customer" in AiFlowInterpreter.SYSTEM)
    }
}
