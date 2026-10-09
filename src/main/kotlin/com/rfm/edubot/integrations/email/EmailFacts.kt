package com.rfm.edubot.integrations.email

/**
 * What an email says that can be read without a model: the quote and invoice numbers it mentions, and
 * the phone and tax numbers in its sender's own words. Quoted history is left out first, so the
 * company's signature quoted back in a reply isn't taken for the sender's, and so are the company's
 * own numbers.
 */
object EmailFacts {
    data class Facts(
        val quoteNumbers: List<String> = emptyList(),
        val invoiceNumbers: List<String> = emptyList(),
        val phones: List<String> = emptyList(),
        val taxIds: List<String> = emptyList(),
    )

    /** [own] are the company's phone and tax numbers, in any format. */
    fun of(subject: String, body: String?, own: Collection<String> = emptyList()): Facts {
        val text = body?.let(::ownText).orEmpty()
        val ownDigits = own.map(::digits).filter { it.length >= 6 }.map { it.takeLast(9) }.toSet()
        val references = documentNumbers("$subject\n$text")
        val taxIds = taxIds(text).filter { it !in ownDigits }
        val phones = phones(text).filter { phone ->
            val tail = digits(phone).takeLast(9)
            tail !in ownDigits && tail !in taxIds
        }
        return Facts(
            quoteNumbers = references.filter { it.startsWith(QUOTE_PREFIX) },
            invoiceNumbers = references.filter { it.startsWith(INVOICE_PREFIX) },
            phones = phones,
            taxIds = taxIds,
        )
    }

    /**
     * [body] up to where the quoted history starts ("On … wrote:", a forwarded or original message header,
     * Outlook's "From: … Sent:" block), without `>` lines. The whole body when that leaves nothing.
     */
    fun ownText(body: String): String {
        val lines = body.lines()
        val cut = lines.indices.firstOrNull { i -> startsHistory(lines, i) } ?: lines.size
        val kept = lines.take(cut).filterNot { it.trimStart().startsWith(">") }.joinToString("\n").trim()
        return kept.ifEmpty { body.trim() }
    }

    /** Quote and invoice numbers in the company's format (`ORC-012`, `FAT-007`), however they were typed. */
    fun documentNumbers(text: String): List<String> =
        DOCUMENT.findAll(text).map { match ->
            val prefix = match.groupValues[1].uppercase()
            val number = match.groupValues[2].trimStart('0').ifEmpty { "0" }.padStart(3, '0')
            "$prefix-$number"
        }.distinct().toList()

    fun phones(text: String): List<String> {
        val found = LinkedHashSet<String>()
        for (pattern in listOf(LABELLED_PHONE, INTERNATIONAL_PHONE, MOBILE_PHONE)) {
            pattern.findAll(text).forEach { match -> formatPhone(match.groups["n"]!!.value)?.let(found::add) }
        }
        return found.distinctBy { digits(it).takeLast(9) }.take(MAX_PHONES)
    }

    /** Portuguese tax numbers next to a label (NIF, NIPC, contribuinte, VAT), with a valid check digit. */
    fun taxIds(text: String): List<String> =
        TAX_ID.findAll(text).map { it.groupValues[2] }.filter(::validNif).distinct().take(MAX_TAX_IDS).toList()

    fun validNif(nif: String): Boolean {
        if (nif.length != 9 || nif.any { !it.isDigit() } || nif.toSet().size == 1) return false
        val sum = (0 until 8).sumOf { (nif[it] - '0') * (9 - it) }
        val check = (11 - sum % 11).let { if (it >= 10) 0 else it }
        return check == nif[8] - '0'
    }

    private fun startsHistory(lines: List<String>, i: Int): Boolean {
        val line = lines[i]
        if (SEPARATOR.containsMatchIn(line)) return true
        if (WROTE.containsMatchIn(line) || (i + 1 < lines.size && WROTE.containsMatchIn("$line ${lines[i + 1]}") && !WROTE.containsMatchIn(lines[i + 1]))) return true
        // Outlook: a "From:" line with "Sent:"/"Date:" right under it.
        return FROM_HEADER.containsMatchIn(line) && lines.drop(i + 1).take(3).any { SENT_HEADER.containsMatchIn(it) }
    }

    /** `+351 912 345 678` for a Portuguese number, `+<digits>` for one with a country code, else as written. */
    private fun formatPhone(raw: String): String? {
        val trimmed = raw.trim()
        var all = digits(trimmed)
        val international = trimmed.startsWith("+") || all.startsWith("00")
        if (all.startsWith("00")) all = all.drop(2)
        if (all.length !in 8..15) return null
        val national = when {
            international && all.startsWith("351") && all.length == 12 -> all.drop(3)
            !international && all.length == 9 && all[0] in "29" -> all
            else -> null
        }
        return when {
            national != null -> "+351 ${national.substring(0, 3)} ${national.substring(3, 6)} ${national.substring(6)}"
            international -> "+$all"
            else -> trimmed.replace(Regex("\\s+"), " ")
        }
    }

    private fun digits(text: String) = text.filter { it.isDigit() }

    const val QUOTE_PREFIX = "ORC-"
    const val INVOICE_PREFIX = "FAT-"
    private const val MAX_PHONES = 3
    private const val MAX_TAX_IDS = 2

    private val DOCUMENT = Regex("(?i)\\b(ORC|FAT)[\\s_-]?(\\d{1,6})\\b")
    private val LABELLED_PHONE = Regex(
        "(?i)\\b(?:tel|telf|telef|telefone|tel[ée]fono|telem[óo]vel|tlm|tlf|m[óo]vel|m[óo]vil|mobile|phone|cell|whatsapp)\\b\\.?[^\\d+\\n]{0,6}(?<n>\\+?\\d[\\d .()-]{6,18}\\d)",
    )
    private val INTERNATIONAL_PHONE = Regex("(?<![\\w+])(?<n>(?:\\+|00)\\d{1,3}[ .-]?\\(?\\d[\\d .()-]{6,16}\\d)")
    private val MOBILE_PHONE = Regex("(?<![\\d+])(?<n>9[1236]\\d[ .-]?\\d{3}[ .-]?\\d{3})(?!\\d)")
    private val TAX_ID = Regex("(?i)\\b(nif|nipc|contribuinte|vat|tax\\s?id|tin)\\b[^\\d\\n]{0,15}(?:PT\\s?)?(\\d{9})\\b")
    private val WROTE = Regex("(?i)^\\s*(on|em|el|le|am)\\b.{0,240}\\b(wrote|escreveu|escribi[óo]|a [ée]crit|schrieb)\\s*:\\s*$")
    private val SEPARATOR = Regex(
        "(?i)^\\s*-{2,}\\s*(original message|mensagem original|mensaje original|forwarded message|mensagem encaminhada|mensaje reenviado)\\s*-{2,}",
    )
    private val FROM_HEADER = Regex("(?i)^\\s*\\*?(from|de)\\s*:\\*?\\s+\\S")
    private val SENT_HEADER = Regex("(?i)^\\s*\\*?(sent|enviado|enviada|date|data|fecha)\\s*:")
}
