package com.rfm.edubot.agents.runtime

import com.rfm.edubot.agents.model.Condition
import com.rfm.edubot.agents.model.ConditionGroup
import com.rfm.edubot.agents.model.ConditionMatch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConditionEvaluatorTest {
    private val today = LocalDate(2026, 10, 10)
    private val zone = TimeZone.of("Europe/Lisbon")
    private val variables = buildJsonObject {
        putJsonObject("invoice") {
            put("status", "PENDING")
            put("totalCents", 80_000)
            put("dueDate", "2026-10-12")
            put("isOverdue", false)
        }
        putJsonObject("quote") { put("sentAt", "2026-10-05T10:00:00Z") }
        putJsonObject("client") { put("email", ""); put("name", "Obras Silva Lda") }
    }

    private fun check(field: String, op: String, value: kotlinx.serialization.json.JsonElement? = null) =
        ConditionEvaluator.matches(Condition(field, op, value), variables, today, zone)

    @Test
    fun `comparisons read numbers, text and flags`() {
        assertTrue(check("invoice.totalCents", "gt", JsonPrimitive(50_000)))
        assertFalse(check("invoice.totalCents", "lt", JsonPrimitive("500")))
        assertTrue(check("invoice.status", "eq", JsonPrimitive("pending")))
        assertTrue(check("invoice.isOverdue", "eq", JsonPrimitive(false)))
        assertTrue(check("invoice.status", "in", buildJsonArray { add(JsonPrimitive("PENDING")); add(JsonPrimitive("OVERDUE")) }))
        assertTrue(check("client.name", "contains", JsonPrimitive("silva")))
    }

    @Test
    fun `empty and missing fields`() {
        assertTrue(check("client.email", "is_empty"))
        assertFalse(check("client.email", "not_empty"))
        assertTrue(check("client.taxId", "not_exists"))
        assertFalse(check("client.taxId", "eq", JsonPrimitive("x")), "a missing field never equals a value")
        assertTrue(check("client.taxId", "neq", JsonPrimitive("x")))
    }

    @Test
    fun `day windows count from today in the company's timezone`() {
        assertTrue(check("quote.sentAt", "days_since", JsonPrimitive(3)))
        assertFalse(check("quote.sentAt", "days_since", JsonPrimitive(7)))
        assertTrue(check("invoice.dueDate", "days_until", JsonPrimitive(3)))
        assertFalse(check("invoice.dueDate", "days_until", JsonPrimitive(1)))
    }

    @Test
    fun `groups match all or any`() {
        val all = ConditionGroup(ConditionMatch.ALL, listOf(Condition("invoice.status", "eq", JsonPrimitive("PENDING")), Condition("client.email", "not_empty")))
        val any = all.copy(match = ConditionMatch.ANY)
        assertFalse(ConditionEvaluator.matches(all, variables, today, zone))
        assertTrue(ConditionEvaluator.matches(any, variables, today, zone))
        assertTrue(ConditionEvaluator.matches(null, variables, today, zone))
    }
}
