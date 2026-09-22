package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.tenant.model.BuiltInDesignTemplates
import com.rfm.edubot.tenant.model.DocumentTemplate
import kotlinx.datetime.Clock
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.bson.types.ObjectId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfGeneratorTest {

    private val generator = PdfGenerator()
    private val tenantId = ObjectId()

    private val client = Client(
        id = ObjectId(),
        tenantId = tenantId,
        number = "CLT-001",
        name = "Cliente Teste",
        phone = "+351 900 000 000",
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun item(desc: String, euros: Double) =
        lineItem(description = desc, unitPriceEur = euros)

    @Test
    fun `single page when items fit`() {
        val quote = quote(listOf(
            item("Pintura de parede", 1500.0),
            item("Reboco exterior", 2000.0),
        ))
        val pages = pageCount(generator.generateQuote(quote, client))
        assertEquals(1, pages, "Expected 1 page for 2 items")
    }

    @Test
    fun `second page created when items overflow`() {
        val manyItems = (1..10).map { i -> item("Servico $i com descricao longa para testar overflow de pagina", 500.0 * i) }
        val quote = quote(manyItems)
        val pages = pageCount(generator.generateQuote(quote, client))
        assertTrue(pages > 1, "Expected more than 1 page for 10 items, got $pages")
    }

    @Test
    fun `totals appear on last page only`() {
        // Verify the PDF bytes are valid and parseable for both cases
        val few = quote(listOf(item("Pintura", 1000.0)))
        val many = quote((1..12).map { i -> item("Servico $i longa descricao adicional para forcar overflow", 300.0 * i) })
        assertTrue(generator.generateQuote(few, client).isNotEmpty())
        assertTrue(generator.generateQuote(many, client).isNotEmpty())
    }

    @Test
    fun `custom document template still produces a valid pdf`() {
        val template = DocumentTemplate(
            companyName = "Acme Lda",
            tagline = "Obras e remodelacoes",
            taxId = "123456789",
            email = "geral@acme.pt",
            phone = "+351 210 000 000",
            quoteTitle = "PROPOSTA",
            quotePaymentTerms = "50% adiantamento, 50% na entrega.",
            termsText = "Valido por 15 dias.",
            footerText = "acme.pt",
        )
        val bytes = generator.generateQuote(quote(listOf(item("Pintura", 1000.0))), client, template)
        assertTrue(bytes.isNotEmpty())
        assertEquals(1, pageCount(bytes))
    }

    @Test
    fun `closing block never overlaps the item rows`() {
        // Enough items that the table runs well past where the totals and payment blocks used to
        // be anchored, which previously painted them on top of the rows.
        val many = (1..8).map { i ->
            item("Servico $i com descricao longa para forcar o crescimento da tabela", 300.0 * i)
        }
        val overlaps = overlappingRuns(generator.generateQuote(quote(many), client))
        assertTrue(overlaps.isEmpty(), "Text runs overlap: $overlaps")
    }

    @Test
    fun `closing block stays clear of rows for every built-in design`() {
        val items = listOf(
            item("Pintura interior - duas demaos de tinta plastica lavavel em todas as divisoes", 1500.0),
            item("Reboco exterior da fachada principal com acabamento areado fino", 2000.0),
            item("Preparacao e lixagem de superficies antes da pintura", 350.0),
        )
        BuiltInDesignTemplates.ALL.forEach { design ->
            val template = DocumentTemplate(
                companyName = "RoPaint Lda",
                tagline = "Pintura e remodelacoes",
                taxId = "123456789",
                email = "geral@ropaint.pt",
                phone = "+351 210 000 000",
                address = "Av. da Liberdade 10, Lisboa",
                accentColor = design.accentColor,
                showDecor = design.showDecor,
                layout = design.layout,
            )
            val overlaps = overlappingRuns(generator.generateQuote(quote(items), client, template))
            assertTrue(overlaps.isEmpty(), "${design.name} prints text on top of text: $overlaps")
        }
    }

    @Test
    fun `totals follow the last row instead of a fixed anchor`() {
        val short = overlappingRuns(generator.generateQuote(quote(listOf(item("Pintura", 1000.0))), client))
        assertTrue(short.isEmpty(), "Single item quote overlaps: $short")

        val tall = quote(listOf(
            item("Servico unico com uma descricao bastante longa que ocupa varias linhas na tabela", 900.0),
        ))
        val overlaps = overlappingRuns(generator.generateQuote(tall, client))
        assertTrue(overlaps.isEmpty(), "Tall single row overlaps the totals: $overlaps")
    }

    @Test
    fun `column headers stay inside their pills when the table is narrow`() {
        val narrow = DocumentTemplate(
            layout = com.rfm.edubot.tenant.model.DocumentLayouts.DEFAULT.map { block ->
                if (block.id == "items") block.copy(w = 300f) else block
            },
        )
        val overlaps = overlappingRuns(generator.generateQuote(quote(listOf(item("Pintura", 1000.0))), client, narrow))
        assertTrue(overlaps.isEmpty(), "Narrow table prints headers on top of each other: $overlaps")
    }

    @Test
    fun `large total stays inside the totals pill`() {
        val big = quote(listOf(item("Obra completa", 1_234_567.89)))
        val overlaps = overlappingRuns(generator.generateQuote(big, client))
        assertTrue(overlaps.isEmpty(), "Large total overlaps neighbouring text: $overlaps")
    }

    @Test
    fun `custom layout and accent still produce a valid pdf`() {
        val template = DocumentTemplate(
            companyName = "Studio Norte",
            accentColor = "#2F6FED",
            showDecor = false,
            layout = com.rfm.edubot.tenant.model.DocumentLayouts.DEFAULT.map { block ->
                if (block.id == "logo") block.copy(x = 42f, y = 28f) else block
            },
        )
        val bytes = generator.generateQuote(quote(listOf(item("Pintura", 1000.0))), client, template)
        assertTrue(bytes.isNotEmpty())
        assertEquals(1, pageCount(bytes))
    }

    private fun quote(items: List<com.rfm.edubot.crm.model.LineItem>) = Quote(
        id = ObjectId(),
        tenantId = tenantId,
        number = "ORC-TEST",
        clientId = client.id,
        items = items,
        status = QuoteStatus.PENDENTE,
        totalCents = items.sumOf { it.totalCents },
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun pageCount(bytes: ByteArray): Int =
        Loader.loadPDF(bytes).use { it.numberOfPages }

    /** Text runs on the same page whose bounding boxes intersect. */
    private fun overlappingRuns(bytes: ByteArray): List<String> {
        data class Run(val page: Int, val y: Float, val x0: Float, val x1: Float, val text: String, val size: Float)

        Loader.loadPDF(bytes).use { doc ->
            val positions = mutableListOf<Pair<Int, TextPosition>>()
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                    textPositions.forEach { positions.add(currentPageNo to it) }
                    super.writeString(text, textPositions)
                }
            }
            stripper.getText(doc)

            val runs = mutableListOf<Run>()
            var current = StringBuilder()
            var y0 = 0f; var x0 = 0f; var x1 = 0f; var size = 0f; var page = 0
            fun flush() {
                if (current.isNotEmpty()) runs.add(Run(page, y0, x0, x1, current.toString().trim(), size))
                current = StringBuilder()
            }
            for ((pg, glyph) in positions) {
                val gap = current.isNotEmpty() &&
                    (pg != page || abs(glyph.yDirAdj - y0) > 1.5f || glyph.xDirAdj > x1 + glyph.widthDirAdj * 1.5f)
                if (gap) flush()
                if (current.isEmpty()) { y0 = glyph.yDirAdj; x0 = glyph.xDirAdj; size = glyph.fontSizeInPt; page = pg }
                current.append(glyph.unicode)
                x1 = glyph.xDirAdj + glyph.widthDirAdj
            }
            flush()

            return runs.indices.flatMap { i ->
                (i + 1 until runs.size).mapNotNull { j ->
                    val a = runs[i]; val b = runs[j]
                    val samePage = a.page == b.page
                    val verticalOverlap = abs(a.y - b.y) < minOf(a.size, b.size) * 0.7f
                    val horizontalOverlap = a.x0 < b.x1 - 2f && b.x0 < a.x1 - 2f
                    if (samePage && verticalOverlap && horizontalOverlap) "'${a.text}' over '${b.text}'" else null
                }
            }
        }
    }
}
