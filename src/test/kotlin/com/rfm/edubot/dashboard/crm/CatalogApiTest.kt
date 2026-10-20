package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** `/app/api/crm/standard-items` over HTTP: the catalog's gate, isolation between companies, error codes. */
class CatalogApiTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("catalog_api")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val base = "/app/api/crm/standard-items"

    private fun JsonObjectBuilder.item(title: String) {
        put("type", "service"); put("category", "Pintura"); put("unit", "m2"); put("defaultUnitPriceEur", 12.5); put("title", title)
    }

    @Test
    fun `the catalog answers 403 without its module`() = dashboardApiTest(mongo) {
        val off = company(listOf(DashboardModules.QUOTES))
        assertEquals(HttpStatusCode.Forbidden, off.get(base).status)
        assertEquals(HttpStatusCode.Forbidden, off.post(base) { item("Pintura interior") }.status)
        assertEquals(HttpStatusCode.Forbidden, off.delete("$base/srv-pintura").status)
    }

    @Test
    fun `a company never changes another company's catalog`() = dashboardApiTest(mongo) {
        val a = company()
        val b = company()
        val id = a.post(base) { item("Pintura interior") }.obj["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.NotFound, b.post("$base/$id") { item("Mudado") }.status)
        assertEquals(HttpStatusCode.NotFound, b.delete("$base/$id").status)
        assertFalse(b.get(base).array.any { it.jsonObject["id"]?.jsonPrimitive?.content == id })
        assertEquals("Pintura interior", a.get(base).array.single().jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Created, b.post(base) { item("Pintura interior") }.status, "ids and codes are per company")
    }

    @Test
    fun `catalog writes answer stable error codes`() = dashboardApiTest(mongo) {
        val a = company()
        assertEquals("invalid_body", a.sendMalformed("POST", base).error)
        assertEquals("invalid_body", a.post(base) { put("title", "Sem tipo") }.error, "missing fields")
        assertEquals("title_required", a.post(base) { item(" ") }.error)
        assertEquals("code_invalid", a.post(base) { item("Pintura"); put("code", "não é código!") }.error)
        val created = a.post(base) { item("Pintura"); put("code", "PIN-1") }
        assertEquals(HttpStatusCode.Created, created.status, created.toString())
        val taken = a.post(base) { item("Outra pintura"); put("code", "PIN-1") }
        assertEquals(HttpStatusCode.Conflict, taken.status)
        assertEquals("code_taken", taken.error)
        assertEquals(HttpStatusCode.NotFound, a.post("$base/srv-nada") { item("Nada") }.status)
    }
}
