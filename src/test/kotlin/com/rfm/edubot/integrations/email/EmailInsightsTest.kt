package com.rfm.edubot.integrations.email

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmailInsightsTest {
    private val now = Instant.parse("2026-10-09T10:00:00Z")

    private fun read(submitted: JsonObject, services: List<String> = emptyList()) = EmailInsights.read(submitted, services, now)

    @Test
    fun `the model's answer becomes insights, in the declared types`() {
        val insights = read(
            buildJsonObject {
                put("summary", "  A Maria pede orçamento para pintar a sala.  ")
                put("intent", "QUOTE_REQUEST")
                putJsonObject("contact") {
                    put("name", "Maria Silva")
                    put("company", "Silva & Filhos")
                    put("phone", "912 345 678")
                    put("email", "Maria@Cliente.PT")
                    put("taxId", "123456789")
                    put("city", "Lisboa")
                }
                put("request", "Pintura da sala, 20 m2")
                putJsonArray("items") {
                    addJsonObject { put("description", "Pintura de paredes"); put("quantity", 20); put("unit", "m2") }
                    addJsonObject { put("description", "  "); put("quantity", 1) }
                    addJsonObject { put("description", "Deslocação"); put("quantity", "dois") }
                }
                put("date", "2026-10-14")
                put("time", "09:30")
                put("amount", "1.250,50 €")
                put("serviceName", "pintura interior")
                put("reply", "Olá Maria, obrigado pelo contacto.")
            },
            services = listOf("Pintura interior", "Limpeza"),
        )!!
        assertEquals("A Maria pede orçamento para pintar a sala.", insights.summary)
        assertEquals(EmailIntents.QUOTE_REQUEST, insights.intent)
        assertEquals("maria@cliente.pt", insights.contact.email)
        assertEquals("Silva & Filhos", insights.contact.company)
        assertEquals(listOf(EmailItem("Pintura de paredes", 20.0, "m2"), EmailItem("Deslocação", null, null)), insights.items)
        assertEquals("2026-10-14", insights.date)
        assertEquals("09:30", insights.time)
        assertEquals(125_050L, insights.amountCents)
        assertEquals("Pintura interior", insights.serviceName, "the company's own spelling of the service")
        assertEquals(now, insights.generatedAt)
    }

    @Test
    fun `anything that isn't what was asked for is left out instead of guessed`() {
        val insights = read(
            buildJsonObject {
                put("summary", "Pedido")
                put("intent", "spam")
                put("accepted", "maybe")
                putJsonObject("contact") { put("phone", "12"); put("email", "not an address"); put("name", "null") }
                put("date", "2026-02-30")
                put("time", "25:00")
                put("dueDate", "amanhã")
                put("amount", -40)
                put("serviceName", "Corte de cabelo")
                putJsonArray("items") { repeat(15) { i -> addJsonObject { put("description", "Linha $i") } } }
            },
            services = listOf("Limpeza"),
        )!!
        assertEquals(EmailIntents.OTHER, insights.intent)
        assertNull(insights.accepted)
        assertEquals(EmailContact(), insights.contact)
        assertNull(insights.date)
        assertNull(insights.time)
        assertNull(insights.dueDate)
        assertNull(insights.amountCents)
        assertNull(insights.serviceName)
        assertEquals(EmailInsights.MAX_ITEMS, insights.items.size)
    }

    @Test
    fun `without a summary there is nothing to show`() {
        assertNull(read(buildJsonObject { put("intent", "question") }))
        assertNull(read(buildJsonObject { put("summary", "   "); put("intent", "question") }))
    }

    @Test
    fun `amounts read in Portuguese and English notation`() {
        assertEquals(1234.5, EmailInsights.parseAmount("1.234,50"))
        assertEquals(1234.5, EmailInsights.parseAmount("1,234.50"))
        assertEquals(1234.0, EmailInsights.parseAmount("1.234"))
        assertEquals(230.0, EmailInsights.parseAmount("€ 230"))
        assertEquals(12.5, EmailInsights.parseAmount("12,5 EUR"))
        assertNull(EmailInsights.parseAmount("sem valor"))
        assertTrue((EmailInsights.parseAmount("0,99") ?: 0.0) < 1)
    }

    @Test
    fun `a boolean the model wrote as text still counts`() {
        val insights = read(buildJsonObject { put("summary", "Aceita"); put("intent", "quote_reply"); put("accepted", "true"); putJsonArray("x") { add(1) } })!!
        assertEquals(true, insights.accepted)
    }
}
