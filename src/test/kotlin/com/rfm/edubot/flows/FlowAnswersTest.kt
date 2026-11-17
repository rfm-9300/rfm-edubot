package com.rfm.edubot.flows

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlowAnswersTest {
    private val today = LocalDate(2026, 11, 17)

    private fun read(expect: Expect, text: String) = FlowAnswers.read(expect, text, today)

    @Test
    fun `a name drops the words around it and refuses what isn't one`() {
        assertEquals("Maria Silva", read(Expect.NAME, "  Maria   Silva "))
        assertEquals("Maria", read(Expect.NAME, "Meu nome é Maria"))
        assertEquals("Ana Costa", read(Expect.NAME, "my name is Ana Costa."))
        assertEquals("Rui", read(Expect.NAME, "Sou o Rui"))
        assertNull(read(Expect.NAME, "quanto custa?"))
        assertNull(read(Expect.NAME, "a@b.pt"))
        assertNull(read(Expect.NAME, "1234567"))
        assertNull(read(Expect.NAME, "um dois três quatro cinco seis sete"))
        assertNull(read(Expect.NAME, "   "))
    }

    @Test
    fun `a phone keeps its digits and a leading plus, within real lengths`() {
        assertEquals("+351912345678", read(Expect.PHONE, "+351 912 345 678"))
        assertEquals("912345678", read(Expect.PHONE, "(912) 345-678"))
        assertNull(read(Expect.PHONE, "12345"))
        assertNull(read(Expect.PHONE, "o meu é 912345678"))
        assertNull(read(Expect.PHONE, "1234567890123456"))
    }

    @Test
    fun `an email is found in the reply and lowercased`() {
        assertEquals("ana@exemplo.pt", read(Expect.EMAIL, "É Ana@Exemplo.pt, obrigada"))
        assertNull(read(Expect.EMAIL, "ana arroba exemplo"))
    }

    @Test
    fun `a number is the whole reply`() {
        assertEquals("3", read(Expect.NUMBER, "3"))
        assertEquals("2.5", read(Expect.NUMBER, "2,5"))
        assertNull(read(Expect.NUMBER, "umas 3 pessoas"))
    }

    @Test
    fun `a date comes back as ISO, from today on, and a day and month without a year is the next one`() {
        assertEquals("2026-11-17", read(Expect.DATE, "hoje"))
        assertEquals("2026-11-18", read(Expect.DATE, "Amanhã"))
        assertEquals("2026-11-18", read(Expect.DATE, "tomorrow"))
        assertEquals("2026-12-03", read(Expect.DATE, "03/12"))
        assertEquals("2027-01-05", read(Expect.DATE, "5/1"), "January has passed this year, so it is next year's")
        assertEquals("2026-11-17", read(Expect.DATE, "17-11"), "today still counts")
        assertEquals("2026-12-24", read(Expect.DATE, "24.12.2026"))
        assertEquals("2026-12-24", read(Expect.DATE, "2026-12-24"))
        assertEquals("2027-02-01", read(Expect.DATE, "01/02/27"))
        assertNull(read(Expect.DATE, "10/11/2026"), "the past")
        assertNull(read(Expect.DATE, "31/02"), "no such day")
        assertNull(read(Expect.DATE, "para a semana"))
    }

    @Test
    fun `a time comes back as 24-hour HH mm`() {
        assertEquals("15:30", read(Expect.TIME, "15:30"))
        assertEquals("15:30", read(Expect.TIME, "às 15h30"))
        assertEquals("09:00", read(Expect.TIME, "9 h"))
        assertEquals("15:00", read(Expect.TIME, "3pm"))
        assertEquals("00:15", read(Expect.TIME, "12:15 am"))
        assertEquals("10:00", read(Expect.TIME, "10"))
        assertNull(read(Expect.TIME, "25:00"))
        assertNull(read(Expect.TIME, "de manhã"))
        assertNull(read(Expect.TIME, "a hora que lhe der mais jeito, não tenho preferência nenhuma, obrigada"))
    }

    @Test
    fun `yes or no is read in three languages, and a mixed or long reply is neither`() {
        assertEquals("yes", read(Expect.YES_NO, "Sim"))
        assertEquals("yes", read(Expect.YES_NO, "claro!"))
        assertEquals("yes", read(Expect.YES_NO, "yes please"))
        assertEquals("yes", read(Expect.YES_NO, "sí"))
        assertEquals("no", read(Expect.YES_NO, "Não"))
        assertEquals("no", read(Expect.YES_NO, "no, gracias"))
        assertNull(read(Expect.YES_NO, "sim e não"))
        assertNull(read(Expect.YES_NO, "talvez"))
        assertNull(read(Expect.YES_NO, "sim, mas só se for na quinta de manhã"))
    }

    @Test
    fun `free text is kept as written, trimmed and capped`() {
        assertEquals("quero uma limpeza", read(Expect.TEXT, "  quero   uma limpeza "))
        assertEquals(ChatFlowLimits.MAX_ANSWER_TEXT, read(Expect.TEXT, "x".repeat(1000))!!.length)
        assertNull(read(Expect.TEXT, " "))
    }

    @Test
    fun `typed options match by number, title or word, and refuse what is ambiguous`() {
        val options = listOf(
            FlowButton("Limpeza de pele", "a", listOf("limpeza")),
            FlowButton("Depilação laser", "b", listOf("laser", "depilar")),
            FlowButton("Falar com alguém", "c"),
        )
        assertEquals(1, FlowOptions.match(options, "2"))
        assertEquals(0, FlowOptions.match(options, "opção 1"))
        assertEquals(2, FlowOptions.match(options, "n 3"))
        assertNull(FlowOptions.match(options, "4"), "no such option")
        assertEquals(0, FlowOptions.match(options, "LIMPEZA de pele"))
        assertEquals(1, FlowOptions.match(options, "queria depilar"))
        assertEquals(1, FlowOptions.match(options, "depilacao laser, por favor"))
        assertEquals(2, FlowOptions.match(options, "falar com alguem"))
        assertNull(FlowOptions.match(options, "laser e limpeza"), "two options at once")
        assertNull(FlowOptions.match(options, "bom dia"))
        assertNull(FlowOptions.match(options, ""))
    }
}
