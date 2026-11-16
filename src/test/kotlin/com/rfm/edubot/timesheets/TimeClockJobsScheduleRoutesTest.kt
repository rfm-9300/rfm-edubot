package com.rfm.edubot.timesheets

import at.favre.lib.crypto.bcrypt.BCrypt
import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.building.BuildingJobProjects
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.dashboard.EmployeeLookup
import com.rfm.edubot.dashboard.dashboardAccountRoutes
import com.rfm.edubot.dashboard.dashboardRoutes
import com.rfm.edubot.dashboard.employeeWorkRoutes
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.notifications.notificationRoutes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import com.rfm.edubot.testing.TestMongo
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


/** Phase 2 of the time clock over HTTP: hours per job, and planned shifts against clock-ins. */
class TimeClockJobsScheduleRoutesTest {
    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("time_clock_jobs")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }

        private const val PASSWORD = "correct horse"

        // Made up per run: GitGuardian blocks the pull request on a password-like literal.
        private val EMPLOYEE_PASSWORD = ObjectId().toHexString()
    }

    private val users get() = DashboardUserRepository(mongo)
    private val tenants get() = TenantRepository(mongo)
    private val notifications get() = NotificationRepository(mongo)
    private val lookup: EmployeeLookup = { tenantId, id -> EmployeeRepository(mongo, tenantId).findById(id) }
    private val runtime = RuntimeConfig(
        AppConfig(
            port = 8080,
            whatsapp = AppConfig.WhatsAppConfig(verifyToken = "v", appSecret = "s", phoneNumberId = "1", accessToken = "t"),
            instagram = AppConfig.InstagramConfig(appId = "ig", appSecret = "s", redirectUri = "https://example.com/cb"),
            openrouter = AppConfig.OpenRouterConfig(apiKey = "k", primaryModel = "a", fallbackModel = "b", maxTokens = 512),
            mongo = AppConfig.MongoConfig(uri = "unused", database = "unused"),
            rateLimit = AppConfig.RateLimitConfig(),
            admin = AppConfig.AdminConfig(jwtSecret = "test-secret"),
            pdfStoragePath = "/tmp/pdfs",
        ),
    )
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The server's clock, stopped by the tests at a moment in November (Lisbon is on UTC then) so minutes come out
     * exact; sign-in tokens use the real one.
     */
    private var stopped: Instant? = null
    private val now: () -> Instant = { stopped ?: Clock.System.now() }

    private fun at(iso: String) {
        stopped = Instant.parse(iso)
    }

    private fun clockTest(block: suspend (HttpClient) -> Unit): Unit = testApplication {
        application {
            configureSerialization()
            configureAdminAuth(runtime, tenants, users, lookup)
            routing {
                dashboardAccountRoutes(tenants, users, runtime, employees = lookup)
                dashboardRoutes(
                    mongo = mongo,
                    tenantRepository = tenants,
                    dashboardUsers = users,
                    pipelineFactory = mockk(relaxed = true),
                    personaCompiler = mockk(relaxed = true),
                    aiClient = mockk(relaxed = true),
                    runtimeConfig = runtime,
                    channelBindingService = mockk(relaxed = true),
                    instagramSocial = mockk(relaxed = true),
                )
                employeeWorkRoutes(mongo, users, notifications)
                timeClockRoutes(mongo, notifications, now, BuildingJobProjects(mongo))
                timesheetRoutes(mongo, now, BuildingJobProjects(mongo))
                notificationRoutes(notifications)
            }
        }
        block(client)
    }

    private inner class Company(private val modules: List<String>) {
        val id = ObjectId()
        lateinit var tenant: Tenant
        lateinit var admin: DashboardUser
        lateinit var member: DashboardUser
        lateinit var employee: Employee
        val employeeEmail = "ana-${id.toHexString()}@limpezas.test"

        suspend fun create(): Company {
            val created = Clock.System.now()
            tenant = tenants.create(
                Tenant(id = id, slug = "t-${id.toHexString()}", name = "Limpezas Sol", channels = emptyList(), enabledModules = modules, status = TenantStatus.ACTIVE, createdAt = created, updatedAt = created),
            )
            admin = user(DashboardUserRole.TENANT_ADMIN)
            member = user(DashboardUserRole.TENANT_MEMBER)
            employee = EmployeeRepository(mongo, id).create("Ana Costa", "+351 910 ${id.toHexString().takeLast(6)}", "Limpeza")
            return this
        }

        private suspend fun user(role: DashboardUserRole): DashboardUser {
            val userId = ObjectId()
            return users.create(
                DashboardUser(
                    id = userId, tenantId = id, email = "${role.name.lowercase()}-${userId.toHexString()}@limpezas.test",
                    passwordHash = BCrypt.withDefaults().hashToString(4, PASSWORD.toCharArray()), role = role, createdAt = Clock.System.now(),
                ),
            )
        }
    }

    private suspend fun company(vararg modules: String) = Company(modules.toList()).create()

    private suspend fun HttpClient.send(method: String, path: String, token: String?, body: JsonObject? = null): HttpResponse {
        val request: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            token?.let { bearerAuth(it) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        return when (method) {
            "GET" -> get(path, request)
            "PATCH" -> patch(path, request)
            "PUT" -> put(path, request)
            "DELETE" -> delete(path, request)
            else -> post(path, request)
        }
    }

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.list(): JsonArray = json.parseToJsonElement(bodyAsText()).jsonArray
    private suspend fun HttpResponse.error(): String? = obj()["error"]?.jsonPrimitive?.content
    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.content
    private fun JsonObject.int(name: String): Int = this[name]!!.jsonPrimitive.content.toInt()
    private fun JsonObject.obj(name: String): JsonObject = this[name]!!.jsonObject
    private fun JsonObject.items(name: String): List<JsonObject> = this[name]?.jsonArray?.map { it.jsonObject }.orEmpty()
    private fun JsonObject.strings(name: String): List<String> = this[name]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private suspend fun HttpClient.token(email: String, password: String = PASSWORD): String =
        send("POST", "/app/auth/login", null, buildJsonObject { put("email", email); put("password", password) }).obj().text("token")!!

    private suspend fun HttpClient.employeeToken(c: Company, adminToken: String): String {
        val given = send("POST", "/app/api/crm/employees/${c.employee.id}/access", adminToken, buildJsonObject { put("email", c.employeeEmail); put("password", EMPLOYEE_PASSWORD) })
        assertEquals(HttpStatusCode.Created, given.status, given.bodyAsText())
        return token(c.employeeEmail, EMPLOYEE_PASSWORD)
    }

    private fun punch(type: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", type)
        put("channel", "WEB")
        put("locationError", "PERMISSION_DENIED")
        extra()
    }

    private fun JsonObjectBuilder.job(clientId: String? = null, activityId: String? = null, note: String? = null) = putJsonObject("job") {
        clientId?.let { put("clientId", it) }
        activityId?.let { put("activityId", it) }
        note?.let { put("note", it) }
    }

    @Test
    fun `hours per job, strictly, clock in on a job, switch, split afterwards, approve once every minute has a job`() = clockTest { http ->
        val c = company(DashboardModules.TIMESHEETS, DashboardModules.CLIENTS)
        val adminToken = http.token(c.admin.email)
        val memberToken = http.token(c.member.email)
        val employeeToken = http.employeeToken(c, adminToken)
        val client = ClientRepository(mongo, c.id).create("Casa Silva", "+351 21${c.id.toHexString().takeLast(7)}")

        val cleaningBody = buildJsonObject { put("name", "Limpeza"); put("code", "LIM") }
        assertEquals("not_allowed", http.send("POST", "/app/api/timesheets/activities", memberToken, cleaningBody).error())
        val cleaning = http.send("POST", "/app/api/timesheets/activities", adminToken, cleaningBody).obj().text("id")!!
        assertEquals("name_taken", http.send("POST", "/app/api/timesheets/activities", adminToken, buildJsonObject { put("name", "limpeza") }).error())
        val travel = http.send("POST", "/app/api/timesheets/activities", adminToken, buildJsonObject { put("name", "Deslocação"); put("billable", false) }).obj().text("id")!!
        val rules = http.send(
            "PUT", "/app/api/timesheets/settings", adminToken,
            buildJsonObject { put("workLog", "REQUIRED"); putJsonObject("jobNeeds") { put("activity", true) } },
        ).obj()
        assertEquals("REQUIRED", rules.text("workLog"))
        assertEquals("invalid_policy", http.send("PUT", "/app/api/timesheets/settings", adminToken, buildJsonObject { put("workLog", "SOMETIMES") }).error())

        val options = http.send("GET", "/app/api/portal/time/jobs", employeeToken).obj()
        assertEquals(listOf("Casa Silva"), options.items("clients").map { it.text("name") })
        assertEquals(listOf("Deslocação", "Limpeza"), options.items("activities").map { it.text("name") })

        at("2026-11-16T08:00:00Z")
        assertEquals("job_required", http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("IN")).error())
        val incomplete = http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("IN") { job(clientId = client.id.toHexString()) })
        assertEquals("job_incomplete", incomplete.error())
        assertEquals("activity", incomplete.obj().text("detail"))
        assertEquals("activity_not_found", http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("IN") { job(activityId = ObjectId().toHexString()) }).error())
        val opened = http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("IN") { job(client.id.toHexString(), cleaning) })
        assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())
        val shiftId = opened.obj().obj("shift").text("id")!!
        val first = opened.obj().obj("shift").items("segments").single()
        assertEquals(listOf("Casa Silva", "Limpeza", "true"), listOf(first.obj("job").text("clientName"), first.obj("job").text("activityName"), first.text("complete")))

        at("2026-11-16T10:00:00Z")
        assertEquals("same_job", http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("SWITCH") { job(client.id.toHexString(), cleaning) }).error())
        val switched = http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("SWITCH") { job(activityId = travel) })
        assertEquals(HttpStatusCode.Created, switched.status, switched.bodyAsText())
        assertEquals(2, switched.obj().obj("shift").items("segments").size)
        at("2026-11-16T12:00:00Z")
        http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("BREAK_START"))
        assertEquals("on_break", http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("SWITCH") { job(client.id.toHexString(), cleaning) }).error())
        at("2026-11-16T13:00:00Z")
        http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("BREAK_END"))
        at("2026-11-16T17:00:00Z")
        val closed = http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("OUT")).obj().obj("shift")
        assertEquals(listOf(120, 360), closed.items("segments").map { it.int("minutes") }, "the lunch break is in neither part")

        // Ana splits her afternoon afterwards and leaves an hour without a job: the strict rule flags it.
        val split = http.send(
            "PUT", "/app/api/portal/time/shifts/$shiftId/segments", employeeToken,
            buildJsonObject {
                putJsonArray("segments") {
                    addJsonObject { put("start", "08:00"); job(client.id.toHexString(), cleaning) }
                    addJsonObject { put("start", "10:00"); job(activityId = travel) }
                    addJsonObject { put("start", "16:00") }
                }
            },
        )
        assertEquals(HttpStatusCode.OK, split.status, split.bodyAsText())
        val splitShift = split.obj()
        assertTrue("UNALLOCATED" in splitShift.strings("flags"))
        assertEquals(60, splitShift.int("unallocatedMinutes"))
        assertEquals(listOf("CLOCK", "CLOCK", "EMPLOYEE"), splitShift.items("segments").map { it.text("source") }, "the boundaries she didn't move keep their clock")
        assertEquals("segments_same_start", http.send(
            "PUT", "/app/api/portal/time/shifts/$shiftId/segments", employeeToken,
            buildJsonObject { putJsonArray("segments") { addJsonObject { put("start", "08:00") }; addJsonObject { put("start", "10:30") }; addJsonObject { put("start", "10:30") } } },
        ).error())

        assertEquals("unallocated", http.send("POST", "/app/api/timesheets/shifts/approve", memberToken, buildJsonObject { putJsonArray("ids") { add(kotlinx.serialization.json.JsonPrimitive(shiftId)) } }).error())
        val teamFix = buildJsonObject {
            putJsonArray("segments") {
                addJsonObject { put("start", "08:00"); job(client.id.toHexString(), cleaning) }
                addJsonObject { put("start", "10:00"); job(activityId = travel) }
                addJsonObject { put("start", "16:00"); job(client.id.toHexString(), cleaning) }
            }
        }
        assertEquals("reason_required", http.send("PUT", "/app/api/timesheets/shifts/$shiftId/segments", memberToken, teamFix).error())
        val fixed = http.send("PUT", "/app/api/timesheets/shifts/$shiftId/segments", memberToken, JsonObject(teamFix + ("reason" to kotlinx.serialization.json.JsonPrimitive("Voltou à casa Silva")))).obj()
        assertTrue("UNALLOCATED" !in fixed.strings("flags"))
        assertEquals(3, fixed.items("edits").last().obj("jobs").items("after").size)
        val approved = http.send("POST", "/app/api/timesheets/shifts/approve", memberToken, buildJsonObject { putJsonArray("ids") { add(kotlinx.serialization.json.JsonPrimitive(shiftId)) } }).obj()
        assertEquals(1, approved.int("approved"))
        assertEquals("already_approved", http.send("PUT", "/app/api/portal/time/shifts/$shiftId/segments", employeeToken, teamFix).error())

        val report = http.send("GET", "/app/api/timesheets/jobs?from=2026-11-16&to=2026-11-16", memberToken).obj()
        assertEquals(480, report.int("minutes"))
        assertEquals(180, report.int("billableMinutes"), "two hours in the morning and one after travel, on the billable activity")
        assertEquals(0, report.int("unallocatedMinutes"))
        assertEquals(listOf("Deslocação" to 300, "Limpeza" to 180), report.items("byActivity").map { it.text("name") to it.int("minutes") })
        assertEquals(listOf("Casa Silva" to 180, "" to 300), report.items("byClient").map { it.text("name").orEmpty() to it.int("minutes") }, "travel had no client")
        assertEquals(3, report.items("entries").size)
        val onClient = http.send("GET", "/app/api/timesheets/jobs?from=2026-11-16&to=2026-11-16&clientId=${client.id}", memberToken).obj()
        assertEquals(180, onClient.int("minutes"))
        val csv = http.send("GET", "/app/api/timesheets/jobs.csv?from=2026-11-16&to=2026-11-16", memberToken).bodyAsText().removePrefix("﻿").trimEnd().split("\r\n")
        assertEquals(4, csv.size, "a header and one row per segment")
        assertTrue(csv[1].contains("Casa Silva;;Limpeza"), csv[1])

        assertEquals("activity_in_use", http.send("DELETE", "/app/api/timesheets/activities/$travel", adminToken).error())
        assertEquals("false", http.send("PATCH", "/app/api/timesheets/activities/$travel", adminToken, buildJsonObject { put("active", false) }).obj().text("active"))
        assertEquals(listOf("Limpeza"), http.send("GET", "/app/api/portal/time/jobs", employeeToken).obj().items("activities").map { it.text("name") }, "out of use, out of the picker")
        val other = company(DashboardModules.TIMESHEETS)
        assertEquals(HttpStatusCode.NotFound, http.send("PUT", "/app/api/timesheets/shifts/$shiftId/segments", http.token(other.admin.email), JsonObject(teamFix + ("reason" to kotlinx.serialization.json.JsonPrimitive("x")))).status)
    }

    @Test
    fun `planned shifts, the strict window, lateness, lunch out and back, no-shows, copying and excusing`() = clockTest { http ->
        val c = company(DashboardModules.TIMESHEETS)
        val adminToken = http.token(c.admin.email)
        val memberToken = http.token(c.member.email)
        val employeeToken = http.employeeToken(c, adminToken)
        http.send(
            "PUT", "/app/api/timesheets/settings", adminToken,
            buildJsonObject { put("schedule", "BLOCK"); put("lateGraceMinutes", 5); put("earlyClockInMinutes", 15); put("noShowMinutes", 30) },
        )
        assertEquals("invalid_minutes", http.send("PUT", "/app/api/timesheets/settings", adminToken, buildJsonObject { put("noShowMinutes", 1) }).error())

        at("2026-11-16T07:00:00Z")
        val plan = buildJsonObject {
            put("employeeId", c.employee.id.toHexString())
            putJsonArray("days") { add(kotlinx.serialization.json.JsonPrimitive("2026-11-16")); add(kotlinx.serialization.json.JsonPrimitive("2026-11-17")) }
            put("start", "08:00")
            put("end", "16:00")
            put("note", "Escritórios do 3.º andar")
        }
        val created = http.send("POST", "/app/api/timesheets/schedule", memberToken, plan)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val (monday, tuesday) = created.list().map { it.jsonObject.text("id")!! }
        assertEquals("planned_overlap", http.send(
            "POST", "/app/api/timesheets/schedule", memberToken,
            buildJsonObject { put("employeeId", c.employee.id.toHexString()); putJsonArray("days") { add(kotlinx.serialization.json.JsonPrimitive("2026-11-16")) }; put("start", "15:00"); put("end", "18:00") },
        ).error())

        val status = http.send("GET", "/app/api/portal/time", employeeToken).obj()
        assertEquals("08:00", status.obj("planned").text("startTime"))
        assertEquals("TOO_EARLY" to "07:45", status.obj("clockIn").text("state") to status.obj("clockIn").text("opensTime"))
        val early = http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("IN")).obj()
        assertEquals("too_early" to "07:45", early.text("error") to early.text("opensTime"))

        at("2026-11-16T08:12:00Z")
        val morning = http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("IN")).obj().obj("shift")
        assertEquals(monday, morning.obj("planned").text("id"))
        assertEquals(12, morning.int("lateMinutes"))
        assertTrue("LATE" in morning.strings("flags"))
        at("2026-11-16T12:00:00Z")
        val lunch = http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("OUT")).obj().obj("shift")
        assertTrue("LEFT_EARLY" in lunch.strings("flags"))
        at("2026-11-16T12:45:00Z")
        val afternoon = http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("IN")).obj().obj("shift")
        assertTrue("LATE" !in afternoon.strings("flags"), "coming back for the same plan isn't arriving late")
        assertTrue("LEFT_EARLY" !in http.send("GET", "/app/api/timesheets/shifts/${lunch.text("id")}", memberToken).obj().strings("flags"), "nor was going to lunch leaving early")
        at("2026-11-16T16:00:00Z")
        http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("OUT"))

        val week = http.send("GET", "/app/api/timesheets/schedule?from=2026-11-16&to=2026-11-22", memberToken).obj()
        val attended = week.items("planned").first { it.text("id") == monday }.obj("attendance")
        assertEquals(listOf("ATTENDED", "12", "true", "08:12", "16:00"), listOf("state", "lateMinutes", "late", "arrivedTime", "leftTime").map { attended.text(it) })
        assertEquals("UPCOMING", week.items("planned").first { it.text("id") == tuesday }.obj("attendance").text("state"))
        assertEquals("planned_attended", http.send("DELETE", "/app/api/timesheets/schedule/$monday", memberToken).error())
        val totals = http.send("GET", "/app/api/timesheets?from=2026-11-16&to=2026-11-22", memberToken).obj().items("totals").single()
        assertEquals(listOf(960, 1, 0), listOf(totals.int("plannedMinutes"), totals.int("late"), totals.int("absences")))

        at("2026-11-18T08:00:00Z")
        assertEquals("not_scheduled", http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("IN")).error())

        // Tuesday: nobody clocks in. Forty minutes in, the team hears about it, once.
        at("2026-11-17T08:40:00Z")
        val watch = AttendanceWatch(mongo, tenants, notifications, now)
        assertEquals(1, watch.check())
        assertEquals(0, watch.check())
        val bell = http.send("GET", "/app/api/notifications", memberToken).obj().items("items").filter { it.text("kind") == "time_no_show" }
        assertEquals(listOf("planned:$tuesday"), bell.map { it.text("ref") })
        val board = http.send("GET", "/app/api/timesheets/board", memberToken).obj()
        assertEquals(listOf(tuesday to "MISSING"), board.items("expected").map { it.text("id") to it.obj("attendance").text("state") })
        assertEquals("reason_required", http.send("POST", "/app/api/timesheets/schedule/$tuesday/excuse", memberToken, buildJsonObject { put("reason", " ") }).error())
        val excused = http.send("POST", "/app/api/timesheets/schedule/$tuesday/excuse", memberToken, buildJsonObject { put("reason", "Consulta médica") }).obj()
        assertEquals("EXCUSED", excused.obj("attendance").text("state"))
        assertTrue(http.send("GET", "/app/api/timesheets/board", memberToken).obj().items("expected").isEmpty())

        val copied = http.send("POST", "/app/api/timesheets/schedule/copy", memberToken, buildJsonObject { put("from", "2026-11-16"); put("to", "2026-11-23") }).obj()
        assertEquals(2 to 0, copied.int("created") to copied.int("skipped"))
        val again = http.send("POST", "/app/api/timesheets/schedule/copy", memberToken, buildJsonObject { put("from", "2026-11-16"); put("to", "2026-11-23") }).obj()
        assertEquals(0 to 2, again.int("created") to again.int("skipped"), "a copy never overlaps what is there")
        assertEquals("invalid_week", http.send("POST", "/app/api/timesheets/schedule/copy", memberToken, buildJsonObject { put("from", "2026-11-17"); put("to", "2026-11-23") }).error())
        val next = http.send("GET", "/app/api/timesheets/schedule?from=2026-11-23&to=2026-11-29", memberToken).obj().items("planned")
        assertEquals(listOf("08:00–16:00", "08:00–16:00"), next.map { "${it.text("startTime")}–${it.text("endTime")}" })
        assertTrue(next.none { it["excuse"] != null }, "an excuse isn't copied")

        val mine = http.send("GET", "/app/api/portal/time/schedule?from=2026-11-16&to=2026-11-29", employeeToken).list()
        assertEquals(4, mine.size)
        val moved = http.send("PATCH", "/app/api/timesheets/schedule/${next.first().text("id")}", memberToken, buildJsonObject { put("start", "09:00") }).obj()
        assertEquals("09:00", moved.text("startTime"))
        assertEquals(HttpStatusCode.OK, http.send("DELETE", "/app/api/timesheets/schedule/${next.first().text("id")}", memberToken).status)

        val other = company(DashboardModules.TIMESHEETS)
        val stranger = http.token(other.admin.email)
        assertEquals(HttpStatusCode.NotFound, http.send("PATCH", "/app/api/timesheets/schedule/$tuesday", stranger, buildJsonObject { put("start", "07:00") }).status)
        assertTrue(http.send("GET", "/app/api/timesheets/schedule?from=2026-11-16&to=2026-11-22", stranger).obj().items("planned").isEmpty())
        assertEquals(HttpStatusCode.Unauthorized, http.send("GET", "/app/api/timesheets/schedule", employeeToken).status, "the team's planner isn't an employee's")

        // Planned shifts are attendance history: an employee who has some is archived, not deleted.
        val rui = EmployeeRepository(mongo, c.id).create("Rui Matos", "+351 912 ${c.id.toHexString().takeLast(6)}", "Limpeza")
        http.send(
            "POST", "/app/api/timesheets/schedule", memberToken,
            buildJsonObject { put("employeeId", rui.id.toHexString()); putJsonArray("days") { add(kotlinx.serialization.json.JsonPrimitive("2026-11-24")) }; put("start", "09:00"); put("end", "13:00") },
        )
        assertEquals("in_use", http.send("DELETE", "/app/api/crm/employees/${rui.id}", adminToken).error())
    }
}
