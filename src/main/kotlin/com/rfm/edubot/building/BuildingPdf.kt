package com.rfm.edubot.building

import com.rfm.edubot.building.model.Measurement
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.SiteLogEntry
import com.rfm.edubot.crm.PdfGenerator
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.tenant.model.DocumentLayouts
import com.rfm.edubot.tenant.model.DocumentTemplate
import kotlinx.datetime.LocalDate
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
import kotlin.math.min

/** What a measurement's PDF prints: the measurement, its project and client, the company's day it was approved, and its invoice. */
class MeasurementSheet(val measurement: Measurement, val project: Project, val client: Client?, val approvedOn: LocalDate?, val invoiceNumber: String?)

/** One diary day, with some of its photos (thumbnail JPEGs) and how many more it has. */
class DiaryDay(val entry: SiteLogEntry, val photos: List<DiaryPhoto>, val morePhotos: Int)

class DiaryPhoto(val caption: String?, val jpeg: ByteArray)

/** What the diary's PDF prints: the project's days from [from] to [to], oldest first. */
class DiarySheet(val project: Project, val client: Client?, val managerName: String?, val from: LocalDate, val to: LocalDate, val days: List<DiaryDay>)

/**
 * Building's PDFs, in the company's [locale] and with its logo, name and colour: a measurement (auto de medição)
 * with its lines, totals and signature boxes, and the site diary of a period. Apart from the quotes' and invoices'
 * generator, whose page the company designs block by block; these flow from page to page.
 */
class BuildingPdf(private val template: DocumentTemplate, private val locale: String) {
    private fun t(key: String, vararg params: Pair<String, String>) = BuildingCopy.t(locale, key, *params)

    private val accent = DocumentLayouts.sanitizeAccent(template.accentColor).takeIf { it.isNotEmpty() }?.let { hex ->
        Color(hex.substring(1, 3).toInt(16), hex.substring(3, 5).toInt(16), hex.substring(5, 7).toInt(16))
    } ?: DEFAULT_ACCENT
    private val accentText = PdfGenerator.readableOnWhite(accent)
    private val onAccent = PdfGenerator.onFill(accent)

    fun measurement(sheet: MeasurementSheet): ByteArray {
        val m = sheet.measurement
        val title = t("pdf.measurementTitle", "n" to m.number.toString())
        return write("$title · ${sheet.project.number}") { w ->
            header(
                w, title, t("pdf.status.${m.status.name}"),
                listOfNotNull(
                    t("pdf.project") to "${sheet.project.number} · ${sheet.project.name}",
                    sheet.client?.let { t("pdf.client") to clientLine(it) },
                    sheet.project.address?.takeIf { it.isNotBlank() }?.let { t("pdf.site") to it },
                    t("pdf.periodEnd") to BuildingCopy.date(m.periodEnd),
                    sheet.approvedOn?.let { t("pdf.approved") to BuildingCopy.date(it) },
                    sheet.invoiceNumber?.let { t("pdf.invoice") to it },
                ),
            )
            measurementLines(w, m)
            measurementTotals(w, m)
            m.notes?.takeIf { it.isNotBlank() }?.let { paragraphBlock(w, t("pdf.notes"), it) }
            signatures(w)
        }
    }

    fun diary(sheet: DiarySheet): ByteArray {
        val title = t("pdf.diaryTitle")
        val period = "${BuildingCopy.date(sheet.from)} – ${BuildingCopy.date(sheet.to)}"
        return write("$title · ${sheet.project.number} · $period") { w ->
            header(
                w, title, null,
                listOfNotNull(
                    t("pdf.project") to "${sheet.project.number} · ${sheet.project.name}",
                    sheet.client?.let { t("pdf.client") to clientLine(it) },
                    sheet.project.address?.takeIf { it.isNotBlank() }?.let { t("pdf.site") to it },
                    t("pdf.period") to period,
                    sheet.managerName?.let { t("pdf.manager") to it },
                ),
            )
            if (sheet.days.isEmpty()) {
                w.text(t("pdf.noEntries"), LEFT, w.y - 10f, 9.5f, w.regular, SOFT)
                w.y -= 24f
            }
            sheet.days.forEach { diaryDay(w, it) }
        }
    }

    // ── The measurement ───────────────────────────────────────────────────────────────────────────

    private class Column(val label: String, val width: Float, val right: Boolean)

