package com.rfm.edubot.dashboard.building

import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.Clock
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Building's PDFs: a measurement and a project's diary, read back as text. */
class BuildingPdfTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("building_pdf")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }

        private fun textOf(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

        private fun pagesOf(bytes: ByteArray): Int = Loader.loadPDF(bytes).use { it.numberOfPages }

        private fun imagesOf(bytes: ByteArray): Int = Loader.loadPDF(bytes).use { doc -> doc.pages.sumOf { page -> page.resources.xObjectNames.count() } }

        /** A real photo, which PDFBox can draw, small enough to be its own thumbnail. */
        private val PHOTO: ByteArray = ByteArrayOutputStream().also { out ->
            val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB).apply { createGraphics().run { color = java.awt.Color(180, 120, 60); fillRect(0, 0, 64, 48); dispose() } }
            ImageIO.write(image, "jpg", out)
        }.toByteArray()
    }

    private val api = BUILDING_API

    @Test
    fun `a measurement prints its lines, totals and signature boxes in the company's language`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a, name = "Marta Marques")
        val id = project(a) {
            put("clientId", clientId); put("status", "ACTIVE"); put("retentionPct", 5.0); put("address", "Rua do Sol 12")
            putJsonArray("contract") {
                addJsonObject { put("description", "Paredes"); put("quantity", 100.0); put("unit", "m2"); put("unitPriceEur", 25.0) }
                addJsonObject { put("description", "Cobertura"); put("quantity", 1.0); put("unit", "vg"); put("unitPriceEur", 8000.0) }
            }
        }.text("id")!!
        val draft = a.post("$api/measurements") { put("projectId", id) }
        val (walls, roof) = draft.list("lines").map { it.str("key")!! }
        val mId = draft.text("id")!!
        a.patch("$api/measurements/$mId") {
            put("notes", "Medição conferida em obra")
            putJsonArray("lines") { addJsonObject { put("key", walls); put("toDateQty", 40.0) }; addJsonObject { put("key", roof); put("toDateQty", 0.5) } }
        }

        val pdf = a.download("$api/measurements/$mId/pdf")
        assertEquals(HttpStatusCode.OK, pdf.status)
        assertEquals(ContentType.Application.Pdf, pdf.contentType?.withoutParameters())
        val text = textOf(pdf.bytes)
        val expected = listOf(
            "Auto de medição n.º 1", "RASCUNHO", "Obras Silva", "Marta Marques", "Rua do Sol 12", "Paredes", "Cobertura",
            "5 000,00 €", "-250,00 €", "4 750,00 €", "Retenção de garantia (5 %)", "Líquido a faturar", "Executado: 47,6 %",
            "Medição conferida em obra", "O empreiteiro", "O dono de obra", "Página 1 de 1",
        )
        expected.forEach { assertTrue(it in text, "$it in:\n$text") }

        tenants.setLocale(a.tenant.slug, "en", Clock.System.now())
        val english = textOf(a.download("$api/measurements/$mId/pdf").bytes)
        listOf("Measurement no. 1", "DRAFT", "€4,750.00", "-€250.00", "Retention held (5%)", "Net to invoice", "The client", "Page 1 of 1")
            .forEach { assertTrue(it in english, "$it in:\n$english") }
        assertEquals(HttpStatusCode.NotFound, a.download("$api/measurements/${org.bson.types.ObjectId()}/pdf").status)
    }

    @Test
    fun `a long measurement goes on to more pages, its columns heading each one`() = dashboardApiTest(mongo) {
        val a = company()
        val id = project(a) {
            putJsonArray("contract") {
                repeat(70) { n -> addJsonObject { put("description", "Artigo $n de alvenaria e revestimentos"); put("quantity", 10.0); put("unit", "m2"); put("unitPriceEur", 12.5) } }
            }
        }.text("id")!!
        val mId = a.post("$api/measurements") { put("projectId", id) }.text("id")!!
        val bytes = a.download("$api/measurements/$mId/pdf").bytes
        val pages = pagesOf(bytes)
        assertTrue(pages >= 2, "$pages pages")
        val text = textOf(bytes)
        assertTrue("Página $pages de $pages" in text)
        assertEquals(pages, Regex("Neste auto Acumulado").findAll(text).count(), "the columns head every page")
    }

    @Test
    fun `the diary prints a project's days in a period with their photos`() = dashboardApiTest(mongo) {
        val a = company()
        val id = project(a) { put("status", "ACTIVE") }.text("id")!!
        val phase = twoPhases(a, id).list("phases")[1].str("id")
        val rainy = a.post("$api/diary") {
            put("projectId", id); put("day", a.day(-2)); put("weather", "RAIN"); put("temperatureC", 12); put("workers", 6)
            put("workDone", "Cofragem da laje do piso 1"); put("incidents", "Entrega de aço atrasada")
            putJsonArray("phaseProgress") { addJsonObject { put("phaseId", phase); put("progressPct", 35) } }
        }.text("id")!!
        a.post("$api/diary") { put("projectId", id); put("day", a.day(-1)); put("workDone", "Betonagem da laje") }
        a.post("$api/diary") { put("projectId", id); put("day", a.day(-40)); put("workDone", "Montagem do estaleiro") }
        repeat(2) { n -> assertEquals(HttpStatusCode.Created, a.upload("$api/projects/$id/photos", "laje-$n.jpg", PHOTO, mapOf("logId" to rainy, "caption" to "Laje cofrada $n")).status) }

        val pdf = a.download("$api/diary/pdf?projectId=$id&from=${a.day(-7)}&to=${a.day(0)}")
        assertEquals(HttpStatusCode.OK, pdf.status)
        val text = textOf(pdf.bytes)
        listOf("Diário de obra", "Cofragem da laje do piso 1", "Entrega de aço atrasada", "Chuva", "12 °C", "6 em obra", "Estrutura: de 20 % para 35 %", "Betonagem da laje", "Laje cofrada 0")
            .forEach { assertTrue(it in text, "$it in:\n$text") }
        assertFalse("Montagem do estaleiro" in text, "a day outside the period")
        assertTrue(text.indexOf("Cofragem") < text.indexOf("Betonagem"), "oldest first")
        assertEquals(2, imagesOf(pdf.bytes))
    }

    @Test
    fun `the diary needs a project and a period of at most three months`() = dashboardApiTest(mongo) {
        val a = company()
        val id = project(a).text("id")!!
        assertEquals("project_not_found", a.get("$api/diary/pdf").error)
        assertEquals("invalid_range", a.get("$api/diary/pdf?projectId=$id&from=${a.day(0)}&to=${a.day(-1)}").error)
        assertEquals("range_too_long", a.get("$api/diary/pdf?projectId=$id&from=${a.day(-200)}&to=${a.day(0)}").error)
        val empty = a.download("$api/diary/pdf?projectId=$id")
        assertEquals(HttpStatusCode.OK, empty.status, "the last 30 days by default")
        assertTrue("Sem registos neste período." in textOf(empty.bytes))
    }

    @Test
    fun `a change order prints its lines, what it does to the contract, and who approved it from where`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a, name = "Marta Marques")
        val id = project(a) {
            put("clientId", clientId); put("plannedStart", a.day(-10)); put("plannedEnd", a.day(20))
            putJsonArray("contract") { addJsonObject { put("description", "Moradia"); put("quantity", 1.0); put("unitPriceEur", 10000.0) } }
        }.text("id")!!
        val change = a.post("$api/changes") {
            put("projectId", id); put("title", "Claraboia na sala"); put("daysImpact", 3)
            putJsonArray("items") { addJsonObject { put("description", "Claraboia 120x80"); put("quantity", 1.0); put("unit", "un"); put("unitPriceEur", 1850.0) } }
        }
        val number = change.text("number")!!
        val waiting = a.download("$api/changes/${change.text("id")}/pdf")
        assertEquals(HttpStatusCode.OK, waiting.status)
        val unsigned = textOf(waiting.bytes)
        assertTrue(number in unsigned && "Claraboia 120x80" in unsigned && "Marta Marques" in unsigned, unsigned)
        assertTrue("O dono de obra" in unsigned, "undecided, it has signature boxes")

        val token = a.post("$api/changes/${change.text("id")}/status") { put("status", "SENT") }.text("approvalToken")!!
        call("POST", "/app/share/change/$token/decision", null, """{"decision":"APPROVED","name":"Marta Marques"}""", headers = mapOf("X-Forwarded-For" to "85.240.1.2"))
        val signed = textOf(publicDownload("/app/share/change/$token/pdf").bytes)
        assertTrue("Aprovada por Marta Marques" in signed, signed)
        assertTrue("85.240.1.2" in signed, "where the answer came from")
        assertTrue("10 000,00 €" in signed && "11 850,00 €" in signed, "the contract before and after")
        assertFalse("O dono de obra" in signed, "decided, no signature boxes")
    }
}
