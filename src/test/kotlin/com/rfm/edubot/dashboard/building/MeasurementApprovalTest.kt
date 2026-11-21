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
import io.ktor.http.ContentType
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A client approving or disputing a measurement (auto de medição) from its link, before the company invoices it. */
class MeasurementApprovalTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("building_measurement_approval")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val api = BUILDING_API

    /** A 10 000 € house for Ana Costa, half built, measured in a draft: the measurement's id. */
    private suspend fun DashboardApi.measuredDraft(company: TestCompany): String {
        val clientId = client(company, name = "Ana Costa")
        val projectId = project(company) {
            put("clientId", clientId); put("retentionPct", 5.0)
            putJsonArray("contract") { addJsonObject { put("description", "Moradia"); put("quantity", 1.0); put("unit", "vg"); put("unitPriceEur", 10000.0) } }
        }.text("id")!!
        val draft = company.post("$api/measurements") { put("projectId", projectId) }
        val line = draft.list("lines").single().str("key")
        company.patch("$api/measurements/${draft.text("id")}") { putJsonArray("lines") { addJsonObject { put("key", line); put("toDateQty", 0.5) } } }
        return draft.text("id")!!
    }

    private suspend fun DashboardApi.answer(token: String, decision: String, name: String, note: String? = null) =
        call("POST", "/app/share/measurement/$token/decision", null, buildString {
            append("""{"decision":"$decision","name":"$name"""")
            if (note != null) append(""","note":"$note"""")
            append("}")
        }, headers = mapOf("X-Forwarded-For" to "85.240.1.2", "User-Agent" to "Mozilla/5.0 (Android) Chrome/130"))

    @Test
    fun `the client approves a measurement sent to them, once, and the company can invoice it`() = dashboardApiTest(mongo) {
        val a = company()
        val id = measuredDraft(a)
        val sent = a.post("$api/measurements/$id/send")
        assertEquals(HttpStatusCode.OK, sent.status, sent.toString())
        val token = sent.text("approvalToken")!!
        assertTrue(token.length >= 43, "a link nobody can guess")
        assertEquals(listOf("DRAFT", "true"), listOf(sent.text("status"), sent.text("awaitingClient")), "sent, it is still a draft waiting for the client")
        assertEquals(token, a.post("$api/measurements/$id/send").text("approvalToken"), "sending it again keeps the link")

        assertEquals(HttpStatusCode.OK, call("GET", "/app/share/measurement/$token", null).status)
        val page = call("GET", "/app/share/measurement/$token/data", null)
        assertEquals(HttpStatusCode.OK, page.status, page.toString())
        assertEquals(listOf("DRAFT", "true"), listOf(page.text("status"), page.text("awaiting")))
        assertEquals(5000.0 to 4750.0, page.eur("grossEur") to page.eur("netEur"), "half of the house, less 5% held")
        assertEquals(listOf("Moradia"), page.list("lines").map { it.str("description") })
        for (hidden in listOf("id", "projectId", "clientName", "approvalToken", "createdBy")) assertFalse(hidden in page.obj, hidden)

        assertEquals("name_required" to "name", answer(token, "APPROVED", " ").let { it.error to it.detail })
        assertEquals("invalid_decision" to "decision", answer(token, "MAYBE", "Ana Costa").let { it.error to it.detail })
        val approved = answer(token, "APPROVED", "Ana Costa")
        assertEquals(HttpStatusCode.OK, approved.status, approved.toString())
        assertEquals(listOf("APPROVED", "Ana Costa", "CLIENT_LINK", "false"), listOf("status", "approvedBy", "approvedVia", "awaiting").map { approved.text(it) })
        val again = answer(token, "DISPUTED", "Outra Pessoa", note = "Não")
        assertEquals(HttpStatusCode.Conflict to "already_decided", again.status to again.error)

        val seen = a.get("$api/measurements/$id")
        assertEquals(listOf("APPROVED", "CLIENT_LINK", "Ana Costa", "85.240.1.2"), listOf("status", "approvedVia", "approvedBy", "approvalIp").map { seen.text(it) })
        assertEquals(token, seen.text("approvalToken"), "the link keeps showing the answer")
        val invoiced = a.post("$api/measurements/$id/invoice") { put("dueDate", a.day(30)) }
        assertEquals(HttpStatusCode.Created, invoiced.status, invoiced.toString())

        val event = DomainEventLog(mongo).timeline(a.tenant.id, SubjectRef(SubjectTypes.MEASUREMENT, id)).first { it.type == DomainEventTypes.MEASUREMENT_APPROVED }
        assertEquals(ActorType.CLIENT to "Ana Costa", event.actor.type to event.actor.name)
        assertEquals("CLIENT_LINK", event.payload["via"]?.jsonPrimitive?.contentOrNull)
        val bell = a.get("/app/api/notifications", a.memberToken).list("items").single { it.str("kind") == "measurement_decided" }
        assertEquals(listOf("Ana Costa", "APPROVED", "1"), listOf("name", "decision", "n").map { bell["params"]!!.jsonObject.str(it) })

        val pdf = publicDownload("/app/share/measurement/$token/pdf")
        assertEquals(HttpStatusCode.OK, pdf.status)
        assertEquals(ContentType.Application.Pdf, pdf.contentType?.withoutParameters())
    }

    @Test
    fun `a dispute needs a note, keeps the draft and its note for the team, and sending again makes a new link`() = dashboardApiTest(mongo) {
        val a = company()
        val id = measuredDraft(a)
        val token = a.post("$api/measurements/$id/send").text("approvalToken")!!

        assertEquals("note_required" to "note", answer(token, "DISPUTED", "Ana Costa").let { it.error to it.detail })
        val disputed = answer(token, "DISPUTED", "Ana Costa", note = "A cozinha ainda não está feita")
        assertEquals(HttpStatusCode.OK, disputed.status, disputed.toString())
        assertEquals(listOf("DRAFT", "false"), listOf(disputed.text("status"), disputed.text("awaiting")))
        assertEquals("A cozinha ainda não está feita", disputed.obj["dispute"]!!.jsonObject.str("note"))
        assertEquals("already_decided", answer(token, "APPROVED", "Ana Costa").error, "one answer per sending")

        val seen = a.get("$api/measurements/$id")
        val dispute = seen.obj["dispute"]!!.jsonObject
        assertEquals(listOf("Ana Costa", "A cozinha ainda não está feita", "85.240.1.2"), listOf("name", "note", "ip").map { dispute.str(it) })
        assertEquals("false", seen.text("awaitingClient"))
        val event = DomainEventLog(mongo).timeline(a.tenant.id, SubjectRef(SubjectTypes.MEASUREMENT, id)).first { it.type == DomainEventTypes.MEASUREMENT_DISPUTED }
        assertEquals(ActorType.CLIENT, event.actor.type)

        val resent = a.post("$api/measurements/$id/send")
        val second = resent.text("approvalToken")!!
        assertNotEquals(token, second)
        assertNull(resent.obj["dispute"]?.takeIf { it.toString() != "null" }, "sent again, the dispute is answered")
        assertEquals(HttpStatusCode.NotFound, call("GET", "/app/share/measurement/$token/data", null).status, "the old link ends")

        val line = seen.list("lines").single().str("key")
        val edited = a.patch("$api/measurements/$id") { putJsonArray("lines") { addJsonObject { put("key", line); put("toDateQty", 0.4) } } }
        assertNull(edited.text("approvalToken"), "the client approves what they read: an edit ends the link")
        assertEquals(HttpStatusCode.NotFound, call("GET", "/app/share/measurement/$second/data", null).status)
        assertEquals(HttpStatusCode.NotFound, answer(second, "APPROVED", "Ana Costa").status)
    }

    @Test
    fun `only a measured draft is sent, and a made-up link or a company that isn't active opens nothing`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a)
        val projectId = project(a) {
            put("clientId", clientId)
            putJsonArray("contract") { addJsonObject { put("description", "Moradia"); put("quantity", 1.0); put("unitPriceEur", 1000.0) } }
        }.text("id")!!
        val empty = a.post("$api/measurements") { put("projectId", projectId) }.text("id")!!
        assertEquals("nothing_measured", a.post("$api/measurements/$empty/send").error)
        a.delete("$api/measurements/$empty")

        val id = measuredDraft(a)
        a.post("$api/measurements/$id/approve")
        assertEquals(HttpStatusCode.Conflict to "measurement_locked", a.post("$api/measurements/$id/send").let { it.status to it.error })
        a.post("$api/measurements/$id/reopen")
        val token = a.post("$api/measurements/$id/send").text("approvalToken")
        assertNotNull(token)

        val b = company()
        assertEquals(HttpStatusCode.NotFound, b.post("$api/measurements/$id/send").status, "another company's measurement")
        assertEquals(HttpStatusCode.NotFound, call("GET", "/app/share/measurement/not-a-token/data", null).status)
        tenants.setStatus(a.tenant.slug, TenantStatus.SUSPENDED, Clock.System.now())
        assertEquals(HttpStatusCode.NotFound, call("GET", "/app/share/measurement/$token/data", null).status)
        assertEquals(HttpStatusCode.NotFound, answer(token, "APPROVED", "Ana Costa").status)
    }
}