    private fun measurementLines(w: Writer, m: Measurement) {
        val columns = listOf(
            Column(t("pdf.thLine"), 171f, false), Column(t("pdf.thUnit"), 32f, false),
            Column(t("pdf.thContract"), 50f, true), Column(t("pdf.thPrevious"), 50f, true), Column(t("pdf.thPeriod"), 50f, true),
            Column(t("pdf.thToDate"), 50f, true), Column("%", 36f, true), Column(t("pdf.thValue"), 76f, true),
        )
        val xs = columns.runningFold(LEFT) { x, c -> x + c.width }
        fun head() {
            w.fill(SURFACE, LEFT, w.y - 20f, WIDTH, 20f)
            columns.forEachIndexed { i, c ->
                if (c.right) w.textRight(c.label, xs[i + 1] - CELL_PAD, w.y - 13f, 7f, w.bold, SOFT)
                else w.text(w.fit(c.label, w.bold, 7f, c.width - CELL_PAD * 2), xs[i] + CELL_PAD, w.y - 13f, 7f, w.bold, SOFT)
            }
            w.y -= 20f
        }
        w.room(44f)
        head()
        m.lines.forEach { line ->
            val description = w.wrap(line.description, w.regular, 8f, columns[0].width - CELL_PAD * 2)
            val rowH = description.size * 10.5f + 8f
            if (w.y - rowH < BOTTOM) {
                w.newPage()
                head()
            }
            val baseline = w.y - 12f
            description.forEachIndexed { i, text -> w.text(text, LEFT + CELL_PAD, baseline - i * 10.5f, 8f, w.regular, INK) }
            w.text(w.fit(line.unit, w.regular, 8f, columns[1].width - CELL_PAD * 2), xs[1] + CELL_PAD, baseline, 8f, w.regular, SOFT)
            val cells = listOf(
                BuildingCopy.decimal(locale, line.quantity), BuildingCopy.decimal(locale, line.previousQty),
                BuildingCopy.decimal(locale, MeasurementMath.periodQty(line)), BuildingCopy.decimal(locale, line.toDateQty),
                BuildingCopy.pct(locale, MeasurementMath.pctToDate(line)), BuildingCopy.money(locale, MeasurementMath.periodCents(line)),
            )
            cells.forEachIndexed { i, value ->
                val font = if (i == cells.lastIndex) w.bold else w.regular
                w.textRight(value, xs[i + 3] - CELL_PAD, baseline, 8f, font, INK)
            }
            w.y -= rowH
            w.line(RULE, LEFT, w.y, RIGHT, w.y, 0.5f)
        }
        w.y -= 16f
    }

    private fun measurementTotals(w: Writer, m: Measurement) {
        val totals = MeasurementMath.totals(m.lines, m.retentionPct)
        val rows = listOfNotNull(
            t("pdf.totalContract") to BuildingCopy.money(locale, totals.contractCents),
            t("pdf.totalPrevious") to BuildingCopy.money(locale, totals.previousCents),
            t("pdf.totalToDate") to BuildingCopy.money(locale, totals.toDateCents),
            t("pdf.totalPeriod") to BuildingCopy.money(locale, totals.grossCents),
            if (m.retentionPct > 0) t("retention", "pct" to BuildingCopy.percent(locale, m.retentionPct)) to BuildingCopy.money(locale, -totals.retentionCents) else null,
        )
        w.room(rows.size * 16f + 34f)
        val x = RIGHT - 250f
        val top = w.y
        val done = if (totals.contractCents > 0) (totals.toDateCents.toDouble() / totals.contractCents * 100).coerceIn(0.0, 100.0) else 0.0
        w.text(t("pdf.done", "pct" to BuildingCopy.pct(locale, done)), LEFT, top - 11f, 9.5f, w.bold, INK)
        w.fill(RULE, LEFT, top - 26f, 200f, 5f)
        w.fill(accent, LEFT, top - 26f, (200 * done / 100).toFloat(), 5f)
        rows.forEach { (label, value) ->
            w.text(label, x, w.y - 11f, 8.5f, w.regular, SOFT)
            w.textRight(value, RIGHT, w.y - 11f, 8.5f, w.regular, INK)
            w.y -= 16f
        }
        w.fill(accent, x - 8f, w.y - 22f, 258f, 22f)
        w.text(t("pdf.totalNet"), x, w.y - 15f, 9.5f, w.bold, onAccent)
        w.textRight(BuildingCopy.money(locale, totals.netCents), RIGHT - 4f, w.y - 15f, 10f, w.bold, onAccent)
        w.y -= 40f
    }

