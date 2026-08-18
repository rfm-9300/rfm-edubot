package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.LineItem
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.tenant.model.DocumentLayoutBlock
import com.rfm.edubot.tenant.model.DocumentLayouts
import com.rfm.edubot.tenant.model.DocumentTemplate
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

class PdfGenerator {

    private val pageBg     = Color.WHITE
    private val surface    = Color(247, 247, 247)
    private val ink        = Color(18, 18, 18)
    private val inkMuted   = Color(85, 88, 90)
    private val inkFaint   = Color(150, 165, 175)
    private val brand      = Color(150, 170, 182)
    private val brandDark  = Color(120, 145, 157)
    private val wave       = Color(198, 206, 211)
    private var cBrand     = brand
    private var cBrandDark = brandDark
    private var cOnBrand   = Color.WHITE

    // ── Fonts — reloaded per document (PDType0Font is doc-scoped) ────
    private var regular: PDFont = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private var bold:    PDFont = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

    private fun loadFonts(doc: PDDocument) {
        regular = loadFont(doc, "pdf/Montserrat-Regular.ttf", Standard14Fonts.FontName.HELVETICA)
        bold = loadFont(doc, "pdf/Montserrat-Bold.ttf", Standard14Fonts.FontName.HELVETICA_BOLD)
    }

    private fun loadFont(doc: PDDocument, resource: String, fallback: Standard14Fonts.FontName): PDFont {
        val stream = javaClass.classLoader.getResourceAsStream(resource) ?: return PDType1Font(fallback)
        return stream.use {
            runCatching { PDType0Font.load(doc, it, true) }
                .getOrElse { PDType1Font(fallback) }
        }
    }

    // ── Page geometry ─────────────────────────────────────────────────
    private val W          = PDRectangle.A4.width    // 595 pt
    private val H          = PDRectangle.A4.height   // 842 pt
    private val MARGIN     = 42f
    private val CONTENT_W  = W - MARGIN * 2          // 511 pt
    private val logoResources = listOf("pdf/ropaint-logo.jpeg", "pdf/ropaint-logo.jpg")

    // ── Table column widths (shared between header and rows) ──────────
    private val COL_SERVICE = 140f
    private val COL_DESC    = 218f
    private val COL_VALUE   = 110f
    private val COL_GAP     = 21.5f

    // Space reserved at the bottom of the LAST page (totals + payment + footer).
    private val LAST_PAGE_BOTTOM_LIMIT = 200f
    // Minimal bottom margin on intermediate pages — no totals/footer drawn there.
    private val INTER_PAGE_BOTTOM_LIMIT = 60f
    // Y where rows start on continuation pages (below a thin continuation header)
    private val CONTINUATION_ROW_START = H - 80f

    // ── Public entry points ───────────────────────────────────────────

    fun generateQuote(
        quote: Quote,
        client: Client,
        template: DocumentTemplate = DocumentTemplate(),
    ): ByteArray {
        val payment = quote.notes?.takeIf { it.isNotBlank() }
            ?: template.quotePaymentTerms.takeIf { it.isNotBlank() }
        return buildDocument(
            docType = template.quoteTitle.ifBlank { "ORÇAMENTO" },
            number = quote.number,
            client = client,
            items = quote.items,
            totalCents = quote.totalCents,
            paymentTerms = payment,
            template = template,
            meta = listOfNotNull(
                "Estado: ${ptStatus(quote.status.name)}",
                quote.validUntil?.let { "Valido ate: ${fmtDate(it.toString())}" },
                "Emitido em: ${fmtDate(quote.createdAt.toString().take(10))}",
            ),
        )
    }

    fun generateInvoice(
        invoice: Invoice,
        client: Client,
        template: DocumentTemplate = DocumentTemplate(),
    ): ByteArray = buildDocument(
        docType = template.invoiceTitle.ifBlank { "FATURA" },
        number = invoice.number,
        client = client,
        items = invoice.items,
        totalCents = invoice.totalCents,
        paymentTerms = template.invoicePaymentTerms.takeIf { it.isNotBlank() },
        template = template,
        meta = listOf(
            "Estado: ${ptStatus(invoice.status.name)}",
            "Vencimento: ${fmtDate(invoice.dueDate.toString())}",
            "Emitido em: ${fmtDate(invoice.createdAt.toString().take(10))}",
        ),
    )

