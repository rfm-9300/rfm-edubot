package com.rfm.edubot.dashboard.building

import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.Reply
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import com.rfm.edubot.timesheets.zone
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/app/api/building` over HTTP: each page's gate, isolation between companies, the error contract, progress
 * against the plan, the diary moving phases, billing a quote through measurements once, change orders, issues on
 * Home, photos and the client's share page.
 */
class BuildingApiTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("building_api")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }

        private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(600) { 7 }
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(600) { 7 }
    }

    private val api = "/app/api/building"

    /** The company's day [offset] days from today, in its timezone, as the server reads it. */
    private fun TestCompany.day(offset: Int = 0): String =
        Clock.System.now().toLocalDateTime(tenant.zone()).date.plus(offset, DateTimeUnit.DAY).toString()

    private suspend fun DashboardApi.client(company: TestCompany, name: String = "Casa Silva"): String =
        company.post("/app/api/crm/clients") {
            put("name", name); put("phone", "+351 91${(1..7).map { (0..9).random() }.joinToString("")}"); put("taxId", "1"); put("address", "Rua 1")
        }.text("id")!!

    private suspend fun DashboardApi.project(company: TestCompany, body: JsonObjectBuilder.() -> Unit = {}): Reply =
        company.post("$api/projects") { put("name", "Moradia Silva"); body() }

    /** Two phases: ten days that ended ten days ago, finished, and twenty days around today at [second]%. */
    private suspend fun DashboardApi.twoPhases(company: TestCompany, projectId: String, second: Int = 20): Reply =
        company.put("$api/projects/$projectId/phases") {
            putJsonArray("phases") {
                addJsonObject { put("name", "Fundações"); put("plannedStart", company.day(-20)); put("plannedEnd", company.day(-11)); put("progressPct", 100) }
                addJsonObject { put("name", "Estrutura"); put("plannedStart", company.day(-10)); put("plannedEnd", company.day(9)); put("progressPct", second) }
            }
        }

    /** A quote of 100 m² of walls at 25 € and a roof at 8 000 €: 10 500 €. */
    private suspend fun DashboardApi.quote(company: TestCompany, clientId: String): String =
        company.post("/app/api/crm/quotes") {
            put("clientId", clientId)
            putJsonArray("items") {
                addJsonObject { put("description", "Paredes"); put("quantity", 100.0); put("unit", "m2"); put("unitPriceEur", 25.0) }
                addJsonObject { put("description", "Cobertura"); put("quantity", 1.0); put("unit", "vg"); put("unitPriceEur", 8000.0) }
            }
        }.also { assertEquals(HttpStatusCode.Created, it.status, it.toString()) }.text("id")!!

    private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(name: String): Int = this[name]!!.jsonPrimitive.int
    private fun JsonObject.eur(name: String): Double = this[name]!!.jsonPrimitive.double
    private fun JsonObject.list(name: String): List<JsonObject> = this[name]!!.jsonArray.map { it.jsonObject }
    private fun Reply.int(name: String): Int = obj.int(name)
    private fun Reply.eur(name: String): Double = obj.eur(name)
    private fun Reply.list(name: String): List<JsonObject> = obj.list(name)

    @Test
    fun `each Building page answers 403 without its module, and every one brings projects`() = dashboardApiTest(mongo) {
        val off = company(listOf(DashboardModules.CLIENTS, DashboardModules.QUOTES, DashboardModules.INVOICES))
        for (path in listOf("projects", "diary", "issues", "changes", "measurements")) {
            assertEquals(HttpStatusCode.Forbidden, off.get("$api/$path").status, path)
            assertEquals(HttpStatusCode.Forbidden, off.post("$api/$path") { put("projectId", ObjectId().toHexString()) }.status, path)
        }
        val diaryOnly = company(listOf(DashboardModules.SITE_DIARY))
        assertEquals(HttpStatusCode.OK, diaryOnly.get("$api/projects").status, "the diary brings the projects it is written for")
        assertEquals(HttpStatusCode.OK, diaryOnly.get("$api/diary").status)
        assertEquals(HttpStatusCode.Forbidden, diaryOnly.get("$api/issues").status)
        val noInvoices = company(listOf(DashboardModules.MEASUREMENTS))
        assertEquals(HttpStatusCode.Forbidden, noInvoices.post("$api/measurements/${ObjectId()}/invoice") { put("dueDate", noInvoices.day(30)) }.status)
        assertEquals(HttpStatusCode.NotFound, noInvoices.get("$api/measurements/${ObjectId()}").status)
    }

    @Test
    fun `progress weighs the phases, compares them with the plan of yesterday, and the diary moves them`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a)
        val created = project(a) { put("clientId", clientId); put("status", "ACTIVE") }
        assertEquals(HttpStatusCode.Created, created.status, created.toString())
        val id = created.text("id")!!
        assertEquals(a.day(), created.text("actualStart"), "starting the work dates it")

        val planned = twoPhases(a, id)
        assertEquals(47, planned.int("progressPct"), "10 days done and 20 days at 20%")
        assertEquals(67, planned.int("plannedPct"), "the first phase and half of the second, by the end of yesterday")
        assertEquals(-6, planned.int("scheduleDays"))
        assertEquals("AT_RISK", planned.text("health"))
        assertEquals(1, planned.int("latePhases"), "30 points behind its plan")
        assertEquals(a.day(9), planned.text("plannedEnd"))
        val second = planned.list("phases")[1]
        assertEquals("LATE", second.str("state"))

        val entry = a.post("$api/diary") {
            put("projectId", id)
            put("workDone", "Pilares do 1.º piso")
            put("weather", "CLEAR")
            put("workers", 5)
            put("shared", true)
            putJsonArray("phaseProgress") { addJsonObject { put("phaseId", second.str("id")); put("progressPct", 60) } }
        }
        assertEquals(HttpStatusCode.Created, entry.status, entry.toString())
        assertEquals(a.day(), entry.text("day"))
        val moved = entry.list("progress").single()
        assertEquals(listOf(20, 60), listOf(moved.int("fromPct"), moved.int("toPct")))

        val after = a.get("$api/projects/$id")
        assertEquals(73, after.int("progressPct"))
        assertEquals(2, after.int("scheduleDays"))
        assertEquals("ON_TRACK", after.text("health"))
        assertEquals(0, after.int("latePhases"))
        assertEquals(a.day(), after.text("lastDiaryDay"))

        a.post("$api/diary") { put("projectId", id); put("workDone", "Outra vez") }.let {
            assertEquals(HttpStatusCode.Conflict, it.status)
            assertEquals("day_taken", it.error)
            assertEquals(entry.text("id"), it.detail, "the entry already written that day")
        }
        assertEquals("future_day", a.post("$api/diary") { put("projectId", id); put("day", a.day(1)); put("workDone", "Amanhã") }.error)
        a.post("$api/diary") { put("projectId", id); put("day", "ontem"); put("workDone", "x") }.let {
            assertEquals("invalid_date", it.error)
            assertEquals("day", it.detail)
        }
        assertEquals("work_required", a.post("$api/diary") { put("projectId", id); put("day", a.day(-1)) }.error)

        val edited = a.patch("$api/diary/${entry.text("id")}") {
            putJsonArray("phaseProgress") { addJsonObject { put("phaseId", second.str("id")); put("progressPct", 70) } }
            putJsonArray("clear") { add("workers") }
        }
        assertEquals(HttpStatusCode.OK, edited.status, edited.toString())
        assertNull(edited.obj["workers"]?.jsonPrimitive?.contentOrNull)
        val recorded = edited.list("progress").single()
        assertEquals(listOf(20, 70), listOf(recorded.int("fromPct"), recorded.int("toPct")), "the entry keeps where the phase was before it")
        assertEquals(70, a.get("$api/projects/$id").list("phases")[1].int("progressPct"))
        assertEquals(listOf(entry.text("id")), a.get("$api/diary?projectId=$id").ids())
        assertEquals("invalid_range", a.get("$api/diary?from=${a.day()}&to=${a.day(-3)}").error)
    }

    @Test
    fun `a project from a quote takes its contract, and its measurements bill the quote once`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a)
        val quoteId = quote(a, clientId)
        val created = a.post("$api/projects") { put("quoteId", quoteId) }
        assertEquals(HttpStatusCode.Created, created.status, created.toString())
        val id = created.text("id")!!
        assertEquals("Paredes + Cobertura", created.text("name"), "named after the quote's lines")
        assertEquals(10500.0, created.eur("contractEur"))
        assertEquals(clientId, created.text("clientId"))
        assertEquals(listOf("Paredes", "Cobertura"), created.list("contract").map { it.str("description") })
        assertEquals("ACEITO", a.get("/app/api/crm/quotes/$quoteId").text("status"), "the quote turns accepted")
        assertEquals("quote_in_project", project(a) { put("quoteId", quoteId) }.error)
        assertEquals(listOf(id), a.get("$api/projects?quoteId=$quoteId").ids())
        assertEquals(5.0, a.patch("$api/projects/$id") { put("retentionPct", 5.0) }.eur("retentionPct"))

        val draft = a.post("$api/measurements") { put("projectId", id) }
        assertEquals(HttpStatusCode.Created, draft.status, draft.toString())
        assertEquals(id, a.get("/app/api/crm/quotes/$quoteId").text("projectId"), "the quote is billed through the project now")
        assertEquals("already_measured", a.post("/app/api/crm/quotes/$quoteId/invoice") { put("dueDate", a.day(30)) }.error)
        assertEquals("already_measured", a.post("/app/api/crm/quotes/$quoteId/service").error)
        a.post("$api/measurements") { put("projectId", id) }.let {
            assertEquals("measurement_open", it.error)
            assertEquals(draft.text("id"), it.detail)
        }

        val (walls, roof) = draft.list("lines").map { it.str("key")!! }
        val mId = draft.text("id")!!
        a.patch("$api/measurements/$mId") { putJsonArray("lines") { addJsonObject { put("key", walls); put("toDateQty", 140.0) } } }.let {
            assertEquals("above_contract", it.error)
            assertEquals(walls, it.detail, "the line the quantity is for")
        }
        val measured = a.patch("$api/measurements/$mId") {
            putJsonArray("lines") {
                addJsonObject { put("key", walls); put("toDateQty", 40.0) }
                addJsonObject { put("key", roof); put("toDateQty", 0.5) }
            }
        }
        assertEquals(5000.0, measured.eur("grossEur"))
        assertEquals(250.0, measured.eur("retentionEur"))
        assertEquals(4750.0, measured.eur("netEur"))
        assertEquals("contract_locked", a.patch("$api/projects/$id") {
            putJsonArray("contract") { addJsonObject { put("description", "Tudo"); put("quantity", 1.0); put("unitPriceEur", 1.0) } }
        }.error)
        assertEquals("not_approved", a.post("$api/measurements/$mId/invoice") { put("dueDate", a.day(30)) }.error)
        assertEquals("APPROVED", a.post("$api/measurements/$mId/approve").text("status"))

        val home = a.get("/app/api/overview").obj
        val card = home["projects"]!!.jsonObject
        assertEquals(1, card.int("toInvoiceCount"))
        assertEquals(475000, card.int("toInvoiceCents"))
        assertTrue(home.list("attention").any { it.str("kind") == "measurement_to_invoice" && it.str("id") == mId })

        val invoiced = a.post("$api/measurements/$mId/invoice") { put("dueDate", a.day(30)) }
        assertEquals(HttpStatusCode.Created, invoiced.status, invoiced.toString())
        val invoiceId = invoiced.text("invoiceId")!!
        assertEquals("INVOICED", invoiced.obj["measurement"]!!.jsonObject.str("status"))
        val invoice = a.get("/app/api/crm/invoices/$invoiceId")
        assertEquals(4750.0, invoice.eur("totalEur"))
        assertEquals(id, invoice.text("projectId"))
        assertEquals("${created.text("number")} · Paredes + Cobertura", invoice.text("projectLabel"))
        assertEquals(1, invoice.int("measurementNumber"))
        assertEquals(
            listOf("Auto n.º 1 · Paredes", "Auto n.º 1 · Cobertura", "Retenção de garantia (5 %)"),
            invoice.list("items").map { it.str("description") },
        )
        assertEquals("already_invoiced", a.post("$api/measurements/$mId/invoice") { put("dueDate", a.day(30)) }.error)
        assertEquals("measurement_invoiced", a.delete("$api/measurements/$mId").error)

        assertEquals("CANCELLED", a.post("/app/api/crm/invoices/$invoiceId/cancel").text("status"))
        val released = a.get("$api/measurements/$mId")
        assertEquals("APPROVED", released.text("status"), "cancelling the invoice frees the measurement")
        assertNull(released.text("invoiceId"))
        val again = a.post("$api/measurements/$mId/invoice") { put("dueDate", a.day(30)) }
        assertNotEquals(invoiceId, again.text("invoiceId"))

        val next = a.post("$api/measurements") { put("projectId", id) }
        assertEquals(2, next.int("number"))
        assertEquals(listOf(40.0, 0.5), next.list("lines").map { it.eur("previousQty") }, "a measurement starts from what the one before measured")
        assertEquals("below_previous", a.patch("$api/measurements/${next.text("id")}") {
            putJsonArray("lines") { addJsonObject { put("key", walls); put("toDateQty", 30.0) } }
        }.error)
        assertEquals("in_use", a.delete("$api/projects/$id").error)
        assertEquals(HttpStatusCode.OK, a.delete("$api/measurements/${next.text("id")}").status)
    }

    @Test
    fun `removing a project's only measurement frees its quote, and a quote billed first can't be measured`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a)
        val quoteId = quote(a, clientId)
        val id = project(a) { put("quoteId", quoteId) }.text("id")!!
        val draft = a.post("$api/measurements") { put("projectId", id) }.text("id")!!
        assertEquals(id, a.get("/app/api/crm/quotes/$quoteId").text("projectId"))
        assertEquals(HttpStatusCode.OK, a.delete("$api/measurements/$draft").status)
        assertNull(a.get("/app/api/crm/quotes/$quoteId").text("projectId"))

        val billed = quote(a, clientId)
        assertEquals(HttpStatusCode.Created, a.post("/app/api/crm/quotes/$billed/invoice") { put("dueDate", a.day(30)) }.status)
        val other = project(a) { put("quoteId", billed) }.text("id")!!
        assertEquals("quote_billed", a.post("$api/measurements") { put("projectId", other) }.error)
    }

    @Test
    fun `approved change orders add to the contract, the end and the next measurement`() = dashboardApiTest(mongo) {
        val a = company()
        val id = project(a) {
            put("status", "ACTIVE")
            putJsonArray("contract") { addJsonObject { put("description", "Moradia"); put("quantity", 1.0); put("unitPriceEur", 100000.0) } }
        }.text("id")!!
        twoPhases(a, id)
        val change = a.post("$api/changes") {
            put("projectId", id)
            put("title", "Muro do jardim")
            put("daysImpact", 5)
            putJsonArray("items") { addJsonObject { put("description", "Muro"); put("quantity", 10.0); put("unit", "m"); put("unitPriceEur", 50.0) } }
        }
        assertEquals(HttpStatusCode.Created, change.status, change.toString())
        val changeId = change.text("id")!!
        assertEquals("DRAFT", change.text("status"))
        assertEquals("items_required", a.post("$api/changes") { put("projectId", id); put("title", "Nada") }.error)
        assertEquals("SENT", a.post("$api/changes/$changeId/status") { put("status", "SENT") }.text("status"))
        a.post("$api/changes/$changeId/status") { put("status", "MAYBE") }.let {
            assertEquals("invalid_status", it.error)
            assertEquals("status", it.detail)
        }
        val approved = a.post("$api/changes/$changeId/status") { put("status", "APPROVED"); put("decidedBy", "Sr. Silva") }
        assertEquals("Sr. Silva", approved.text("decidedBy"))
        assertEquals("change_locked", a.patch("$api/changes/$changeId") { put("title", "Outro") }.error)
        assertEquals("invalid_transition", a.post("$api/changes/$changeId/status") { put("status", "SENT") }.error)

        val project = a.get("$api/projects/$id")
        assertEquals(500.0, project.eur("changesEur"))
        assertEquals(100500.0, project.eur("currentContractEur"))
        assertEquals(a.day(14), project.text("revisedEnd"), "five more days")

        val measurement = a.post("$api/measurements") { put("projectId", id) }
        val added = measurement.list("lines").single { it.str("key")!!.startsWith("co:$changeId:") }
        a.patch("$api/measurements/${measurement.text("id")}") { putJsonArray("lines") { addJsonObject { put("key", added.str("key")); put("toDateQty", 10.0) } } }
        assertEquals("change_measured", a.post("$api/changes/$changeId/status") { put("status", "DRAFT") }.error)
        assertEquals("change_approved", a.delete("$api/changes/$changeId").error)
    }

    @Test
    fun `issues keep their history, and overdue ones and delayed projects reach Home`() = dashboardApiTest(mongo) {
        val a = company()
        val id = project(a) { put("status", "ACTIVE") }.text("id")!!
        val issue = a.post("$api/issues") { put("projectId", id); put("title", "Fissura na parede"); put("priority", "HIGH"); put("dueDate", a.day(-2)) }
        assertEquals(HttpStatusCode.Created, issue.status, issue.toString())
        assertEquals("OPEN", issue.text("status"))
        assertEquals("true", issue.obj["overdue"]!!.jsonPrimitive.content)
        val issueId = issue.text("id")!!

        val employee = EmployeeRepository(mongo, a.tenant.id).create("Rui Costa", "+351 930 000 002")
        val started = a.patch("$api/issues/$issueId") { put("status", "IN_PROGRESS"); put("note", "Pedreiro chamado"); put("employeeId", employee.id.toHexString()) }
        assertEquals("Rui Costa", started.text("assigneeName"))
        val history = started.list("history")
        assertEquals(listOf(null, "OPEN"), history.map { it.str("from") })
        assertEquals("Pedreiro chamado", history.last().str("note"))

        val late = project(a) { put("name", "Armazém"); put("status", "ACTIVE") }.text("id")!!
        a.put("$api/projects/$late/phases") {
            putJsonArray("phases") { addJsonObject { put("name", "Tudo"); put("plannedStart", a.day(-30)); put("plannedEnd", a.day(-5)); put("progressPct", 10) } }
        }
        val home = a.get("/app/api/overview").obj
        val card = home["projects"]!!.jsonObject
        assertEquals(1, card.int("overdueIssues"))
        assertEquals(1, card.int("delayed"))
        val kinds = home.list("attention").map { it.str("kind") to it.str("id") }
        assertTrue("site_issue_overdue" to issueId in kinds, kinds.toString())
        assertTrue("project_delayed" to late in kinds, kinds.toString())

        val resolved = a.patch("$api/issues/$issueId") { put("status", "RESOLVED") }
        assertNotNull(resolved.text("resolvedAt"))
        assertEquals("false", resolved.obj["overdue"]!!.jsonPrimitive.contentOrNull)
        assertEquals(listOf(issueId), a.get("$api/issues?status=RESOLVED&projectId=$id").ids())
        assertEquals("invalid_status", a.get("$api/issues?status=LATE").error)
    }

    @Test
    fun `photos are JPEG, and the client's page shows only what the team shared`() = dashboardApiTest(mongo) {
        val a = company()
        val id = project(a) { put("status", "ACTIVE"); put("address", "Rua das Flores 1") }.text("id")!!
        val shared = a.upload("$api/projects/$id/photos", "obra.jpg", JPEG, mapOf("caption" to "Fachada"))
        assertEquals(HttpStatusCode.Created, shared.status, shared.toString())
        assertEquals("true", shared.obj["shared"]!!.jsonPrimitive.content, "a photo of the site is on the client's page")
        val issueId = a.post("$api/issues") { put("projectId", id); put("title", "Mancha") }.text("id")!!
        val inside = a.upload("$api/projects/$id/photos", "mancha.jpg", JPEG, mapOf("issueId" to issueId))
        assertEquals("false", inside.obj["shared"]!!.jsonPrimitive.content, "a photo of an issue stays inside the team")
        a.upload("$api/projects/$id/photos", "plano.png", PNG).let {
            assertEquals(HttpStatusCode.UnsupportedMediaType, it.status)
            assertEquals("photo_type", it.error)
        }
        assertEquals("log_not_found", a.upload("$api/projects/$id/photos", "x.jpg", JPEG, mapOf("logId" to ObjectId().toHexString())).error)
        assertEquals(HttpStatusCode.OK, a.get("$api/photos/${shared.text("id")}?size=thumb").status)
        assertEquals(2, a.get("$api/projects/$id").int("photoCount"))
        a.post("$api/diary") { put("projectId", id); put("workDone", "Reboco"); put("shared", true) }
        a.post("$api/diary") { put("projectId", id); put("day", a.day(-1)); put("workDone", "Nota interna") }

        val token = a.post("$api/projects/$id/share").text("shareToken")!!
        assertEquals(HttpStatusCode.OK, call("GET", "/app/share/$token", null).status)
        val page = call("GET", "/app/share/$token/data", null)
        assertEquals(HttpStatusCode.OK, page.status, page.toString())
        assertEquals(listOf(shared.text("id")), page.list("photos").map { it.str("id") })
        assertEquals(listOf("Reboco"), page.list("updates").map { it.str("workDone") })
        for (hidden in listOf("contractEur", "clientName", "clientPhone", "openIssues", "health", "shareToken")) assertFalse(hidden in page.obj, hidden)
        assertEquals(HttpStatusCode.OK, call("GET", "/app/share/$token/photos/${shared.text("id")}", null).status)
        assertEquals(HttpStatusCode.NotFound, call("GET", "/app/share/$token/photos/${inside.text("id")}", null).status)

        val rotated = a.post("$api/projects/$id/share/rotate").text("shareToken")!!
        assertEquals(HttpStatusCode.NotFound, call("GET", "/app/share/$token/data", null).status, "the old link stops working")
        assertEquals(HttpStatusCode.OK, call("GET", "/app/share/$rotated/data", null).status)
        assertNull(a.delete("$api/projects/$id/share").text("shareToken"))
        assertEquals(HttpStatusCode.NotFound, call("GET", "/app/share/$rotated/data", null).status)
    }

    @Test
    fun `a company never reads, changes or lists another company's Building records`() = dashboardApiTest(mongo) {
        val a = company()
        val b = company()
        val id = project(a) { put("status", "ACTIVE") }.text("id")!!
        twoPhases(a, id)
        val entry = a.post("$api/diary") { put("projectId", id); put("workDone", "Betão") }.text("id")!!
        val issue = a.post("$api/issues") { put("projectId", id); put("title", "Fuga") }.text("id")!!
        val change = a.post("$api/changes") {
            put("projectId", id); put("title", "Extra")
            putJsonArray("items") { addJsonObject { put("description", "Extra"); put("unitPriceEur", 10.0) } }
        }.text("id")!!
        val photo = a.upload("$api/projects/$id/photos", "obra.jpg", JPEG).text("id")!!

        assertEquals(HttpStatusCode.NotFound, b.get("$api/projects/$id").status)
        assertEquals(HttpStatusCode.NotFound, b.patch("$api/projects/$id") { put("name", "Minha") }.status)
        assertEquals(HttpStatusCode.NotFound, b.delete("$api/projects/$id").status)
        assertEquals(HttpStatusCode.NotFound, b.post("$api/projects/$id/share").status)
        assertEquals(HttpStatusCode.NotFound, b.get("$api/diary/$entry").status)
        assertEquals(HttpStatusCode.NotFound, b.patch("$api/diary/$entry") { put("workDone", "x") }.status)
        assertEquals(HttpStatusCode.NotFound, b.get("$api/issues/$issue").status)
        assertEquals(HttpStatusCode.NotFound, b.delete("$api/issues/$issue").status)
        assertEquals(HttpStatusCode.NotFound, b.post("$api/changes/$change/status") { put("status", "APPROVED") }.status)
        assertEquals(HttpStatusCode.NotFound, b.get("$api/photos/$photo").status)
        assertEquals(HttpStatusCode.NotFound, b.delete("$api/photos/$photo").status)
        assertEquals("project_not_found", b.post("$api/diary") { put("projectId", id); put("workDone", "x") }.error)
        assertEquals("project_not_found", b.post("$api/measurements") { put("projectId", id) }.error)
        for (path in listOf("projects", "diary", "issues", "changes", "measurements")) assertTrue(b.get("$api/$path").array.isEmpty(), path)

        val kept = a.get("$api/projects/$id")
        assertEquals("Moradia Silva", kept.text("name"))
        assertEquals("DRAFT", a.get("$api/changes/$change").text("status"))
        assertEquals(HttpStatusCode.OK, a.get("$api/photos/$photo").status)
    }

    @Test
    fun `Building answers the error contract for what it can't read, and only an admin deletes a project`() = dashboardApiTest(mongo) {
        val a = company()
        val id = project(a).text("id")!!
        assertEquals("invalid_body", a.sendMalformed("POST", "$api/projects").error)
        assertEquals("invalid_id", a.get("$api/projects/nope").error)
        a.post("$api/diary") { put("projectId", "nope"); put("workDone", "x") }.let {
            assertEquals("invalid_id", it.error)
            assertEquals("projectId", it.detail)
        }
        a.get("$api/diary?from=ontem").let {
            assertEquals("invalid_date", it.error)
            assertEquals("from", it.detail)
        }
        assertEquals("invalid_id", a.get("$api/measurements?projectId=nope").error)
        assertEquals("name_required", a.post("$api/projects") { put("name", " ") }.error)
        assertEquals("invalid_dates", a.patch("$api/projects/$id") { put("plannedStart", a.day(5)); put("plannedEnd", a.day()) }.error)
        assertEquals("invalid_retention", a.patch("$api/projects/$id") { put("retentionPct", 30.0) }.error)
        assertEquals("phase_not_found", a.patch("$api/projects/$id/phases/nope") { put("progressPct", 10) }.error)
        assertEquals("nothing_to_measure", a.post("$api/measurements") { put("projectId", id) }.error)
        call("DELETE", "$api/projects/$id", a.memberToken).let {
            assertEquals(HttpStatusCode.Forbidden, it.status)
            assertEquals("not_allowed", it.error)
        }
        assertEquals(HttpStatusCode.OK, a.delete("$api/projects/$id").status)
        assertEquals(HttpStatusCode.NotFound, a.get("$api/projects/$id").status)
    }
}
