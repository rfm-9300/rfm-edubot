package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** `/app/api/crm/invoices` over HTTP: its gate, isolation between companies, and the error contract. */
class InvoiceApiTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("invoice_api")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val base = "/app/api/crm/invoices"

    private suspend fun DashboardApi.client(company: TestCompany, name: String = "Casa Martins"): String =
        company.post("/app/api/crm/clients") { put("name", name); put("phone", "+351 91${(1..7).map { (0..9).random() }.joinToString("")}"); put("taxId", "1"); put("address", "Rua 1") }.text("id")!!

    private fun JsonObjectBuilder.invoice(clientId: String, due: String = "2026-12-01") {
        put("clientId", clientId)
        put("dueDate", due)
        putJsonArray("items") { addJsonObject { put("description", "Pintura"); put("unitPriceEur", 400.0) } }
    }

    @Test
    fun `invoices answer 403 without their module`() = dashboardApiTest(mongo) {
        val off = company(listOf(DashboardModules.CLIENTS, DashboardModules.QUOTES))
        assertEquals(HttpStatusCode.Forbidden, off.get(base).status)
        assertEquals(HttpStatusCode.Forbidden, off.post(base) { invoice(ObjectId().toHexString()) }.status)
        assertEquals(HttpStatusCode.Forbidden, off.patch("$base/${ObjectId()}/paid").status)
        assertEquals(HttpStatusCode.Forbidden, off.get("$base/${ObjectId()}/pdf").status)
    }

    @Test
    fun `a company never reads, pays, cancels or deletes another company's invoices`() = dashboardApiTest(mongo) {
        val a = company()
        val b = company()
        val clientId = client(a)
        val id = a.post(base) { invoice(clientId) }.text("id")!!
        assertEquals(HttpStatusCode.NotFound, b.get("$base/$id").status)
        assertEquals(HttpStatusCode.NotFound, b.get("$base/$id/pdf").status)
        assertEquals(HttpStatusCode.NotFound, b.patch("$base/$id/paid").status)
        assertEquals(HttpStatusCode.NotFound, b.post("$base/$id/cancel").status)
        assertEquals(HttpStatusCode.NotFound, b.delete("$base/$id").status)
        assertEquals(HttpStatusCode.NotFound, b.patch("$base/$id/tax-office-code") { put("taxOfficeCode", "X") }.status)
        assertFalse(id in b.get(base).ids())
        assertEquals("client not found", b.post(base) { invoice(clientId) }.error)
        val kept = a.get("$base/$id")
        assertEquals("PENDING", kept.text("status"))
        assertEquals("Casa Martins", kept.text("clientName"))
    }

    @Test
    fun `invoices answer 400 with an error code for anything they can't read`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a)
        val id = a.post(base) { invoice(clientId) }.text("id")!!
        assertEquals("invalid_body", a.sendMalformed("POST", base).error)
        a.post(base) { invoice(clientId, due = "next week") }.let { assertEquals("invalid_date", it.error); assertEquals("dueDate", it.detail) }
        a.post(base) { invoice("nope") }.let { assertEquals("invalid_id", it.error); assertEquals("clientId", it.detail) }
        a.post(base) { invoice(clientId); put("quoteId", "nope") }.let { assertEquals("invalid_id", it.error); assertEquals("quoteId", it.detail) }
        assertEquals("tax_office_code_too_long", a.post(base) { invoice(clientId); put("taxOfficeCode", "x".repeat(81)) }.error)
        a.get("$base?status=LATE").let { assertEquals(HttpStatusCode.BadRequest, it.status); assertEquals("invalid_status", it.error) }
        a.get("$base?clientId=nope").let { assertEquals(HttpStatusCode.BadRequest, it.status); assertEquals("invalid_id", it.error) }
        assertEquals("invalid_id", a.get("$base/nope").error)
        assertEquals("invalid_id", a.patch("$base/nope/paid").error)
        assertEquals("installment_invalid", a.put("$base/$id/installments") { putJsonArray("installments") { addJsonObject { put("amountEur", 400.0); put("dueDate", "1/12") } } }.error)
        assertEquals(HttpStatusCode.NotFound, a.get("$base/${ObjectId()}").status)
        assertEquals("PAID", a.patch("$base/$id/paid").text("status"))
        assertEquals("invoice_paid", a.post("$base/$id/cancel").error)
        val listed = a.get(base).array.map { it.jsonObject }
        assertEquals(listOf("Casa Martins"), listed.map { it["clientName"]!!.jsonPrimitive.content })
    }
}
