package com.rfm.edubot.dashboard.building

import com.mongodb.client.model.Updates
import com.rfm.edubot.ai.TenantUsageRepository
import com.rfm.edubot.ai.UsageSources
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.FakeModel
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A project's updates for its client: the AI's draft of a period, posted to the client's page by the team. */
class ProjectReportsTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("building_reports")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val api = BUILDING_API

    @Test
    fun `the AI drafts the client's update from what the client may read, and the team posts it to their page`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a)
        val id = project(a) { put("clientId", clientId); put("status", "ACTIVE") }.text("id")!!
        val phase = twoPhases(a, id).list("phases")[1].str("id")
        assertEquals("nothing_to_report", a.post("$api/projects/$id/reports/draft").error)

        a.post("$api/diary") {
            put("projectId", id); put("day", a.day(-1)); put("workDone", "Betonagem da laje do piso 1"); put("shared", true)
            putJsonArray("phaseProgress") { addJsonObject { put("phaseId", phase); put("progressPct", 45) } }
        }
        a.post("$api/diary") { put("projectId", id); put("day", a.day(-2)); put("workDone", "Reparação na cave"); put("incidents", "Fuga de água na cave") }
        val change = a.post("$api/changes") {
            put("projectId", id); put("title", "Claraboia"); put("daysImpact", 3)
            putJsonArray("items") { addJsonObject { put("description", "Claraboia"); put("unitPriceEur", 1850.0) } }
        }.text("id")!!
        a.post("$api/changes/$change/status") { put("status", "SENT") }
        a.post("$api/changes/$change/status") { put("status", "APPROVED") }

        model.answer = { FakeModel.text("Caro cliente, esta semana betonámos a laje do piso 1.\n\nObras Silva", prompt = 300, completion = 80) }
        val draft = a.post("$api/projects/$id/reports/draft")
        assertEquals(HttpStatusCode.OK, draft.status, draft.toString())
        assertEquals(listOf(a.day(-6), a.day(0)), listOf(draft.text("from"), draft.text("to")), "the last 7 days")
        assertEquals(1, draft.int("sharedDays"))
        assertTrue(draft.text("text")!!.startsWith("Caro cliente"))
        val asked = model.last()
        assertTrue("European Portuguese" in asked.system.single())
        val facts = asked.lastUser!!
        listOf("Moradia Silva", "Betonagem da laje do piso 1", "Estrutura: from 20% to 45%", "Claraboia (+3 days").forEach { assertTrue(it in facts, "$it in:\n$facts") }
        listOf("Reparação na cave", "Fuga de água", "1850").forEach { assertFalse(it in facts, "$it must not reach the model:\n$facts") }
        assertEquals(380L, TenantUsageRepository(mongo, a.tenant.id).tokensBySourceThisMonth()[UsageSources.BUILDING])

        assertEquals("text_required", a.post("$api/projects/$id/reports") { put("text", " ") }.error)
        val posted = a.post("$api/projects/$id/reports") { put("from", draft.text("from")); put("to", draft.text("to")); put("text", "  Esta semana betonámos a laje.  ") }
        assertEquals(HttpStatusCode.Created, posted.status, posted.toString())
        assertEquals("Esta semana betonámos a laje.", posted.text("text"))
        val reportId = posted.text("id")!!
        assertEquals(listOf(reportId), a.get("$api/projects/$id/reports").ids())

        val token = a.post("$api/projects/$id/share").text("shareToken")!!
        assertEquals(listOf("Esta semana betonámos a laje."), call("GET", "/app/share/$token/data", null).list("reports").map { it.str("text") })
        assertEquals("Laje betonada.", a.patch("$api/reports/$reportId") { put("text", "Laje betonada.") }.text("text"))
        assertEquals(HttpStatusCode.OK, a.delete("$api/reports/$reportId").status)
        assertEquals(emptyList(), call("GET", "/app/share/$token/data", null).list("reports"))
    }

    @Test
    fun `the draft stays within the month's AI budget, and says so when the model fails`() = dashboardApiTest(mongo) {
        val a = company()
        val id = project(a).text("id")!!
        a.post("$api/diary") { put("projectId", id); put("workDone", "Limpeza do estaleiro"); put("shared", true) }
        model.answer = { throw IllegalStateException("down") }
        val failed = a.post("$api/projects/$id/reports/draft")
        assertEquals(HttpStatusCode.BadGateway to "ai_unavailable", failed.status to failed.error)

        tenants.update(a.tenant.slug, Updates.set("monthlyTokenBudget", 0L))
        val over = a.post("$api/projects/$id/reports/draft")
        assertEquals(HttpStatusCode.Conflict to "token_budget", over.status to over.error)
        assertEquals("invalid_range", a.post("$api/projects/$id/reports/draft") { put("from", a.day(0)); put("to", a.day(-3)) }.error)
    }
}
