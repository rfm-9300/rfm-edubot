package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.LineItem
import com.rfm.edubot.crm.model.Quote
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

class PdfGenerator {

    private val pageBg     = Color.WHITE
    private val surface    = Color(247, 247, 247)
    private val ink        = Color(18, 18, 18)
    private val inkMuted   = Color(85, 88, 90)
    private val inkFaint   = Color(150, 165, 175)
    private val brand      = Color(150, 170, 182)
    private val brandDark  = Color(120, 145, 157)
    private val wave       = Color(198, 206, 211)

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

    fun generateQuote(quote: Quote, client: Client): ByteArray = buildDocument(
        docType    = "ORÇAMENTO",
        number     = quote.number,
        client     = client,
        items      = quote.items,
        totalCents = quote.totalCents,
        notes      = quote.notes,
        meta       = listOfNotNull(
            "Estado: ${ptStatus(quote.status.name)}",
            quote.validUntil?.let { "Valido ate: ${fmtDate(it.toString())}" },
            "Emitido em: ${fmtDate(quote.createdAt.toString().take(10))}",
        ),
    )

    fun generateInvoice(invoice: Invoice, client: Client): ByteArray = buildDocument(
        docType    = "FATURA",
        number     = invoice.number,
        client     = client,
        items      = invoice.items,
        totalCents = invoice.totalCents,
        notes      = null,
        meta       = listOf(
            "Estado: ${ptStatus(invoice.status.name)}",
            "Vencimento: ${fmtDate(invoice.dueDate.toString())}",
            "Emitido em: ${fmtDate(invoice.createdAt.toString().take(10))}",
        ),
    )

    // ── Document assembly ─────────────────────────────────────────────

