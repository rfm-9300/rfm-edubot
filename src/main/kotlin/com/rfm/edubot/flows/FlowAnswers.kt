package com.rfm.edubot.flows

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Reads a customer's reply to a question as the kind of answer it expects, without any AI: the fixed words and
 * formats a person is likely to type. The answer comes back in one form (dates as `2026-11-18`, times as `15:30`,
 * yes or no as `yes` or `no`) so later steps and actions can rely on it; null means it isn't that kind of answer.
 */
internal object FlowAnswers {
    fun read(expect: Expect, text: String, today: LocalDate): String? {
        val said = text.trim().replace(Regex("\\s+"), " ")
        if (said.isEmpty()) return null
        return when (expect) {
            Expect.TEXT -> said.take(ChatFlowLimits.MAX_ANSWER_TEXT)
            Expect.NAME -> name(said)
            Expect.PHONE -> phone(said)
            Expect.EMAIL -> EMAIL.find(said)?.value?.lowercase()
            Expect.NUMBER -> NUMBER.matchEntire(said)?.value?.replace(',', '.')
            Expect.DATE -> date(said, today)?.toString()
            Expect.TIME -> time(said)
            Expect.YES_NO -> yesNo(said)
        }
    }

    private val EMAIL = Regex("[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]{2,}")
    private val NUMBER = Regex("-?\\d+(?:[.,]\\d+)?")
    private val NAME_LEAD = Regex(
        "^(?:my name is|my name's|i am|i'm|this is|sou o|sou a|sou|me chamo|meu nome é|meu nome e|o meu nome é|o meu nome e|chamo-me|chamo me|me llamo|mi nombre es|soy)\\s+",
        RegexOption.IGNORE_CASE,
    )

    private fun name(said: String): String? {
        val name = said.replace(NAME_LEAD, "").trim().trimEnd('.', '!')
        val words = name.split(' ')
        return name.takeIf { it.length in 2..80 && it.any(Char::isLetter) && words.size <= 6 && '?' !in it && '@' !in it && it.count(Char::isDigit) <= 2 }
    }

    private fun phone(said: String): String? {
        if (said.any { !(it.isDigit() || it in " +-().") }) return null
        val digits = said.filter(Char::isDigit)
        if (digits.length !in 7..15) return null
        return (if (said.trimStart().startsWith("+")) "+" else "") + digits
    }

    private val TODAY = setOf("hoje", "today", "hoy")
    private val TOMORROW = setOf("amanha", "tomorrow", "manana")
    private val ISO = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})")
    private val DAY_MONTH = Regex("(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2,4}))?")

    /** A date from today on; a day and month without a year is the next time it comes round. */
    private fun date(said: String, today: LocalDate): LocalDate? {
        val plain = FlowText.normalize(said)
        if (plain in TODAY) return today
        if (plain in TOMORROW) return today.plus(DatePeriod(days = 1))
        ISO.find(said)?.destructured?.let { (y, m, d) -> return build(y.toInt(), m.toInt(), d.toInt())?.takeIf { it >= today } }
        val match = DAY_MONTH.find(said)?.destructured ?: return null
        val (d, m, y) = match
        if (y.isNotEmpty()) {
            val year = y.toInt().let { if (it < 100) 2000 + it else it }
            return build(year, m.toInt(), d.toInt())?.takeIf { it >= today }
        }
        val thisYear = build(today.year, m.toInt(), d.toInt())
        return if (thisYear != null && thisYear >= today) thisYear else build(today.year + 1, m.toInt(), d.toInt())
    }

    private fun build(year: Int, month: Int, day: Int): LocalDate? = runCatching { LocalDate(year, month, day) }.getOrNull()

    private val CLOCK = Regex("(\\d{1,2})\\s*[:h.]\\s*(\\d{2})\\s*(am|pm)?", RegexOption.IGNORE_CASE)
    private val HOUR_ONLY = Regex("(?<![\\d:.])(\\d{1,2})\\s*(?:h|horas?|hrs?|am|pm|o'?clock)\\b", RegexOption.IGNORE_CASE)

    /** `15:30`, `15h30`, `3pm`, `9 h`; the first time found in a short reply. */
    private fun time(said: String): String? {
        if (said.length > 40) return null
        if (said.length <= 2 && said.all(Char::isDigit)) return said.toInt().takeIf { it in 0..23 }?.let { "%02d:00".format(it) }
        val (hour, minute, meridiem) = CLOCK.find(said)?.destructured?.let { (h, m, ap) -> Triple(h.toInt(), m.toInt(), ap.lowercase()) }
            ?: HOUR_ONLY.find(said)?.let { found ->
                Triple(found.groupValues[1].toInt(), 0, Regex("am|pm", RegexOption.IGNORE_CASE).find(found.value)?.value?.lowercase().orEmpty())
            }
            ?: return null
        var h = hour
        if (meridiem == "pm" && h in 1..11) h += 12
        if (meridiem == "am" && h == 12) h = 0
        return if (h in 0..23 && minute in 0..59) "%02d:%02d".format(h, minute) else null
    }

    private val YES = setOf("sim", "s", "yes", "y", "si", "claro", "ok", "okay", "certo", "pode", "confirmo", "confirmar", "correto", "exato", "quero", "perfeito", "vale", "dale", "afirmativo")
    private val NO = setOf("nao", "n", "no", "nope", "nunca", "cancelar", "cancela", "negativo")

    private fun yesNo(said: String): String? {
        val words = FlowText.normalize(said).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 4) return null
        val yes = words.any { it in YES }
        val no = words.any { it in NO }
        return when {
            yes && !no -> "yes"
            no && !yes -> "no"
            else -> null
        }
    }
}

/** Matching what a customer typed to the options a message offered. */
internal object FlowOptions {
    private val NUMBERED = Regex("^(?:option|opcao|opciones?|opcoes|numero|number|n|no|num)?\\s*(\\d{1,2})$")

    /** The position of the option [said] chooses, or null when it chooses none or more than one. */
    fun match(options: List<FlowButton>, said: String): Int? {
        val plain = FlowText.normalize(said)
        if (plain.isEmpty() || options.isEmpty()) return null
        NUMBERED.matchEntire(plain)?.groupValues?.get(1)?.toInt()?.let { n -> return (n - 1).takeIf { it in options.indices } }
        options.indexOfFirst { FlowText.normalize(it.title) == plain }.takeIf { it >= 0 }?.let { return it }
        val hits = options.indices.filter { i -> (listOf(options[i].title) + options[i].words).any { FlowText.containsPhrase(plain, it) } }
        return hits.singleOrNull()
    }
}
