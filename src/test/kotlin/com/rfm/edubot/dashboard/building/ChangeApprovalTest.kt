package com.rfm.edubot.dashboard.building

import com.rfm.edubot.events.ActorType
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.TenantStatus
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.Clock
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A client approving or rejecting a change order from its own link: the page, the answer, and when the link ends. */
class ChangeApprovalTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("building_change_approval")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val api = BUILDING_API

    /** A 1 850 € skylight, three days more, sent to its client: the change's id and its link's token. */
    private suspend fun DashboardApi.sentChange(company: TestCompany): Pair<String, String> {
        val clientId = client(company, name = "Ana Costa")
        val projectId = project(company) { put("clientId", clientId); put("address", "Rua das Flores 1") }.text("id")!!
        val change = company.post("$api/changes") {
            put("projectId", projectId); put("title", "Claraboia"); put("daysImpact", 3)
            putJsonArray("items") { addJsonObject { put("description", "Claraboia"); put("quantity", 1.0); put("unit", "un"); put("unitPriceEur", 1850.0) } }
        }.text("id")!!
        val sent = company.post("$api/changes/$change/status") { put("status", "SENT") }
        assertEquals(HttpStatusCode.OK, sent.status, sent.toString())
        return change to sent.text("approvalToken")!!
    }

    private suspend fun DashboardApi.decide(token: String, decision: String, name: String, note: String? = null) =
        call("POST", "/app/share/change/$token/decision", null, buildString {
            append("""{"decision":"$decision","name":"$name"""")
            if (note != null) append(""","note":"$note"""")
            append("}")
        })

    @Test
    fun `the client approves a sent change from its link, once, in their own name`() = dashboardApiTest(mongo) {
        val a = company()
        val (change, token) = sentChange(a)
        assertTrue(token.length >= 43, "a link nobody can guess")

        assertEquals(HttpStatusCode.OK, call("GET", "/app/share/change/$token", null).status)
        val page = call("GET", "/app/share/change/$token/data", null)
        assertEquals(HttpStatusCode.OK, page.status, page.toString())
        assertEquals(listOf("Claraboia", "ADDITION", "SENT", "Rua das Flores 1"), listOf("title", "kind", "status", "address").map { page.text(it) })
        assertEquals(1850.0, page.eur("totalEur"))
        assertEquals(3, page.int("daysImpact"))
        assertEquals(listOf("Claraboia"), page.list("items").map { it.str("description") })
        for (hidden in listOf("id", "projectId", "clientName", "clientPhone", "approvalToken", "createdBy")) assertFalse(hidden in page.obj, hidden)

        val nameless = decide(token, "APPROVED", " ")
        assertEquals(HttpStatusCode.BadRequest, nameless.status)
        assertEquals("name_required" to "name", nameless.error to nameless.detail)
        val maybe = decide(token, "MAYBE", "Ana Costa")
        assertEquals("invalid_decision" to "decision", maybe.error to maybe.detail)
        assertEquals("SENT", call("GET", "/app/share/change/$token/data", null).text("status"), "a refused answer changes nothing")

        val approved = decide(token, "APPROVED", "Ana Costa", note = "Pode avançar")
        assertEquals(HttpStatusCode.OK, approved.status, approved.toString())
        assertEquals(listOf("APPROVED", "Ana Costa", "CLIENT_LINK", "Pode avançar"), listOf("status", "decidedBy", "decidedVia", "decisionNote").map { approved.text(it) })
        val again = decide(token, "REJECTED", "Outra Pessoa")
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("already_decided", again.error)
        assertEquals("APPROVED", call("GET", "/app/share/change/$token/data", null).text("status"), "the link keeps showing the answer")

        val seen = a.get("$api/changes/$change")
        assertEquals(listOf("APPROVED", "CLIENT_LINK", "Ana Costa"), listOf("status", "decidedVia", "decidedBy").map { seen.text(it) })
        assertEquals(token, seen.text("approvalToken"), "the team can still open what the client saw")

        val decided = DomainEventLog(mongo).timeline(a.tenant.id, SubjectRef(SubjectTypes.CHANGE_ORDER, change)).single { it.type == DomainEventTypes.CHANGE_ORDER_DECIDED }
        assertEquals(listOf("APPROVED", "CLIENT_LINK", "Ana Costa"), listOf("to", "via", "decidedBy").map { decided.payload[it]?.jsonPrimitive?.contentOrNull })
        assertEquals(ActorType.CLIENT to "Ana Costa", decided.actor.type to decided.actor.name)

        val bell = a.get("/app/api/notifications", a.memberToken).list("items").single { it.str("kind") == "change_order_decided" }
        val params = bell["params"]!!.jsonObject
        assertEquals(listOf("Ana Costa", "APPROVED", "Claraboia", "Moradia Silva"), listOf("name", "decision", "title", "project").map { params.str(it) })
        assertEquals(SubjectTypes.CHANGE_ORDER to change, bell["subject"]!!.jsonObject.let { it.str("type") to it.str("id") })
    }

    @Test
    fun `editing a sent change or taking it back to draft ends its link, and the team's answer closes it`() = dashboardApiTest(mongo) {
        val a = company()
        val (change, token) = sentChange(a)
        val edited = a.patch("$api/changes/$change") {
            put("title", "Claraboia maior")
            putJsonArray("items") { addJsonObject { put("description", "Claraboia"); put("quantity", 1.0); put("unitPriceEur", 2400.0) } }
        }
        assertEquals("DRAFT", edited.text("status"), "the client approves what they read")
        assertNull(edited.text("approvalToken"))
        assertEquals(HttpStatusCode.NotFound, call("GET", "/app/share/change/$token/data", null).status)
        assertEquals(HttpStatusCode.NotFound, decide(token, "APPROVED", "Ana Costa").status)

        val second = a.post("$api/changes/$change/status") { put("status", "SENT") }.text("approvalToken")!!
        assertNotEquals(token, second)
        a.post("$api/changes/$change/status") { put("status", "REJECTED"); put("decidedBy", "Ana Costa") }
        val page = call("GET", "/app/share/change/$second/data", null)
        assertEquals(listOf("REJECTED", "TEAM"), listOf("status", "decidedVia").map { page.text(it) })
        assertEquals("already_decided", decide(second, "APPROVED", "Ana Costa").error)

        a.post("$api/changes/$change/status") { put("status", "DRAFT") }
        assertEquals(HttpStatusCode.NotFound, call("GET", "/app/share/change/$second/data", null).status, "back to draft, the link ends")
    }

    @Test
    fun `a made-up link, or one of a company that isn't active, opens nothing`() = dashboardApiTest(mongo) {
        val a = company()
        val (_, token) = sentChange(a)
        assertEquals(HttpStatusCode.NotFound, call("GET", "/app/share/change/not-a-token/data", null).status)
        assertEquals(HttpStatusCode.NotFound, decide("not-a-token", "APPROVED", "Ana Costa").status)

        tenants.setStatus(a.tenant.slug, TenantStatus.SUSPENDED, Clock.System.now())
        assertEquals(HttpStatusCode.NotFound, call("GET", "/app/share/change/$token/data", null).status)
        assertEquals(HttpStatusCode.NotFound, decide(token, "APPROVED", "Ana Costa").status)
    }
}
