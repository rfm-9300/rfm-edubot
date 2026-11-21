package com.rfm.edubot.dashboard.building

import com.rfm.edubot.events.ActorType
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A supplier's link to the site issues assigned to them: what it shows, what they can say from it, and when it ends. */
class SupplierIssueLinkTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("building_supplier_links")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val api = BUILDING_API

    private suspend fun DashboardApi.supplier(company: TestCompany, name: String): String =
        company.post("/app/api/crm/suppliers") { put("name", name); put("phone", "+351 93${(1..7).map { (0..9).random() }.joinToString("")}") }
            .also { assertEquals(HttpStatusCode.Created, it.status, it.toString()) }.text("id")!!

    private suspend fun DashboardApi.issue(company: TestCompany, projectId: String, title: String, supplierId: String? = null): String =
        company.post("$api/issues") { put("projectId", projectId); put("title", title); supplierId?.let { put("supplierId", it) } }.text("id")!!

    private suspend fun DashboardApi.move(token: String, issueId: String, status: String, name: String, note: String? = null) =
        call("POST", "/app/share/issues/$token/issues/$issueId/status", null, buildString {
            append("""{"status":"$status","name":"$name"""")
            if (note != null) append(""","note":"$note"""")
            append("}")
        }, headers = mapOf("X-Forwarded-For" to "94.60.0.7"))

    @Test
    fun `a supplier sees only their issues and starts and fixes them from their link, in their own name`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a, name = "Ana Costa")
        val projectId = project(a) { put("clientId", clientId); put("address", "Rua das Flores 1") }.text("id")!!
        val plumber = supplier(a, "Canalizações Lda")
        val painter = supplier(a, "Pinturas Lda")
        val leak = issue(a, projectId, "Fuga na casa de banho", plumber)
        val wall = issue(a, projectId, "Parede por pintar", painter)
        issue(a, projectId, "Rodapé solto")

        assertNull(a.get("$api/suppliers/$plumber/link").text("token"), "no link until the team makes one")
        val token = a.post("$api/suppliers/$plumber/link").text("token")!!
        assertTrue(token.length >= 43, "a link nobody can guess")
        assertEquals(token, a.post("$api/suppliers/$plumber/link").text("token"), "making it again keeps the link")
        assertEquals(token, a.get("$api/issues/$leak").text("supplierLinkToken"), "the issue's record shows its supplier's link")

        assertEquals(HttpStatusCode.OK, call("GET", "/app/share/issues/$token", null).status)
        val page = call("GET", "/app/share/issues/$token/data", null)
        assertEquals(HttpStatusCode.OK, page.status, page.toString())
        assertEquals("Canalizações Lda", page.text("supplier"))
        val shown = page.list("issues")
        assertEquals(listOf(leak), shown.map { it.str("id") }, "only the plumber's issue")
        assertEquals("Rua das Flores 1", shown.single().str("address"))
        assertFalse(page.body.contains("Ana Costa"), "never the client")
        assertFalse(page.body.contains("Parede por pintar"), "never another supplier's issue")

        assertEquals("name_required" to "name", move(token, leak, "IN_PROGRESS", " ").let { it.error to it.detail })
        assertEquals("invalid_status", move(token, leak, "CLOSED", "Rui").error, "closing stays with the team")
        assertEquals(HttpStatusCode.NotFound, move(token, wall, "RESOLVED", "Rui").status, "another supplier's issue isn't found")
        val started = move(token, leak, "IN_PROGRESS", "Rui")
        assertEquals(HttpStatusCode.OK, started.status, started.toString())
        assertEquals("IN_PROGRESS", started.list("issues").single().str("status"))
        val fixed = move(token, leak, "RESOLVED", "Rui", note = "Trocado o sifão")
        assertEquals(listOf("RESOLVED"), fixed.list("issues").map { it.str("status") }, "a fixed issue stays in view")
        assertEquals(listOf("IN_PROGRESS", "RESOLVED"), fixed.list("issues").single().list("answers").map { it.str("to") })
        assertEquals(HttpStatusCode.Conflict to "invalid_transition", move(token, leak, "RESOLVED", "Rui").let { it.status to it.error })

        val seen = a.get("$api/issues/$leak")
        assertEquals("RESOLVED", seen.text("status"))
        val step = seen.list("history").last()
        assertEquals(listOf("Rui · Canalizações Lda", "SUPPLIER_LINK", "94.60.0.7", "Trocado o sifão"), listOf("by", "via", "ip", "note").map { step.str(it) })
        val events = DomainEventLog(mongo).timeline(a.tenant.id, SubjectRef(SubjectTypes.SITE_ISSUE, leak)).filter { it.type == DomainEventTypes.SITE_ISSUE_STATUS_CHANGED }
        assertTrue(events.all { it.actor.type == ActorType.SUPPLIER && it.payload["via"]?.jsonPrimitive?.contentOrNull == "SUPPLIER_LINK" }, events.toString())
        val bell = a.get("/app/api/notifications", a.memberToken).list("items").filter { it.str("kind") == "site_issue_supplier" }
        assertEquals(listOf("RESOLVED", "IN_PROGRESS"), bell.map { it["params"]!!.jsonObject.str("to") })
    }

    @Test
    fun `a supplier adds photos to their open issue, which stay inside the team, and sees only those`() = dashboardApiTest(mongo) {
        val a = company()
        val projectId = project(a).text("id")!!
        val plumber = supplier(a, "Canalizações Lda")
        val leak = issue(a, projectId, "Fuga", plumber)
        val other = issue(a, projectId, "Outra")
        val token = a.post("$api/suppliers/$plumber/link").text("token")!!

        val added = publicUpload("/app/share/issues/$token/issues/$leak/photos", "obra.jpg", JPEG_BYTES, mapOf("name" to "Rui"))
        assertEquals(HttpStatusCode.Created, added.status, added.toString())
        val photo = added.list("issues").single().list("photos").single().str("id")!!
        val teamPhoto = a.get("$api/issues/$leak").list("photos").single()
        assertEquals(listOf("false", "Rui · Canalizações Lda"), listOf(teamPhoto.str("shared"), teamPhoto.str("uploadedBy")), "not on the client's page")
        assertEquals(HttpStatusCode.OK, publicDownload("/app/share/issues/$token/photos/$photo?size=thumb").status)

        val theirs = a.upload("$api/projects/$projectId/photos", "x.jpg", JPEG_BYTES, mapOf("issueId" to other)).text("id")!!
        assertEquals(HttpStatusCode.NotFound, publicDownload("/app/share/issues/$token/photos/$theirs").status, "a photo of an issue that isn't theirs")
        assertEquals(HttpStatusCode.NotFound, publicUpload("/app/share/issues/$token/issues/$other/photos", "obra.jpg", JPEG_BYTES).status)
        assertEquals(HttpStatusCode.UnsupportedMediaType, publicUpload("/app/share/issues/$token/issues/$leak/photos", "obra.png", PNG_BYTES).status)
    }

    @Test
    fun `renewing or turning the link off ends the old one, and another company's links open nothing`() = dashboardApiTest(mongo) {
        val a = company()
        val projectId = project(a).text("id")!!
        val plumber = supplier(a, "Canalizações Lda")
        val leak = issue(a, projectId, "Fuga", plumber)
        val first = a.post("$api/suppliers/$plumber/link").text("token")!!
        val second = a.post("$api/suppliers/$plumber/link/rotate").text("token")!!
        assertNotEquals(first, second)
        assertEquals(HttpStatusCode.NotFound, call("GET", "/app/share/issues/$first/data", null).status)
        assertEquals(HttpStatusCode.OK, call("GET", "/app/share/issues/$second/data", null).status)

        val b = company()
        assertEquals(HttpStatusCode.NotFound, b.post("$api/suppliers/$plumber/link").status, "another company's supplier")
        assertEquals(HttpStatusCode.NotFound, b.get("$api/suppliers/$plumber/link").status)

        a.delete("$api/suppliers/$plumber/link")
        assertEquals(HttpStatusCode.NotFound, call("GET", "/app/share/issues/$second/data", null).status)
        assertEquals(HttpStatusCode.NotFound, move(second, leak, "RESOLVED", "Rui").status)
        assertNull(a.get("$api/issues/$leak").text("supplierLinkToken"))

        val third = a.post("$api/suppliers/$plumber/link").text("token")!!
        a.post("/app/api/crm/suppliers/$plumber/archive")
        assertEquals(HttpStatusCode.NotFound, call("GET", "/app/share/issues/$third/data", null).status, "an archived supplier's link stops")
        val changes = DomainEventLog(mongo).timeline(a.tenant.id, SubjectRef(SubjectTypes.SUPPLIER, plumber)).filter { it.type == DomainEventTypes.SUPPLIER_LINK_CHANGED }
        assertEquals(listOf("on", "off", "rotated", "on"), changes.map { it.payload["action"]?.jsonPrimitive?.contentOrNull })
    }
}
