package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `/app/api/crm/suppliers` and `/app/api/crm/employees` over HTTP: gates, isolation between companies, error codes. */
class DirectoryApiTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("directory_api")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private fun phone() = "+351 22${(1..7).map { (0..9).random() }.joinToString("")}"

    @Test
    fun `suppliers and employees each answer 403 without their module`() = dashboardApiTest(mongo) {
        val none = company(listOf(DashboardModules.CLIENTS))
        for (path in listOf("/app/api/crm/suppliers", "/app/api/crm/employees")) {
            assertEquals(HttpStatusCode.Forbidden, none.get(path).status, path)
            assertEquals(HttpStatusCode.Forbidden, none.post(path) { put("name", "X"); put("phone", phone()) }.status, path)
            assertEquals(HttpStatusCode.Forbidden, none.delete("$path/${ObjectId()}").status, path)
        }
        val payments = company(listOf(DashboardModules.PAYMENTS))
        assertEquals(HttpStatusCode.OK, payments.get("/app/api/crm/suppliers").status, "payments brings suppliers along")
        assertEquals(HttpStatusCode.Forbidden, payments.get("/app/api/crm/employees").status)
    }

    @Test
    fun `a company never reads or changes another company's suppliers and employees`() = dashboardApiTest(mongo) {
        val a = company()
        val b = company()
        for (path in listOf("/app/api/crm/suppliers", "/app/api/crm/employees")) {
            val number = phone()
            val id = a.post(path) { put("name", "Tintas Norte"); put("phone", number) }.text("id")!!
            assertEquals(HttpStatusCode.NotFound, b.get("$path/$id").status, path)
            assertEquals(HttpStatusCode.NotFound, b.patch("$path/$id") { put("name", "Outro"); put("phone", number) }.status, path)
            assertEquals(HttpStatusCode.NotFound, b.post("$path/$id/archive").status, path)
            assertEquals(HttpStatusCode.NotFound, b.delete("$path/$id").status, path)
            assertFalse(id in b.get(path).ids(), path)
            assertTrue(id in a.get(path).ids(), path)
            assertEquals("Tintas Norte", a.get("$path/$id").text("name"), path)
        }
    }

    @Test
    fun `directory writes answer stable error codes`() = dashboardApiTest(mongo) {
        val a = company()
        for (path in listOf("/app/api/crm/suppliers", "/app/api/crm/employees")) {
            val number = phone()
            assertEquals(HttpStatusCode.Created, a.post(path) { put("name", "Luz & Cia"); put("phone", number) }.status, path)
            assertEquals("invalid_body", a.sendMalformed("POST", path).error, path)
            assertEquals("name and phone are required", a.post(path) { put("name", ""); put("phone", phone()) }.error, path)
            val taken = a.post(path) { put("name", "Outro"); put("phone", number) }
            assertEquals(HttpStatusCode.Conflict, taken.status, path)
            assertEquals("phone_taken", taken.error, path)
            assertEquals(HttpStatusCode.BadRequest, a.get("$path/x").status, path)
            assertEquals(HttpStatusCode.NotFound, a.get("$path/${ObjectId()}").status, path)
        }
        assertEquals("type_too_long", a.post("/app/api/crm/suppliers") { put("name", "X"); put("phone", phone()); put("type", "t".repeat(61)) }.error)
        assertEquals("invalid_birth_date", a.post("/app/api/crm/employees") { put("name", "X"); put("phone", phone()); put("birthDate", "31/12/1990") }.error)
    }
}