    // ── Document assembly ─────────────────────────────────────────────

    private fun buildDocument(
        docType: String,
        number: String,
        client: Client,
        items: List<LineItem>,
        totalCents: Long,
        paymentTerms: String?,
        template: DocumentTemplate,
        meta: List<String>,
    ): ByteArray {
        PDDocument().use { doc ->
            loadFonts(doc)
            applyTheme(template)
            val blocks = if (template.layout.isNotEmpty()) DocumentLayouts.resolve(template) else null
            val itemsBlock = blocks?.get("items")?.takeIf { it.visible }
            val tableHeaderY = itemsBlock?.let { H - it.y - 30f } ?: (H - 320f)
            val firstPageStartY = tableHeaderY - 18f

            // Pre-calculate row heights and page breaks (two-pass layout).
            val rowHeights = items.map { rowHeightFor(it) }
            val pageBreakSet = layoutItems(rowHeights, firstPageStartY).drop(1).toSet()

            // ── Page 1 ──
            var page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            var cs = PDPageContentStream(doc, page)
            fillRect(cs, pageBg, 0f, 0f, W, H)
            if (template.showDecor) drawDecor(cs)
            if (blocks != null) {
                drawHeaderLaidOut(doc, cs, template, blocks)
                drawDocumentInfoLaidOut(cs, docType, number, client, meta, blocks)
            } else {
                drawHeader(doc, cs, template)
                drawDocumentInfo(cs, docType, number, client, meta)
            }

            var rowY = if (itemsBlock != null) drawTableHeader(cs, tableHeaderY, itemsBlock) else drawTableHeader(cs, H - 320f)
            var pageNum = 1

            for ((idx, item) in items.withIndex()) {
                val (service, description) = splitItem(item)
                val serviceLines = wrap(service, regular, 9.5f, COL_SERVICE - 32f).take(4)
                val descLines = wrap(description, regular, 8f, COL_DESC - 24f).take(6)
                val rowH = rowHeights[idx]

                if (idx in pageBreakSet) {
                    cs.close()
                    pageNum++
                    page = PDPage(PDRectangle.A4)
                    doc.addPage(page)
                    cs = PDPageContentStream(doc, page)
                    fillRect(cs, pageBg, 0f, 0f, W, H)
                    if (template.showDecor) drawDecor(cs)
                    drawContinuationHeader(cs, docType, number, pageNum)
                    rowY = drawTableHeader(cs, CONTINUATION_ROW_START, itemsBlock)
                }

                drawItemRow(cs, idx, item, serviceLines, descLines, rowH, rowY, itemsBlock)
                rowY -= rowH + 10f
            }

            if (blocks == null || blocks["totals"]?.visible != false) {
                drawTotals(cs, totalCents, rowY - 12f, blocks?.get("totals")?.takeIf { it.visible })
            }
            drawPaymentAndTerms(cs, paymentTerms, template, blocks)
            drawFooter(cs, template, blocks?.get("footer"))
            cs.close()

            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    /** Calculates the rendered height of a single item row. */
    private fun rowHeightFor(item: LineItem): Float {
        val (service, description) = splitItem(item)
        val serviceLines = wrap(service, regular, 9.5f, COL_SERVICE - 32f).take(4)
        val descLines    = wrap(description, regular, 8f, COL_DESC - 24f).take(6)
        return maxOf(72f, 22f + maxOf(serviceLines.size * 14f, descLines.size * 11.5f))
    }

    /**
     * Two-pass layout: returns a list of item indices where each page begins.
     *
     * Pass 1 uses INTER_PAGE_BOTTOM_LIMIT so intermediate pages fill completely.
     * Pass 2 re-simulates only the last page with LAST_PAGE_BOTTOM_LIMIT to
     * ensure room remains for the totals pill + payment/footer block.
     */
    private fun layoutItems(heights: List<Float>, firstPageStartY: Float = H - 338f): List<Int> {
        if (heights.isEmpty()) return listOf(0)

        val pageStarts = mutableListOf(0)

        // Pass 1: fill each page as much as possible (no totals space needed on intermediate pages)
        var rowY = firstPageStartY
        for ((idx, rowH) in heights.withIndex()) {
            if (rowY - rowH < INTER_PAGE_BOTTOM_LIMIT) {
                pageStarts.add(idx)
                rowY = CONTINUATION_ROW_START - 18f
            }
            rowY -= rowH + 10f
        }

        // Pass 2: re-simulate the current last page with LAST_PAGE_BOTTOM_LIMIT.
        // Repeat until stable — each iteration may promote one more item to a new page.
        var changed = true
        while (changed) {
            changed = false
            val lastStart = pageStarts.last()
            val startY = if (lastStart == 0) firstPageStartY else CONTINUATION_ROW_START - 18f
            var lastY = startY
            for (idx in lastStart until heights.size) {
                val rowH = heights[idx]
                if (lastY - rowH < LAST_PAGE_BOTTOM_LIMIT) {
                    pageStarts.add(idx)
                    changed = true
                    break
                }
                lastY -= rowH + 10f
            }
        }

        return pageStarts
    }

    // ── Header ────────────────────────────────────────────────────────

    private fun drawHeader(doc: PDDocument, cs: PDPageContentStream, template: DocumentTemplate) {
        fillRect(cs, Color(210, 218, 223), 0f, H - 83f, W, 1.2f)
        val taxId = template.taxId.trim()
        val email = template.email.trim()
        val phone = template.phone.trim()
        if (taxId.isNotBlank()) text(cs, taxId, MARGIN + 50f, H - 58f, 8f, bold, ink)
        val contact = email.ifBlank { phone }
        if (contact.isNotBlank()) text(cs, contact, W / 2f - 22f, H - 58f, 8f, bold, ink)
        if (phone.isNotBlank() || taxId.isNotBlank()) {
            fillCircle(cs, cBrand, MARGIN + 80f, H - 83f, 9f)
            textCentered(cs, "T", MARGIN + 80f, H - 86.5f, 7.5f, bold, cOnBrand)
        }
        if (email.isNotBlank() || contact.isNotBlank()) {
            fillCircle(cs, cBrand, W / 2f + 18f, H - 83f, 9f)
            textCentered(cs, "@", W / 2f + 18f, H - 86.5f, 7f, bold, cOnBrand)
        }
        drawLogo(doc, cs, W - MARGIN - 150f, H - 112f, 150f, 52f, template)
    }

    private fun drawHeaderLaidOut(
        doc: PDDocument,
        cs: PDPageContentStream,
        template: DocumentTemplate,
        blocks: Map<String, DocumentLayoutBlock>,
    ) {
        val contact = blocks["contact"]?.takeIf { it.visible }
        val logo = blocks["logo"]?.takeIf { it.visible }
        val company = blocks["company"]?.takeIf { it.visible }
        contact?.let { block ->
            fillRect(cs, Color(210, 218, 223), 0f, block.pdfY() - 1f, W, 1.2f)
            val taxId = template.taxId.trim()
            val email = template.email.trim()
            val phone = template.phone.trim()
            val baseline = block.pdfTop() - 16f
            if (taxId.isNotBlank()) text(cs, taxId, block.x + 8f, baseline, 8f, bold, ink)
            val contactLine = email.ifBlank { phone }
            if (contactLine.isNotBlank()) {
                text(cs, contactLine, block.x + block.w * 0.48f, baseline, 8f, bold, ink)
            }
            if (phone.isNotBlank() || taxId.isNotBlank()) {
                fillCircle(cs, cBrand, block.x + 38f, block.pdfY(), 9f)
                textCentered(cs, "T", block.x + 38f, block.pdfY() - 3.5f, 7.5f, bold, cOnBrand)
            }
            if (email.isNotBlank() || contactLine.isNotBlank()) {
                fillCircle(cs, cBrand, block.x + block.w * 0.55f, block.pdfY(), 9f)
                textCentered(cs, "@", block.x + block.w * 0.55f, block.pdfY() - 3.5f, 7f, bold, cOnBrand)
            }
        }
        company?.let { block ->
            val name = template.companyName.takeIf { it.isNotBlank() } ?: "EMPRESA"
            var y = block.pdfTop() - 14f
            text(cs, sanitize(name).uppercase(), block.x, y, 11f, bold, ink)
            y -= 13f
            if (template.tagline.isNotBlank()) {
                text(cs, sanitize(template.tagline), block.x, y, 8f, regular, inkMuted)
                y -= 12f
            }
            if (template.address.isNotBlank()) {
                wrap(template.address, regular, 8f, block.w).take(2).forEach { line ->
                    text(cs, line, block.x, y, 8f, regular, inkMuted)
                    y -= 11f
                }
            }
        }
        logo?.let { drawLogo(doc, cs, it.x, it.pdfY(), it.w, it.h, template) }
    }

    private fun drawDocumentInfo(cs: PDPageContentStream, docType: String, number: String, client: Client, meta: List<String>) {
        val issueDate = meta.firstOrNull { it.startsWith("Emitido em:") }?.substringAfter(":")?.trim()
        text(cs, "${sanitize(docType)}  ${sanitize(number)}", MARGIN, H - 168f, 17f, bold, ink)
        issueDate?.let { text(cs, "Data: $it", MARGIN, H - 190f, 9f, bold, inkMuted) }
        // "CLIENTE" is a plain bold label (not letter-spaced — only column headers use spaced())
        text(cs, "CLIENTE", MARGIN, H - 228f, 11f, bold, cBrand)
        text(cs, sanitize(client.name).uppercase(), MARGIN, H - 246f, 12f, bold, ink)
        text(cs, sanitize(client.phone), MARGIN, H - 263f, 10f, regular, inkMuted)
        var clientY = H - 279f
        if (client.number.isNotBlank()) {
            text(cs, sanitize(client.number), MARGIN, clientY, 9f, regular, inkMuted)
            clientY -= 16f
        }
        val address = client.address?.takeIf { it.isNotBlank() }
        if (address != null) {
            wrap(address, regular, 9f, 240f).take(2).forEach { line ->
                text(cs, line, MARGIN, clientY, 9f, regular, inkMuted)
                clientY -= 12f
            }
        }
    }

    private fun drawDocumentInfoLaidOut(
        cs: PDPageContentStream,
        docType: String,
        number: String,
        client: Client,
        meta: List<String>,
        blocks: Map<String, DocumentLayoutBlock>,
    ) {
        val title = blocks["title"]?.takeIf { it.visible }
        val clientBlock = blocks["client"]?.takeIf { it.visible }
        title?.let { block ->
            val issueDate = meta.firstOrNull { it.startsWith("Emitido em:") }?.substringAfter(":")?.trim()
            text(cs, "${sanitize(docType)}  ${sanitize(number)}", block.x, block.pdfTop() - 20f, 17f, bold, ink)
            issueDate?.let { text(cs, "Data: $it", block.x, block.pdfTop() - 40f, 9f, bold, inkMuted) }
        }
        clientBlock?.let { block ->
            var y = block.pdfTop() - 14f
            text(cs, "CLIENTE", block.x, y, 11f, bold, cBrand)
            y -= 18f
            text(cs, sanitize(client.name).uppercase(), block.x, y, 12f, bold, ink)
            y -= 17f
            text(cs, sanitize(client.phone), block.x, y, 10f, regular, inkMuted)
            y -= 16f
            if (client.number.isNotBlank()) {
                text(cs, sanitize(client.number), block.x, y, 9f, regular, inkMuted)
                y -= 14f
            }
            client.address?.takeIf { it.isNotBlank() }?.let { address ->
                wrap(address, regular, 9f, block.w).take(2).forEach { line ->
                    text(cs, line, block.x, y, 9f, regular, inkMuted)
                    y -= 12f
                }
            }
        }
    }

    // ── Items table ───────────────────────────────────────────────────

    /** Draws the column-header pills and returns the Y for the first data row. */
    private fun drawTableHeader(cs: PDPageContentStream, y: Float, items: DocumentLayoutBlock? = null): Float {
        val x = items?.x ?: MARGIN
        val contentW = items?.w ?: CONTENT_W
        val scale = contentW / CONTENT_W
        val service = COL_SERVICE * scale
        val desc = COL_DESC * scale
        val value = COL_VALUE * scale
        val gap = COL_GAP * scale
        pill(cs, x, y, service, 30f, cBrand)
        pill(cs, x + service + gap, y, desc, 30f, cBrand)
        pill(cs, x + service + gap + desc + gap, y, value, 30f, cBrand)
        textCentered(cs, spaced("SERVIÇO"),  x + service / 2f, y + 10f, 11f, bold, cOnBrand)
        textCentered(cs, spaced("DESCRIÇÃO"), x + service + gap + desc / 2f, y + 10f, 11f, bold, cOnBrand)
        textCentered(cs, spaced("VALOR"),    x + service + gap + desc + gap + value / 2f, y + 10f, 11f, bold, cOnBrand)
        return y - 18f
    }

    /** Draws a single table row. `idx` is the global item index (for alternating colors). */
    private fun drawItemRow(
        cs: PDPageContentStream,
        idx: Int,
        item: LineItem,
        serviceLines: List<String>,
        descLines: List<String>,
        rowH: Float,
        rowY: Float,
        items: DocumentLayoutBlock? = null,
    ) {
        val x = items?.x ?: MARGIN
        val contentW = items?.w ?: CONTENT_W
        val scale = contentW / CONTENT_W
        val service = COL_SERVICE * scale
        val desc = COL_DESC * scale
        val value = COL_VALUE * scale
        val gap = COL_GAP * scale
        pill(cs, x, rowY - rowH, contentW, rowH, if (idx % 2 == 0) surface else Color(250, 250, 250))

        val serviceFontSize = 9.5f
        val serviceLineH    = 13.5f
        val serviceBlockH   = serviceLines.size * serviceLineH
        var sy = rowY - (rowH - serviceBlockH) / 2f - serviceFontSize + 2f
        serviceLines.forEach { line -> text(cs, line, x + 20f, sy, serviceFontSize, regular, ink); sy -= serviceLineH }

        val descFontSize = 8.5f
        val descLineH    = 12f
        val descBlockH   = descLines.size * descLineH
        var dy = rowY - (rowH - descBlockH) / 2f - descFontSize + 2f
        descLines.forEach { line -> text(cs, line, x + service + gap + 12f, dy, descFontSize, regular, ink); dy -= descLineH }

        textCentered(cs, money(item.totalCents), x + service + gap + desc + gap + value / 2f, rowY - rowH / 2f - 4f, 11f, bold, ink)
    }

    /** Thin header printed at the top of every continuation page. */
    private fun drawContinuationHeader(cs: PDPageContentStream, docType: String, number: String, pageNum: Int) {
        fillRect(cs, Color(210, 218, 223), 0f, H - 40f, W, 1.2f)
        text(cs, "${sanitize(docType)}  ${sanitize(number)}", MARGIN, H - 28f, 11f, bold, ink)
        textR(cs, "Pagina $pageNum", W - MARGIN, H - 28f, 9f, regular, inkMuted)
    }

    // ── Totals card ───────────────────────────────────────────────────

    private fun drawTotals(cs: PDPageContentStream, totalCents: Long, y: Float, block: DocumentLayoutBlock? = null): Float {
        val w = block?.w ?: 220f
        val h = block?.h?.coerceAtLeast(24f) ?: 30f
        val x = block?.x ?: (W - MARGIN - w)
        pill(cs, x, y - h, w, h, cBrand)
        textCentered(cs, spaced("TOTAL: ${money(totalCents)}"), x + w / 2f, y - h / 2f - 4f, 13f, bold, cOnBrand)
        return y - h
    }

    // ── Notes ─────────────────────────────────────────────────────────

    private fun drawPaymentAndTerms(
        cs: PDPageContentStream,
        paymentTerms: String?,
        template: DocumentTemplate,
        blocks: Map<String, DocumentLayoutBlock>? = null,
    ) {
        val payment = paymentTerms?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PAYMENT_TERMS
        val terms = template.termsText.takeIf { it.isNotBlank() } ?: DEFAULT_TERMS
        val paymentBlock = blocks?.get("payment")?.takeIf { it.visible }
        val termsBlock = blocks?.get("terms")?.takeIf { it.visible }
        if (blocks == null) {
            text(cs, spaced("FORMA DE PAGAMENTO"), MARGIN + 8f, 122f, 11f, bold, cBrand)
            var y = 101f
            wrap(payment, regular, 9f, 315f).take(4).forEach { line ->
                text(cs, line, MARGIN + 8f, y, 9f, regular, ink)
                y -= 13f
            }
            text(cs, spaced("TERMOS E CONDIÇÕES"), MARGIN + 8f, 63f, 11f, bold, cBrand)
            wrap(terms, regular, 9f, 315f).take(2).forEachIndexed { idx, line ->
                text(cs, line, MARGIN + 8f, 47f - idx * 12f, 9f, regular, ink)
            }
            return
        }
        paymentBlock?.let { block ->
            text(cs, spaced("FORMA DE PAGAMENTO"), block.x, block.pdfTop() - 12f, 11f, bold, cBrand)
            var y = block.pdfTop() - 28f
            wrap(payment, regular, 9f, block.w).take(4).forEach { line ->
                text(cs, line, block.x, y, 9f, regular, ink)
                y -= 13f
            }
        }
        termsBlock?.let { block ->
            text(cs, spaced("TERMOS E CONDIÇÕES"), block.x, block.pdfTop() - 12f, 11f, bold, cBrand)
            wrap(terms, regular, 9f, block.w).take(2).forEachIndexed { idx, line ->
                text(cs, line, block.x, block.pdfTop() - 28f - idx * 12f, 9f, regular, ink)
            }
        }
    }

    // ── Footer ────────────────────────────────────────────────────────

    private fun drawFooter(cs: PDPageContentStream, template: DocumentTemplate, block: DocumentLayoutBlock? = null) {
        if (block?.visible == false) return
        val footer = template.footerText.takeIf { it.isNotBlank() } ?: DEFAULT_FOOTER
        if (block != null) {
            textR(cs, footer, block.x + block.w, block.pdfY() + 4f, 7.5f, regular, Color(130, 130, 130))
        } else {
            textR(cs, footer, W - MARGIN, 22f, 7.5f, regular, Color(130, 130, 130))
        }
    }

    // ── Primitives ────────────────────────────────────────────────────

    private fun fillRect(cs: PDPageContentStream, color: Color, x: Float, y: Float, w: Float, h: Float) {
        cs.setNonStrokingColor(color)
        cs.addRect(x, y, w, h)
        cs.fill()
    }

    private fun fillCircle(cs: PDPageContentStream, color: Color, cx: Float, cy: Float, r: Float) {
        val k = 0.55228475f
        cs.setNonStrokingColor(color)
        cs.moveTo(cx + r, cy)
        cs.curveTo(cx + r, cy + r * k, cx + r * k, cy + r, cx, cy + r)
        cs.curveTo(cx - r * k, cy + r, cx - r, cy + r * k, cx - r, cy)
        cs.curveTo(cx - r, cy - r * k, cx - r * k, cy - r, cx, cy - r)
        cs.curveTo(cx + r * k, cy - r, cx + r, cy - r * k, cx + r, cy)
        cs.fill()
    }

    private fun pill(cs: PDPageContentStream, x: Float, y: Float, w: Float, h: Float, color: Color) {
        val r = h / 2f
        val k = 0.55228475f
        cs.setNonStrokingColor(color)
        cs.moveTo(x + r, y)
        cs.lineTo(x + w - r, y)
        cs.curveTo(x + w - r + r * k, y, x + w, y + r - r * k, x + w, y + r)
        cs.curveTo(x + w, y + r + r * k, x + w - r + r * k, y + h, x + w - r, y + h)
        cs.lineTo(x + r, y + h)
        cs.curveTo(x + r - r * k, y + h, x, y + r + r * k, x, y + r)
        cs.curveTo(x, y + r - r * k, x + r - r * k, y, x + r, y)
        cs.fill()
    }

    private fun drawDecor(cs: PDPageContentStream) {
        cs.setStrokingColor(wave)
        cs.setLineWidth(0.35f)
        repeat(18) { i ->
            val offset = i * 7f
            cs.moveTo(0f, H - 18f - offset)
            cs.curveTo(120f, H + 35f - offset, 205f, H - 100f + offset * 0.3f, 345f, H - 30f - offset)
            cs.curveTo(455f, H + 25f - offset, 510f, H - 145f + offset, W, H - 76f - offset * 0.25f)
            cs.stroke()
        }
        repeat(18) { i ->
            val offset = i * 7f
            cs.moveTo(W - 20f, 205f - offset)
            cs.curveTo(500f, 250f - offset, 465f, 100f + offset * 0.35f, 380f, 138f - offset)
            cs.curveTo(305f, 175f - offset, 310f, -20f + offset, 235f, 35f - offset * 0.25f)
            cs.stroke()
        }
    }

    private fun drawLogo(
        doc: PDDocument,
        cs: PDPageContentStream,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        template: DocumentTemplate,
    ) {
        val uploaded = template.logoPath?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { Files.readAllBytes(Path.of(path)) }.getOrNull()
        }
        val logoBytes = uploaded ?: logoResources.firstNotNullOfOrNull { resource ->
            this::class.java.classLoader.getResource(resource)?.readBytes()
        }?.takeIf { template.companyName.isBlank() && template.logoPath.isNullOrBlank() }

        if (logoBytes != null) {
            val image = PDImageXObject.createFromByteArray(doc, logoBytes, "logo")
            val scale = minOf(w / image.width, h / image.height)
            val imageW = image.width * scale
            val imageH = image.height * scale
            cs.drawImage(image, x + (w - imageW) / 2f, y + (h - imageH) / 2f, imageW, imageH)
            return
        }

        val company = template.companyName.takeIf { it.isNotBlank() } ?: "EMPRESA"
        val tagline = template.tagline.takeIf { it.isNotBlank() } ?: ""
        fillRect(cs, cBrandDark, x, y, w, h)
        text(cs, sanitize(company).uppercase().take(18), x + 12f, y + (if (tagline.isBlank()) 20f else 26f), 14f, bold, cOnBrand)
        if (tagline.isNotBlank()) {
            text(cs, sanitize(tagline).uppercase().take(28), x + 12f, y + 12f, 7f, regular, cOnBrand)
        }
    }

    private fun applyTheme(template: DocumentTemplate) {
        val parsed = parseHex(template.accentColor)
        cBrand = parsed ?: brand
        cBrandDark = parsed?.let { darken(it, 0.82f) } ?: brandDark
        cOnBrand = onBrand(cBrand)
    }

    private fun parseHex(value: String): Color? {
        val hex = DocumentLayouts.sanitizeAccent(value).removePrefix("#")
        if (hex.length != 6) return null
        return runCatching {
            Color(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
        }.getOrNull()
    }

    private fun darken(color: Color, factor: Float): Color = Color(
        (color.red * factor).toInt().coerceIn(0, 255),
        (color.green * factor).toInt().coerceIn(0, 255),
        (color.blue * factor).toInt().coerceIn(0, 255),
    )

    private fun onBrand(color: Color): Color {
        val luminance = (0.299f * color.red + 0.587f * color.green + 0.114f * color.blue) / 255f
        return if (luminance > 0.62f) ink else Color.WHITE
    }

    companion object {
        const val DEFAULT_PAYMENT_TERMS =
            "15% na adjudicacao, 70% a meio da execucao da obra e os restantes 15% apos a sua conclusao."
        const val DEFAULT_TERMS = "Este documento e valido por 30 dias."
        const val DEFAULT_FOOTER = "gerado por thebotslab.pt"
    }

    private fun textCentered(cs: PDPageContentStream, value: String, xCenter: Float, y: Float, size: Float, font: PDFont, color: Color) {
        val safe = sanitize(value).ifBlank { return }
        text(cs, safe, xCenter - textWidth(safe, font, size) / 2f, y, size, font, color)
    }

    /** Left-aligned text. */
    private fun text(cs: PDPageContentStream, value: String, x: Float, y: Float, size: Float, font: PDFont, color: Color) {
        val safe = sanitize(value).ifBlank { return }
        cs.beginText()
        cs.setNonStrokingColor(color)
        cs.setFont(font, size)
        cs.newLineAtOffset(x, y)
        cs.showText(safe)
        cs.endText()
    }

    /** Right-aligned text — `xRight` is the right edge. */
    private fun textR(cs: PDPageContentStream, value: String, xRight: Float, y: Float, size: Float, font: PDFont, color: Color) {
        val safe = sanitize(value).ifBlank { return }
        val w = font.getStringWidth(safe) / 1000f * size
        text(cs, safe, xRight - w, y, size, font, color)
    }

    private fun textWidth(value: String, font: PDFont, size: Float): Float =
        font.getStringWidth(sanitize(value)) / 1000f * size

    private fun splitItem(item: LineItem): Pair<String, String> {
        val description = sanitize(item.description).trim()
        val separators = listOf(" - ", ": ", " | ")
        separators.forEach { sep ->
            val idx = description.indexOf(sep)
            if (idx > 0) return description.take(idx).trim() to description.drop(idx + sep.length).trim()
        }
        val firstPeriod = description.indexOf(". ")
        if (firstPeriod in 12..80) return description.take(firstPeriod + 1).trim() to description.drop(firstPeriod + 2).trim()
        val detail = if (item.quantity != 1.0 || item.unit.isNotBlank()) {
            "Quantidade: ${trimDouble(item.quantity)} ${sanitize(item.unit)}"
        } else {
            description
        }
        return description to detail
    }

    private fun spaced(value: String): String = sanitize(value).uppercase().toCharArray().joinToString(" ")

    // ── Text helpers ──────────────────────────────────────────────────

    private fun wrap(value: String, font: PDFont, size: Float, maxWidth: Float): List<String> {
        val words = sanitize(value).split(Regex("\\s+")).filter { it.isNotBlank() }
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isBlank()) word else "$current $word"
            if (font.getStringWidth(candidate) / 1000f * size <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotBlank()) lines.add(current)
                current = word
            }
        }
        if (current.isNotBlank()) lines.add(current)
        return lines.ifEmpty { listOf("") }
    }

    private fun sanitize(value: String): String = value
        .replace('–', '-')
        .replace('—', '-')
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .filter { it.code >= 0x20 } // allow full Unicode — Montserrat supports it

    private fun money(cents: Long): String = "%.2f EUR".format(cents / 100.0)

    private fun trimDouble(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)

    private fun fmtDate(iso: String): String {
        val p = iso.take(10).split("-")
        return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else iso
    }

    private fun ptStatus(status: String) = when (status) {
        "PENDENTE"  -> "Pendente"
        "SENT"      -> "Enviado"
        "ACEITO"    -> "Aceito"
        "PENDING"   -> "Pendente"
        "PAID"      -> "Pago"
        "OVERDUE"   -> "Vencido"
        "CANCELLED" -> "Cancelado"
        else        -> status
    }
}