    private fun signatures(w: Writer) {
        w.room(96f)
        val boxW = (WIDTH - 24f) / 2
        listOf(t("pdf.contractor"), t("pdf.owner")).forEachIndexed { i, label ->
            val x = LEFT + i * (boxW + 24f)
            w.text(label, x, w.y - 10f, 8.5f, w.bold, SOFT)
            w.line(INK, x, w.y - 58f, x + boxW, w.y - 58f, 0.6f)
            w.text("${t("pdf.signDate")}: ____ / ____ / ________", x, w.y - 74f, 8f, w.regular, MUTED)
        }
        w.y -= 92f
    }

    // ── The diary ─────────────────────────────────────────────────────────────────────────────────

    private fun diaryDay(w: Writer, day: DiaryDay) {
        val e = day.entry
        w.room(64f)
        w.fill(SURFACE, LEFT, w.y - 22f, WIDTH, 22f)
        w.text(BuildingCopy.longDate(locale, e.day), LEFT + 8f, w.y - 15f, 10f, w.bold, INK)
        val facts = listOfNotNull(
            e.weather?.let { t("pdf.weather.${it.name}") },
            e.temperatureC?.let { "$it °C" },
            e.workers?.let { t("pdf.workers", "n" to it.toString()) },
            e.lostHours?.takeIf { it > 0 }?.let { t("pdf.lostHours", "n" to BuildingCopy.decimal(locale, it, 1)) },
        ).joinToString("  ·  ")
        if (facts.isNotEmpty()) w.textRight(facts, RIGHT - 8f, w.y - 15f, 8f, w.regular, SOFT)
        w.y -= 32f
        listOf(
            t("pdf.workDone") to e.workDone, t("pdf.equipment") to e.equipment, t("pdf.materials") to e.materials,
            t("pdf.visitors") to e.visitors, t("pdf.incidents") to e.incidents,
        ).forEach { (label, text) -> text?.takeIf { it.isNotBlank() }?.let { paragraphBlock(w, label, it) } }
        if (e.progress.isNotEmpty()) {
            val moved = e.progress.joinToString("  ·  ") { t("pdf.phaseMoved", "name" to it.phaseName, "from" to it.fromPct.toString(), "to" to it.toPct.toString()) }
            paragraphBlock(w, t("pdf.progress"), moved)
        }
        photoGrid(w, day.photos)
        if (day.morePhotos > 0) {
            w.room(14f)
            w.text(t("pdf.morePhotos", "n" to day.morePhotos.toString()), LEFT, w.y - 9f, 7.5f, w.regular, MUTED)
            w.y -= 16f
        }
        w.y -= 10f
    }

    private fun photoGrid(w: Writer, photos: List<DiaryPhoto>) {
        val cellW = (WIDTH - PHOTO_GAP * (PHOTO_COLUMNS - 1)) / PHOTO_COLUMNS
        photos.mapNotNull { photo -> w.image(photo.jpeg)?.let { it to photo.caption } }.chunked(PHOTO_COLUMNS).forEach { row ->
            val sized = row.map { (image, caption) ->
                val scale = min(cellW / image.width, PHOTO_MAX_H / image.height)
                Triple(image, image.width * scale to image.height * scale, caption?.takeIf { it.isNotBlank() }?.let { w.wrap(it, w.regular, 7f, cellW).take(2) }.orEmpty())
            }
            val rowH = sized.maxOf { it.second.second + it.third.size * 9f } + 12f
            w.room(rowH)
            sized.forEachIndexed { i, (image, size, caption) ->
                val x = LEFT + i * (cellW + PHOTO_GAP)
                w.cs.drawImage(image, x, w.y - size.second, size.first, size.second)
                caption.forEachIndexed { line, text -> w.text(text, x, w.y - size.second - 9f - line * 9f, 7f, w.regular, MUTED) }
            }
            w.y -= rowH
        }
    }

    // ── Shared blocks ─────────────────────────────────────────────────────────────────────────────

