package com.rfm.edubot.tenant.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentLayoutsTest {

    @Test
    fun `sanitize drops unknown ids and clamps geometry`() {
        val cleaned = DocumentLayouts.sanitize(
            listOf(
                DocumentLayoutBlock("logo", -40f, 20f, 12f, 8f),
                DocumentLayoutBlock("unknown", 10f, 10f, 80f, 80f),
                DocumentLayoutBlock("logo", 10f, 10f, 80f, 80f),
            ),
        )
        assertEquals(1, cleaned.size)
        assertEquals("logo", cleaned[0].id)
        assertEquals(0f, cleaned[0].x)
        assertTrue(cleaned[0].w >= 36f)
        assertTrue(cleaned[0].h >= 16f)
    }

    @Test
    fun `sanitizeAccent accepts hex and rejects junk`() {
        assertEquals("#2F6FED", DocumentLayouts.sanitizeAccent("2f6fed"))
        assertEquals("#96AAB6", DocumentLayouts.sanitizeAccent("#96aab6"))
        assertEquals("", DocumentLayouts.sanitizeAccent("blue"))
        assertEquals("", DocumentLayouts.sanitizeAccent("#fff"))
    }

    @Test
    fun `resolve overlays saved blocks on the default page`() {
        val template = DocumentTemplate(
            layout = listOf(DocumentLayoutBlock("logo", 20f, 20f, 100f, 40f)),
        )
        val blocks = DocumentLayouts.resolve(template)
        assertEquals(20f, blocks.getValue("logo").x)
        assertEquals(DocumentLayouts.DEFAULT.first { it.id == "title" }.y, blocks.getValue("title").y)
    }

    @Test
    fun `overlappingIds finds intersecting visible blocks only`() {
        val blocks = listOf(
            DocumentLayoutBlock("logo", 10f, 10f, 80f, 40f),
            DocumentLayoutBlock("title", 50f, 20f, 80f, 40f),
            DocumentLayoutBlock("company", 50f, 20f, 80f, 40f, visible = false),
            DocumentLayoutBlock("footer", 10f, 200f, 80f, 20f),
        )
        assertEquals(setOf("logo", "title"), DocumentLayouts.overlappingIds(blocks))
    }

    @Test
    fun `every built-in design has a complete page with no overlapping boxes`() {
        val names = BuiltInDesignTemplates.ALL.map { it.name }
        assertEquals(
            listOf("classic", "modern", "minimal", "editorial", "ledger", "harbor", "atelier"),
            names,
        )
        BuiltInDesignTemplates.ALL.forEach { design ->
            assertEquals(DocumentLayouts.IDS.toSet(), design.layout.map { it.id }.toSet(), design.name)
            assertTrue(DocumentLayouts.overlappingIds(design.layout).isEmpty(), design.name)
            assertTrue(design.accentColor.matches(Regex("^#[0-9A-F]{6}$")), design.name)
        }
    }
}
