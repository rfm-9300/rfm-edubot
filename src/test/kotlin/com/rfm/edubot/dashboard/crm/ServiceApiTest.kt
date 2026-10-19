package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.add
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

/** `/app/api/crm/services` (Serviços) over HTTP: its gates (invoicing rows needs invoices too), isolation, error codes. */
class ServiceApiTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("service_api")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val base = "/app/api/crm/services"

    private suspend fun DashboardApi.client(company: TestCompany, name: String = "Casa Martins"): String =
        company.post("/app/api/crm/clients") { put("name", name); put("phone", "+351 91${(1..7).map { (0..9).random() }.joinToString("")}"); put("taxId", "1"); put("address", "Rua 1") }.text("id")!!

    private suspend fun DashboardApi.service(company: TestCompany, clientId: String): String {
        val created = company.post(base) { put("clientId", clientId); put("name", "Limpeza"); put("unitPriceEur", 80.0) }
        assertEquals(HttpStatusCode.Created, created.status, created.toString())
        return created.text("id")!!
    }

    @Test
    fun `serviços answer 403 without their module, and invoicing rows also needs invoices`() = dashboardApiTest(mongo) {
        val off = company(listOf(DashboardModules.QUOTES))
        assertEquals(HttpStatusCode.Forbidden, off.get(base).status)
        assertEquals(HttpStatusCode.Forbidden, off.post(base) { put("clientId", ObjectId().toHexString()); put("name", "X") }.status)
        val noInvoices = company(listOf(DashboardModules.CLIENTS))
        val clientId = client(noInvoices)
        val id = service(noInvoices, clientId)
        val refused = noInvoices.post("$base/invoice") { put("clientId", clientId); put("dueDate", "2026-12-01"); putJsonArray("serviceIds") { add(id) } }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("OPEN", noInvoices.get("$base/$id").text("status"), "nothing was invoiced")
    }

    @Test
    fun `a company never reads, changes, deletes or invoices another company's rows`() = dashboardApiTest(mongo) {
        val a = company()
        val b = company()
        val clientId = client(a)
        val id = service(a, clientId)
        assertEquals(HttpStatusCode.NotFound, b.get("$base/$id").status)
        assertEquals(HttpStatusCode.NotFound, b.patch("$base/$id") { put("name", "Outro") }.status)
        assertEquals(HttpStatusCode.NotFound, b.delete("$base/$id").status)
        assertEquals("client not found", b.post(base) { put("clientId", clientId); put("name", "Limpeza") }.error)
        assertEquals("empty", b.post("$base/invoice") { put("clientId", clientId); put("dueDate", "2026-12-01"); putJsonArray("serviceIds") { add(id) } }.error)
        assertFalse(id in b.get(base).ids())
        assertEquals("OPEN", a.get("$base/$id").text("status"))
    }

    @Test
    fun `serviços answer stable error codes`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a)
        val id = service(a, clientId)
        assertEquals("invalid_body", a.sendMalformed("POST", base).error)
        assertEquals("client required", a.post(base) { put("clientId", "nope"); put("name", "X") }.error)
        assertEquals("name required", a.post(base) { put("clientId", clientId); put("name", " ") }.error)
        assertEquals("item_description_required", a.post(base) {
            put("clientId", clientId); put("name", "X")
            putJsonArray("items") { add(kotlinx.serialization.json.buildJsonObject { put("description", ""); put("unitPriceEur", 1.0) }) }
        }.error)
        assertEquals("due date required", a.post("$base/invoice") { put("clientId", clientId); put("dueDate", "x"); putJsonArray("serviceIds") { add(id) } }.error)
        assertEquals(HttpStatusCode.BadRequest, a.get("$base/nope").status)
        val invoice = a.post("$base/invoice") { put("clientId", clientId); put("dueDate", "2026-12-01"); putJsonArray("serviceIds") { add(id) } }
        assertEquals(HttpStatusCode.Created, invoice.status, invoice.toString())
        val deleting = a.delete("$base/$id")
        assertEquals(HttpStatusCode.Conflict, deleting.status)
        assertEquals("invoiced services cannot be deleted", deleting.error)
        assertEquals(listOf("Casa Martins"), a.get(base).array.map { it.jsonObject["clientName"]!!.jsonPrimitive.content })
        assertEquals(HttpStatusCode.OK, a.delete("/app/api/crm/invoices/${invoice.text("id")}").status)
        assertEquals("OPEN", a.get("$base/$id").text("status"), "deleting the invoice frees its rows")
    }
}