    /** The company (its logo, or its name in its colour, and its details), the title, and the record's facts. */
    private fun header(w: Writer, title: String, status: String?, facts: List<Pair<String, String>>) {
        val company = template.companyName
        val logo = logoBytes()?.let { w.image(it) }
        if (logo != null) {
            val scale = min(LOGO_W / logo.width, LOGO_H / logo.height)
            w.cs.drawImage(logo, LEFT, w.y - logo.height * scale, logo.width * scale, logo.height * scale)
        } else {
            w.text(w.fit(company, w.bold, 15f, 260f), LEFT, w.y - 16f, 15f, w.bold, accentText)
            template.tagline.takeIf { it.isNotBlank() }?.let { w.text(w.fit(it, w.regular, 8f, 260f), LEFT, w.y - 30f, 8f, w.regular, MUTED) }
        }
        val details = listOfNotNull(
            company.takeIf { logo != null && it.isNotBlank() },
            template.taxId.takeIf { it.isNotBlank() }?.let { t("pdf.taxId", "id" to it) },
            template.address.takeIf { it.isNotBlank() },
            listOf(template.phone, template.email).filter { it.isNotBlank() }.joinToString("  ·  ").takeIf { it.isNotBlank() },
        )
        details.forEachIndexed { i, line ->
            val font = if (i == 0 && logo != null) w.bold else w.regular
            w.textRight(w.fit(line, font, 8f, 240f), RIGHT, w.y - 10f - i * 11f, 8f, font, if (i == 0 && logo != null) INK else SOFT)
        }
        w.y -= LOGO_H + 12f
        w.line(accent, LEFT, w.y, RIGHT, w.y, 1.2f)
        w.y -= 30f
        w.text(w.fit(title, w.bold, 18f, WIDTH - 120f), LEFT, w.y, 18f, w.bold, INK)
        status?.let { w.textRight(it.uppercase(), RIGHT, w.y + 3f, 8f, w.bold, accentText) }
        w.y -= 22f
        val colW = WIDTH / 2
        facts.chunked(2).forEach { pair ->
            val lines = pair.map { (_, value) -> w.wrap(value, w.regular, 9.5f, colW - 14f).take(2) }
            pair.forEachIndexed { i, (label, _) ->
                val x = LEFT + i * colW
                w.text(label.uppercase(), x, w.y - 8f, 6.8f, w.bold, MUTED)
                lines[i].forEachIndexed { line, text -> w.text(text, x, w.y - 21f - line * 12f, 9.5f, w.regular, INK) }
            }
            w.y -= 16f + lines.maxOf { it.size } * 12f + 4f
        }
        w.y -= 12f
    }

    /** A small uppercase label and its text, wrapped, breaking across pages line by line. */
    private fun paragraphBlock(w: Writer, label: String, text: String) {
        w.room(30f)
        w.text(label.uppercase(), LEFT, w.y - 8f, 6.8f, w.bold, MUTED)
        w.y -= 14f
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { paragraph ->
            w.wrap(paragraph, w.regular, 9f, WIDTH).forEach { line ->
                w.room(12f)
                w.text(line, LEFT, w.y - 9f, 9f, w.regular, INK)
                w.y -= 12f
            }
        }
        w.y -= 8f
    }

    private fun clientLine(client: Client) =
        listOfNotNull(client.name, client.taxId?.takeIf { it.isNotBlank() }?.let { t("pdf.taxId", "id" to it) }).joinToString(" · ")

    private fun logoBytes(): ByteArray? =
        template.logoPath?.takeIf { it.isNotBlank() }?.let { path -> runCatching { Files.readAllBytes(Path.of(path)) }.getOrNull() }

