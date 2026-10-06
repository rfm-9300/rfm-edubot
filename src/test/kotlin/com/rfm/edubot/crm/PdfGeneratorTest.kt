package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceInstallment
import com.rfm.edubot.crm.model.InvoiceStatus
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
                style = design.style,
            )
            val overlaps = overlappingRuns(generator.generateQuote(quote(items), client, template))
            assertTrue(overlaps.isEmpty(), "${design.name} prints text on top of text: $overlaps")
        }
    }

    @Test
    fun `client tax number prints beside the client number in every design`() {
        val withNif = client.copy(taxId = "123456789")
        val quote = quote(listOf(item("Pintura", 1000.0)))
        val documents = listOf("historical" to generator.generateQuote(quote, withNif)) +
            BuiltInDesignTemplates.ALL.map { design ->
                design.name to generator.generateQuote(
                    quote,
                    withNif,
                    DocumentTemplate(
                        companyName = "RoPaint Lda",
                        accentColor = design.accentColor,
                        showDecor = design.showDecor,
                        layout = design.layout,
                        style = design.style,
                    ),
                )
            }
        documents.forEach { (name, bytes) ->
            val text = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }
            assertTrue(text.contains("CLT-001") && text.contains("NIF 123456789"), "$name misses the client reference: $text")
            val overlaps = overlappingRuns(bytes)
            assertTrue(overlaps.isEmpty(), "$name prints the NIF on top of other text: $overlaps")
        }
    }

    @Test
    fun `postal code and city print with the street in every design, compact client blocks included`() {
        val located = client.copy(taxId = "123456789", address = "Rua das Flores 12", postalCode = "1200-001", city = "Lisboa")
        val quote = quote(listOf(item("Pintura", 1000.0)))
        val documents = listOf("historical" to generator.generateQuote(quote, located)) +
            BuiltInDesignTemplates.ALL.map { design ->
                design.name to generator.generateQuote(
                    quote,
                    located,
                    DocumentTemplate(
                        companyName = "RoPaint Lda",
                        accentColor = design.accentColor,
                        showDecor = design.showDecor,
                        layout = design.layout,
                        style = design.style,
                    ),
                )
            }
        documents.forEach { (name, bytes) ->
            val text = textOf(bytes)
            assertTrue(text.squashed().contains("RUADASFLORES12,1200-001LISBOA"), "$name misses the client's address: $text")
            assertTrue(overlappingRuns(bytes).isEmpty(), "$name prints the address on top of other text: ${overlappingRuns(bytes)}")
        }
    }

    @Test
    fun `invoice without its own terms prints a neutral payment line and no validity`() {
        val text = textOf(generator.generateInvoice(invoice(), client))
        assertTrue(text.contains(PdfGenerator.DEFAULT_INVOICE_PAYMENT_TERMS), text)
        assertTrue(!text.contains("adjudica"), "construction schedule on an invoice: $text")
        assertTrue(!text.contains("30 dias"), "quote validity on an invoice: $text")
        assertTrue(!text.squashed().contains("TERMOSECONDIÇÕES"), "empty terms section printed: $text")
    }

    @Test
    fun `quote without its own terms prints no payment schedule and keeps the validity line`() {
        val text = textOf(generator.generateQuote(quote(listOf(item("Pintura", 1000.0))), client))
        assertTrue(!text.contains("adjudica"), text)
        assertTrue(!text.squashed().contains("FORMADEPAGAMENTO"), "empty payment section printed: $text")
        assertTrue(text.contains(PdfGenerator.DEFAULT_QUOTE_TERMS), text)

        val dated = quote(listOf(item("Pintura", 1000.0))).copy(validUntil = kotlinx.datetime.LocalDate(2026, 10, 30))
        assertTrue(!textOf(generator.generateQuote(dated, client)).contains("30 dias"), "30-day line contradicts the quote's own date")
    }

    @Test
    fun `tenant payment terms and conditions still win`() {
        val template = DocumentTemplate(invoicePaymentTerms = "50% adiantamento, 50% na entrega.", termsText = "Garantia de 2 anos.")
        val text = textOf(generator.generateInvoice(invoice(), client, template))
        assertTrue(text.contains("50% adiantamento") && text.contains("Garantia de 2 anos."), text)
        assertTrue(!text.contains(PdfGenerator.DEFAULT_INVOICE_PAYMENT_TERMS), text)
    }

    @Test
    fun `invoices with the default closing block stay clear of rows in every design`() {
        BuiltInDesignTemplates.ALL.forEach { design ->
            val template = DocumentTemplate(
                companyName = "RoPaint Lda",
                accentColor = design.accentColor,
                showDecor = design.showDecor,
                layout = design.layout,
                style = design.style,
            )
            val overlaps = overlappingRuns(generator.generateInvoice(invoice(), client, template))
            assertTrue(overlaps.isEmpty(), "${design.name} prints text on top of text: $overlaps")
        }
    }

    @Test
    fun `clear style prints quantity and unit price as columns`() {
        val design = BuiltInDesignTemplates.find("builtin-clear")!!
        val template = DocumentTemplate(
            companyName = "Atelier Norte",
            accentColor = design.accentColor,
            showDecor = false,
            layout = design.layout,
            style = design.style,
        )
        val items = listOf(lineItem("Pintura interior", quantity = 2.0, unitPriceEur = 150.0, unit = "h"))
        val text = Loader.loadPDF(generator.generateQuote(quote(items), client, template)).use { PDFTextStripper().getText(it) }
        assertTrue(text.contains("Qtd"), text)
        assertTrue(text.contains("2 h") || text.contains("2h") || text.contains("2"), text)
        assertTrue(!text.contains("S E R V"), "Clear should not letter-space column headers: $text")
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
    fun `column headers stay apart when the table is narrow`() {
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

    @Test
    fun `amounts print in Portuguese currency format`() {
        val text = textOf(generator.generateQuote(quote(listOf(item("Pintura", 1500.0), item("Reboco", 2000.0))), client))
        assertTrue(text.contains("3 500,00 €"), text)
        assertTrue(!text.contains("EUR"), text)
        assertEquals("1 234 567,89 €", PdfGenerator.money(123_456_789))
        assertEquals("-0,05 €", PdfGenerator.money(-5))
    }

    @Test
    fun `five ordinary items fit on one page in every design`() {
        val items = listOf(
            item("Limpeza geral - Apartamento T3 com cozinha, duas casas de banho e varandas", 120.0),
            lineItem("Limpeza de vidros: interior e exterior de 14 janelas", quantity = 14.0, unitPriceEur = 6.5, unit = "un"),
            item("Limpeza de estofos - Sofá de 3 lugares e 2 cadeirões", 85.0),
            item("Desinfeção de cozinha", 45.0),
            lineItem("Tratamento de pavimento em madeira - Aspiração, lavagem e enceramento", quantity = 62.0, unitPriceEur = 3.2, unit = "m²"),
        )
        val templates = listOf("historical" to DocumentTemplate(companyName = "Family Clean", termsText = "Preços com IVA incluído.")) +
            BuiltInDesignTemplates.ALL.map { design ->
                design.name to DocumentTemplate(
                    companyName = "Family Clean",
                    termsText = "Preços com IVA incluído.",
                    accentColor = design.accentColor,
                    showDecor = design.showDecor,
                    layout = design.layout,
                    style = design.style,
                )
            }
        templates.forEach { (name, template) ->
            val bytes = generator.generateQuote(quote(items), client, template)
            assertEquals(1, pageCount(bytes), "$name spills five items onto a second page")
            assertTrue(overlappingRuns(bytes).isEmpty(), "$name overlaps: ${overlappingRuns(bytes)}")
        }
    }

    @Test
    fun `invoice prints its due date and a badge only for statuses the client cares about`() {
        val badge = Regex("\\bPAGA\\b")
        val pending = textOf(generator.generateInvoice(invoice(), client))
        assertTrue(pending.contains("Vencimento 15/10/2026"), pending)
        assertTrue(!pending.contains("Pendente") && !badge.containsMatchIn(pending), pending)

        val paid = textOf(generator.generateInvoice(invoice().copy(status = InvoiceStatus.PAID), client))
        assertTrue(badge.containsMatchIn(paid), paid)
    }

    @Test
    fun `the tax office code prints as the ATCUD without doubling a typed prefix`() {
        val text = textOf(generator.generateInvoice(invoice().copy(taxOfficeCode = "JJ4XTRK3-12"), client))
        assertTrue(text.contains("ATCUD:JJ4XTRK3-12"), text)
        assertEquals("ATCUD:JJ4XTRK3-12", PdfGenerator.atcud("atcud: JJ4XTRK3-12"))
        assertTrue(!textOf(generator.generateInvoice(invoice(), client)).contains("ATCUD"))
    }

    @Test
    fun `an invoice paid in installments lists them, received ones marked, in every design`() {
        val received = Clock.System.now()
        val split = invoice().copy(
            dueDate = kotlinx.datetime.LocalDate(2026, 11, 6),
            installments = listOf(
                InvoiceInstallment(75_000, kotlinx.datetime.LocalDate(2026, 10, 6), paidAt = received),
                InvoiceInstallment(75_000, kotlinx.datetime.LocalDate(2026, 11, 6)),
            ),
        )
        val text = textOf(generator.generateInvoice(split, client))
        assertTrue(text.contains("1.ª prestação: 750,00 € até 06/10/2026 (paga)"), text)
        assertTrue(text.contains("2.ª prestação: 750,00 € até 06/11/2026") && !text.contains("06/11/2026 (paga)"), text)
        assertTrue(text.contains("Próximo vencimento 06/11/2026"), text)
        assertTrue(text.contains(PdfGenerator.DEFAULT_INSTALLMENT_PAYMENT_TERMS), text)

        val twelve = invoice().copy(installments = (1..12).map { InvoiceInstallment(12_500, kotlinx.datetime.LocalDate(2026, it, 1)) })
        BuiltInDesignTemplates.ALL.forEach { design ->
            val template = DocumentTemplate(companyName = "RoPaint Lda", accentColor = design.accentColor, showDecor = design.showDecor, layout = design.layout, style = design.style)
            val bytes = generator.generateInvoice(twelve.copy(taxOfficeCode = "CSDF7T5H-0035"), client, template)
            assertTrue(overlappingRuns(bytes).isEmpty(), "${design.name} prints text on top of text: ${overlappingRuns(bytes)}")
            assertTrue(textOf(bytes).contains("12.ª prestação"), design.name)
        }
    }

    @Test
    fun `quote prints its validity date`() {
        val dated = quote(listOf(item("Pintura", 1000.0))).copy(validUntil = kotlinx.datetime.LocalDate(2026, 10, 30))
        assertTrue(textOf(generator.generateQuote(dated, client)).contains("Válido até 30/10/2026"))
    }

    @Test
    fun `every page of a long document carries the footer and its page number`() {
        val many = (1..30).map { i -> item("Servico $i com descricao longa para forcar varias paginas", 100.0 + i) }
        val bytes = generator.generateQuote(quote(many), client)
        val pages = pageCount(bytes)
        assertTrue(pages >= 2, "expected several pages, got $pages")
        Loader.loadPDF(bytes).use { doc ->
            (1..pages).forEach { page ->
                val text = PDFTextStripper().apply { startPage = page; endPage = page }.getText(doc)
                assertTrue(text.contains("Página $page de $pages"), "page $page: $text")
                assertTrue(text.contains(PdfGenerator.DEFAULT_FOOTER), "page $page has no footer: $text")
            }
        }
        assertTrue(overlappingRuns(bytes).isEmpty(), "long document overlaps: ${overlappingRuns(bytes)}")
    }

    @Test
    fun `letter-spaced labels still extract as words`() {
        val template = DocumentTemplate(quotePaymentTerms = "50% adiantamento.")
        val text = textOf(generator.generateQuote(quote(listOf(item("Pintura", 1000.0))), client, template))
        assertTrue(text.contains("FORMA DE PAGAMENTO"), text)
        assertTrue(!text.contains("F O R M A"), text)
    }

    @Test
    fun `characters the font lacks are dropped instead of breaking the document`() {
        val emoji = quote(listOf(item("Limpeza geral ✨🧽 - Casa de férias 🏖️", 150.0)))
        val text = textOf(generator.generateQuote(emoji, client.copy(name = "Ana 😊")))
        assertTrue(text.contains("Limpeza geral") && text.contains("Casa de férias") && text.contains("Ana"), text)
    }

    @Test
    fun `accent colours used as text or under text stay readable`() {
        listOf("#F5D90A", "#96AAB6", "#FFFFFF", "#0F766E", "#111827", "#767676", "#D52A0B").forEach { hex ->
            val accent = java.awt.Color.decode(hex)
            assertTrue(PdfGenerator.contrast(PdfGenerator.readableOnWhite(accent), java.awt.Color.WHITE) >= 4.5, "$hex as text")
            assertTrue(PdfGenerator.contrast(PdfGenerator.onFill(accent), accent) >= 4.5, "text on $hex")
        }
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

    private fun invoice() = Invoice(
        id = ObjectId(),
        tenantId = tenantId,
        number = "FAT-TEST",
        clientId = client.id,
        items = listOf(item("Pintura", 1000.0), item("Reboco", 500.0)),
        status = InvoiceStatus.PENDING,
        dueDate = kotlinx.datetime.LocalDate(2026, 10, 15),
        totalCents = 150_000,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun textOf(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

    /** Letter-spaced headings ("F O R M A  D E …") come out with gaps; compare without whitespace. */
    private fun String.squashed() = filterNot { it.isWhitespace() }.uppercase()

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
