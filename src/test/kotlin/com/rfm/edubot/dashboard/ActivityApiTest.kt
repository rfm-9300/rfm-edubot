package com.rfm.edubot.dashboard

import com.mongodb.client.model.Filters
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The activity log: who did what, for the company's admins, and each record's history for whoever opens it. */
class ActivityApiTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("activity_api")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private fun JsonObject.s(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.actor(): JsonObject = this["actor"]!!.jsonObject
    private fun items(reply: com.rfm.edubot.testing.Reply): List<JsonObject> = reply.obj["items"]!!.jsonArray.map { it.jsonObject }

    private suspend fun DashboardApi.client(company: TestCompany, name: String, token: String = company.token): String =
        company.post("/app/api/crm/clients", token) {
            put("name", name); put("phone", "+351 91${(1..7).map { (0..9).random() }.joinToString("")}"); put("taxId", "1"); put("address", "Rua 1")
        }
            .also { assertEquals(HttpStatusCode.Created, it.status, it.toString()) }.text("id")!!

    private suspend fun DashboardApi.invoice(company: TestCompany, clientId: String, token: String = company.token): com.rfm.edubot.testing.Reply =
        company.post("/app/api/crm/invoices", token) {
            put("clientId", clientId); put("dueDate", "2026-12-01")
            putJsonArray("items") { addJsonObject { put("description", "Pintura"); put("unitPriceEur", 400.0) } }
        }

    @Test
    fun `admins read who did what, members get the record's history only`() = dashboardApiTest(mongo) {
        val a = company()
        val ana = client(a, "Ana Costa")
        val invoice = invoice(a, ana, a.memberToken)
        val number = invoice.text("number")!!
        a.delete("/app/api/crm/invoices/${invoice.text("id")}")

        val log = a.get("/app/api/activity")
        assertEquals(HttpStatusCode.OK, log.status, log.toString())
        val rows = items(log)
        val created = rows.single { it.s("type") == DomainEventTypes.CLIENT_CREATED }
        assertEquals(listOf("USER", a.admin.email, a.admin.id.toHexString()), listOf("type", "name", "id").map { created.actor().s(it) })
        assertEquals("Ana Costa", created.s("label")?.substringAfter(" · "))
        val made = rows.single { it.s("type") == DomainEventTypes.INVOICE_CREATED }
        assertTrue(made.actor().s("name")!!.startsWith("member-"), "the member made the invoice")
        val deleted = rows.single { it.s("type") == DomainEventTypes.INVOICE_DELETED }
        assertEquals(number to "invoice", deleted.s("label") to deleted.s("subjectType"), "a deleted invoice keeps its number")
        assertEquals(rows.map { it.s("at")!! }.sortedDescending(), rows.map { it.s("at")!! }, "newest first")
        assertTrue(rows.none { it.containsKey("payload") }, "only the facts a row needs leave the server")

        assertEquals(HttpStatusCode.Forbidden to "not_allowed", a.get("/app/api/activity", a.memberToken).let { it.status to it.error })
        assertEquals(HttpStatusCode.Forbidden, a.get("/app/api/activity/people", a.memberToken).status)
        val history = a.get("/app/api/activity/records/client/$ana", a.memberToken)
        assertEquals(HttpStatusCode.OK, history.status, history.toString())
        val types = history.array.map { it.jsonObject.s("type") }
        assertTrue(types.containsAll(listOf(DomainEventTypes.CLIENT_CREATED, DomainEventTypes.INVOICE_CREATED, DomainEventTypes.INVOICE_DELETED)), types.toString())
        assertEquals(HttpStatusCode.NotFound, a.get("/app/api/activity/records/nothing/$ana").status)

        val b = company()
        assertTrue(items(b.get("/app/api/activity")).isEmpty(), "another company's log is its own")
        assertTrue(b.get("/app/api/activity/records/client/$ana").array.isEmpty())
        val quotesOnly = company(listOf(DashboardModules.QUOTES))
        assertEquals(HttpStatusCode.Forbidden, quotesOnly.get("/app/api/activity/records/client/$ana").status, "a record's history needs its module")

        val people = a.get("/app/api/activity/people").array.map { it.jsonObject.s("email") }
        assertTrue(people.containsAll(listOf(a.admin.email)) && people.any { it!!.startsWith("member-") }, people.toString())
    }

    @Test
    fun `the log filters by person, area and days, and pages back in time`() = dashboardApiTest(mongo) {
        val a = company()
        val ana = client(a, "Ana Costa")
        client(a, "Rui Silva", a.memberToken)
        invoice(a, ana)
        invoice(a, ana)
        val member = users.listByTenant(a.tenant.id).single { it.email.startsWith("member-") }

        val mine = items(a.get("/app/api/activity?actor=user:${member.id.toHexString()}"))
        assertEquals(listOf(DomainEventTypes.CLIENT_CREATED), mine.map { it.s("type") })
        assertTrue(items(a.get("/app/api/activity?actor=CLIENT")).isEmpty(), "nobody answered from a link")
        val billing = items(a.get("/app/api/activity?area=invoices"))
        assertTrue(billing.isNotEmpty() && billing.all { it.s("subjectType") == "invoice" }, billing.toString())
        assertEquals("invalid_area", a.get("/app/api/activity?area=nowhere").error)
        assertTrue(items(a.get("/app/api/activity?from=2020-01-01&to=2020-01-31")).isEmpty(), "nothing happened back then")
        assertEquals("invalid_date", a.get("/app/api/activity?from=yesterday").error)

        val first = a.get("/app/api/activity?limit=2")
        val next = first.text("next")!!
        val second = a.get("/app/api/activity?limit=2&before=$next")
        val all = items(a.get("/app/api/activity"))
        assertEquals(all.take(4).map { it.s("id") }, (items(first) + items(second)).map { it.s("id") }, "the pages follow on, none twice")
    }

    @Test
    fun `history-only events wait for no agent, and the ones agents can react to do`() = dashboardApiTest(mongo) {
        val a = company()
        val ana = client(a, "Ana Costa")
        val id = invoice(a, ana).text("id")!!
        a.post("/app/api/crm/invoices/$id/cancel")
        val docs = mongo.database.getCollection<Document>(DomainEventLog.COLLECTION)
            .find(Filters.and(Filters.eq("tenantId", a.tenant.id), Filters.eq("subject.id", id))).toList()
        val status = docs.associate { it.getString("type") to (it.get("dispatch", Document::class.java)?.getString("status")) }
        assertEquals(DomainEventLog.NONE, status[DomainEventTypes.INVOICE_CANCELLED], "no agent listens for a cancellation")
        assertTrue(status[DomainEventTypes.INVOICE_CREATED] != DomainEventLog.NONE, status.toString())
    }
}
