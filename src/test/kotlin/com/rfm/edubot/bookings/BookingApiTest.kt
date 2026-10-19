package com.rfm.edubot.bookings

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
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
import kotlin.test.assertTrue

/** `/app/api/bookings` over HTTP: its gate, isolation between companies' calendars, and its error codes. */
class BookingApiTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("booking_api")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val base = "/app/api/bookings"
    private val week = "from=2026-11-30T00:00:00Z&to=2026-12-07T00:00:00Z"

    private suspend fun DashboardApi.service(company: TestCompany, name: String = "Consulta"): String =
        company.post("$base/services") { put("name", name); put("durationMinutes", 30) }.text("id")!!

    private fun JsonObjectBuilder.booking(serviceId: String, at: String = "2026-12-01T10:00:00Z") {
        put("serviceId", serviceId)
        put("startAt", at)
        put("contactName", "Maria Silva")
        put("contactPhone", "+351 912 345 678")
    }

    @Test
    fun `bookings answer 403 without their module`() = dashboardApiTest(mongo) {
        val off = company(listOf(DashboardModules.CLIENTS))
        for (path in listOf(base, "$base/services", "$base/availability", "$base/slots?serviceId=consulta&$week", "$base/${ObjectId()}")) {
            assertEquals(HttpStatusCode.Forbidden, off.get(path).status, path)
        }
        assertEquals(HttpStatusCode.Forbidden, off.post(base) { booking("consulta") }.status)
        assertEquals(HttpStatusCode.Forbidden, off.post("$base/services") { put("name", "Consulta") }.status)
        assertEquals(HttpStatusCode.Forbidden, off.put("$base/availability") { putJsonArray("rules") {} }.status)
        val on = company(listOf(DashboardModules.BOOKINGS))
        val serviceId = service(on)
        assertEquals(HttpStatusCode.Created, on.post(base) { booking(serviceId) }.status)
    }

    @Test
    fun `a company never reads, moves or books into another company's calendar`() = dashboardApiTest(mongo) {
        val a = company()
        val b = company()
        val serviceId = service(a)
        val id = a.post(base) { booking(serviceId) }.text("id")!!
        assertEquals(HttpStatusCode.NotFound, b.get("$base/$id").status)
        b.post("$base/$id") { put("status", "CANCELLED") }.let {
            assertEquals(HttpStatusCode.NotFound, it.status)
            assertEquals("booking_not_found", it.error)
        }
        assertFalse(id in b.get(base).ids())
        assertTrue(b.get("$base/services").array.isEmpty())
        assertEquals("service_not_found", b.post(base) { booking(serviceId) }.error, "a's service isn't b's to book")
        assertEquals(HttpStatusCode.NotFound, b.post("$base/services/$serviceId") { put("name", "Outra") }.status)
        assertEquals(HttpStatusCode.NotFound, b.delete("$base/services/$serviceId").status)
        val theirs = service(b, "Massagem")
        assertEquals(HttpStatusCode.Created, b.post(base) { booking(theirs) }.status, "a's booking doesn't take b's time")

        assertEquals("PENDING", a.get("$base/$id").text("status"))
        val mine = a.get("$base/services").array.single().jsonObject
        assertEquals("Consulta", mine["name"]!!.jsonPrimitive.content)
        assertTrue(mine["active"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `bookings answer stable error codes`() = dashboardApiTest(mongo) {
        val a = company()
        val serviceId = service(a)
        assertEquals("invalid_body", a.sendMalformed("POST", base).error)
        assertEquals("invalid_body", a.post(base) { put("serviceId", serviceId) }.error, "startAt is required")
        assertEquals("service_not_found", a.post(base) { booking("") }.error)
        assertEquals("invalid_time", a.post(base) { booking(serviceId, at = "amanhã") }.error)
        assertEquals("invalid_status", a.post(base) { booking(serviceId); put("status", "LATER") }.error)
        assertEquals("client_not_found", a.post(base) { booking(serviceId); put("clientId", "nope") }.error)
        assertEquals("contact_required", a.post(base) { put("serviceId", serviceId); put("startAt", "2026-12-02T10:00:00Z") }.error)
        val id = a.post(base) { booking(serviceId) }.text("id")!!
        a.post(base) { booking(serviceId) }.let {
            assertEquals(HttpStatusCode.Conflict, it.status)
            assertEquals("conflict", it.error)
        }
        assertEquals("invalid_id", a.get("$base/nope").error)
        assertEquals("invalid_id", a.post("$base/nope") { put("status", "CANCELLED") }.error)
        assertEquals("invalid_status", a.post("$base/$id") { put("status", "LATER") }.error)
        assertEquals("invalid_status", a.get("$base?status=LATER").error)
        assertEquals("invalid_id", a.get("$base?clientId=nope").error)
        assertEquals(HttpStatusCode.NotFound, a.get("$base/${ObjectId()}").status)
        assertEquals("serviceId_required", a.get("$base/slots").error)
        assertEquals("from_required", a.get("$base/slots?serviceId=$serviceId").error)
        val badRule = a.put("$base/availability") {
            putJsonArray("rules") { addJsonObject { put("dayOfWeek", 9); put("startLocal", "09:00"); put("endLocal", "18:00") } }
        }
        assertEquals("invalid_availability", badRule.error)
        assertEquals("name_required", a.post("$base/services") { put("name", " ") }.error)
        assertEquals("invalid_time", a.post("$base/services") { put("name", "Rápido"); put("durationMinutes", 1) }.error)
        assertEquals("CANCELLED", a.post("$base/$id") { put("status", "CANCELLED") }.text("status"))
    }
}
