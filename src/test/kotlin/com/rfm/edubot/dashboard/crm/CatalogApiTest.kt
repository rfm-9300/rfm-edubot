package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.Reply
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
import kotlin.test.assertTrue

/** `/app/api/crm/standard-items` and `/catalog-categories` over HTTP: the catalog's gate, isolation between companies, error codes. */
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
    private val categories = "/app/api/crm/catalog-categories"

    private fun Reply.names() = array.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun Reply.countOf(name: String) = array.first { it.jsonObject["name"]!!.jsonPrimitive.content == name }.jsonObject["itemCount"]!!.jsonPrimitive.content.toInt()

    @Test
    fun `categories answer 403 without the catalog module and never cross companies`() = dashboardApiTest(mongo) {
        val off = company(listOf(DashboardModules.QUOTES))
        assertEquals(HttpStatusCode.Forbidden, off.get(categories).status)
        assertEquals(HttpStatusCode.Forbidden, off.post(categories) { put("name", "Pintura") }.status)
        val a = company()
        val b = company()
        assertEquals(HttpStatusCode.Created, a.post(categories) { put("name", "Só da A") }.status)
        assertFalse("Só da A" in b.get(categories).names())
        assertEquals(HttpStatusCode.NotFound, b.delete("$categories?name=${"Só da A".replace(" ", "%20")}").status)
        assertTrue("Só da A" in a.get(categories).names())
    }

    @Test
    fun `categories list the ones created and the ones items already use, once each`() = dashboardApiTest(mongo) {
        val a = company()
        a.post(base) { item("Pintura interior") }
        a.post(base) { item("Pintura exterior") }
        a.post(base) { item("Lixar"); put("category", "pintura") }
        assertEquals(HttpStatusCode.Created, a.post(categories) { put("name", "Eletricidade") }.status)
        val listed = a.get(categories)
        assertEquals(listOf("Eletricidade", "Pintura"), listed.names(), "'Pintura' and 'pintura' are one category, named after the spelling most items use")
        assertEquals(3, listed.countOf("Pintura"))
        assertEquals(0, listed.countOf("Eletricidade"))
    }

    @Test
    fun `creating a category refuses a blank, a long or a repeated name`() = dashboardApiTest(mongo) {
        val a = company()
        assertEquals("name_required", a.post(categories) { put("name", "   ") }.error)
        assertEquals("name_too_long", a.post(categories) { put("name", "x".repeat(61)) }.error)
        assertEquals("invalid_body", a.sendMalformed("POST", categories).error)
        assertEquals(HttpStatusCode.Created, a.post(categories) { put("name", "  Inspeção   técnica ") }.status)
        val again = a.post(categories) { put("name", "inspecao tecnica") }
        assertEquals(HttpStatusCode.Conflict, again.status, "case and accents don't make another category")
        assertEquals("category_exists", again.error)
        a.post(base) { item("Pintura") }
        assertEquals("category_exists", a.post(categories) { put("name", "PINTURA") }.error, "nor does one items already use")
        assertEquals(listOf("Inspeção técnica", "Pintura"), a.get(categories).names())
    }

    @Test
    fun `renaming a category moves its items and refuses a name another category has`() = dashboardApiTest(mongo) {
        val a = company()
        val id = a.post(base) { item("Pintura interior") }.obj["id"]!!.jsonPrimitive.content
        a.post(base) { item("Lixar"); put("category", "pintura") }
        a.post(base) { item("Cabo"); put("category", "Eletricidade") }
        val renamed = a.put("$categories?name=Pintura") { put("name", "Pinturas") }
        assertEquals(HttpStatusCode.OK, renamed.status, renamed.toString())
        assertEquals(2, renamed.obj["itemCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("Eletricidade", "Pinturas"), a.get(categories).names())
        assertEquals("Pinturas", a.get(base).array.first { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject["category"]!!.jsonPrimitive.content)
        assertEquals("category_exists", a.put("$categories?name=Pinturas") { put("name", "eletricidade") }.error)
        assertEquals(HttpStatusCode.OK, a.put("$categories?name=Pinturas") { put("name", "PINTURAS") }.status, "only the case changes")
        assertEquals(HttpStatusCode.NotFound, a.put("$categories?name=Nada") { put("name", "Algo") }.status)
        assertEquals("name_required", a.put("$categories?name=Pinturas") { put("name", " ") }.error)
    }

    @Test
    fun `a created category can be renamed and deleted, one in use cannot be deleted`() = dashboardApiTest(mongo) {
        val a = company()
        a.post(categories) { put("name", "Rascunho") }
        assertEquals(HttpStatusCode.OK, a.put("$categories?name=Rascunho") { put("name", "Canalização") }.status)
        a.post(base) { item("Torneira"); put("category", "Canalização") }
        val refused = a.delete("$categories?name=Canaliza%C3%A7%C3%A3o")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("category_in_use", refused.error)
        assertEquals("1", refused.text("detail"))
        a.post(categories) { put("name", "Vazia") }
        assertEquals(HttpStatusCode.OK, a.delete("$categories?name=Vazia").status)
        assertFalse("Vazia" in a.get(categories).names())
        assertEquals(HttpStatusCode.NotFound, a.delete("$categories?name=Vazia").status)
    }
}
