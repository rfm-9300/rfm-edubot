package com.rfm.edubot.integrations.email

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailFactsTest {

    @Test
    fun `quote and invoice numbers read in the company's format however they were typed`() {
        assertEquals(
            listOf("ORC-012", "FAT-007", "ORC-1234"),
            EmailFacts.documentNumbers("Sobre o orc 12 e a fatura FAT-0007, e também ORC_1234 e ORC-012 outra vez"),
        )
        assertTrue(EmailFacts.documentNumbers("FATURA de março, orçamento pedido").isEmpty(), "words that start like a prefix aren't numbers")
    }

    @Test
    fun `phones come from labels, country codes and Portuguese mobile numbers`() {
        val text = """
            Bom dia,
            Maria Silva
            Telemóvel: 912 345 678
            Tel. 213 456 789
            WhatsApp +44 7700 900123
        """.trimIndent()
        assertEquals(listOf("+351 912 345 678", "+351 213 456 789", "+447700900123"), EmailFacts.phones(text))
        assertEquals(listOf("+351 961 234 567"), EmailFacts.phones("ligue para 961234567 ou 00351 961 234 567"))
        assertTrue(EmailFacts.phones("IBAN PT50 0035 0000 9123 4567 8901 2, total 1.250,00 €, 2026-10-09").isEmpty())
    }

    @Test
    fun `tax numbers need a label and a valid check digit`() {
        assertEquals(listOf("123456789"), EmailFacts.taxIds("NIF: 123456789"))
        assertEquals(listOf("501964843"), EmailFacts.taxIds("Contribuinte n.º PT 501964843"))
        assertTrue(EmailFacts.taxIds("NIF 123456788").isEmpty(), "wrong check digit")
        assertTrue(EmailFacts.taxIds("encomenda 123456789").isEmpty(), "no label")
        assertFalse(EmailFacts.validNif("111111111"))
    }

    @Test
    fun `quoted history is left out, so the company's own signature isn't read as the sender's`() {
        val body = """
            Aceito o orçamento, obrigado!
            João

            Em seg., 5/10/2026 às 10:00, Obras Lda <obras@example.pt>
            escreveu:
            > Segue o orçamento ORC-002.
            > Tel: 211 111 111
        """.trimIndent()
        assertEquals("Aceito o orçamento, obrigado!\nJoão", EmailFacts.ownText(body))
        val facts = EmailFacts.of("Re: Orçamento ORC-002", body)
        assertEquals(listOf("ORC-002"), facts.quoteNumbers, "the subject still names the quote")
        assertTrue(facts.phones.isEmpty())

        val outlook = "Pago amanhã.\n\nFrom: Obras <obras@example.pt>\nSent: Monday\nTo: ana@example.pt\nTelf 212 222 222"
        assertEquals("Pago amanhã.", EmailFacts.ownText(outlook))
        assertEquals("> only a quote", EmailFacts.ownText("> only a quote"), "a body that is all quote stays whole")
    }

    @Test
    fun `the company's own numbers are never offered as the sender's`() {
        val facts = EmailFacts.of("Pedido", "Contactos: 912 345 678 ou 213 456 789\nNIF 123456789", own = listOf("+351 213 456 789", "PT123456789"))
        assertEquals(listOf("+351 912 345 678"), facts.phones)
        assertTrue(facts.taxIds.isEmpty())
    }
}