    /** Lays [content] out from page to page, then numbers the pages and writes the footer on each. */
    private fun write(runningHead: String, content: (Writer) -> Unit): ByteArray = PDDocument().use { doc ->
        val w = Writer(doc, font(doc, "pdf/Montserrat-Regular.ttf", Standard14Fonts.FontName.HELVETICA), font(doc, "pdf/Montserrat-Bold.ttf", Standard14Fonts.FontName.HELVETICA_BOLD), runningHead)
        w.newPage()
        content(w)
        w.cs.close()
        val footer = template.footerText.ifBlank { template.companyName }
        w.pages.forEachIndexed { i, page ->
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                w.cs = cs
                w.line(RULE, LEFT, 42f, RIGHT, 42f, 0.5f)
                w.text(w.fit(footer, w.regular, 7.5f, 360f), LEFT, 30f, 7.5f, w.regular, MUTED)
                w.textRight(t("pdf.page", "n" to (i + 1).toString(), "total" to w.pages.size.toString()), RIGHT, 30f, 7.5f, w.regular, MUTED)
            }
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    private fun font(doc: PDDocument, resource: String, fallback: Standard14Fonts.FontName): PDFont =
        javaClass.classLoader.getResourceAsStream(resource)?.use { stream -> runCatching { PDType0Font.load(doc, stream, true) }.getOrNull() }
            ?: PDType1Font(fallback)

    /** A page cursor: [y] is where the next block starts, and [room] turns the page when a block wouldn't fit. */
    private class Writer(val doc: PDDocument, val regular: PDFont, val bold: PDFont, val runningHead: String) {
        lateinit var cs: PDPageContentStream
        val pages = mutableListOf<PDPage>()
        var y = TOP

        fun newPage() {
            if (pages.isNotEmpty()) cs.close()
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            pages += page
            cs = PDPageContentStream(doc, page)
            y = TOP
            if (pages.size > 1) {
                text(fit(runningHead, regular, 8f, WIDTH), LEFT, y - 8f, 8f, regular, MUTED)
                y -= 24f
            }
        }

        fun room(height: Float) {
            if (y - height < BOTTOM) newPage()
        }

        fun text(value: String, x: Float, y: Float, size: Float, font: PDFont, color: Color) {
            val safe = printable(value, font).ifBlank { return }
            cs.beginText()
            cs.setNonStrokingColor(color)
            cs.setFont(font, size)
            cs.newLineAtOffset(x, y)
            cs.showText(safe)
            cs.endText()
        }

        fun textRight(value: String, right: Float, y: Float, size: Float, font: PDFont, color: Color) =
            text(value, right - width(value, font, size), y, size, font, color)

        fun width(value: String, font: PDFont, size: Float): Float = font.getStringWidth(printable(value, font)) / 1000f * size

        /** [value] cut with an ellipsis so it fits [max] on one line. */
        fun fit(value: String, font: PDFont, size: Float, max: Float): String {
            val clean = printable(value, font)
            if (width(clean, font, size) <= max) return clean
            var cut = clean
            while (cut.isNotEmpty() && width("$cut…", font, size) > max) cut = cut.dropLast(1)
            return "${cut.trimEnd()}…"
        }

        /** [value] in lines no wider than [max]; a word longer than a line is cut to fit. */
        fun wrap(value: String, font: PDFont, size: Float, max: Float): List<String> {
            val lines = mutableListOf<String>()
            var current = ""
            printable(value, font).split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (width(candidate, font, size) <= max) {
                    current = candidate
                } else {
                    if (current.isNotEmpty()) lines += current
                    current = word
                    if (width(word, font, size) > max) {
                        lines += fit(word, font, size, max)
                        current = ""
                    }
                }
            }
            if (current.isNotEmpty()) lines += current
            return lines.ifEmpty { listOf("") }
        }

        fun fill(color: Color, x: Float, y: Float, w: Float, h: Float) {
            if (w <= 0f || h <= 0f) return
            cs.setNonStrokingColor(color)
            cs.addRect(x, y, w, h)
            cs.fill()
        }

        fun line(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
            cs.setStrokingColor(color)
            cs.setLineWidth(width)
            cs.moveTo(x1, y1)
            cs.lineTo(x2, y2)
            cs.stroke()
        }

        /** A photo or logo PDFBox can read; null otherwise (a WebP), which then just isn't printed. */
        fun image(bytes: ByteArray): PDImageXObject? = runCatching { PDImageXObject.createFromByteArray(doc, bytes, "image") }.getOrNull()

        /** [value] on one line, with the characters [font] has no glyph for swapped for a plain one or left out. */
        private fun printable(value: String, font: PDFont): String {
            val clean = value.replace(Regex("[\\r\\n\\t]+"), " ").filter { it.code >= 0x20 }
            if (encodes(font, clean)) return clean
            val out = StringBuilder()
            var i = 0
            while (i < clean.length) {
                val glyph = String(Character.toChars(clean.codePointAt(i)))
                when {
                    encodes(font, glyph) -> out.append(glyph)
                    glyph == "–" || glyph == "—" -> out.append('-')
                    glyph == "’" -> out.append('\'')
                }
                i += glyph.length
            }
            return out.toString()
        }

        private fun encodes(font: PDFont, value: String): Boolean = runCatching { font.encode(value) }.isSuccess
    }

    companion object {
        /** Photos printed with each diary day. */
        const val DIARY_PHOTOS = 6

        private const val LEFT = 40f
        private const val RIGHT = 555f
        private const val WIDTH = RIGHT - LEFT
        private const val TOP = 802f
        private const val BOTTOM = 58f
        private const val CELL_PAD = 4f
        private const val LOGO_W = 150f
        private const val LOGO_H = 46f
        private const val PHOTO_COLUMNS = 3
        private const val PHOTO_GAP = 10f
        private const val PHOTO_MAX_H = 118f

        private val INK = Color(17, 19, 24)
        private val SOFT = Color(71, 84, 103)
        private val MUTED = Color(102, 112, 133)
        private val RULE = Color(228, 231, 236)
        private val SURFACE = Color(244, 246, 248)
        private val DEFAULT_ACCENT = Color(150, 170, 182)
    }
}