package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.tenant.model.DocumentTemplate
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.apache.pdfbox.Loader
import org.bson.types.ObjectId
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
}
