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
import kotlin.test.assertNotEquals

/** `/app/api/crm/quotes` over HTTP: its gates (invoicing one needs invoices too), isolation, and the error contract. */
class QuoteApiTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("quote_api")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val base = "/app/api/crm/quotes"

    private suspend fun DashboardApi.client(company: TestCompany, name: String = "Casa Martins"): String =
        company.post("/app/api/crm/clients") { put("name", name); put("phone", "+351 91${(1..7).map { (0..9).random() }.joinToString("")}"); put("taxId", "1"); put("address", "Rua 1") }.text("id")!!

    private fun JsonObjectBuilder.quote(clientId: String) {
        put("clientId", clientId)
        putJsonArray("items") { addJsonObject { put("description", "Pintura"); put("unitPriceEur", 300.0) } }
    }

    @Test
    fun `quotes answer 403 without their module, and invoicing one also needs invoices`() = dashboardApiTest(mongo) {
        val off = company(listOf(DashboardModules.CLIENTS))
        assertEquals(HttpStatusCode.Forbidden, off.get(base).status)
        assertEquals(HttpStatusCode.Forbidden, off.post(base) { quote(ObjectId().toHexString()) }.status)
        val quotesOnly = company(listOf(DashboardModules.CLIENTS, DashboardModules.QUOTES))
        val quotesOnlyClient = client(quotesOnly)
        val id = quotesOnly.post(base) { quote(quotesOnlyClient) }.text("id")!!
        assertEquals(HttpStatusCode.OK, quotesOnly.get("$base/$id").status)
        val refused = quotesOnly.post("$base/$id/invoice") { put("dueDate", "2026-12-01") }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("", refused.body)
        val both = company(listOf(DashboardModules.CLIENTS, DashboardModules.QUOTES, DashboardModules.INVOICES))
        val bothClient = client(both)
        val invoiced = both.post("$base/${both.post(base) { quote(bothClient) }.text("id")}/invoice") { put("dueDate", "2026-12-01") }
        assertEquals(HttpStatusCode.Created, invoiced.status, invoiced.toString())
    }

    @Test
    fun `a company never reads, changes or invoices another company's quotes`() = dashboardApiTest(mongo) {
        val a = company()
        val b = company()
        val clientId = client(a)
        val id = a.post(base) { quote(clientId) }.text("id")!!
        assertEquals(HttpStatusCode.NotFound, b.get("$base/$id").status)
        assertEquals(HttpStatusCode.NotFound, b.patch("$base/$id") { put("status", "SENT") }.status)
        assertEquals(HttpStatusCode.NotFound, b.get("$base/$id/pdf").status)
        assertEquals(HttpStatusCode.NotFound, b.post("$base/$id/invoice") { put("dueDate", "2026-12-01") }.status)
        assertFalse(id in b.get(base).ids())
        assertFalse(id in b.get("$base?clientId=$clientId").ids())
        assertEquals("client not found", b.post(base) { quote(clientId) }.error, "a's client isn't b's to quote")
        assertEquals("PENDENTE", a.get("$base/$id").text("status"))
    }

    @Test
    fun `deleting a quote needs the quotes module, and another company's quote is never found`() = dashboardApiTest(mongo) {
        val a = company()
        val b = company()
        val off = company(listOf(DashboardModules.CLIENTS))
        val clientId = client(a)
        val id = a.post(base) { quote(clientId) }.text("id")!!
        assertEquals(HttpStatusCode.Forbidden, off.delete("$base/$id").status)
        assertEquals(HttpStatusCode.NotFound, b.delete("$base/$id").status)
        assertEquals(HttpStatusCode.OK, a.get("$base/$id").status, "b's attempt left a's quote alone")
        assertEquals("invalid_id", a.delete("$base/nope").error)
        assertEquals(HttpStatusCode.NotFound, a.delete("$base/${ObjectId()}").status)
    }

    @Test
    fun `a quote is deleted for good, and its number is not reused`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a)
        val first = a.post(base) { quote(clientId) }
        val id = first.text("id")!!
        val number = first.text("number")!!
        val deleted = a.delete("$base/$id")
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertEquals("true", deleted.text("deleted"))
        assertEquals(HttpStatusCode.NotFound, a.get("$base/$id").status)
        assertFalse(id in a.get(base).ids())
        assertEquals(HttpStatusCode.NotFound, a.delete("$base/$id").status, "a second delete finds nothing")
        assertNotEquals(number, a.post(base) { quote(clientId) }.text("number"))
    }

    @Test
    fun `a quote that is invoiced or a service can't be deleted until that is cancelled`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a)

        val invoiced = a.post(base) { quote(clientId) }.text("id")!!
        val invoice = a.post("$base/$invoiced/invoice") { put("dueDate", "2026-12-01") }.text("id")!!
        a.delete("$base/$invoiced").let { assertEquals(HttpStatusCode.Conflict, it.status); assertEquals("already_invoiced", it.error) }
        assertEquals(HttpStatusCode.OK, a.get("$base/$invoiced").status)
        assertEquals(HttpStatusCode.OK, a.post("/app/api/crm/invoices/$invoice/cancel") {}.status)
        assertEquals(HttpStatusCode.OK, a.delete("$base/$invoiced").status, "a cancelled invoice frees the quote")
        assertEquals(HttpStatusCode.OK, a.get("/app/api/crm/invoices/$invoice").status, "the cancelled invoice stays, without its quote")
        assertEquals(null, a.get("/app/api/crm/invoices/$invoice").text("quoteNumber"))

        val serviced = a.post(base) { quote(clientId) }.text("id")!!
        assertEquals(HttpStatusCode.Created, a.post("$base/$serviced/service") {}.status)
        a.delete("$base/$serviced").let { assertEquals(HttpStatusCode.Conflict, it.status); assertEquals("already_serviced", it.error) }
    }

    @Test
    fun `quotes answer 400 with an error code for anything they can't read`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a)
        val id = a.post(base) { quote(clientId) }.text("id")!!
        assertEquals("invalid_body", a.sendMalformed("POST", base).error)
        assertEquals("invalid_body", a.post(base) { put("clientId", clientId) }.error, "items are required")
        assertEquals("at least one item is required", a.post(base) { put("clientId", clientId); putJsonArray("items") {} }.error)
        a.post(base) { quote("not-an-id") }.let { assertEquals("invalid_id", it.error); assertEquals("clientId", it.detail) }
        a.post(base) { quote(clientId); put("validUntil", "31/12/2026") }.let { assertEquals("invalid_date", it.error); assertEquals("validUntil", it.detail) }
        a.get("$base?clientId=nope").let { assertEquals(HttpStatusCode.BadRequest, it.status); assertEquals("invalid_id", it.error) }
        a.get("$base?status=NOPE").let { assertEquals(HttpStatusCode.BadRequest, it.status); assertEquals("invalid_status", it.error) }
        a.get("$base/nope").let { assertEquals("invalid_id", it.error); assertEquals("id", it.detail) }
        assertEquals("invalid status", a.patch("$base/$id") { put("status", "NOPE") }.error)
        assertEquals(HttpStatusCode.NotFound, a.get("$base/${ObjectId()}").status)
        assertEquals("due date required", a.post("$base/$id/invoice") { put("dueDate", "soon") }.error)
        assertEquals(HttpStatusCode.Created, a.post("$base/$id/invoice") { put("dueDate", "2026-12-01") }.status)
        assertEquals("already_invoiced", a.post("$base/$id/invoice") { put("dueDate", "2026-12-01") }.error)
        assertEquals(listOf("Casa Martins"), a.get(base).array.map { it.jsonObject["clientName"]!!.jsonPrimitive.content })
    }
}