    private fun buildDocument(
        docType:    String,
        number:     String,
        client:     Client,
        items:      List<LineItem>,
        totalCents: Long,
        notes:      String?,
        meta:       List<String>,
    ): ByteArray {
        PDDocument().use { doc ->
            loadFonts(doc)

            // Pre-calculate row heights and page breaks (two-pass layout).
            val rowHeights   = items.map { rowHeightFor(it) }
            val pageBreakSet = layoutItems(rowHeights).drop(1).toSet()

            // ── Page 1 ──
            var page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            var cs = PDPageContentStream(doc, page)
            fillRect(cs, pageBg, 0f, 0f, W, H)
            drawDecor(cs)
            drawHeader(doc, cs)
            drawDocumentInfo(cs, docType, number, client, meta)

            var rowY = drawTableHeader(cs, H - 320f)
            var pageNum = 1

            for ((idx, item) in items.withIndex()) {
                val (service, description) = splitItem(item)
                val serviceLines = wrap(service, regular, 9.5f, COL_SERVICE - 32f).take(4)
                val descLines    = wrap(description, regular, 8f, COL_DESC - 24f).take(6)
                val rowH = rowHeights[idx]

                if (idx in pageBreakSet) {
                    cs.close()
                    pageNum++
                    page = PDPage(PDRectangle.A4)
                    doc.addPage(page)
                    cs = PDPageContentStream(doc, page)
                    fillRect(cs, pageBg, 0f, 0f, W, H)
                    drawDecor(cs)
                    drawContinuationHeader(cs, docType, number, pageNum)
                    rowY = drawTableHeader(cs, CONTINUATION_ROW_START)
                }

                drawItemRow(cs, idx, item, serviceLines, descLines, rowH, rowY)
                rowY -= rowH + 10f
            }

            drawTotals(cs, totalCents, rowY - 12f)
            drawPaymentAndTerms(cs, notes)
            drawFooter(cs)
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
    private fun layoutItems(heights: List<Float>): List<Int> {
        if (heights.isEmpty()) return listOf(0)

        val pageStarts = mutableListOf(0)

        // Pass 1: fill each page as much as possible (no totals space needed on intermediate pages)
        var rowY = H - 338f  // first page: after table header at H-320 minus 18pt header height
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
            val startY = if (lastStart == 0) H - 338f else CONTINUATION_ROW_START - 18f
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

    private fun drawHeader(doc: PDDocument, cs: PDPageContentStream) {
        fillRect(cs, Color(210, 218, 223), 0f, H - 83f, W, 1.2f)
        text(cs, "999999999", MARGIN + 50f, H - 58f, 8f, bold, ink)
        text(cs, "geral@isal.pt", W / 2f - 22f, H - 58f, 8f, bold, ink)
        fillCircle(cs, brand, MARGIN + 80f, H - 83f, 9f)
        fillCircle(cs, brand, W / 2f + 18f, H - 83f, 9f)
        // ASCII alternatives — ☎ and ✉ are outside Latin-1 and won't render in Type1 Helvetica
        textCentered(cs, "T", MARGIN + 80f, H - 86.5f, 7.5f, bold, Color.WHITE)
        textCentered(cs, "@", W / 2f + 18f, H - 86.5f, 7f, bold, Color.WHITE)
        drawLogo(doc, cs, W - MARGIN - 150f, H - 112f, 150f, 52f)
    }

    private fun drawDocumentInfo(cs: PDPageContentStream, docType: String, number: String, client: Client, meta: List<String>) {
        val issueDate = meta.firstOrNull { it.startsWith("Emitido em:") }?.substringAfter(":")?.trim()
        text(cs, "${sanitize(docType)}  ${sanitize(number)}", MARGIN, H - 168f, 17f, bold, ink)
        issueDate?.let { text(cs, "Data: $it", MARGIN, H - 190f, 9f, bold, inkMuted) }
        // "CLIENTE" is a plain bold label (not letter-spaced — only column headers use spaced())
        text(cs, "CLIENTE", MARGIN, H - 228f, 11f, bold, brand)
        text(cs, sanitize(client.name).uppercase(), MARGIN, H - 246f, 12f, bold, ink)
        text(cs, sanitize(client.phone), MARGIN, H - 263f, 10f, regular, inkMuted)
        if (client.number.isNotBlank()) text(cs, sanitize(client.number), MARGIN, H - 279f, 9f, regular, inkMuted)
    }

    // ── Items table ───────────────────────────────────────────────────

    /** Draws the column-header pills and returns the Y for the first data row. */
    private fun drawTableHeader(cs: PDPageContentStream, y: Float): Float {
        pill(cs, MARGIN, y, COL_SERVICE, 30f, brand)
        pill(cs, MARGIN + COL_SERVICE + COL_GAP, y, COL_DESC, 30f, brand)
        pill(cs, MARGIN + COL_SERVICE + COL_GAP + COL_DESC + COL_GAP, y, COL_VALUE, 30f, brand)
        textCentered(cs, spaced("SERVIÇO"),  MARGIN + COL_SERVICE / 2f, y + 10f, 11f, bold, Color.WHITE)
        textCentered(cs, spaced("DESCRIÇÃO"), MARGIN + COL_SERVICE + COL_GAP + COL_DESC / 2f, y + 10f, 11f, bold, Color.WHITE)
        textCentered(cs, spaced("VALOR"),    MARGIN + COL_SERVICE + COL_GAP + COL_DESC + COL_GAP + COL_VALUE / 2f, y + 10f, 11f, bold, Color.WHITE)
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
    ) {
        pill(cs, MARGIN, rowY - rowH, CONTENT_W, rowH, if (idx % 2 == 0) surface else Color(250, 250, 250))

        val serviceFontSize = 9.5f
        val serviceLineH    = 13.5f
        val serviceBlockH   = serviceLines.size * serviceLineH
        var sy = rowY - (rowH - serviceBlockH) / 2f - serviceFontSize + 2f
        serviceLines.forEach { line -> text(cs, line, MARGIN + 20f, sy, serviceFontSize, regular, ink); sy -= serviceLineH }

        val descFontSize = 8.5f
        val descLineH    = 12f
        val descBlockH   = descLines.size * descLineH
        var dy = rowY - (rowH - descBlockH) / 2f - descFontSize + 2f
        descLines.forEach { line -> text(cs, line, MARGIN + COL_SERVICE + COL_GAP + 12f, dy, descFontSize, regular, ink); dy -= descLineH }

        textCentered(cs, money(item.totalCents), MARGIN + COL_SERVICE + COL_GAP + COL_DESC + COL_GAP + COL_VALUE / 2f, rowY - rowH / 2f - 4f, 11f, bold, ink)
    }

    /** Thin header printed at the top of every continuation page. */
    private fun drawContinuationHeader(cs: PDPageContentStream, docType: String, number: String, pageNum: Int) {
        fillRect(cs, Color(210, 218, 223), 0f, H - 40f, W, 1.2f)
        text(cs, "${sanitize(docType)}  ${sanitize(number)}", MARGIN, H - 28f, 11f, bold, ink)
        textR(cs, "Pagina $pageNum", W - MARGIN, H - 28f, 9f, regular, inkMuted)
    }

    // ── Totals card ───────────────────────────────────────────────────

    private fun drawTotals(cs: PDPageContentStream, totalCents: Long, y: Float): Float {
        val w = 220f
        val h = 30f
        val x = W - MARGIN - w
        pill(cs, x, y - h, w, h, brand)
        textCentered(cs, spaced("TOTAL: ${money(totalCents)}"), x + w / 2f, y - 20f, 13f, bold, Color.WHITE)
        return y - h
    }

    // ── Notes ─────────────────────────────────────────────────────────

    private fun drawPaymentAndTerms(cs: PDPageContentStream, notes: String?) {
        val payment = notes?.takeIf { it.isNotBlank() }
            ?: "15% na adjudicacao, 70% a meio da execucao da obra e os restantes 15% apos a sua conclusao."
        text(cs, spaced("FORMA DE PAGAMENTO"), MARGIN + 8f, 122f, 11f, bold, brand)
        var y = 101f
        wrap(payment, regular, 9f, 315f).take(4).forEach { line ->
            text(cs, line, MARGIN + 8f, y, 9f, regular, ink)
            y -= 13f
        }
        text(cs, spaced("TERMOS E CONDIÇÕES"), MARGIN + 8f, 63f, 11f, bold, brand)
        text(cs, "Este documento e valido por 30 dias.", MARGIN + 8f, 47f, 9f, regular, ink)
    }

    // ── Footer ────────────────────────────────────────────────────────

    private fun drawFooter(cs: PDPageContentStream) {
        textR(cs, "gerado por thebotslab.pt", W - MARGIN, 22f, 7.5f, regular, Color(130, 130, 130))
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

    private fun drawLogo(doc: PDDocument, cs: PDPageContentStream, x: Float, y: Float, w: Float, h: Float) {
        val logoBytes = logoResources.firstNotNullOfOrNull { resource ->
            this::class.java.classLoader.getResource(resource)?.readBytes()
        }
        if (logoBytes != null) {
            val image = PDImageXObject.createFromByteArray(doc, logoBytes, "ropaint-logo")
            val scale = minOf(w / image.width, h / image.height)
            val imageW = image.width * scale
            val imageH = image.height * scale
            cs.drawImage(image, x + (w - imageW) / 2f, y + (h - imageH) / 2f, imageW, imageH)
            return
        }

        fillRect(cs, brandDark, x, y, w, h)
        text(cs, "ISAL", x + 16f, y + 21f, 22f, bold, Color.WHITE)
        text(cs, "CONSTRUCAO E GESTAO", x + 68f, y + 28f, 7f, bold, Color.WHITE)
        text(cs, "ORCAMENTOS", x + 68f, y + 17f, 6.5f, regular, Color.WHITE)
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
        "ACEITO"    -> "Aceito"
        "PENDING"   -> "Pendente"
        "PAID"      -> "Pago"
        "OVERDUE"   -> "Vencido"
        "CANCELLED" -> "Cancelado"
        else        -> status
    }
}
