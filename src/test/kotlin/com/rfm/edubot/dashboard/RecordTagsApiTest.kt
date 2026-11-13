package com.rfm.edubot.dashboard

import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tags.RecordTags
import com.rfm.edubot.tags.TagKind
import com.rfm.edubot.testing.Reply
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import com.rfm.edubot.testing.DashboardApi
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Staff tagging records over HTTP: each record type's gate, the limits, isolation between companies and cleanup. */
class RecordTagsApiTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("record_tags")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val clients = "/app/api/crm/clients"
    private val catalog = "/app/api/crm/standard-items"

    private suspend fun DashboardApi.client(company: TestCompany): String {
        val created = company.post(clients) { put("name", "Casa Martins"); put("phone", "9${(10_000_000..99_999_999).random()}"); put("taxId", "245678901"); put("address", "Rua do Sol 4") }
        assertEquals(HttpStatusCode.Created, created.status, created.toString())
        return created.text("id")!!
    }

    private suspend fun DashboardApi.catalogItem(company: TestCompany): String {
        val created = company.post(catalog) { put("type", "service"); put("category", "Pintura"); put("unit", "m2"); put("defaultUnitPriceEur", 12.5); put("title", "Pintura interior") }
        assertEquals(HttpStatusCode.Created, created.status, created.toString())
        return created.text("id")!!
    }

    private suspend fun DashboardApi.tag(company: TestCompany, path: String, vararg tags: String): Reply =
        company.put(path) { putJsonArray("tags") { tags.forEach { add(it) } } }

    private fun Reply.tagsOf(id: String): List<String>? = obj[id]?.jsonArray?.map { it.jsonPrimitive.content }

    @Test
    fun `a record's tags are set, replaced and cleared, and read back for the whole list`() = dashboardApiTest(mongo) {
        val a = company()
        val first = client(a)
        val second = client(a)
        val set = tag(a, "$clients/$first/tags", "VIP", " Obras  grandes ", "vip", "")
        assertEquals(HttpStatusCode.OK, set.status, set.toString())
        assertEquals(listOf("VIP", "Obras grandes"), set.obj["tags"]!!.jsonArray.map { it.jsonPrimitive.content }, "trimmed, spaces collapsed, repeats dropped ignoring case")
        tag(a, "$clients/$second/tags", "Lisboa")
        val all = a.get("$clients/tags")
        assertEquals(listOf("VIP", "Obras grandes"), all.tagsOf(first))
        assertEquals(listOf("Lisboa"), all.tagsOf(second))

        tag(a, "$clients/$first/tags", "Antigo")
        assertEquals(listOf("Antigo"), a.get("$clients/tags").tagsOf(first))
        tag(a, "$clients/$first/tags")
        assertEquals(null, a.get("$clients/tags").tagsOf(first), "an empty list removes the record's tags")
        assertEquals(listOf("Lisboa"), a.get("$clients/tags").tagsOf(second))
    }

    @Test
    fun `tags break out of their limits with a stable error code`() = dashboardApiTest(mongo) {
        val a = company()
        val id = client(a)
        val tooMany = tag(a, "$clients/$id/tags", *Array(RecordTags.MAX_TAGS + 1) { "tag $it" })
        assertEquals(HttpStatusCode.BadRequest, tooMany.status)
        assertEquals("too_many_tags", tooMany.error)
        val tooLong = tag(a, "$clients/$id/tags", "x".repeat(RecordTags.MAX_LENGTH + 1))
        assertEquals("tag_too_long", tooLong.error)
        assertEquals(HttpStatusCode.OK, tag(a, "$clients/$id/tags", *Array(RecordTags.MAX_TAGS) { "tag $it" }).status, "ten is allowed")
        assertEquals("invalid_body", a.sendMalformed("PUT", "$clients/$id/tags").error)
        assertEquals("invalid_id", tag(a, "$clients/not-an-id/tags", "a").error)
    }

    @Test
    fun `a company can't tag another company's record or a record that doesn't exist`() = dashboardApiTest(mongo) {
        val a = company()
        val b = company()
        val id = client(a)
        val refused = tag(b, "$clients/$id/tags", "Roubado")
        assertEquals(HttpStatusCode.NotFound, refused.status)
        assertEquals("record_not_found", refused.error)
        assertEquals(HttpStatusCode.NotFound, tag(a, "$clients/${ObjectId().toHexString()}/tags", "Fantasma").status)
        tag(a, "$clients/$id/tags", "Meu")
        assertEquals(emptyMap(), b.get("$clients/tags").obj.toMap(), "another company sees none of them")
        assertEquals(listOf("Meu"), a.get("$clients/tags").tagsOf(id))
    }

    @Test
    fun `tags of a removed record go with it`() = dashboardApiTest(mongo) {
        val a = company()
        val gone = client(a)
        val kept = client(a)
        tag(a, "$clients/$gone/tags", "Temporário")
        tag(a, "$clients/$kept/tags", "Fica")
        assertEquals(HttpStatusCode.OK, a.delete("$clients/$gone").status)
        val all = a.get("$clients/tags")
        assertEquals(null, all.tagsOf(gone))
        assertEquals(listOf("Fica"), all.tagsOf(kept))
        assertEquals(setOf(kept), all.obj.keys, "the orphan is deleted, not just hidden")
    }

    @Test
    fun `a catalog item is tagged by its own key`() = dashboardApiTest(mongo) {
        val a = company()
        val id = catalogItem(a)
        assertEquals(HttpStatusCode.OK, tag(a, "$catalog/$id/tags", "Interior").status)
        assertEquals(listOf("Interior"), a.get("$catalog/tags").tagsOf(id))
        assertEquals(HttpStatusCode.NotFound, tag(a, "$catalog/srv-nada/tags", "x").status)
    }

    @Test
    fun `each record type's tags are behind its own module`() = dashboardApiTest(mongo) {
        val routes = mapOf(
            TagKind.CLIENT to "/app/api/crm/clients",
            TagKind.SUPPLIER to "/app/api/crm/suppliers",
            TagKind.EMPLOYEE to "/app/api/crm/employees",
            TagKind.SERVICE to "/app/api/crm/services",
            TagKind.CATALOG_ITEM to catalog,
            TagKind.QUOTE to "/app/api/crm/quotes",
            TagKind.INVOICE to "/app/api/crm/invoices",
            TagKind.PAYMENT to "/app/api/crm/payments",
            TagKind.PROJECT to "/app/api/building/projects",
        )
        assertEquals(TagKind.entries.toSet(), routes.keys, "a new kind needs its route mounted and listed here")
        val off = company(listOf(DashboardModules.CONVERSATIONS))
        val on = company()
        for ((kind, path) in routes) {
            assertEquals(HttpStatusCode.Forbidden, off.get("$path/tags").status, "$kind without its module")
            assertEquals(HttpStatusCode.Forbidden, tag(off, "$path/${ObjectId().toHexString()}/tags", "x").status, "$kind without its module")
            assertEquals(HttpStatusCode.OK, on.get("$path/tags").status, "$kind with it")
        }
    }

    @Test
    fun `tags are cleaned the same way wherever they come from`() {
        assertEquals(RecordTags.Cleaned.Valid(listOf("A", "b c")), RecordTags.clean(listOf(" A ", "a", "b   c", " ", "B C")))
        assertTrue(RecordTags.clean(List(RecordTags.MAX_TAGS + 1) { "t$it" }) is RecordTags.Cleaned.Invalid)
        assertEquals(RecordTags.Cleaned.Valid(emptyList()), RecordTags.clean(listOf("", "  ")))
    }
}
