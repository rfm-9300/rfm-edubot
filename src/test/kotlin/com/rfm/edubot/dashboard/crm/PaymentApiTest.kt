package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.Reply
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertNull

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

    private fun Reply.rowsByDue() = array.map { it.jsonObject }.sortedBy { it["dueDate"]!!.jsonPrimitive.content }

    private fun JsonObject.field(name: String) = this[name]!!.jsonPrimitive.content

    @Test
    fun `a repeating bill makes one payment per date, each with its own number and its own life`() = dashboardApiTest(mongo) {
        val a = company()
        val supplierId = supplier(a)
        val first = a.post(base) { bill(supplierId, due = "2026-01-31"); put("repeatEvery", "monthly"); put("repeatTimes", 3) }
        assertEquals(HttpStatusCode.Created, first.status, first.toString())
        assertEquals("1", first.text("seriesPosition"), "the answer is the first payment")
        val rows = a.get(base).rowsByDue()
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31"), rows.map { it.field("dueDate") }, "the 31st falls on the 28th and goes back")
        assertEquals(3, rows.map { it.field("number") }.toSet().size, "each payment has its own number")
        assertEquals(1, rows.map { it.field("seriesId") }.toSet().size, "they share a series")
        assertEquals(listOf("1", "2", "3"), rows.map { it.field("seriesPosition") })
        assertEquals(setOf("3"), rows.map { it.field("seriesSize") }.toSet())
        assertEquals(setOf("monthly"), rows.map { it.field("repeatEvery") }.toSet())
        assertEquals(setOf("PENDING"), rows.map { it.field("status") }.toSet())

        val second = rows[1].field("id")
        assertEquals("PAID", a.patch("$base/$second/paid").text("status"))
        assertEquals(listOf("PENDING", "PAID", "PENDING"), a.get(base).rowsByDue().map { it.field("status") }, "paying one leaves the others")
        assertEquals("2", a.get("$base/$second").text("seriesPosition"))
    }

    @Test
    fun `a payment without repeat fields, or with a blank one, is a single payment with no series`() = dashboardApiTest(mongo) {
        val a = company()
        val supplierId = supplier(a)
        val one = a.post(base) { bill(supplierId) }
        assertEquals(HttpStatusCode.Created, one.status)
        assertNull(one.text("seriesId"))
        val blank = a.post(base) { bill(supplierId); put("repeatEvery", ""); put("repeatTimes", 6) }
        assertEquals(HttpStatusCode.Created, blank.status)
        assertNull(blank.text("seriesId"))
        assertEquals(2, a.get(base).array.size)
    }

    @Test
    fun `a repeating bill needs a known frequency and between 2 and 24 payments, and makes none when refused`() = dashboardApiTest(mongo) {
        val a = company()
        val supplierId = supplier(a)
        assertEquals("repeat every weekly, monthly, quarterly or yearly", a.post(base) { bill(supplierId); put("repeatEvery", "daily"); put("repeatTimes", 3) }.error)
        val range = "repeat times between 2 and 24"
        assertEquals(range, a.post(base) { bill(supplierId); put("repeatEvery", "monthly") }.error)
        assertEquals(range, a.post(base) { bill(supplierId); put("repeatEvery", "monthly"); put("repeatTimes", 1) }.error)
        assertEquals(range, a.post(base) { bill(supplierId); put("repeatEvery", "monthly"); put("repeatTimes", 25) }.error)
        assertEquals(0, a.get(base).array.size)
        assertEquals(HttpStatusCode.Created, a.post(base) { bill(supplierId); put("repeatEvery", "weekly"); put("repeatTimes", 24) }.status)
        assertEquals(24, a.get(base).array.size)
    }

    @Test
    fun `an employee can be paid on repeat too`() = dashboardApiTest(mongo) {
        val a = company()
        val employeeId = employee(a)
        assertEquals(HttpStatusCode.Created, a.post(base) { bill(employeeId = employeeId); put("repeatEvery", "monthly"); put("repeatTimes", 2) }.status)
        val rows = a.get(base).rowsByDue()
        assertEquals(2, rows.size)
        assertEquals(setOf("Rui Costa"), rows.map { it.field("employeeName") }.toSet())
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
