package com.rfm.edubot.agents.runtime

import com.rfm.edubot.tenant.model.TenantLocales
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/**
 * Logic-free `{{path}}` templates with optional filters: `{{invoice.total}}`, `{{invoice.dueDate | date}}`,
 * `{{client.name | upper}}`, `{{client.email | default: sem email}}`. Unknown paths render empty; the
 * validator catches them before an agent is saved.
 */
object TemplateRenderer {
    private val token = Regex("""\{\{\s*([A-Za-z0-9_.\-]+)\s*(?:\|\s*([a-z_]+)\s*(?::\s*([^}]*?))?\s*)?\}\}""")
    private val wholeToken = Regex("""^\s*\{\{\s*([A-Za-z0-9_.\-]+)\s*\}\}\s*$""")

    fun references(text: String): List<String> = token.findAll(text).map { it.groupValues[1] }.toList()

    fun referencesIn(element: JsonElement?): List<String> = when (element) {
        is JsonPrimitive -> if (element.isString) references(element.content) else emptyList()
        is JsonObject -> element.values.flatMap { referencesIn(it) }
        is JsonArray -> element.flatMap { referencesIn(it) }
        else -> emptyList()
    }

    fun render(template: String, variables: JsonObject, formatter: ValueFormatter): String =
        token.replace(template) { match ->
            val value = lookup(variables, match.groupValues[1])
            formatter.format(value, match.groupValues[2].ifBlank { null }, match.groupValues[3].ifBlank { null })
        }

    /**
     * Renders every string in [input]. A string that is exactly one `{{path}}` takes the variable's own
     * JSON value, so `"{{params.days}}"` becomes the number 3 rather than the text "3".
     */
    fun renderJson(input: JsonObject, variables: JsonObject, formatter: ValueFormatter): JsonObject =
        JsonObject(input.mapValues { (_, value) -> renderElement(value, variables, formatter) })

    private fun renderElement(element: JsonElement, variables: JsonObject, formatter: ValueFormatter): JsonElement = when (element) {
        is JsonPrimitive -> if (!element.isString || !element.content.contains("{{")) {
            element
        } else {
            val whole = wholeToken.find(element.content)
            val raw = whole?.let { lookup(variables, it.groupValues[1]) }
            if (raw != null && raw !is JsonNull && !(raw is JsonPrimitive && raw.isString)) raw else JsonPrimitive(render(element.content, variables, formatter))
        }
        is JsonObject -> renderJson(element, variables, formatter)
        is JsonArray -> JsonArray(element.map { renderElement(it, variables, formatter) })
        else -> element
    }

    fun lookup(variables: JsonObject, path: String): JsonElement? {
        var current: JsonElement? = variables
        for (part in path.split('.')) {
            current = (current as? JsonObject)?.get(part) ?: return null
        }
        return current
    }
}

/** Formats variables for people: money, dates and times in the company's language and timezone. */
class ValueFormatter(locale: String, private val zone: TimeZone, currencyCode: String = "EUR") {
    private val javaLocale: Locale = javaLocale(locale)
    private val zoneId: ZoneId = ZoneId.of(zone.id)
    private val money = NumberFormat.getCurrencyInstance(javaLocale).apply { currency = Currency.getInstance(currencyCode) }
    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(javaLocale)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", javaLocale)

    fun format(value: JsonElement?, filter: String?, argument: String?): String {
        val text = when (value) {
            null, JsonNull -> ""
            is JsonPrimitive -> value.contentOrNull.orEmpty()
            is JsonArray -> value.joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
            is JsonObject -> ""
        }
        return when (filter) {
            null -> natural(text)
            "money" -> (value as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }?.let { formatCents(it) } ?: text
            "date" -> formatDate(text) ?: text
            "datetime" -> formatDateTime(text) ?: text
            "time" -> formatTime(text) ?: text
            "upper" -> text.uppercase(javaLocale)
            "lower" -> text.lowercase(javaLocale)
            "first" -> text.trim().substringBefore(' ')
            "default" -> text.ifBlank { argument.orEmpty() }
            else -> text
        }
    }

    fun formatCents(cents: Long): String = money.format(cents / 100.0)

    /** ISO dates and instants read naturally without a filter. */
    private fun natural(text: String): String = when {
        ISO_DATE.matches(text) -> formatDate(text) ?: text
        ISO_INSTANT.matches(text) -> formatDateTime(text) ?: text
        else -> text
    }

    fun formatDate(text: String): String? = runCatching {
        if (ISO_DATE.matches(text)) LocalDate.parse(text).format(dateFormat)
        else java.time.Instant.parse(text).atZone(zoneId).toLocalDate().format(dateFormat)
    }.getOrNull()

    fun formatDateTime(text: String): String? = runCatching {
        val at = java.time.Instant.parse(text).atZone(zoneId)
        "${at.toLocalDate().format(dateFormat)} ${at.toLocalTime().format(timeFormat)}"
    }.getOrNull()

    fun formatTime(text: String): String? = runCatching { java.time.Instant.parse(text).atZone(zoneId).toLocalTime().format(timeFormat) }.getOrNull()

    fun formatInstant(instant: kotlinx.datetime.Instant): String = formatDateTime(instant.toJavaInstant().toString()) ?: instant.toString()

    companion object {
        private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val ISO_INSTANT = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?Z$")

        fun javaLocale(locale: String): Locale = when (TenantLocales.normalize(locale)) {
            "en" -> Locale.UK
            "es" -> Locale.forLanguageTag("es-ES")
            else -> Locale.forLanguageTag("pt-PT")
        }
    }
}
