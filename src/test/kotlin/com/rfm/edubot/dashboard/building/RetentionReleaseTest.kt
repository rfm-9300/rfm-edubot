package com.rfm.edubot.dashboard.building

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The retention a finished project's measurements kept, invoiced back to the client, and held again when that invoice goes. */
class RetentionReleaseTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("building_retention")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val api = BUILDING_API

    @Test
    fun `a finished project invoices the retention its measurements kept, once, until that invoice goes`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a)
        val created = project(a) {
            put("clientId", clientId); put("status", "ACTIVE"); put("retentionPct", 5.0)
            putJsonArray("contract") { addJsonObject { put("description", "Moradia"); put("quantity", 1.0); put("unit", "vg"); put("unitPriceEur", 10000.0) } }
        }
        val id = created.text("id")!!
        val retention = "$api/projects/$id/retention/invoice"
        val measurement = a.post("$api/measurements") { put("projectId", id) }
        val mId = measurement.text("id")!!
        a.patch("$api/measurements/$mId") { putJsonArray("lines") { addJsonObject { put("key", measurement.list("lines").single().str("key")); put("toDateQty", 0.5) } } }
        a.post("$api/measurements/$mId/approve")

        assertEquals("project_not_completed", a.post(retention) { put("dueDate", a.day(30)) }.error)
        a.patch("$api/projects/$id") { put("status", "COMPLETED") }
        val pending = a.post(retention) { put("dueDate", a.day(30)) }
        assertEquals(HttpStatusCode.Conflict, pending.status)
        assertEquals("measurement_pending" to mId, pending.error to pending.detail, "a measurement still to invoice would hold its retention after the release")
        a.post("$api/measurements/$mId/invoice") { put("dueDate", a.day(30)) }
        val before = a.get("$api/projects/$id")
        assertEquals(250.0 to 0.0, before.eur("retentionHeldEur") to before.eur("retentionReleasedEur"))

        val released = a.post(retention) { put("dueDate", a.day(30)) }
        assertEquals(HttpStatusCode.Created, released.status, released.toString())
        val invoiceId = released.text("invoiceId")!!
        val after = released.obj["project"]!!.jsonObject
        assertEquals(0.0 to 250.0, after.eur("retentionHeldEur") to after.eur("retentionReleasedEur"))
        assertEquals(listOf(released.text("invoiceNumber")), after.list("retentionReleases").map { it.str("invoiceNumber") })
        val invoice = a.get("/app/api/crm/invoices/$invoiceId")
        assertEquals(250.0, invoice.eur("totalEur"))
        assertEquals(clientId to id, invoice.text("clientId") to invoice.text("projectId"))
        assertEquals(listOf("Devolução da retenção de garantia · ${created.text("number")} · Moradia Silva"), invoice.list("items").map { it.str("description") })
        assertTrue(invoice.obj["retentionRelease"]!!.jsonPrimitive.boolean)
        assertEquals("nothing_held", a.post(retention) { put("dueDate", a.day(30)) }.error, "a second click invoices nothing")

        assertEquals("CANCELLED", a.post("/app/api/crm/invoices/$invoiceId/cancel").text("status"))
        assertEquals(250.0, a.get("$api/projects/$id").eur("retentionHeldEur"), "cancelling the invoice holds the retention again")
        val again = a.post(retention) { put("dueDate", a.day(30)) }.text("invoiceId")!!
        assertNotEquals(invoiceId, again)
        assertEquals(HttpStatusCode.OK, a.delete("/app/api/crm/invoices/$again").status)
        val deleted = a.get("$api/projects/$id")
        assertEquals(250.0, deleted.eur("retentionHeldEur"), "so does deleting it")
        assertEquals(emptyList(), deleted.list("retentionReleases"))
    }

    @Test
    fun `releasing the retention needs invoices, a client and something held`() = dashboardApiTest(mongo) {
        val noInvoices = company(listOf(DashboardModules.MEASUREMENTS))
        assertEquals(HttpStatusCode.Forbidden, noInvoices.post("$api/projects/${ObjectId()}/retention/invoice") { put("dueDate", noInvoices.day(30)) }.status)

        val a = company()
        val nobody = project(a) { put("status", "COMPLETED") }.text("id")!!
        assertEquals("no_client", a.post("$api/projects/$nobody/retention/invoice") { put("dueDate", a.day(30)) }.error)
        val clientId = client(a)
        val nothing = project(a) { put("clientId", clientId); put("status", "COMPLETED"); put("retentionPct", 5.0) }.text("id")!!
        assertEquals("nothing_held", a.post("$api/projects/$nothing/retention/invoice") { put("dueDate", a.day(30)) }.error)
        assertEquals("invalid_date", a.post("$api/projects/$nothing/retention/invoice") { put("dueDate", "soon") }.error)
        assertEquals(HttpStatusCode.NotFound, a.post("$api/projects/${ObjectId()}/retention/invoice") { put("dueDate", a.day(30)) }.status)
    }
}
