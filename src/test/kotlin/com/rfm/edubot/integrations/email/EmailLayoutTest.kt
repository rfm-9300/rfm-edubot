package com.rfm.edubot.integrations.email

import com.rfm.edubot.tenant.model.DocumentLayouts
import com.rfm.edubot.tenant.model.DocumentTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailLayoutTest {
    private val template = DocumentTemplate(
        companyName = "Obras & Filhos",
        taxId = "PT509999999",
        phone = "+351 210 000 000",
        email = "geral@obras.pt",
        footerText = "Obrigado pela preferência.",
        accentColor = "0f766e",
    )

    @Test
    fun `the html brands the body and escapes everything the company or a run wrote`() {
        val body = "Olá <b>Ana</b>,\n\nVeja https://obras.pt/orcamentos/12?x=1&y=2.\nAté breve\n\njavascript:alert(1)"
        val html = EmailLayout.render("Orçamento <12>", body, template, "Rui\n<script>x</script>", "pt-PT").html

        assertTrue(html.startsWith("<!DOCTYPE html><html lang=\"pt-PT\">"))
        assertTrue("<title>Orçamento &lt;12&gt;</title>" in html)
        assertTrue("border-top:4px solid #0F766E" in html)
        assertTrue(">Obras &amp; Filhos</td>" in html)
        assertTrue("Olá &lt;b&gt;Ana&lt;/b&gt;," in html)
        assertTrue("<a href=\"https://obras.pt/orcamentos/12?x=1&amp;y=2\" style=\"color:#1d4ed8;\">https://obras.pt/orcamentos/12?x=1&amp;y=2</a>.<br>Até breve" in html, html)
        assertFalse("href=\"javascript" in html)
        assertTrue("Rui<br>&lt;script&gt;x&lt;/script&gt;" in html)
        assertFalse("<script>" in html)
        assertTrue("Obras &amp; Filhos · PT509999999 · +351 210 000 000 · geral@obras.pt<br>Obrigado pela preferência." in html)
        assertEquals(3, Regex("<p style").findAll(html).count() - 1, "three paragraphs and the signature")
    }

    @Test
    fun `the text version keeps the body and signs off with the standard delimiter`() {
        val rendered = EmailLayout.render("Assunto", "  Olá Ana,\n\nObrigado.  ", DocumentTemplate(), "Rui Silva", "en")
        assertEquals("Olá Ana,\n\nObrigado.\n\n-- \nRui Silva", rendered.text)
        assertTrue("border-top:4px solid ${DocumentLayouts.DEFAULT_ACCENT}" in rendered.html, "the built-in accent without one set")
        assertFalse("border-top:1px solid #e5e7eb" in rendered.html, "no footer without company details")
        assertEquals("Olá", EmailLayout.render("S", "Olá", DocumentTemplate(), "  ", "en").text)
    }
}
