package com.rfm.edubot.agents.runtime

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateRendererTest {
    private val variables = buildJsonObject {
        putJsonObject("client") { put("firstName", "Ana"); put("email", "") }
        putJsonObject("invoice") {
            put("number", "FAT-012")
            put("total", "1 234,50 €")
            put("totalCents", 123_450)
            put("dueDate", "2026-10-03")
        }
        putJsonObject("booking") { put("start", "2026-10-05T14:30:00Z") }
        putJsonObject("params") { put("days", 3) }
    }
    private val pt = ValueFormatter("pt-PT", TimeZone.of("Europe/Lisbon"))
    private val en = ValueFormatter("en", TimeZone.of("Europe/Lisbon"))

    @Test
    fun `variables fill in and dates read naturally in the company's language`() {
        val text = TemplateRenderer.render("Olá {{client.firstName}}, a fatura {{invoice.number}} vence a {{invoice.dueDate}}.", variables, pt)
        assertEquals("Olá Ana, a fatura FAT-012 vence a 03/10/2026.", text)
        assertEquals("Due 3 Oct 2026", TemplateRenderer.render("Due {{invoice.dueDate}}", variables, en))
    }

    @Test
    fun `filters format money, times and fallbacks`() {
        assertEquals(pt.formatCents(123_450), TemplateRenderer.render("{{invoice.totalCents | money}}", variables, pt))
        assertEquals("15:30", TemplateRenderer.render("{{booking.start | time}}", variables, pt))
        assertEquals("sem email", TemplateRenderer.render("{{client.email | default: sem email}}", variables, pt))
        assertEquals("03/10/2026", TemplateRenderer.render("{{invoice.dueDate | default: sem data}}", variables, pt), "a value that is there reads as without the filter")
        assertEquals("ANA", TemplateRenderer.render("{{client.firstName | upper}}", variables, pt))
    }

    @Test
    fun `unknown variables render empty`() {
        assertEquals("Olá !", TemplateRenderer.render("Olá {{client.nickname}}!", variables, pt))
    }

    @Test
    fun `a field holding one variable keeps the variable's type`() {
        val rendered = TemplateRenderer.renderJson(
            buildJsonObject { put("days", "{{params.days}}"); put("text", "Em {{params.days}} dias") },
            variables,
            pt,
        )
        assertEquals(JsonPrimitive(3L), rendered["days"])
        assertEquals(JsonPrimitive("Em 3 dias"), rendered["text"])
    }

    @Test
    fun `references are listed for the validator`() {
        assertEquals(listOf("client.firstName", "invoice.dueDate"), TemplateRenderer.references("{{client.firstName}} {{ invoice.dueDate | date }}"))
    }
}
