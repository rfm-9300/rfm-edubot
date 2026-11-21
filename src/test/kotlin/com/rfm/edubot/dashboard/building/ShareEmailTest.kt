package com.rfm.edubot.dashboard.building

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.ActorType
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.integrations.google.FakeGoogle
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.Reply
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.InternetAddress
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The links people outside the company get, sent by email: who it goes to, what it says, who may send it, and the log. */
class ShareEmailTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("building_share_email")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }

        private const val ORIGIN = "https://app.example.test"
    }

    private val api = BUILDING_API

    private suspend fun DashboardApi.clientWithEmail(company: TestCompany): String =
        company.post("/app/api/crm/clients") {
            put("name", "Ana Costa"); put("phone", "+351 911 000 111"); put("taxId", "1"); put("address", "Rua 1"); put("email", "ana@example.pt")
        }.also { assertEquals(HttpStatusCode.Created, it.status, it.toString()) }.text("id")!!

    private suspend fun DashboardApi.projectFor(company: TestCompany): String {
        val clientId = clientWithEmail(company)
        return project(company) {
            put("clientId", clientId)
            putJsonArray("contract") { addJsonObject { put("description", "Moradia"); put("quantity", 1.0); put("unit", "vg"); put("unitPriceEur", 10000.0) } }
        }.text("id")!!
    }

    /** A 1 850 € skylight sent to its client: the change's id and its link's token. */
    private suspend fun DashboardApi.sentChange(company: TestCompany, projectId: String): Pair<String, String> {
        val change = company.post("$api/changes") {
            put("projectId", projectId); put("title", "Claraboia"); put("daysImpact", 3)
            putJsonArray("items") { addJsonObject { put("description", "Claraboia"); put("quantity", 1.0); put("unit", "un"); put("unitPriceEur", 1850.0) } }
        }.text("id")!!
        return change to company.post("$api/changes/$change/status") { put("status", "SENT") }.text("approvalToken")!!
    }

    private suspend fun DashboardApi.send(company: TestCompany, path: String, to: String?, origin: String = ORIGIN, requestId: String? = null): Reply =
        company.post(path) {
            to?.let { put("to", it) }
            put("origin", origin)
            requestId?.let { put("requestId", it) }
        }

    private suspend fun DashboardApi.draft(company: TestCompany, path: String): Reply = company.get("$path?origin=$ORIGIN")

    /** The plain text of what Gmail was asked to send. */
    private fun FakeGoogle.GmailSend.text(): String {
        fun walk(part: Part): String = when {
            part.isMimeType("text/plain") -> part.content.toString()
            part.isMimeType("multipart/*") -> (part.content as Multipart).let { m -> (0 until m.count).joinToString("\n") { walk(m.getBodyPart(it)) } }
            else -> ""
        }
        return walk(parsed())
    }

    private fun FakeGoogle.GmailSend.recipients() = parsed().getRecipients(Message.RecipientType.TO).map { (it as InternetAddress).address }

    private suspend fun DashboardApi.history(company: TestCompany, type: String, id: String) =
        DomainEventLog(mongo).timeline(company.tenant.id, SubjectRef(type, id)).filter { it.type.endsWith(".link_emailed") }

    @Test
    fun `a sent change is emailed to its client with the link and no money, and the send is in the log`() {
        val google = FakeGoogle()
        dashboardApiTest(mongo, google) {
            val a = company()
            val (change, token) = sentChange(a, projectFor(a))
            val path = "$api/changes/$change/link/email"

            val before = draft(a, path)
            assertEquals(HttpStatusCode.OK, before.status, before.toString())
            assertEquals("ana@example.pt", before.text("to"), "the client's address on record")
            assertEquals("false", before.text("canSend"), "no account to send from yet")
            assertNull(before.text("lastSentAt"))
            val unsent = send(a, path, "ana@example.pt")
            assertEquals(HttpStatusCode.Conflict to "no_email_account", unsent.status to unsent.error, "it says so instead of failing silently")
            assertTrue(google.sends.isEmpty())

            connectGmail(a)
            val ready = draft(a, path)
            assertEquals(listOf("true", "obras@example.pt"), listOf(ready.text("canSend"), ready.text("from")))
            val text = ready.text("text")!!
            assertTrue("$ORIGIN/app/share/change/$token" in text, "the email carries the link")
            assertTrue(text.startsWith("Olá Ana,"), "in the company's language, to the client by first name")
            assertTrue("Claraboia" in text && "Obras Silva" in text, text)
            assertFalse(Regex("1[ .]?850|€").containsMatchIn(ready.text("subject")!! + text), "no money in the email")

            val sent = send(a, path, " Obra@Example.PT ", requestId = "button-0001")
            assertEquals(HttpStatusCode.OK, sent.status, sent.toString())
            assertEquals(listOf("obra@example.pt", "false"), listOf(sent.text("to"), sent.text("alreadySent")), "the recipient was edited")
            assertEquals("true", send(a, path, "obra@example.pt", requestId = "button-0001").text("alreadySent"), "once per press")
            assertEquals(1, google.sends.size)
            assertEquals(listOf("obra@example.pt"), google.sends.single().recipients())
            assertEquals(ready.text("subject"), google.sends.single().parsed().subject)
            assertTrue("$ORIGIN/app/share/change/$token" in google.sends.single().text())

            val events = history(a, SubjectTypes.CHANGE_ORDER, change)
            val event = events.single()
            assertEquals(DomainEventTypes.CHANGE_ORDER_LINK_EMAILED, event.type)
            assertEquals(listOf(ActorType.USER, a.admin.email), listOf(event.actor.type, event.actor.name), "who sent it")
            assertEquals("obra@example.pt", event.payload["to"]?.jsonPrimitive?.contentOrNull)
            val again = draft(a, path)
            assertEquals(listOf("obra@example.pt", a.admin.email), listOf(again.text("lastSentTo"), again.text("lastSentBy")))
            assertTrue(again.text("lastSentAt") != null)
        }
    }

    @Test
    fun `what can't be emailed is refused before anything is sent`() {
        val google = FakeGoogle()
        dashboardApiTest(mongo, google) {
            val a = company()
            connectGmail(a)
            val projectId = projectFor(a)
            val draftChange = a.post("$api/changes") {
                put("projectId", projectId); put("title", "Rufo")
                putJsonArray("items") { addJsonObject { put("description", "Rufo"); put("quantity", 1.0); put("unit", "un"); put("unitPriceEur", 100.0) } }
            }.text("id")!!
            val (change, _) = sentChange(a, projectId)
            val path = "$api/changes/$change/link/email"

            assertEquals(HttpStatusCode.Conflict to "link_off", "$api/changes/$draftChange/link/email".let { send(a, it, "ana@example.pt") }.let { it.status to it.error }, "a draft has no link yet")
            assertEquals("invalid_origin", send(a, path, "ana@example.pt", origin = "javascript:alert(1)").error)
            assertEquals("invalid_origin", send(a, path, "ana@example.pt", origin = "https://evil.test/phish?x=").error, "only a scheme and a host")
            assertEquals("invalid_origin", a.get("$path?origin=nope").error)
            assertEquals("no_email", send(a, path, " ").error)
            val bad = send(a, path, "ana at example")
            assertEquals(HttpStatusCode.BadRequest to "invalid_recipient", bad.status to bad.error)
            assertEquals(HttpStatusCode.BadRequest to "invalid_recipient", send(a, path, "a@example.pt\nBcc: x@example.pt").let { it.status to it.error }, "no header injection")
            assertEquals(HttpStatusCode.NotFound, send(a, "$api/changes/0123456789abcdef01234567/link/email", "ana@example.pt").status)
            val other = company()
            assertEquals(HttpStatusCode.NotFound, send(other, path, "ana@example.pt").status, "another company's change")
            val noChanges = company(DashboardModules.catalog - DashboardModules.CHANGE_ORDERS)
            assertEquals(HttpStatusCode.Forbidden, send(noChanges, path, "ana@example.pt").status, "the link's own module gates it")
            assertEquals(HttpStatusCode.Unauthorized, call("POST", path, null, "{}").status)
            assertTrue(google.sends.isEmpty())

            // A decided change no longer has a link worth sending.
            val decided = a.post("$api/changes/$change/status") { put("status", "APPROVED") }
            assertEquals(HttpStatusCode.OK, decided.status, decided.toString())
            assertEquals("link_off", send(a, path, "ana@example.pt").error)
        }
    }

    @Test
    fun `a measurement, the client's page and a supplier's issues each go by email to the right place`() {
        val google = FakeGoogle()
        dashboardApiTest(mongo, google) {
            val a = company()
            connectGmail(a)
            val projectId = projectFor(a)

            val draftMeasurement = a.post("$api/measurements") { put("projectId", projectId) }
            val measurement = draftMeasurement.text("id")!!
            val line = draftMeasurement.list("lines").single().str("key")
            a.patch("$api/measurements/$measurement") { putJsonArray("lines") { addJsonObject { put("key", line); put("toDateQty", 0.5) } } }
            val measurementPath = "$api/measurements/$measurement/link/email"
            assertEquals("link_off", send(a, measurementPath, "ana@example.pt").error, "not sent to the client yet")
            val token = a.post("$api/measurements/$measurement/send").text("approvalToken")!!
            assertEquals("ana@example.pt", draft(a, measurementPath).text("to"))
            assertEquals(HttpStatusCode.OK, send(a, measurementPath, "ana@example.pt").status)
            val measurementMail = google.sends.last()
            assertTrue("$ORIGIN/app/share/measurement/$token" in measurementMail.text())
            assertTrue(measurementMail.parsed().subject.startsWith("Medição 1"), measurementMail.parsed().subject)
            assertEquals(1, history(a, SubjectTypes.MEASUREMENT, measurement).size)

            val sharePath = "$api/projects/$projectId/share/email"
            assertEquals("link_off", send(a, sharePath, "ana@example.pt").error, "the client's page is off")
            val shareToken = a.post("$api/projects/$projectId/share").text("shareToken")!!
            assertEquals("ana@example.pt", draft(a, sharePath).text("to"))
            assertEquals(HttpStatusCode.OK, send(a, sharePath, "ana@example.pt").status)
            assertTrue("$ORIGIN/app/share/$shareToken" in google.sends.last().text())
            assertEquals(1, history(a, SubjectTypes.PROJECT, projectId).count { it.type == DomainEventTypes.PROJECT_LINK_EMAILED })
            a.delete("$api/projects/$projectId/share")
            assertEquals("link_off", send(a, sharePath, "ana@example.pt").error, "turned off again")

            val supplier = a.post("/app/api/crm/suppliers") { put("name", "Canalizações Lda"); put("phone", "+351 931 000 222") }.text("id")!!
            val supplierPath = "$api/suppliers/$supplier/link/email"
            assertEquals("link_off", send(a, supplierPath, "rui@example.pt").error, "no link yet")
            val issuesToken = a.post("$api/suppliers/$supplier/link").text("token")!!
            val offer = draft(a, supplierPath)
            assertNull(offer.text("to"), "suppliers have no email on record: the team types it")
            assertEquals("no_email", send(a, supplierPath, null).error)
            assertEquals(HttpStatusCode.OK, send(a, supplierPath, "rui@example.pt").status)
            assertTrue("$ORIGIN/app/share/issues/$issuesToken" in google.sends.last().text())
            assertEquals(listOf("rui@example.pt"), google.sends.last().recipients())
            assertEquals(1, history(a, SubjectTypes.SUPPLIER, supplier).count { it.type == DomainEventTypes.SUPPLIER_LINK_EMAILED })
            assertEquals(3, google.sends.size)
        }
    }
}
