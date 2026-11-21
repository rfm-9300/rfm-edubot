package com.rfm.edubot.dashboard.building

import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The client's project page as a portal: what waits for their answer, the changes decided, and the budget when shown. */
class ClientPortalTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("building_client_portal")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val api = BUILDING_API

    @Test
    fun `the page lists what waits for the client and shows money only once the team turns the budget on`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a, name = "Ana Costa")
        val projectId = project(a) {
            put("clientId", clientId)
            putJsonArray("contract") { addJsonObject { put("description", "Moradia"); put("quantity", 1.0); put("unit", "vg"); put("unitPriceEur", 10000.0) } }
        }.text("id")!!
        assertEquals("share_off", a.patch("$api/projects/$projectId/share") { put("showBudget", true) }.error, "the page must exist first")
        val share = a.post("$api/projects/$projectId/share")
        assertEquals(false.toString(), share.text("shareBudget"))
        val page = "/app/share/${share.text("shareToken")}/data"

        suspend fun changeOf(title: String, eur: Double): String =
            a.post("$api/changes") {
                put("projectId", projectId); put("title", title); put("daysImpact", 2)
                putJsonArray("items") { addJsonObject { put("description", title); put("quantity", 1.0); put("unitPriceEur", eur) } }
            }.text("id")!!
        val wall = changeOf("Muro", 500.0)
        a.post("$api/changes/$wall/status") { put("status", "APPROVED"); put("decidedBy", "Ana Costa") }
        val skylight = changeOf("Claraboia", 1850.0)
        val changeToken = a.post("$api/changes/$skylight/status") { put("status", "SENT") }.text("approvalToken")!!
        val draft = a.post("$api/measurements") { put("projectId", projectId) }
        val line = draft.list("lines").first { it.str("description") == "Moradia" }.str("key")
        a.patch("$api/measurements/${draft.text("id")}") { putJsonArray("lines") { addJsonObject { put("key", line); put("toDateQty", 0.5) } } }
        val measurementToken = a.post("$api/measurements/${draft.text("id")}/send").text("approvalToken")!!

        val hidden = call("GET", page, null)
        assertEquals(HttpStatusCode.OK, hidden.status, hidden.toString())
        val decisions = hidden.list("decisions")
        assertEquals(listOf("change" to "/app/share/change/$changeToken", "measurement" to "/app/share/measurement/$measurementToken"), decisions.map { it.str("kind") to it.str("path") })
        assertTrue(decisions.all { it["amountEur"] == JsonNull }, "no money while the budget is off")
        assertEquals(listOf("Muro" to "APPROVED"), hidden.list("changes").map { it.str("title") to it.str("status") })
        assertTrue(hidden.list("changes").all { it["amountEur"] == JsonNull })
        assertEquals(JsonNull, hidden.obj["budget"])
        assertFalse(hidden.body.contains("10000") || hidden.body.contains("1850"), "no amount anywhere on the page")

        val on = a.patch("$api/projects/$projectId/share") { put("showBudget", true) }
        assertEquals(true.toString(), on.text("shareBudget"))
        val shown = call("GET", page, null)
        val budget = shown.obj["budget"]!!.jsonObject
        assertEquals(listOf(10000.0, 500.0, 10500.0, 1850.0), listOf("contractEur", "changesEur", "currentEur", "pendingEur").map { budget.eur(it) })
        assertEquals(1850.0, shown.list("decisions").first().eur("amountEur"))
        assertEquals(500.0, shown.list("changes").single().eur("amountEur"))

        val rotated = a.post("$api/projects/$projectId/share/rotate")
        assertEquals(true.toString(), rotated.text("shareBudget"), "a new link keeps the budget shown")
    }
}
