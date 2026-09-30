package com.rfm.edubot.whatsapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TemplateDraftTest {
    private val valid = TemplateDraft(
        name = "booking_reminder",
        language = "pt_PT",
        category = "UTILITY",
        header = "Lembrete",
        body = "Olá {{1}}, a sua limpeza é amanhã às {{2}}. Até lá!",
        bodyExamples = listOf("Ana", "10h"),
        footer = "The Bots Lab",
        quickReplies = listOf("Confirmar", "Remarcar"),
    )

    @Test
    fun `a draft that follows Meta's rules has no problem`() {
        assertNull(valid.problem())
        assertNull(valid.copy(header = null, footer = null, quickReplies = emptyList(), body = "Obrigado pela preferência!", bodyExamples = emptyList()).problem())
    }

    @Test
    fun `names are lowercase letters, digits and underscores`() {
        assertEquals("template_name_invalid", valid.copy(name = "Booking Reminder").problem())
        assertEquals("template_name_invalid", valid.copy(name = "").problem())
    }

    @Test
    fun `language and category must be ones Meta accepts here`() {
        assertEquals("template_language_invalid", valid.copy(language = "Portuguese").problem())
        assertEquals("template_category_invalid", valid.copy(category = "AUTHENTICATION").problem())
    }

    @Test
    fun `body variables are numbered from 1 without gaps and never open or close the text`() {
        assertEquals("template_variables_invalid", valid.copy(body = "Olá {{1}}, até {{3}}.", bodyExamples = listOf("Ana", "10h")).problem())
        assertEquals("template_variables_invalid", valid.copy(body = "{{1}}, a sua limpeza é amanhã.", bodyExamples = listOf("Ana")).problem())
        assertEquals("template_variables_invalid", valid.copy(body = "A sua limpeza é às {{1}}", bodyExamples = listOf("10h")).problem())
        assertEquals("template_variables_invalid", valid.copy(body = "Olá {{nome}}, até amanhã.", bodyExamples = listOf("Ana")).problem())
    }

    @Test
    fun `every variable needs an example`() {
        assertEquals("template_examples_missing", valid.copy(bodyExamples = listOf("Ana")).problem())
        assertEquals("template_examples_missing", valid.copy(bodyExamples = listOf("Ana", " ")).problem())
    }

    @Test
    fun `header, footer and buttons stay within WhatsApp's limits`() {
        assertEquals("template_header_invalid", valid.copy(header = "Olá {{1}}").problem())
        assertEquals("template_footer_invalid", valid.copy(footer = "x".repeat(61)).problem())
        assertEquals("template_buttons_invalid", valid.copy(quickReplies = listOf("A", "B", "C", "D")).problem())
        assertEquals("template_buttons_invalid", valid.copy(quickReplies = listOf("Sim", "Sim")).problem())
        assertEquals("template_body_invalid", valid.copy(body = " ", bodyExamples = emptyList()).problem())
    }
}
