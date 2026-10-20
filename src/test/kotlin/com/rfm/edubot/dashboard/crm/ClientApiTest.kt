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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `/app/api/crm/clients` over HTTP: its module gate, one company never reaching another's clients, and the form's error codes. */
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
        assertEquals(HttpStatusCode.Created, b.post(base) { put("name", "Casa B"); put("phone", number); put("taxId", "1"); put("address", "x") }.status, "phones are unique per company")
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
        val taken = a.post(base) { put("name", "Ana"); put("phone", number); put("taxId", "1"); put("address", "Rua 1") }
        assertEquals(HttpStatusCode.Conflict, taken.status)
        assertEquals("phone_taken", taken.error)
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
}
