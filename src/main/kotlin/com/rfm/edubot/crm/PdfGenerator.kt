package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.LineItem
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.tenant.model.DocumentDesignStyle
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
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class PdfGenerator {

    // ── Palette ───────────────────────────────────────────────────────
    private val ink       = INK
    private val inkSoft   = Color(71, 84, 103)
    private val inkMuted  = Color(102, 112, 133)
    private val rule      = Color(228, 231, 236)
    private val surface   = Color(247, 248, 250)
    private val surfaceAlt = Color(251, 251, 252)

    // ── Per-document theme — set by applyTheme() ──────────────────────
    private var design     = DocumentDesignStyle.CLASSIC
    private var showDecor  = true
    private var cBrand     = DEFAULT_ACCENT_COLOR
    private var cBrandText = readableOnWhite(DEFAULT_ACCENT_COLOR)
    private var cOnBrand   = onFill(DEFAULT_ACCENT_COLOR)
    private var cWave      = mix(DEFAULT_ACCENT_COLOR, Color.WHITE, 0.45f)

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
    private val W         = PDRectangle.A4.width    // 595 pt
    private val H         = PDRectangle.A4.height   // 842 pt
    private val MARGIN    = 42f
    private val logoResources = listOf("pdf/ropaint-logo.jpeg", "pdf/ropaint-logo.jpg")

    // Three columns with readable gaps need about 420pt. Below that the editor's minimum (220pt)
    // forces the columns into each other, so the table keeps a floor and the overflow is the block
    // running past its frame — which the editor already flags as an overlap.
    private val MIN_TABLE_WIDTH = 420f

    // Classic table: a header bar, then rounded rows separated by a small gap.
    private val HEADER_BAR_H = 24f
    private val HEADER_TO_ROWS = 6f
    private val CLASSIC_ROW_GAP = 5f
    private val CLASSIC_MIN_ROW = 34f
    private val CLASSIC_ROW_PAD_X = 14f

    // Rows on intermediate pages stop here; the footer sits below.
    private val INTER_PAGE_BOTTOM_LIMIT = 56f
    // Where the table header sits on continuation pages (below the continuation header).
    private val CONTINUATION_TABLE_TOP = H - 60f

    // Closing block: gap below the last row, totals, the gap under them, and the footer.
    private val CLOSING_GAP = 18f
    private val TOTALS_GAP = 16f
    private val FOOTER_RESERVE = 34f
    private val FOOTER_Y = 24f

    // Payment and terms sections.
    private val SECTION_HEADING = 15f
    private val SECTION_LINE = 12.5f
    private val SECTION_GAP = 10f
    private val PAYMENT_MAX_LINES = 6
    private val TERMS_MAX_LINES = 8

    private val rowGap get() = if (design.ruled) 0f else CLASSIC_ROW_GAP

    // ── Public entry points ───────────────────────────────────────────

    fun generateQuote(
        quote: Quote,
        client: Client,
        template: DocumentTemplate = DocumentTemplate(),
    ): ByteArray {
        val payment = quote.notes?.takeIf { it.isNotBlank() }
            ?: template.quotePaymentTerms.takeIf { it.isNotBlank() }
        // A quote with its own "valid until" date already shows it; "valid for 30 days" would contradict it.
        val terms = template.termsText.takeIf { it.isNotBlank() }
            ?: DEFAULT_QUOTE_TERMS.takeIf { quote.validUntil == null }
        return buildDocument(
            docType = template.quoteTitle.ifBlank { "ORÇAMENTO" },
            client = client,
            items = quote.items,
            totalCents = quote.totalCents,
            paymentTerms = payment,
            terms = terms,
            template = template,
            meta = DocumentMeta(
                number = quote.number,
                issued = fmtDate(quote.createdAt.toString()),
                dueLabel = quote.validUntil?.let { "Válido até" },
                due = quote.validUntil?.let { fmtDate(it.toString()) },
                status = Status.ACCEPTED.takeIf { quote.status == QuoteStatus.ACEITO },
            ),
        )
    }

    fun generateInvoice(
        invoice: Invoice,
        client: Client,
        template: DocumentTemplate = DocumentTemplate(),
    ): ByteArray {
        val split = invoice.installments.isNotEmpty()
        val stillDue = invoice.installments.any { it.paidAt == null } && invoice.status != InvoiceStatus.PAID
        return buildDocument(
            docType = template.invoiceTitle.ifBlank { "FATURA" },
            client = client,
            items = invoice.items,
            totalCents = invoice.totalCents,
            paymentTerms = template.invoicePaymentTerms.takeIf { it.isNotBlank() }
                ?: if (split) DEFAULT_INSTALLMENT_PAYMENT_TERMS else DEFAULT_INVOICE_PAYMENT_TERMS,
            terms = template.termsText.takeIf { it.isNotBlank() },
            template = template,
            meta = DocumentMeta(
                number = invoice.number,
                issued = fmtDate(invoice.createdAt.toString()),
                // With installments still open, the due date is the next one's.
                dueLabel = if (stillDue) "Próximo vencimento" else "Vencimento",
                due = fmtDate(invoice.dueDate.toString()),
                status = when (invoice.status) {
                    InvoiceStatus.PAID -> Status.PAID
                    InvoiceStatus.OVERDUE -> Status.OVERDUE
                    InvoiceStatus.CANCELLED -> Status.CANCELLED
                    InvoiceStatus.PENDING -> null
                },
                taxCode = invoice.taxOfficeCode?.let(::atcud),
            ),
            installments = invoice.installments.mapIndexed { i, part ->
                val paid = part.paidAt != null || invoice.status == InvoiceStatus.PAID
                "${i + 1}.ª prestação: ${money(part.amountCents)} até ${fmtDate(part.dueDate.toString())}" + if (paid) " (paga)" else ""
            },
        )
    }

    /** What the title block prints under the document name. */
    private data class DocumentMeta(
        val number: String,
        val issued: String,
        val dueLabel: String?,
        val due: String?,
        val status: Status?,
        val taxCode: String? = null,
    )

    /** Statuses worth telling the recipient; internal ones (pending, sent) print nothing. */
    private enum class Status(val label: String, val ink: Color, val fill: Color) {
        PAID("Paga", Color(6, 118, 71), Color(220, 250, 230)),
        OVERDUE("Vencida", Color(180, 35, 24), Color(254, 228, 226)),
        CANCELLED("Cancelada", Color(71, 84, 103), Color(234, 236, 240)),
        ACCEPTED("Aceite", Color(6, 118, 71), Color(220, 250, 230)),
    }

    // ── Document assembly ─────────────────────────────────────────────

    /** Builds one document. Synchronized: fonts and theme live on this shared instance while it runs. */
    @Synchronized
    private fun buildDocument(
        docType: String,
        client: Client,
        items: List<LineItem>,
        totalCents: Long,
        paymentTerms: String?,
        terms: String?,
        template: DocumentTemplate,
        meta: DocumentMeta,
        installments: List<String> = emptyList(),
    ): ByteArray {
        PDDocument().use { doc ->
            loadFonts(doc)
            applyTheme(template)
            // An empty layout is the default page; a saved one overlays it block by block.
            val blocks = DocumentLayouts.resolve(template)
            val table = blocks.getValue("items")

            // Pre-calculate row heights and page breaks (two-pass layout).
            val rows = items.map { rowLayout(it, table) }
            val reserve = closingBlockHeight(paymentTerms, installments, terms, blocks)
            val pageBreaks = layoutItems(
                heights = rows.map { it.height },
                firstPageStartY = rowsTop(H - table.y),
                continuationStartY = rowsTop(CONTINUATION_TABLE_TOP),
                bottomReserve = reserve,
            ).drop(1).toSet()

            var cs = newPage(doc, first = true)
            drawHeader(doc, cs, template, blocks)
            drawTitle(cs, docType, meta, blocks["title"]?.takeIf { it.visible })
            drawClient(cs, client, blocks["client"]?.takeIf { it.visible })

            var rowTop = drawTableHeader(cs, H - table.y, table)
            var bottom = rowTop
            for ((idx, row) in rows.withIndex()) {
                if (idx in pageBreaks) {
                    cs.close()
                    cs = newPage(doc, first = false)
                    drawContinuationHeader(cs, docType, meta.number, template, table)
                    rowTop = drawTableHeader(cs, CONTINUATION_TABLE_TOP, table)
                }
                drawItemRow(cs, idx, row, rowTop, table)
                bottom = rowTop - row.height
                rowTop = bottom - rowGap
            }

            // Totals, payment and terms flow down from the last row instead of being painted at
            // fixed coordinates: a fixed anchor lands on top of the rows whenever the table grows
            // past it (see the page-break reservation in layoutItems).
            if (bottom < reserve) {
                cs.close()
                cs = newPage(doc, first = false)
                drawContinuationHeader(cs, docType, meta.number, template, table)
                bottom = CONTINUATION_TABLE_TOP
            }
            var cursor = bottom - CLOSING_GAP
            blocks["totals"]?.takeIf { it.visible }?.let { block ->
                cursor = drawTotals(cs, totalCents, cursor, block) - TOTALS_GAP
            }
            drawPaymentAndTerms(cs, paymentTerms, installments, terms, blocks, cursor)
            cs.close()

            drawFooters(doc, template, blocks["footer"])
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    private fun newPage(doc: PDDocument, first: Boolean): PDPageContentStream {
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        val cs = PDPageContentStream(doc, page)
        if (design == DocumentDesignStyle.BAND) fillRect(cs, cBrand, 0f, 0f, 18f, H)
        if (showDecor && design == DocumentDesignStyle.CLASSIC) drawDecor(cs, top = first)
        return cs
    }

    /** Top of the first row below a table header whose top edge is [tableTop]. */
    private fun rowsTop(tableTop: Float): Float =
        tableTop - if (design.ruled) 24f else HEADER_BAR_H + HEADER_TO_ROWS

    /**
     * Height the closing block (totals, payment terms, terms, footer) needs below the last item
     * row, so the page break leaves exactly enough room for it.
     */
    private fun closingBlockHeight(
        paymentTerms: String?,
        installments: List<String>,
        terms: String?,
        blocks: Map<String, DocumentLayoutBlock>,
    ): Float {
        var height = CLOSING_GAP + FOOTER_RESERVE
        blocks["totals"]?.takeIf { it.visible }?.let { height += totalsHeight(it) + TOTALS_GAP }
        val paymentBlock = blocks["payment"]?.takeIf { it.visible }
        val termsBlock = blocks["terms"]?.takeIf { it.visible }
        val width = (paymentBlock ?: termsBlock)?.w ?: return height
        if (paymentBlock != null) {
            paymentTerms?.takeIf { it.isNotBlank() }?.let {
                height += SECTION_HEADING + wrap(it, regular, 9f, width).take(PAYMENT_MAX_LINES).size * SECTION_LINE + SECTION_GAP
            }
            if (installments.isNotEmpty()) height += SECTION_HEADING + installments.size * SECTION_LINE + SECTION_GAP
        }
        if (termsBlock != null) {
            terms?.takeIf { it.isNotBlank() }?.let {
                height += SECTION_HEADING + wrap(it, regular, 9f, width).take(TERMS_MAX_LINES).size * SECTION_LINE
            }
        }
        return height
    }

    /**
     * Two-pass layout: returns a list of item indices where each page begins.
     *
     * Pass 1 uses INTER_PAGE_BOTTOM_LIMIT so intermediate pages fill completely.
     * Pass 2 re-simulates only the last page, keeping [bottomReserve] clear below the last row so
     * the totals, payment terms and footer fit without covering the rows.
     */
    private fun layoutItems(
        heights: List<Float>,
        firstPageStartY: Float,
        continuationStartY: Float,
        bottomReserve: Float,
    ): List<Int> {
        if (heights.isEmpty()) return listOf(0)
        val pageStarts = mutableListOf(0)

        // Pass 1: fill each page as much as possible (no totals space needed on intermediate pages)
        var rowY = firstPageStartY
        for ((idx, rowH) in heights.withIndex()) {
            if (rowY - rowH < INTER_PAGE_BOTTOM_LIMIT && idx > pageStarts.last()) {
                pageStarts.add(idx)
                rowY = continuationStartY
            }
            rowY -= rowH + rowGap
        }

        // Pass 2: re-simulate the current last page, reserving room for the closing block.
        // Repeat until stable — each iteration may promote one more item to a new page. A row
        // that cannot share a page with the closing block even alone stays put; the closing block
        // then moves to a page of its own (see buildDocument).
        var changed = true
        while (changed) {
            changed = false
            val lastStart = pageStarts.last()
            var lastY = if (lastStart == 0) firstPageStartY else continuationStartY
            for (idx in lastStart until heights.size) {
                val rowH = heights[idx]
                if (lastY - rowH < bottomReserve) {
                    if (idx > lastStart) {
                        pageStarts.add(idx)
                        changed = true
                    }
                    break
                }
                lastY -= rowH + rowGap
            }
        }

        return pageStarts
    }

    // ── Header: logo, company, contact ────────────────────────────────

    private fun drawHeader(
        doc: PDDocument,
        cs: PDPageContentStream,
        template: DocumentTemplate,
        blocks: Map<String, DocumentLayoutBlock>,
    ) {
        blocks["logo"]?.takeIf { it.visible }?.let { drawLogo(doc, cs, it, template) }
        blocks["company"]?.takeIf { it.visible }?.let { drawCompany(cs, template, it) }
        blocks["contact"]?.takeIf { it.visible }?.let { drawContact(cs, template, it) }
    }

    private fun drawCompany(cs: PDPageContentStream, template: DocumentTemplate, block: DocumentLayoutBlock) {
        val name = template.companyName.takeIf { it.isNotBlank() } ?: "Empresa"
        val size = fitSize(name, bold, if (design.ruled) 13f else 12f, 9f, block.w)
        var y = block.pdfTop() - 14f
        text(cs, name, block.x, y, size, bold, ink)
        y -= 13f
        if (template.tagline.isNotBlank()) {
            text(cs, fitLine(template.tagline, regular, 8f, block.w), block.x, y, 8f, regular, inkMuted)
            y -= 11f
        }
        if (template.address.isNotBlank()) {
            wrap(template.address, regular, 8f, block.w).take(2).forEach { line ->
                text(cs, line, block.x, y, 8f, regular, inkMuted)
                y -= 11f
            }
        }
    }

    /** Tax number, email and phone on one line, or stacked when the line would not fit the block. */
    private fun drawContact(cs: PDPageContentStream, template: DocumentTemplate, block: DocumentLayoutBlock) {
        val parts = listOf(template.taxId, template.email, template.phone).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return
        val line = parts.joinToString("  ·  ")
        val top = block.pdfTop()
        if (textWidth(line, regular, 8f) <= block.w) {
            text(cs, line, block.x, top - 16f, 8f, regular, inkSoft)
            if (!design.ruled) strokeLine(cs, rule, block.x, top - 26f, block.x + block.w, top - 26f, 0.8f)
        } else {
            var y = top - 12f
            parts.forEach { part ->
                if (y < block.pdfY() - 2f) return@forEach
                text(cs, fitLine(part, regular, 8f, block.w), block.x, y, 8f, regular, inkSoft)
                y -= 11f
            }
        }
        if (design == DocumentDesignStyle.PLAIN) {
            strokeLine(cs, rule, MARGIN, block.pdfY() - 4f, W - MARGIN, block.pdfY() - 4f, 0.7f)
        }
    }

    private fun drawLogo(doc: PDDocument, cs: PDPageContentStream, block: DocumentLayoutBlock, template: DocumentTemplate) {
        val uploaded = template.logoPath?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { Files.readAllBytes(Path.of(path)) }.getOrNull()
        }
        val logoBytes = uploaded ?: logoResources.firstNotNullOfOrNull { resource ->
            this::class.java.classLoader.getResource(resource)?.readBytes()
        }?.takeIf { template.companyName.isBlank() && template.logoPath.isNullOrBlank() }

        val x = block.x
        val y = block.pdfY()
        if (logoBytes != null) {
            val image = PDImageXObject.createFromByteArray(doc, logoBytes, "logo")
            val scale = minOf(block.w / image.width, block.h / image.height)
            val imageW = image.width * scale
            val imageH = image.height * scale
            // A logo narrower than its frame hugs the page edge it sits nearest to.
            val imageX = if (x + block.w / 2f > W / 2f) x + block.w - imageW else x
            cs.drawImage(image, imageX, y + (block.h - imageH) / 2f, imageW, imageH)
            return
        }
        if (design.ruled) return

        // No logo: the company name on an accent card.
        val pad = 12f
        val width = block.w - pad * 2
        val name = sanitize(template.companyName.ifBlank { "Empresa" }).uppercase()
        val size = fitSize(name, bold, 15f, 9f, width)
        val nameLines = if (textWidth(name, bold, size) <= width) listOf(name) else wrap(name, bold, size, width).take(2)
        val lineH = size * 1.15f
        // Up to two tagline lines, as many as the card's height allows; the last one kept ends in an
        // ellipsis when text is left over.
        val tagline = template.tagline.takeIf { it.isNotBlank() }?.let { wrap(it, regular, 7f, width) } ?: emptyList()
        fun contentH(lines: Int) = nameLines.size * lineH + if (lines == 0) 0f else 2f + lines * 9f
        var kept = min(2, tagline.size)
        while (kept > 0 && contentH(kept) > block.h - 12f) kept--
        val taglineLines = if (kept in 1 until tagline.size) {
            tagline.take(kept - 1) + fitLine(tagline.drop(kept - 1).joinToString(" "), regular, 7f, width)
        } else {
            tagline.take(kept)
        }
        roundRect(cs, x, y, block.w, block.h, 8f, cBrand)
        var baseline = y + block.h / 2f + contentH(taglineLines.size) / 2f - size * 0.8f
        nameLines.forEach { line ->
            text(cs, line, x + pad, baseline, size, bold, cOnBrand)
            baseline -= lineH
        }
        baseline -= 1f
        taglineLines.forEach { line ->
            text(cs, line, x + pad, baseline, 7f, regular, cOnBrand)
            baseline -= 9f
        }
    }

    // ── Title and client ──────────────────────────────────────────────

    private fun drawTitle(cs: PDPageContentStream, docType: String, meta: DocumentMeta, block: DocumentLayoutBlock?) {
        block ?: return
        if (design == DocumentDesignStyle.SPLIT) {
            drawTitleSplit(cs, docType, meta, block)
            return
        }
        val top = block.pdfTop()
        val status = meta.status
        val badgeSpace = status?.let { badgeWidth(it) + 10f } ?: 0f
        val size = fitSize(docType, bold, 20f, 12f, block.w - badgeSpace)
        val baseline = top - 20f
        text(cs, docType, block.x, baseline, size, bold, ink)
        val titleW = textWidth(docType, bold, size)
        val badgeFits = status != null && titleW + badgeSpace <= block.w
        if (status != null && badgeFits) drawBadge(cs, status, block.x + titleW + 10f, baseline)
        drawMetaLine(cs, meta, block.x, top - 38f, block.w, statusInLine = status != null && !badgeFits)
    }

    /** "ORC-0042 · Emitido em 29/09/2026 · Válido até 29/10/2026", shrunk to fit [maxW]. */
    private fun drawMetaLine(cs: PDPageContentStream, meta: DocumentMeta, x: Float, y: Float, maxW: Float, statusInLine: Boolean) {
        val runs = buildList {
            add(Triple(meta.number, bold, cBrandText))
            add(Triple("  ·  Emitido em ${meta.issued}", regular, inkMuted))
            if (meta.dueLabel != null && meta.due != null) add(Triple("  ·  ${meta.dueLabel} ${meta.due}", regular, inkMuted))
            meta.taxCode?.let { add(Triple("  ·  $it", regular, inkMuted)) }
            if (statusInLine && meta.status != null) add(Triple("  ·  ${meta.status.label}", bold, meta.status.ink))
        }
        var size = 9f
        while (size > 7f && runs.sumOf { (s, f, _) -> textWidth(s, f, size).toDouble() } > maxW) size -= 0.5f
        var cursor = x
        runs.forEach { (s, f, color) ->
            text(cs, s, cursor, y, size, f, color)
            cursor += textWidth(s, f, size)
        }
    }

    private fun drawTitleSplit(cs: PDPageContentStream, docType: String, meta: DocumentMeta, block: DocumentLayoutBlock) {
        fillRect(cs, cBrand, block.x, block.pdfY(), block.w, block.h)
        val x = block.x + 12f
        val width = block.w - 24f
        var y = block.pdfTop() - 20f
        text(cs, docType, x, y, fitSize(docType, bold, 13f, 9f, width), bold, cOnBrand)
        y -= 16f
        text(cs, meta.number, x, y, fitSize(meta.number, bold, 11f, 8f, width), bold, cOnBrand)
        y -= 14f
        val lines = listOfNotNull(
            "Emitido em ${meta.issued}" to regular,
            meta.due?.let { "${meta.dueLabel} $it" to regular },
            meta.taxCode?.let { it to regular },
            meta.status?.let { it.label.uppercase() to bold },
        )
        lines.forEach { (line, font) ->
            if (y < block.pdfY() + 5f) return@forEach
            text(cs, fitLine(line, font, 8f, width), x, y, 8f, font, cOnBrand)
            y -= 11f
        }
    }

    private fun drawClient(cs: PDPageContentStream, client: Client, block: DocumentLayoutBlock?) {
        block ?: return
        val split = design == DocumentDesignStyle.SPLIT
        if (split) {
            fillRect(cs, surface, block.x, block.pdfY(), block.w, block.h)
            strokeRect(cs, rule, block.x, block.pdfY(), block.w, block.h, 0.8f)
        }
        val pad = if (split) 10f else 0f
        val x = block.x + pad
        val width = block.w - pad * 2
        val floor = block.pdfY() + 2f
        var y = block.pdfTop() - if (split) 18f else if (design.ruled) 14f else 10f
        if (design.ruled) {
            text(cs, "Cliente", x, y, 8f, bold, inkMuted)
            y -= 14f
            text(cs, client.name, x, y, fitSize(client.name, bold, 11f, 8.5f, width), bold, ink)
            y -= 13f
        } else {
            text(cs, "CLIENTE", x, y, 7.5f, bold, cBrandText, tracking = 1f)
            y -= 16f
            text(cs, client.name, x, y, fitSize(client.name, bold, 12f, 9f, width), bold, ink)
            y -= 14f
        }
        val detailSize = if (design.ruled) 8f else 8.5f
        val detailLead = if (design.ruled) 11f else 12f
        val detailInk = if (design.ruled) inkMuted else inkSoft
        val details = buildList {
            client.phone.takeIf { it.isNotBlank() }?.let { add(it) }
            clientReference(client)?.let { add(it) }
            clientAddress(client)?.let { addAll(wrap(it, regular, detailSize, width).take(2)) }
        }
        for (line in details) {
            if (y < floor) break
            text(cs, line, x, y, detailSize, regular, detailInk)
            y -= detailLead
        }
    }

    // ── Items table ───────────────────────────────────────────────────

    private data class Row(
        val item: LineItem,
        val primary: List<String>,
        val secondary: List<String>,
        val quantityLine: String?,
        val height: Float,
    )

    private data class ClassicCols(val x: Float, val w: Float, val serviceW: Float, val descW: Float) {
        val serviceX get() = x + 14f
        val serviceTextW get() = serviceW - 22f
        val descX get() = x + serviceW
        val descTextW get() = descW - 12f
        val valueRight get() = x + w - 14f
    }

    private fun classicCols(table: DocumentLayoutBlock): ClassicCols {
        val w = table.w.coerceAtLeast(MIN_TABLE_WIDTH)
        val serviceW = w * 0.31f
        val valueW = w * 0.22f
        return ClassicCols(table.x, w, serviceW, w - serviceW - valueW)
    }

    private data class RuledCols(val x: Float, val w: Float, val desc: Float, val qty: Float, val price: Float, val total: Float) {
        val qtyX get() = x + desc
        val priceX get() = qtyX + qty
        val totalX get() = priceX + price
    }

    private fun ruledCols(table: DocumentLayoutBlock): RuledCols {
        val w = table.w.coerceAtLeast(MIN_TABLE_WIDTH)
        val total = 80f
        val price = 80f
        val qty = 50f
        return RuledCols(table.x, w, w - total - price - qty, qty, price, total)
    }

    /** Wraps a row's text once, so the page-break pass and the painter agree on its height. */
    private fun rowLayout(item: LineItem, table: DocumentLayoutBlock): Row {
        if (design.ruled) {
            val lines = wrap(sanitize(item.description), regular, 9f, ruledCols(table).desc - 10f).take(4)
            return Row(item, lines, emptyList(), null, max(22f, 10f + lines.size * 12f))
        }
        val cols = classicCols(table)
        val (service, detail) = splitItem(item)
        val serviceLines = wrap(service, bold, 9f, cols.serviceTextW).take(3)
        val detailLines = if (detail.isBlank()) emptyList() else wrap(detail, regular, 8.5f, cols.descTextW).take(5)
        val quantityLine = if (item.quantity != 1.0) {
            listOf(qty(item.quantity), sanitize(item.unit).trim()).filter { it.isNotBlank() }.joinToString(" ") +
                " × " + money(item.unitPriceCents)
        } else {
            null
        }
        val contentH = maxOf(serviceLines.size * 12f, detailLines.size * 11f, if (quantityLine != null) 24f else 12f)
        return Row(item, serviceLines, detailLines, quantityLine, max(CLASSIC_MIN_ROW, contentH + 20f))
    }

    /** Draws the column header and returns the Y where the first row starts. */
    private fun drawTableHeader(cs: PDPageContentStream, tableTop: Float, table: DocumentLayoutBlock): Float {
        if (design.ruled) {
            val cols = ruledCols(table)
            val y = tableTop - 20f
            strokeLine(cs, ink, cols.x, y, cols.x + cols.w, y, 1.1f)
            val ty = y + 6f
            text(cs, "Descrição", cols.x + 2f, ty, 8f, bold, inkMuted)
            textR(cs, "Qtd", cols.qtyX + cols.qty - 4f, ty, 8f, bold, inkMuted)
            textR(cs, "Preço", cols.priceX + cols.price - 4f, ty, 8f, bold, inkMuted)
            textR(cs, "Total", cols.totalX + cols.total - 2f, ty, 8f, bold, inkMuted)
            return rowsTop(tableTop)
        }
        val cols = classicCols(table)
        roundRect(cs, cols.x, tableTop - HEADER_BAR_H, cols.w, HEADER_BAR_H, 7f, cBrand)
        val ty = tableTop - 15.2f
        text(cs, "SERVIÇO", cols.serviceX, ty, 7.5f, bold, cOnBrand, tracking = 0.9f)
        text(cs, "DESCRIÇÃO", cols.descX, ty, 7.5f, bold, cOnBrand, tracking = 0.9f)
        textR(cs, "VALOR", cols.valueRight, ty, 7.5f, bold, cOnBrand, tracking = 0.9f)
        return rowsTop(tableTop)
    }

    /** Draws a single table row. `idx` is the global item index (for alternating colors). */
    private fun drawItemRow(cs: PDPageContentStream, idx: Int, row: Row, rowTop: Float, table: DocumentLayoutBlock) {
        if (design.ruled) {
            val cols = ruledCols(table)
            if (idx % 2 == 0) fillRect(cs, surface, cols.x, rowTop - row.height, cols.w, row.height)
            strokeLine(cs, rule, cols.x, rowTop - row.height, cols.x + cols.w, rowTop - row.height, 0.6f)
            var dy = rowTop - 12f
            row.primary.forEach { line ->
                text(cs, line, cols.x + 2f, dy, 9f, regular, ink)
                dy -= 12f
            }
            val mid = rowTop - row.height / 2f - 3f
            val quantity = listOf(qty(row.item.quantity), row.item.unit.trim()).filter { it.isNotBlank() }.joinToString(" ")
            textR(cs, quantity, cols.qtyX + cols.qty - 4f, mid, 9f, regular, inkSoft)
            textR(cs, money(row.item.unitPriceCents), cols.priceX + cols.price - 4f, mid, 9f, regular, inkSoft)
            textR(cs, money(row.item.totalCents), cols.totalX + cols.total - 2f, mid, 9f, bold, ink)
            return
        }
        val cols = classicCols(table)
        roundRect(cs, cols.x, rowTop - row.height, cols.w, row.height, 7f, if (idx % 2 == 0) surface else surfaceAlt)
        val contentH = maxOf(row.primary.size * 12f, row.secondary.size * 11f, if (row.quantityLine != null) 24f else 12f)
        val baseline = rowTop - (row.height - contentH) / 2f - 8.5f
        row.primary.forEachIndexed { i, line -> text(cs, line, cols.serviceX, baseline - i * 12f, 9f, bold, ink) }
        row.secondary.forEachIndexed { i, line -> text(cs, line, cols.descX, baseline - i * 11f, 8.5f, regular, inkSoft) }
        textR(cs, money(row.item.totalCents), cols.valueRight, baseline, 10f, bold, ink)
        row.quantityLine?.let { textR(cs, it, cols.valueRight, baseline - 12f, 7.5f, regular, inkMuted) }
    }

    /** Thin header printed at the top of every continuation page. */
    private fun drawContinuationHeader(
        cs: PDPageContentStream,
        docType: String,
        number: String,
        template: DocumentTemplate,
        table: DocumentLayoutBlock,
    ) {
        val left = table.x
        val right = table.x + table.w.coerceAtLeast(MIN_TABLE_WIDTH)
        text(cs, "$docType  ·  $number", left, H - 30f, 9f, bold, ink)
        template.companyName.takeIf { it.isNotBlank() }?.let { textR(cs, it, right, H - 30f, 8f, regular, inkMuted) }
        strokeLine(cs, rule, left, H - 40f, right, H - 40f, 0.8f)
    }

    // ── Totals ────────────────────────────────────────────────────────

    private fun totalsHeight(block: DocumentLayoutBlock): Float =
        if (design.ruled) 26f else block.h.coerceIn(26f, 40f)

    private fun drawTotals(cs: PDPageContentStream, totalCents: Long, y: Float, block: DocumentLayoutBlock): Float {
        val amount = money(totalCents)
        val x = block.x
        val w = block.w
        if (design.ruled) {
            strokeLine(cs, cBrand, x, y, x + w, y, 1.5f)
            text(cs, "Total", x, y - 18f, 10f, bold, ink)
            textR(cs, amount, x + w, y - 18f, fitSize(amount, bold, 13f, 9f, w - 60f), bold, ink)
            return y - totalsHeight(block)
        }
        val h = totalsHeight(block)
        pill(cs, x, y - h, w, h, cBrand)
        val mid = y - h / 2f
        text(cs, "TOTAL", x + 16f, mid - 2.7f, 8f, bold, cOnBrand, tracking = 1f)
        val labelW = textWidth("TOTAL", bold, 8f, tracking = 1f)
        val size = fitSize(amount, bold, 13f, 8f, w - 44f - labelW)
        textR(cs, amount, x + w - 16f, mid - size * 0.36f, size, bold, cOnBrand)
        return y - h
    }

    // ── Payment and terms ─────────────────────────────────────────────

    /**
     * Draws the payment and terms sections stacked below [startY] (PDF coordinates, decreasing
     * downward) and returns the Y below the last line drawn.
     *
     * Horizontal placement comes from the layout blocks, but their vertical positions are ignored:
     * they are authored against an empty table, so honouring them puts the text on top of the item
     * rows as soon as the table grows.
     */
    private fun drawPaymentAndTerms(
        cs: PDPageContentStream,
        paymentTerms: String?,
        installments: List<String>,
        terms: String?,
        blocks: Map<String, DocumentLayoutBlock>,
        startY: Float,
    ): Float {
        val paymentBlock = blocks["payment"]?.takeIf { it.visible }
        val termsBlock = blocks["terms"]?.takeIf { it.visible }
        val anchor = paymentBlock ?: termsBlock ?: return startY
        val payment = paymentTerms?.takeIf { it.isNotBlank() && paymentBlock != null }
        val conditions = terms?.takeIf { it.isNotBlank() && termsBlock != null }
        var y = startY
        payment?.let {
            y = drawSection(cs, "Forma de pagamento", wrap(it, regular, 9f, anchor.w).take(PAYMENT_MAX_LINES), anchor.x, y) - SECTION_GAP
        }
        if (installments.isNotEmpty() && paymentBlock != null) {
            y = drawSection(cs, "Prestações", installments.map { fitLine(it, regular, 9f, anchor.w) }, anchor.x, y) - SECTION_GAP
        }
        conditions?.let {
            y = drawSection(cs, "Termos e condições", wrap(it, regular, 9f, anchor.w).take(TERMS_MAX_LINES), anchor.x, y)
        }
        return y
    }

    private fun drawSection(cs: PDPageContentStream, heading: String, lines: List<String>, x: Float, startY: Float): Float {
        var y = startY
        if (design.ruled) {
            text(cs, heading, x, y, 10f, bold, cBrandText)
        } else {
            text(cs, heading.uppercase(), x, y, 7.5f, bold, cBrandText, tracking = 1f)
        }
        y -= SECTION_HEADING
        lines.forEach { line ->
            text(cs, line, x, y, 9f, regular, ink)
            y -= SECTION_LINE
        }
        return y
    }

    // ── Footer ────────────────────────────────────────────────────────

    /**
     * Footer text and page numbers on every page, drawn once all pages exist. Pinned to the page
     * bottom, never to the block's authored Y: the closing block above it grows with the table
     * and would otherwise collide with a footer parked mid-page.
     */
    private fun drawFooters(doc: PDDocument, template: DocumentTemplate, block: DocumentLayoutBlock?) {
        val pages = doc.numberOfPages
        val footer = template.footerText.takeIf { it.isNotBlank() } ?: DEFAULT_FOOTER
        val xRight = block?.let { it.x + it.w } ?: (W - MARGIN)
        doc.pages.forEachIndexed { index, page ->
            val parts = listOfNotNull(
                footer.takeIf { block?.visible != false },
                "Página ${index + 1} de $pages".takeIf { pages > 1 },
            )
            if (parts.isEmpty()) return@forEachIndexed
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                textR(cs, parts.joinToString("  ·  "), xRight, FOOTER_Y, 7.5f, regular, inkMuted)
            }
        }
    }

    // ── Decoration ────────────────────────────────────────────────────

    /** Fine accent-tinted waves in the top margin (first page) and the bottom edge (every page). */
    private fun drawDecor(cs: PDPageContentStream, top: Boolean) {
        cs.setStrokingColor(cWave)
        cs.setLineWidth(0.5f)
        if (top) {
            cs.saveGraphicsState()
            cs.addRect(0f, H - 40f, W, 40f)
            cs.clip()
            repeat(9) { i ->
                val o = i * 3.4f
                cs.moveTo(0f, H - 8f - o)
                cs.curveTo(W * 0.22f, H + 8f - o, W * 0.42f, H - 34f - o * 0.5f, W * 0.62f, H - 18f - o)
                cs.curveTo(W * 0.8f, H - 4f - o, W * 0.9f, H - 30f - o * 0.6f, W, H - 12f - o * 0.8f)
                cs.stroke()
            }
            cs.restoreGraphicsState()
        }
        cs.saveGraphicsState()
        cs.addRect(0f, 0f, W, 14f)
        cs.clip()
        repeat(5) { i ->
            val o = i * 3f
            cs.moveTo(0f, 4f + o)
            cs.curveTo(W * 0.3f, 16f + o, W * 0.55f, -6f + o, W * 0.75f, 6f + o)
            cs.curveTo(W * 0.88f, 14f + o, W * 0.95f, 2f + o, W, 8f + o)
            cs.stroke()
        }
        cs.restoreGraphicsState()
    }

    // ── Primitives ────────────────────────────────────────────────────

    private fun strokeLine(cs: PDPageContentStream, color: Color, x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
        cs.setStrokingColor(color)
        cs.setLineWidth(width)
        cs.moveTo(x1, y1)
        cs.lineTo(x2, y2)
        cs.stroke()
    }

    private fun strokeRect(cs: PDPageContentStream, color: Color, x: Float, y: Float, w: Float, h: Float, width: Float) {
        cs.setStrokingColor(color)
        cs.setLineWidth(width)
        cs.addRect(x, y, w, h)
        cs.stroke()
    }

    private fun fillRect(cs: PDPageContentStream, color: Color, x: Float, y: Float, w: Float, h: Float) {
        cs.setNonStrokingColor(color)
        cs.addRect(x, y, w, h)
        cs.fill()
    }

    private fun pill(cs: PDPageContentStream, x: Float, y: Float, w: Float, h: Float, color: Color) =
        roundRect(cs, x, y, w, h, h / 2f, color)

    private fun roundRect(cs: PDPageContentStream, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Color) {
        val r = minOf(radius, h / 2f, w / 2f)
        val k = 0.55228475f * r
        cs.setNonStrokingColor(color)
        cs.moveTo(x + r, y)
        cs.lineTo(x + w - r, y)
        cs.curveTo(x + w - r + k, y, x + w, y + r - k, x + w, y + r)
        cs.lineTo(x + w, y + h - r)
        cs.curveTo(x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h)
        cs.lineTo(x + r, y + h)
        cs.curveTo(x + r - k, y + h, x, y + h - r + k, x, y + h - r)
        cs.lineTo(x, y + r)
        cs.curveTo(x, y + r - k, x + r - k, y, x + r, y)
        cs.fill()
    }

    private fun badgeWidth(status: Status): Float = textWidth(status.label.uppercase(), bold, 7f, tracking = 0.6f) + 14f

    /** Status pill beside the title; [baseline] is the title's baseline. */
    private fun drawBadge(cs: PDPageContentStream, status: Status, x: Float, baseline: Float) {
        val y = baseline - 1f
        pill(cs, x, y, badgeWidth(status), 15f, status.fill)
        text(cs, status.label.uppercase(), x + 7f, y + 5f, 7f, bold, status.ink, tracking = 0.6f)
    }

    private fun applyTheme(template: DocumentTemplate) {
        design = DocumentDesignStyle.parse(template.style)
        showDecor = template.showDecor
        cBrand = parseHex(template.accentColor) ?: DEFAULT_ACCENT_COLOR
        cBrandText = readableOnWhite(cBrand)
        cOnBrand = onFill(cBrand)
        cWave = mix(cBrand, Color.WHITE, 0.45f)
    }

    private fun parseHex(value: String): Color? {
        val hex = DocumentLayouts.sanitizeAccent(value).removePrefix("#")
        if (hex.length != 6) return null
        return runCatching {
            Color(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
        }.getOrNull()
    }

    companion object {
        /** Payment terms on an invoice whose tenant has not written its own. Quotes get none. */
        const val DEFAULT_INVOICE_PAYMENT_TERMS = "Pagamento até à data de vencimento."

        /** The same, for an invoice paid in installments; the installments follow under their own heading. */
        const val DEFAULT_INSTALLMENT_PAYMENT_TERMS = "Pagamento em prestações, nas datas indicadas."

        /** Portuguese law prints the code as `ATCUD:` and the code; a prefix typed with it isn't doubled. */
        internal fun atcud(code: String): String =
            "ATCUD:" + code.trim().replace(Regex("^ATCUD\\s*:?\\s*", RegexOption.IGNORE_CASE), "")

        /** Terms on a quote without its own validity date, when the tenant has not written terms. Invoices get none. */
        const val DEFAULT_QUOTE_TERMS = "Este orçamento é válido por 30 dias."
        const val DEFAULT_FOOTER = "gerado por thebotslab.pt"

        private val INK = Color(17, 19, 24)
        private val DEFAULT_ACCENT_COLOR = Color(150, 170, 182)

        /** WCAG relative luminance. */
        internal fun luminance(c: Color): Double {
            fun channel(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        }

        internal fun contrast(a: Color, b: Color): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        /** The accent darkened until it reads as text on white (4.5:1); a yellow accent becomes a dark gold. */
        internal fun readableOnWhite(accent: Color): Color {
            var c = accent
            repeat(30) {
                if (contrast(c, Color.WHITE) >= 4.5) return c
                c = Color((c.red * 0.9f).toInt(), (c.green * 0.9f).toInt(), (c.blue * 0.9f).toInt())
            }
            return c
        }

        /** Ink or white, whichever reads better on [fill]; pure black for mid-tones where neither reaches 4.5:1. */
        internal fun onFill(fill: Color): Color {
            val best = if (contrast(INK, fill) >= contrast(Color.WHITE, fill)) INK else Color.WHITE
            return if (contrast(best, fill) < 4.5 && contrast(Color.BLACK, fill) > contrast(best, fill)) Color.BLACK else best
        }

        private fun mix(a: Color, b: Color, amountOfA: Float): Color = Color(
            (a.red * amountOfA + b.red * (1 - amountOfA)).toInt().coerceIn(0, 255),
            (a.green * amountOfA + b.green * (1 - amountOfA)).toInt().coerceIn(0, 255),
            (a.blue * amountOfA + b.blue * (1 - amountOfA)).toInt().coerceIn(0, 255),
        )

        /** Portuguese currency: 1 234,56 €. */
        internal fun money(cents: Long): String {
            val sign = if (cents < 0) "-" else ""
            val abs = kotlin.math.abs(cents)
            val euros = (abs / 100).toString().reversed().chunked(3).joinToString(" ").reversed()
            return "$sign$euros,${(abs % 100).toString().padStart(2, '0')} €"
        }

        private fun qty(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString()
            else String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.').replace('.', ',')

        private fun fmtDate(iso: String): String {
            val p = iso.take(10).split("-")
            return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else iso
        }
    }

    // ── Text helpers ──────────────────────────────────────────────────

    /** Left-aligned text. [tracking] is extra space between letters, in points. */
    private fun text(
        cs: PDPageContentStream,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        font: PDFont,
        color: Color,
        tracking: Float = 0f,
    ) {
        val safe = printable(value, font).ifBlank { return }
        cs.beginText()
        cs.setNonStrokingColor(color)
        cs.setFont(font, size)
        cs.setCharacterSpacing(tracking)
        cs.newLineAtOffset(x, y)
        cs.showText(safe)
        cs.endText()
    }

    /** Right-aligned text — `xRight` is the right edge. */
    private fun textR(
        cs: PDPageContentStream,
        value: String,
        xRight: Float,
        y: Float,
        size: Float,
        font: PDFont,
        color: Color,
        tracking: Float = 0f,
    ) {
        text(cs, value, xRight - textWidth(value, font, size, tracking), y, size, font, color, tracking)
    }

    private fun textWidth(value: String, font: PDFont, size: Float, tracking: Float = 0f): Float {
        val safe = printable(value, font)
        if (safe.isEmpty()) return 0f
        return font.getStringWidth(safe) / 1000f * size + tracking * (safe.codePointCount(0, safe.length) - 1)
    }

    /** Largest size between [max] and [min] at which [value] fits [width]. */
    private fun fitSize(value: String, font: PDFont, max: Float, min: Float, width: Float): Float {
        var size = max
        while (size > min && textWidth(value, font, size) > width) size -= 0.5f
        return size
    }

    /** [value] cut with an ellipsis so it fits [width] on one line. */
    private fun fitLine(value: String, font: PDFont, size: Float, width: Float): String {
        val clean = printable(value, font)
        if (textWidth(clean, font, size) <= width) return clean
        var cut = clean
        while (cut.isNotEmpty() && textWidth("$cut…", font, size) > width) cut = cut.dropLast(1)
        return "${cut.trimEnd()}…"
    }

    private fun splitItem(item: LineItem): Pair<String, String> {
        val description = sanitize(item.description).trim()
        val separators = listOf(" - ", ": ", " | ")
        separators.forEach { sep ->
            val idx = description.indexOf(sep)
            if (idx > 0) return description.take(idx).trim() to description.drop(idx + sep.length).trim()
        }
        val firstPeriod = description.indexOf(". ")
        if (firstPeriod in 12..80) return description.take(firstPeriod + 1).trim() to description.drop(firstPeriod + 2).trim()
        return description to ""
    }

    /**
     * "Rua das Flores 12, 1200-001 Lisboa" as one text: compact client blocks (68pt) only fit one address
     * line under the phone and reference, and a separate postal code line would be cut there.
     */
    private fun clientAddress(client: Client): String? {
        val locality = listOfNotNull(client.postalCode, client.city).map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ")
        return listOfNotNull(client.address?.trim(), locality).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
    }

    /** Client number and tax number share one line, so a NIF never makes the client block taller. */
    private fun clientReference(client: Client): String? =
        listOfNotNull(
            client.number.takeIf { it.isNotBlank() },
            client.taxId?.takeIf { it.isNotBlank() }?.let { "NIF $it" },
        ).joinToString("  ·  ").takeIf { it.isNotBlank() }

    private fun wrap(value: String, font: PDFont, size: Float, maxWidth: Float): List<String> {
        val words = printable(value, font).split(Regex("\\s+")).filter { it.isNotBlank() }
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
        .filter { it.code >= 0x20 }

    /** [value] without characters [font] has no glyph for (emoji, most non-Latin scripts), which would abort the PDF. */
    private fun printable(value: String, font: PDFont): String {
        val clean = sanitize(value)
        if (encodes(font, clean)) return clean
        val out = StringBuilder()
        var i = 0
        while (i < clean.length) {
            val cp = clean.codePointAt(i)
            val glyph = String(Character.toChars(cp))
            if (encodes(font, glyph)) out.append(glyph)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun encodes(font: PDFont, value: String): Boolean = runCatching { font.encode(value) }.isSuccess
}
