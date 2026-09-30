package com.rfm.edubot.agents.runtime

import com.rfm.edubot.agents.model.Condition
import com.rfm.edubot.agents.model.ConditionGroup
import com.rfm.edubot.agents.model.ConditionMatch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Evaluates "only if" conditions and step guards over a run's variables. No scripting: a fixed set of
 * operators, and a missing field only satisfies `not_exists` / `is_empty`.
 */
object ConditionEvaluator {
    val operators = setOf(
        "eq", "neq", "gt", "gte", "lt", "lte", "contains", "not_contains", "in", "not_in",
        "exists", "not_exists", "is_empty", "not_empty", "days_since", "days_until",
    )

    fun matches(group: ConditionGroup?, variables: JsonObject, today: LocalDate, zone: TimeZone): Boolean {
        if (group == null || group.isEmpty) return true
        val results = group.conditions.map { matches(it, variables, today, zone) }
        return if (group.match == ConditionMatch.ANY) results.any { it } else results.all { it }
    }

    fun matches(condition: Condition, variables: JsonObject, today: LocalDate, zone: TimeZone): Boolean {
        val actual = TemplateRenderer.lookup(variables, condition.field)?.takeIf { it !is JsonNull }
        val expected = condition.value
        return when (condition.op) {
            "exists" -> actual != null
            "not_exists" -> actual == null
            "is_empty" -> isEmpty(actual)
            "not_empty" -> !isEmpty(actual)
            "eq" -> actual != null && equal(actual, expected)
            "neq" -> actual == null || !equal(actual, expected)
            "gt" -> compare(actual, expected)?.let { it > 0 } ?: false
            "gte" -> compare(actual, expected)?.let { it >= 0 } ?: false
            "lt" -> compare(actual, expected)?.let { it < 0 } ?: false
            "lte" -> compare(actual, expected)?.let { it <= 0 } ?: false
            "contains" -> contains(actual, expected)
            "not_contains" -> !contains(actual, expected)
            "in" -> actual != null && options(expected).any { equal(actual, it) }
            "not_in" -> actual == null || options(expected).none { equal(actual, it) }
            // At least N days ago.
            "days_since" -> dateOf(actual, zone)?.let { date -> number(expected)?.let { date.daysUntil(today) >= it } } ?: false
            // Today or within the next N days.
            "days_until" -> dateOf(actual, zone)?.let { date -> number(expected)?.let { today.daysUntil(date).let { days -> days in 0..it.toInt() } } } ?: false
            else -> false
        }
    }

    private fun isEmpty(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> true
        is JsonPrimitive -> value.isString && value.content.isBlank()
        is JsonArray -> value.isEmpty()
        is JsonObject -> value.isEmpty()
    }

    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.contentOrNull

    private fun number(value: JsonElement?): Double? = (value as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.replace(',', '.')?.toDoubleOrNull() }

    private fun equal(actual: JsonElement, expected: JsonElement?): Boolean {
        val a = actual as? JsonPrimitive ?: return false
        val e = expected as? JsonPrimitive ?: return false
        if (!a.isString && a.booleanOrNull != null) return a.booleanOrNull == (e.booleanOrNull ?: e.contentOrNull?.lowercase()?.toBooleanStrictOrNull())
        val an = number(a)
        val en = number(e)
        if (an != null && en != null) return an == en
        return a.content.trim().equals(e.content.trim(), ignoreCase = true)
    }

    private fun compare(actual: JsonElement?, expected: JsonElement?): Int? {
        val an = number(actual)
        val en = number(expected)
        if (an != null && en != null) return an.compareTo(en)
        val at = text(actual) ?: return null
        val et = text(expected) ?: return null
        // ISO dates and instants compare correctly as text.
        return at.compareTo(et)
    }

    private fun contains(actual: JsonElement?, expected: JsonElement?): Boolean {
        val needle = text(expected)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        return when (actual) {
            is JsonArray -> actual.any { text(it)?.lowercase() == needle }
            is JsonPrimitive -> actual.content.lowercase().contains(needle)
            else -> false
        }
    }

    private fun options(expected: JsonElement?): List<JsonElement> = when (expected) {
        is JsonArray -> expected
        is JsonPrimitive -> expected.content.split(',').map { JsonPrimitive(it.trim()) }
        else -> emptyList()
    }

    private fun dateOf(value: JsonElement?, zone: TimeZone): LocalDate? {
        val raw = text(value) ?: return null
        return runCatching { LocalDate.parse(raw) }.getOrNull()
            ?: runCatching { Instant.parse(raw).toLocalDateTime(zone).date }.getOrNull()
    }
}
