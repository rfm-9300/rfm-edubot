package com.rfm.edubot.integrations.email

import com.rfm.edubot.tenant.model.DocumentLayouts
import com.rfm.edubot.tenant.model.DocumentTemplate

/**
 * The branded email around a plain-text body: the company's name and accent from its document
 * template, the account's signature, and the template's contact line and footer. Email clients ignore
 * stylesheets, so the HTML is a centred table with inline styles; it adds no text of its own, so it
 * needs no translation.
 */
object EmailLayout {
    data class Rendered(val text: String, val html: String)

    fun render(subject: String, body: String, template: DocumentTemplate, signature: String?, locale: String): Rendered {
        val sign = signature?.trim()?.takeIf { it.isNotEmpty() }
        val contact = listOf(template.companyName, template.taxId, template.address, template.phone, template.email)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val footer = template.footerText.trim().takeIf { it.isNotEmpty() }
        val text = buildString {
            append(body.trim())
            sign?.let { append("\n\n-- \n").append(it) }
        }
        val accent = DocumentLayouts.sanitizeAccent(template.accentColor).ifEmpty { DocumentLayouts.DEFAULT_ACCENT }
        val html = buildString {
            append("<!DOCTYPE html><html lang=\"").append(escape(locale)).append("\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>").append(escape(subject)).append("</title></head>")
            append("<body style=\"margin:0;padding:0;background:#f4f5f7;\">")
            append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f4f5f7;\"><tr><td align=\"center\" style=\"padding:24px 12px;\">")
            append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;background:#ffffff;border-radius:8px;border-top:4px solid ").append(accent).append(";\">")
            if (template.companyName.isNotBlank()) {
                append("<tr><td style=\"padding:24px 28px 0;font:600 18px/1.3 $FONT;color:#111827;\">").append(escape(template.companyName.trim())).append("</td></tr>")
            }
            append("<tr><td style=\"padding:20px 28px 24px;font:400 15px/1.6 $FONT;color:#1f2937;\">")
            append(paragraphs(body))
            sign?.let { append("<p style=\"margin:20px 0 0;color:#4b5563;\">").append(lines(it)).append("</p>") }
            append("</td></tr>")
            if (contact.isNotEmpty() || footer != null) {
                append("<tr><td style=\"padding:16px 28px 20px;border-top:1px solid #e5e7eb;font:400 12px/1.5 $FONT;color:#6b7280;\">")
                if (contact.isNotEmpty()) append(contact.joinToString(" · ") { escape(it) })
                footer?.let { append(if (contact.isNotEmpty()) "<br>" else "").append(lines(it)) }
                append("</td></tr>")
            }
            append("</table></td></tr></table></body></html>")
        }
        return Rendered(text, html)
    }

    /** Blank lines split paragraphs; single line breaks stay inside one. */
    private fun paragraphs(body: String): String =
        body.trim().replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .filter { it.isNotBlank() }
            .joinToString("") { "<p style=\"margin:0 0 14px;\">${lines(it.trim())}</p>" }

    private fun lines(text: String): String = text.replace("\r\n", "\n").split('\n').joinToString("<br>") { linkify(it) }

    /** Escapes the line and turns http(s) links into anchors. */
    private fun linkify(line: String): String {
        val out = StringBuilder()
        var last = 0
        for (match in LINK.findAll(line)) {
            val url = match.value.trimEnd('.', ',', ';', ':', ')', '!', '?')
            out.append(escape(line.substring(last, match.range.first)))
            out.append("<a href=\"").append(escape(url)).append("\" style=\"color:#1d4ed8;\">").append(escape(url)).append("</a>")
            last = match.range.first + url.length
        }
        out.append(escape(line.substring(last)))
        return out.toString()
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private val LINK = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)
    private const val FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif"
}
