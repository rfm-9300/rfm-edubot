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

/** `/app/api/crm/payments` over HTTP: its gate and the payee's, isolation between companies, error codes. */
class PaymentApiTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("payment_api")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val base = "/app/api/crm/payments"

    private suspend fun DashboardApi.supplier(company: TestCompany, name: String = "Tintas Norte"): String =
        company.post("/app/api/crm/suppliers") { put("name", name); put("phone", "+351 22${(1..7).map { (0..9).random() }.joinToString("")}") }.text("id")!!

    private suspend fun DashboardApi.employee(company: TestCompany, name: String = "Rui Costa"): String =
        company.post("/app/api/crm/employees") { put("name", name); put("phone", "+351 93${(1..7).map { (0..9).random() }.joinToString("")}") }.text("id")!!

    private fun JsonObjectBuilder.bill(supplierId: String? = null, employeeId: String? = null, due: String = "2026-12-01") {
        supplierId?.let { put("supplierId", it) }
        employeeId?.let { put("employeeId", it) }
        put("dueDate", due)
        putJsonArray("items") { addJsonObject { put("description", "Tinta"); put("unitPriceEur", 54.9) } }
    }

    @Test
    fun `payments answer 403 without their module, and paying an employee needs employees`() = dashboardApiTest(mongo) {
        val off = company(listOf(DashboardModules.CLIENTS))
        assertEquals(HttpStatusCode.Forbidden, off.get(base).status)
        assertEquals(HttpStatusCode.Forbidden, off.post(base) { bill(ObjectId().toHexString()) }.status)
        val payments = company(listOf(DashboardModules.PAYMENTS))
        assertEquals(HttpStatusCode.Forbidden, payments.post(base) { bill(employeeId = ObjectId().toHexString()) }.status)
        val supplierId = supplier(payments)
        assertEquals(HttpStatusCode.Created, payments.post(base) { bill(supplierId) }.status)
    }

    @Test
    fun `a company never reads, pays, cancels or deletes another company's payments`() = dashboardApiTest(mongo) {
        val a = company()
        val b = company()
        val supplierId = supplier(a)
        val id = a.post(base) { bill(supplierId) }.text("id")!!
        assertEquals(HttpStatusCode.NotFound, b.get("$base/$id").status)
        assertEquals(HttpStatusCode.NotFound, b.patch("$base/$id/paid").status)
        assertEquals(HttpStatusCode.NotFound, b.post("$base/$id/cancel").status)
        assertEquals(HttpStatusCode.NotFound, b.patch("$base/$id/client") { put("clientId", "") }.status)
        assertEquals(HttpStatusCode.NotFound, b.delete("$base/$id").status)
        assertEquals("supplier not found", b.post(base) { bill(supplierId) }.error, "a's supplier isn't b's to pay")
        val employeeId = employee(a)
        assertEquals("employee not found", b.post(base) { bill(employeeId = employeeId) }.error)
        assertFalse(id in b.get(base).ids())
        assertEquals("PENDING", a.get("$base/$id").text("status"))
    }

    @Test
    fun `payments answer stable error codes`() = dashboardApiTest(mongo) {
        val a = company()
        val supplierId = supplier(a)
        val employeeId = employee(a, "Ana Lopes")
        assertEquals("invalid_body", a.sendMalformed("POST", base).error)
        assertEquals("at least one item is required", a.post(base) { put("supplierId", supplierId); put("dueDate", "2026-12-01"); putJsonArray("items") {} }.error)
        assertEquals("choose a supplier or an employee", a.post(base) { bill() }.error)
        assertEquals("choose a supplier or an employee", a.post(base) { bill(supplierId, employeeId) }.error)
        assertEquals("due date required", a.post(base) { bill(supplierId, due = "amanhã") }.error)
        assertEquals("client not found", a.post(base) { bill(supplierId); put("clientId", ObjectId().toHexString()) }.error)
        assertEquals(HttpStatusCode.BadRequest, a.get("$base/nope").status)
        val id = a.post(base) { bill(supplierId) }.text("id")!!
        assertEquals("PAID", a.patch("$base/$id/paid").text("status"))
        val cancel = a.post("$base/$id/cancel")
        assertEquals(HttpStatusCode.Conflict, cancel.status)
        assertEquals("payment_paid", cancel.error)
        val second = a.post(base) { bill(employeeId = employeeId) }
        assertEquals(HttpStatusCode.Created, second.status, second.toString())
        val names = a.get(base).array.map { it.jsonObject }.associate { row ->
            row["id"]!!.jsonPrimitive.content to listOf("supplierName", "employeeName").joinToString("") { row[it]?.jsonPrimitive?.content.orEmpty() }
        }
        assertEquals(mapOf(id to "Tintas Norte", second.text("id")!! to "Ana Lopes"), names, "the list names each payee")
    }
}
