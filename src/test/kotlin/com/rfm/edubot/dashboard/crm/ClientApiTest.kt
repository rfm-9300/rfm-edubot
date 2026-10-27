package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.CustomField
import com.rfm.edubot.tenant.model.CustomFieldType
import com.rfm.edubot.tenant.model.DirectoryFields
import com.rfm.edubot.tenant.model.FieldDirectory
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `/app/api/crm/clients` over HTTP: its module gate, one company never reaching another's clients, the form's error
 * codes, and the catalog items a client buys.
 */
class ClientApiTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("client_api")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val base = "/app/api/crm/clients"
    private fun phone() = "+351 91${(1..9).map { (0..9).random() }.joinToString("")}"

    private suspend fun DashboardApi.client(company: TestCompany, phone: String = phone()): String {
        val created = company.post(base) { put("name", "Casa Martins"); put("phone", phone); put("taxId", "245678901"); put("address", "Rua do Sol 4") }
        assertEquals(HttpStatusCode.Created, created.status, created.toString())
        return created.text("id")!!
    }

    @Test
    fun `a company without the clients module is refused before any client route runs`() = dashboardApiTest(mongo) {
        val off = company(listOf(DashboardModules.QUOTES))
        for ((method, path) in listOf("GET" to base, "POST" to base, "GET" to "$base/fields", "GET" to "$base/${ObjectId()}", "POST" to "$base/${ObjectId()}/archive")) {
            val reply = call(method, path, off.token, if (method == "POST") "{}" else null)
            assertEquals(HttpStatusCode.Forbidden, reply.status, "$method $path")
            assertEquals("", reply.body, "the dashboard's own pages get a bare 403")
        }
        assertEquals(HttpStatusCode.OK, company(listOf(DashboardModules.CLIENTS)).get(base).status)
    }

    @Test
    fun `a company never reads or changes another company's clients`() = dashboardApiTest(mongo) {
        val a = company()
        val b = company()
        val number = phone()
        val id = client(a, number)
        assertEquals(HttpStatusCode.NotFound, b.get("$base/$id").status)
        assertEquals(HttpStatusCode.NotFound, b.patch("$base/$id") { put("name", "Outra"); put("phone", number); put("taxId", "1"); put("address", "x") }.status)
        assertEquals(HttpStatusCode.NotFound, b.delete("$base/$id").status)
        assertEquals(HttpStatusCode.NotFound, b.post("$base/$id/archive").status)
        assertEquals(HttpStatusCode.NotFound, b.get("$base/by-phone?phone=${number.filter(Char::isDigit)}").status)
        assertFalse(id in b.get(base).ids())
        assertTrue(id in a.get(base).ids())
        assertEquals("Casa Martins", a.get("$base/$id").text("name"), "nothing b sent reached it")
        assertEquals(HttpStatusCode.Created, b.post(base) { put("name", "Casa B"); put("phone", number); put("taxId", "1"); put("address", "x") }.status, "another company's client may have the phone")
    }

    @Test
    fun `client writes answer the client form's error codes`() = dashboardApiTest(mongo) {
        val a = company()
        val number = phone()
        val id = client(a, number)
        assertEquals("invalid_body", a.sendMalformed("POST", base).error)
        assertEquals("invalid_body", call("POST", base, a.token, """{"phone":"+351 910 000 000"}""").error, "a missing field is a malformed body")
        assertEquals("name and phone are required", a.post(base) { put("name", " "); put("phone", phone()) }.error)
        assertEquals("tax_id_required", a.post(base) { put("name", "Ana"); put("phone", phone()); put("address", "Rua 1") }.error)
        assertEquals("invalid_email", a.post(base) { put("name", "Ana"); put("phone", phone()); put("taxId", "1"); put("address", "Rua 1"); put("email", "ana@") }.error)
        val shared = a.post(base) { put("name", "Ana"); put("phone", number); put("taxId", "1"); put("address", "Rua 1") }
        assertEquals(HttpStatusCode.Created, shared.status, "clients may share a phone")
        assertEquals(HttpStatusCode.BadRequest, a.get("$base/not-an-id").status)
        assertEquals(HttpStatusCode.NotFound, a.get("$base/${ObjectId()}").status)
        assertEquals(HttpStatusCode.NotFound, a.patch("$base/${ObjectId()}") { put("name", "Ana"); put("phone", phone()); put("taxId", "1"); put("address", "Rua 1") }.status)
        assertEquals(HttpStatusCode.OK, a.post("$base/$id/archive").status)
        assertTrue(id !in a.get(base).ids(), "an archived client leaves the list")
        assertTrue(id in a.get("$base?archived=1").ids())
    }

    @Test
    fun `the company's own fields are checked and named in the error`() = dashboardApiTest(mongo) {
        val a = company()
        val field = CustomField(key = "obra", label = "Obra", type = CustomFieldType.NUMBER, required = true)
        tenants.setDirectoryFields(a.tenant.slug, FieldDirectory.CLIENTS, DirectoryFields(required = emptySet(), custom = listOf(field)), Clock.System.now())
        val missing = a.post(base) { put("name", "Ana"); put("phone", phone()) }
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals("obra", missing.detail)
        val saved = a.post(base) { put("name", "Ana"); put("phone", phone()); putJsonObject("customFields") { put("obra", 12) } }
        assertEquals(HttpStatusCode.Created, saved.status, saved.toString())
    }

    private suspend fun DashboardApi.catalogItem(company: TestCompany, title: String): String {
        val created = company.post("/app/api/crm/standard-items") {
            put("type", "service"); put("category", "Pintura"); put("unit", "m2"); put("defaultUnitPriceEur", 12.5); put("title", title)
        }
        assertEquals(HttpStatusCode.Created, created.status, created.toString())
        return created.text("id")!!
    }

    private fun itemIds(vararg ids: String): JsonObjectBuilder.() -> Unit = { putJsonArray("itemIds") { ids.forEach { add(JsonPrimitive(it)) } } }

    @Test
    fun `a client keeps the catalog items it buys, from its own company's catalog`() = dashboardApiTest(mongo) {
        val a = company()
        val id = client(a)
        val paint = catalogItem(a, "Pintura interior")
        val wash = catalogItem(a, "Lavagem de fachada")
        val saved = a.put("$base/$id/catalog-items", body = itemIds(wash, paint, wash))
        assertEquals(HttpStatusCode.OK, saved.status, saved.toString())
        assertEquals(listOf(wash, paint), saved.obj["catalogItemIds"]!!.jsonArray.map { it.jsonPrimitive.content }, "in order, without repeats")
        assertEquals(listOf(wash, paint), a.get("$base/$id").obj["catalogItemIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Casa Martins", a.get("$base/$id").text("name"), "the rest of the client stays")

        assertEquals("catalog_item_not_found", a.put("$base/$id/catalog-items", body = itemIds(paint, "srv-nada")).error)
        val b = company()
        val other = catalogItem(b, "Pintura de B")
        assertEquals("catalog_item_not_found", a.put("$base/$id/catalog-items", body = itemIds(other)).error, "another company's item")
        assertEquals(HttpStatusCode.NotFound, b.put("$base/$id/catalog-items", body = itemIds(other)).status)
        assertEquals(HttpStatusCode.NotFound, a.put("$base/${ObjectId()}/catalog-items", body = itemIds(paint)).status)

        assertEquals(HttpStatusCode.OK, a.put("$base/$id/catalog-items", body = itemIds()).status)
        assertEquals(null, a.get("$base/$id").obj["catalogItemIds"], "an empty list is left out like other defaults")
    }

    @Test
    fun `a client's catalog items need the catalog module`() = dashboardApiTest(mongo) {
        val a = company(listOf(DashboardModules.CLIENTS))
        val id = client(a)
        assertEquals(HttpStatusCode.Forbidden, a.put("$base/$id/catalog-items", body = itemIds()).status)
    }
}
