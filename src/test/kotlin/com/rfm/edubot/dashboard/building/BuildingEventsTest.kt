package com.rfm.edubot.dashboard.building

import com.rfm.edubot.agents.runtime.AgentContextBuilder
import com.rfm.edubot.building.ProjectHealthWatch
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
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
import kotlin.test.assertTrue

/** Building's domain events, what an agent run on a Building record sees, and the hourly health watch. */
class BuildingEventsTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("building_events")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val api = BUILDING_API

    @Test
    fun `Building's writes append their events, naming the project and its client`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a)
        val id = project(a) {
            put("clientId", clientId)
            putJsonArray("contract") { addJsonObject { put("description", "Moradia"); put("quantity", 1.0); put("unitPriceEur", 1000.0) } }
        }.text("id")!!
        a.patch("$api/projects/$id") { put("status", "ACTIVE") }
        val phase = twoPhases(a, id).list("phases")[1].str("id")
        a.post("$api/diary") { put("projectId", id); put("workDone", "Betão"); putJsonArray("phaseProgress") { addJsonObject { put("phaseId", phase); put("progressPct", 40) } } }
        val issue = a.post("$api/issues") { put("projectId", id); put("title", "Fuga") }.text("id")!!
        a.patch("$api/issues/$issue") { put("status", "RESOLVED") }
        val change = a.post("$api/changes") {
            put("projectId", id); put("title", "Muro")
            putJsonArray("items") { addJsonObject { put("description", "Muro"); put("unitPriceEur", 300.0) } }
        }.text("id")!!
        a.post("$api/changes/$change/status") { put("status", "SENT") }
        a.post("$api/changes/$change/status") { put("status", "APPROVED"); put("decidedBy", "Sr. Silva") }
        val measurement = a.post("$api/measurements") { put("projectId", id) }
        val line = measurement.list("lines").first().str("key")
        a.patch("$api/measurements/${measurement.text("id")}") { putJsonArray("lines") { addJsonObject { put("key", line); put("toDateQty", 0.5) } } }
        a.post("$api/measurements/${measurement.text("id")}/approve")
        val invoiceId = a.post("$api/measurements/${measurement.text("id")}/invoice") { put("dueDate", a.day(30)) }.text("invoiceId")!!

        val log = DomainEventLog(mongo)
        val onProject = log.timeline(a.tenant.id, SubjectRef(SubjectTypes.PROJECT, id))
        assertEquals(
            setOf(
                DomainEventTypes.PROJECT_CREATED, DomainEventTypes.PROJECT_STATUS_CHANGED, DomainEventTypes.SITE_LOG_CREATED,
                DomainEventTypes.SITE_ISSUE_CREATED, DomainEventTypes.SITE_ISSUE_STATUS_CHANGED, DomainEventTypes.CHANGE_ORDER_SENT,
                DomainEventTypes.CHANGE_ORDER_DECIDED, DomainEventTypes.MEASUREMENT_APPROVED, DomainEventTypes.MEASUREMENT_INVOICED,
            ),
            onProject.map { it.type }.toSet(),
        )
        val status = onProject.single { it.type == DomainEventTypes.PROJECT_STATUS_CHANGED }.payload
        assertEquals(listOf("PLANNING", "ACTIVE"), listOf(status["from"], status["to"]).map { it?.jsonPrimitive?.contentOrNull })
        val decided = onProject.single { it.type == DomainEventTypes.CHANGE_ORDER_DECIDED }
        assertEquals(SubjectRef(SubjectTypes.CHANGE_ORDER, change), decided.subject)
        assertEquals(listOf("APPROVED", "TEAM", "Sr. Silva"), listOf("to", "via", "decidedBy").map { decided.payload[it]?.jsonPrimitive?.contentOrNull })
        val issueMoved = onProject.single { it.type == DomainEventTypes.SITE_ISSUE_STATUS_CHANGED }.payload
        assertEquals("RESOLVED", issueMoved["to"]?.jsonPrimitive?.contentOrNull)
        val invoiced = onProject.single { it.type == DomainEventTypes.MEASUREMENT_INVOICED }
        assertTrue(SubjectRef(SubjectTypes.INVOICE, invoiceId) in invoiced.related, "the invoice's timeline shows it too")
        val onClient = log.timeline(a.tenant.id, SubjectRef(SubjectTypes.CLIENT, clientId)).map { it.type }
        assertTrue(onClient.containsAll(listOf(DomainEventTypes.PROJECT_CREATED, DomainEventTypes.CHANGE_ORDER_DECIDED)), onClient.toString())
    }

    @Test
    fun `an agent run on a Building record sees the record, its project and the client to write to`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a, name = "Marta Marques")
        val id = project(a) { put("clientId", clientId); put("status", "ACTIVE") }.text("id")!!
        twoPhases(a, id)
        val issue = a.post("$api/issues") { put("projectId", id); put("title", "Fissura"); put("dueDate", a.day(-1)) }.text("id")!!
        val change = a.post("$api/changes") {
            put("projectId", id); put("title", "Claraboia"); put("daysImpact", 3)
            putJsonArray("items") { addJsonObject { put("description", "Claraboia"); put("unitPriceEur", 1850.0) } }
        }.text("id")!!
        val entry = a.post("$api/diary") { put("projectId", id); put("workDone", "Cofragem da laje") }.text("id")!!

        val tenant = tenants.findById(a.tenant.id)!!
        val builder = AgentContextBuilder(mongo) { Clock.System.now() }
        val onIssue = builder.build(tenant, SubjectRef(SubjectTypes.SITE_ISSUE, issue))
        assertTrue(onIssue.exists)
        assertEquals(clientId, onIssue.clientId?.toHexString(), "steps that message the client have one")
        val vars = onIssue.variables
        assertEquals("Fissura", vars["issue"]!!.jsonObject.str("title"))
        assertEquals("true", vars["issue"]!!.jsonObject.str("isOverdue"))
        assertEquals("Moradia Silva", vars["project"]!!.jsonObject.str("name"))
        assertEquals("AT_RISK", vars["project"]!!.jsonObject.str("health"))
        assertEquals("1", vars["project"]!!.jsonObject.str("openIssuesOverdue"))
        assertEquals("Marta Marques", vars["client"]!!.jsonObject.str("name"))
        assertEquals("185000", builder.build(tenant, SubjectRef(SubjectTypes.CHANGE_ORDER, change)).variables["change"]!!.jsonObject.str("totalCents"))
        assertEquals("Cofragem da laje", builder.build(tenant, SubjectRef(SubjectTypes.SITE_LOG, entry)).variables["diary"]!!.jsonObject.str("workDone"))
        val onProject = builder.build(tenant, SubjectRef(SubjectTypes.PROJECT, id))
        assertEquals("47", onProject.variables["project"]!!.jsonObject.str("progressPct"))
        assertEquals(false, builder.build(tenant, SubjectRef(SubjectTypes.PROJECT, org.bson.types.ObjectId().toHexString())).exists, "a removed project skips the run")
    }

    @Test
    fun `the health watch announces a project the first time it falls behind, and only then`() = dashboardApiTest(mongo) {
        val a = company()
        val id = project(a) { put("status", "ACTIVE") }.text("id")!!
        val phases = a.put("$api/projects/$id/phases") {
            putJsonArray("phases") { addJsonObject { put("name", "Tudo"); put("plannedStart", a.day(-10)); put("plannedEnd", a.day(5)); put("progressPct", 70) } }
        }
        assertEquals("ON_TRACK", phases.text("health"))
        val tenant = tenants.findById(a.tenant.id)!!
        val watch = ProjectHealthWatch(mongo, tenants)
        assertEquals(0, watch.check(tenant), "the first look only stores what it sees")

        a.patch("$api/projects/$id/phases/${phases.list("phases").single().str("id")}") { put("progressPct", 0) }
        assertEquals(1, watch.check(tenant))
        val delayed = DomainEventLog(mongo).timeline(a.tenant.id, SubjectRef(SubjectTypes.PROJECT, id)).single { it.type == DomainEventTypes.PROJECT_DELAYED }
        assertEquals(listOf("ON_TRACK", "DELAYED"), listOf(delayed.payload["from"], delayed.payload["to"]).map { it?.jsonPrimitive?.contentOrNull })
        assertEquals(0, watch.check(tenant), "nothing new, nothing announced")
    }
}
