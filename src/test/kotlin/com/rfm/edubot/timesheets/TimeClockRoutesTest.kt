package com.rfm.edubot.timesheets

import at.favre.lib.crypto.bcrypt.BCrypt
import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
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
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** The time clock over HTTP with the real auth plugin: employees punching, phones signing, the team reviewing. */
class TimeClockRoutesTest {
    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("time_clock")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }

        private const val PASSWORD = "correct horse"
        private const val SITE_LAT = 38.72230
        private const val SITE_LNG = -9.13930

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

    /** The server's clock, moved by the tests; sign-in tokens still use the real one. */
    private var offset: Duration = Duration.ZERO
    private val now: () -> Instant = { Clock.System.now() + offset }

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
                timeClockRoutes(mongo, notifications, now)
                timesheetRoutes(mongo, now)
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
        val employeeEmail = "ana-${id.toHexString()}@obras.test"

        suspend fun create(): Company {
            val created = Clock.System.now()
            tenant = tenants.create(
                Tenant(id = id, slug = "t-${id.toHexString()}", name = "Obras Silva", channels = emptyList(), enabledModules = modules, status = TenantStatus.ACTIVE, createdAt = created, updatedAt = created),
            )
            admin = user(DashboardUserRole.TENANT_ADMIN)
            member = user(DashboardUserRole.TENANT_MEMBER)
            employee = EmployeeRepository(mongo, id).create("Ana Costa", "+351 910 ${id.toHexString().takeLast(6)}", "Pedreira")
            return this
        }

        private suspend fun user(role: DashboardUserRole): DashboardUser {
            val userId = ObjectId()
            return users.create(
                DashboardUser(
                    id = userId, tenantId = id, email = "${role.name.lowercase()}-${userId.toHexString()}@obras.test",
                    passwordHash = BCrypt.withDefaults().hashToString(4, PASSWORD.toCharArray()), role = role, createdAt = Clock.System.now(),
                ),
            )
        }
    }

    private suspend fun company(vararg modules: String = arrayOf(DashboardModules.TIMESHEETS)) = Company(modules.toList()).create()

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
    private fun JsonObject.strings(name: String): List<String> = this[name]!!.jsonArray.map { it.jsonPrimitive.content }

    private suspend fun HttpClient.token(email: String, password: String = PASSWORD): String =
        send("POST", "/app/auth/login", null, buildJsonObject { put("email", email); put("password", password) }).obj().text("token")!!

    /** The admin gives Ana a sign-in; her token. */
    private suspend fun HttpClient.employeeToken(c: Company, adminToken: String): String {
        val given = send("POST", "/app/api/crm/employees/${c.employee.id}/access", adminToken, buildJsonObject { put("email", c.employeeEmail); put("password", EMPLOYEE_PASSWORD) })
        assertEquals(HttpStatusCode.Created, given.status, given.bodyAsText())
        return token(c.employeeEmail, EMPLOYEE_PASSWORD)
    }

    private fun punch(type: String, lat: Double? = null, lng: Double? = null, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", type)
        put("channel", "WEB")
        if (lat != null && lng != null) putJsonObject("location") { put("latitude", lat); put("longitude", lng); put("accuracyM", 12.0) }
        extra()
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun KeyPair.sign(message: String): String = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").run { initSign(private); update(message.toByteArray()); sign() },
    )

    private suspend fun HttpClient.nonce(token: String): String = send("POST", "/app/api/portal/time/challenge", token).obj().text("nonce")!!

    /** Enrolls [phone] as Ana's, the way the app does; its key id. */
    private suspend fun HttpClient.enroll(token: String, phone: KeyPair, name: String = "Pixel 8"): String {
        val nonce = nonce(token)
        val enrolled = send(
            "POST", "/app/api/portal/time/devices", token,
            buildJsonObject {
                put("name", name)
                put("platform", "ANDROID")
                put("publicKey", Base64.getEncoder().encodeToString(phone.public.encoded))
                put("nonce", nonce)
                put("signature", phone.sign("$nonce:ENROLL"))
            },
        )
        assertEquals(HttpStatusCode.Created, enrolled.status, enrolled.bodyAsText())
        return enrolled.obj().text("keyId")!!
    }

    private suspend fun HttpClient.signedPunch(token: String, phone: KeyPair, keyId: String, type: String, lat: Double = SITE_LAT, lng: Double = SITE_LNG): HttpResponse {
        val nonce = nonce(token)
        return send(
            "POST", "/app/api/portal/time/punches", token,
            punch(type, lat, lng) {
                put("channel", "APP")
                putJsonObject("verification") { put("keyId", keyId); put("nonce", nonce); put("signature", phone.sign("$nonce:$type")) }
            },
        )
    }

    @Test
    fun `with only the time clock, an employee signs in to their hours and nothing else`() = clockTest { http ->
        val c = company()
        val adminToken = http.token(c.admin.email)
        val employeeToken = http.employeeToken(c, adminToken)

        val me = http.send("GET", "/app/api/me", employeeToken).obj()
        assertEquals(listOf("my-hours"), me.strings("modules"))
        assertEquals("page_unavailable", http.send("GET", "/app/api/portal/services", employeeToken).error())
        assertEquals("employees_only", http.send("GET", "/app/api/portal/time", adminToken).error())
        listOf("/app/api/timesheets", "/app/api/timesheets/board", "/app/api/notifications")
            .forEach { assertEquals(HttpStatusCode.Unauthorized, http.send("GET", it, employeeToken).status, it) }

        val status = http.send("GET", "/app/api/portal/time", employeeToken).obj()
        assertEquals("OFF", status.text("state"))
        assertEquals("OPTIONAL", status["policy"]!!.jsonObject.text("location"))
        assertEquals("Europe/Lisbon", status.text("timezone"))

        val other = company(DashboardModules.EMPLOYEES, DashboardModules.SERVICES)
        assertEquals(HttpStatusCode.Forbidden, http.send("GET", "/app/api/timesheets", http.token(other.admin.email)).status, "a company without the module")
    }

    @Test
    fun `an employee clocks in, takes a break and clocks out from the browser`() = clockTest { http ->
        val c = company()
        val employeeToken = http.employeeToken(c, http.token(c.admin.email))

        val first = http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("IN") { put("locationError", "permission_denied"); put("note", "Obra da Rua do Sol") })
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val opened = first.obj()
        assertEquals("WORKING", opened["status"]!!.jsonObject.text("state"))
        val shift = opened["shift"]!!.jsonObject
        assertEquals(listOf("NO_LOCATION", "UNVERIFIED"), shift.strings("flags"))
        assertEquals("Obra da Rua do Sol", shift.text("note"))
        assertEquals("PERMISSION_DENIED", shift["punches"]!!.jsonArray.single().jsonObject.text("locationError"))

        assertEquals("already_clocked_in", http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("IN")).error())
        assertEquals("not_on_break", http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("BREAK_END")).error())
        assertEquals("ON_BREAK", http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("BREAK_START")).obj()["status"]!!.jsonObject.text("state"))
        offset = 90.minutes
        val out = http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("OUT")).obj()
        assertEquals("OFF", out["status"]!!.jsonObject.text("state"))
        val closed = out["shift"]!!.jsonObject
        assertEquals("CLOSED", closed.text("status"))
        assertEquals("PENDING", closed.text("review"))
        assertEquals(90, closed.text("breakMinutes")!!.toInt(), "clocking out on a break ends it")
        assertEquals("not_clocked_in", http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("OUT")).error())
        assertEquals("invalid_type", http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("LUNCH")).error())

        val mine = http.send("GET", "/app/api/portal/time/shifts", employeeToken).list()
        assertEquals(1, mine.size)
        assertEquals("invalid_range", http.send("GET", "/app/api/portal/time/shifts?from=2026-10-10&to=2026-10-01", employeeToken).error())
        assertEquals("range_too_long", http.send("GET", "/app/api/portal/time/shifts?from=2026-01-01&to=2026-10-01", employeeToken).error())
    }

    @Test
    fun `work sites that block keep a clock-in to the site, and flag a clock-out away from it`() = clockTest { http ->
        val c = company(DashboardModules.TIMESHEETS, DashboardModules.CLIENTS)
        val adminToken = http.token(c.admin.email)
        val memberToken = http.token(c.member.email)
        val employeeToken = http.employeeToken(c, adminToken)

        val site = buildJsonObject { put("name", "Obra Rua do Sol"); put("latitude", SITE_LAT); put("longitude", SITE_LNG); put("radiusM", 200) }
        assertEquals("not_allowed", http.send("POST", "/app/api/timesheets/sites", memberToken, site).error())
        assertEquals("invalid_radius", http.send("POST", "/app/api/timesheets/sites", adminToken, buildJsonObject { put("name", "X"); put("latitude", 1.0); put("longitude", 1.0); put("radiusM", 5) }).error())
        assertEquals("invalid_location", http.send("POST", "/app/api/timesheets/sites", adminToken, buildJsonObject { put("name", "X"); put("latitude", 100.0); put("longitude", 1.0) }).error())
        val created = http.send("POST", "/app/api/timesheets/sites", adminToken, site)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        assertEquals("not_allowed", http.send("PUT", "/app/api/timesheets/settings", memberToken, buildJsonObject { put("geofence", "BLOCK") }).error())
        assertEquals("invalid_policy", http.send("PUT", "/app/api/timesheets/settings", adminToken, buildJsonObject { put("geofence", "SOMETIMES") }).error())
        assertEquals("invalid_hours", http.send("PUT", "/app/api/timesheets/settings", adminToken, buildJsonObject { put("maxShiftHours", 40) }).error())
        val rules = http.send("PUT", "/app/api/timesheets/settings", adminToken, buildJsonObject { put("geofence", "BLOCK"); put("location", "REQUIRED"); put("biometric", "OFF") }).obj()
        assertEquals("BLOCK", rules.text("geofence"))

        assertEquals("location_required", http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("IN")).error())
        val away = http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("IN", SITE_LAT + 0.01, SITE_LNG))
        assertEquals(HttpStatusCode.Conflict, away.status)
        val refusal = away.obj()
        assertEquals("outside_sites", refusal.text("error"))
        assertEquals("Obra Rua do Sol", refusal.text("siteName"))
        assertEquals(1112.0, refusal.text("distanceM")!!.toDouble(), 5.0)

        val inside = http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("IN", SITE_LAT + 0.0005, SITE_LNG)).obj()["shift"]!!.jsonObject
        assertEquals("Obra Rua do Sol", inside.text("siteName"))
        assertEquals(emptyList(), inside.strings("flags"))
        val verdict = inside["punches"]!!.jsonArray.single().jsonObject
        assertEquals("true", verdict.text("inside"))
        assertEquals(56, verdict.text("siteDistanceM")!!.toInt())

        val left = http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("OUT", SITE_LAT + 0.02, SITE_LNG))
        assertEquals(HttpStatusCode.Created, left.status)
        assertEquals(listOf("OUTSIDE_SITE"), left.obj()["shift"]!!.jsonObject.strings("flags"))
    }

    @Test
    fun `a phone signs punches with its biometric key, and a signature only counts once`() = clockTest { http ->
        val c = company()
        val adminToken = http.token(c.admin.email)
        val employeeToken = http.employeeToken(c, adminToken)
        val phone = keyPair()

        val badNonce = http.nonce(employeeToken)
        val stranger = keyPair()
        val forged = http.send(
            "POST", "/app/api/portal/time/devices", employeeToken,
            buildJsonObject {
                put("platform", "ANDROID"); put("publicKey", Base64.getEncoder().encodeToString(phone.public.encoded))
                put("nonce", badNonce); put("signature", stranger.sign("$badNonce:ENROLL"))
            },
        )
        assertEquals("invalid_signature", forged.error(), "enrolling needs the key itself")

        val keyId = http.enroll(employeeToken, phone)
        val alert = http.send("GET", "/app/api/notifications", adminToken).obj()["items"]!!.jsonArray.map { it.jsonObject }.single { it.text("kind") == "time_device_enrolled" }
        assertEquals("employee:${c.employee.id}", alert.text("ref"))
        assertEquals("Pixel 8", alert["params"]!!.jsonObject.text("device"))
        assertEquals(keyId, http.send("GET", "/app/api/portal/time", employeeToken).obj()["devices"]!!.jsonArray.single().jsonObject.text("keyId"))

        val nonce = http.nonce(employeeToken)
        val body = punch("IN", SITE_LAT, SITE_LNG) {
            put("channel", "APP")
            putJsonObject("verification") { put("keyId", keyId); put("nonce", nonce); put("signature", phone.sign("$nonce:IN")) }
        }
        val signed = http.send("POST", "/app/api/portal/time/punches", employeeToken, body)
        assertEquals(HttpStatusCode.Created, signed.status, signed.bodyAsText())
        val shift = signed.obj()["shift"]!!.jsonObject
        assertEquals(emptyList(), shift.strings("flags"))
        val evidence = shift["punches"]!!.jsonArray.single().jsonObject
        assertEquals("true", evidence.text("verified"))
        assertEquals("Pixel 8", evidence.text("deviceName"))
        assertEquals("APP", evidence.text("channel"))

        val replay = punch("BREAK_START") { putJsonObject("verification") { put("keyId", keyId); put("nonce", nonce); put("signature", phone.sign("$nonce:BREAK_START")) } }
        assertEquals("challenge_expired", http.send("POST", "/app/api/portal/time/punches", employeeToken, replay).error())
        val fresh = http.nonce(employeeToken)
        val wrongType = punch("OUT") { putJsonObject("verification") { put("keyId", keyId); put("nonce", fresh); put("signature", phone.sign("$fresh:IN")) } }
        assertEquals("invalid_signature", http.send("POST", "/app/api/portal/time/punches", employeeToken, wrongType).error())

        http.send("PUT", "/app/api/timesheets/settings", adminToken, buildJsonObject { put("biometric", "REQUIRED") })
        assertEquals("verification_required", http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("OUT")).error())
        assertEquals(HttpStatusCode.Created, http.signedPunch(employeeToken, phone, keyId, "OUT").status)

        val newPhone = keyPair()
        val newKeyId = http.enroll(employeeToken, newPhone, "iPhone 15")
        assertEquals("device_not_enrolled", http.signedPunch(employeeToken, phone, keyId, "IN").error(), "a new phone replaces the old one")
        val devices = http.send("GET", "/app/api/timesheets/devices?employeeId=${c.employee.id}", adminToken).list().map { it.jsonObject }
        assertEquals(listOf("iPhone 15" to "true", "Pixel 8" to "false"), devices.map { it.text("name") to it.text("active") })
        assertEquals("replaced", devices.last().text("revokedBy"))

        val revoked = http.send("DELETE", "/app/api/timesheets/devices/${devices.first().text("id")}", adminToken).obj()
        assertEquals("false", revoked.text("active"))
        assertEquals("device_not_enrolled", http.signedPunch(employeeToken, newPhone, newKeyId, "IN").error())
    }

    @Test
    fun `the team sees who is working, corrects shifts with a reason, approves them and exports the week`() = clockTest { http ->
        val c = company()
        val adminToken = http.token(c.admin.email)
        val memberToken = http.token(c.member.email)
        val employeeToken = http.employeeToken(c, adminToken)

        val shiftId = http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("IN")).obj()["shift"]!!.jsonObject.text("id")!!
        val board = http.send("GET", "/app/api/timesheets/board", memberToken).obj()["working"]!!.jsonArray.single().jsonObject
        assertEquals("Ana Costa", board.text("employeeName"))
        assertEquals("OPEN", board.text("status"))

        assertEquals("not_overdue", http.send("POST", "/app/api/portal/time/shifts/$shiftId/close", employeeToken, buildJsonObject { put("end", "17:00") }).error())
        offset = 14.hours
        val overdue = http.send("GET", "/app/api/portal/time", employeeToken).obj()
        assertEquals("true", overdue.text("overdue"))
        val startTime = overdue["open"]!!.jsonObject.text("startTime")!!
        val endTime = laterTime(startTime, hours = 8)
        val corrected = http.send("POST", "/app/api/portal/time/shifts/$shiftId/close", employeeToken, buildJsonObject { put("end", endTime) })
        assertEquals(HttpStatusCode.OK, corrected.status, corrected.bodyAsText())
        val closed = corrected.obj()["shift"]!!.jsonObject
        assertTrue(closed.text("workedMinutes")!!.toInt() in 479..480, "the end is typed to the minute")
        assertTrue("MISSED_CLOCK_OUT" in closed.strings("flags"))
        val missed = http.send("GET", "/app/api/notifications", memberToken).obj()["items"]!!.jsonArray.map { it.jsonObject }.single { it.text("kind") == "time_missed_clock_out" }
        assertEquals("shift:$shiftId", missed.text("ref"))

        val day = closed.text("day")!!
        val week = http.send("GET", "/app/api/timesheets?from=$day&to=$day", memberToken).obj()
        val totals = week["totals"]!!.jsonArray.single().jsonObject
        assertEquals(closed.text("workedMinutes")!!.toInt(), totals.text("workedMinutes")!!.toInt())
        assertEquals(1, totals.text("toReview")!!.toInt())
        assertNull(week["shifts"]!!.jsonArray.single().jsonObject["punches"], "the list leaves the evidence to the detail")

        val edit = buildJsonObject {
            put("start", startTime); put("end", laterTime(startTime, hours = 9))
            putJsonArray("breaks") { addJsonObject { put("start", laterTime(startTime, hours = 4)); put("end", laterTime(startTime, hours = 5)) } }
        }
        assertEquals("reason_required", http.send("PATCH", "/app/api/timesheets/shifts/$shiftId", memberToken, edit).error())
        val withReason = JsonObject(edit + ("reason" to kotlinx.serialization.json.JsonPrimitive("Esqueceu a pausa de almoço")))
        val edited = http.send("PATCH", "/app/api/timesheets/shifts/$shiftId", memberToken, withReason).obj()
        assertEquals(480, edited.text("workedMinutes")!!.toInt())
        assertEquals("true", edited.text("edited"))
        val history = edited["edits"]!!.jsonArray.single().jsonObject
        assertEquals(c.member.email, history.text("by"))
        assertEquals(endTime, history["before"]!!.jsonObject.text("endTime"))

        val approved = http.send("POST", "/app/api/timesheets/shifts/approve", memberToken, buildJsonObject { putJsonArray("ids") { add(kotlinx.serialization.json.JsonPrimitive(shiftId)); add(kotlinx.serialization.json.JsonPrimitive("nope")) } })
        assertEquals(1, approved.obj().text("approved")!!.toInt())
        assertEquals("APPROVED", http.send("GET", "/app/api/portal/time/shifts?from=$day&to=$day", employeeToken).list().single().jsonObject.text("review"))
        assertEquals("already_approved", http.send("PATCH", "/app/api/portal/time/shifts/$shiftId", employeeToken, buildJsonObject { put("note", "ok") }).error())

        val manual = http.send(
            "POST", "/app/api/timesheets/shifts", adminToken,
            buildJsonObject { put("employeeId", c.employee.id.toHexString()); put("day", day); put("start", "06:00"); put("end", "06:30"); put("reason", "Sem telemóvel") },
        )
        assertEquals(HttpStatusCode.Created, manual.status, manual.bodyAsText())
        assertEquals(listOf("MANUAL"), manual.obj().strings("flags"))

        val csv = http.send("GET", "/app/api/timesheets/export.csv?from=$day&to=$day", memberToken)
        assertEquals(HttpStatusCode.OK, csv.status)
        assertTrue(csv.headers["Content-Disposition"]!!.contains("timesheets-$day-$day.csv"))
        val rows = csv.bodyAsText().removePrefix("\uFEFF").trimEnd().split("\r\n")
        assertEquals(3, rows.size)
        assertTrue(rows.drop(1).all { it.contains("Ana Costa") })

        assertEquals("in_use", http.send("DELETE", "/app/api/crm/employees/${c.employee.id}", adminToken).error(), "shifts are kept, so the employee is archived instead")
    }

    @Test
    fun `coordinates expire after the retention period, the site verdict stays`() = clockTest { http ->
        val c = company()
        val adminToken = http.token(c.admin.email)
        val employeeToken = http.employeeToken(c, adminToken)
        http.send("POST", "/app/api/timesheets/sites", adminToken, buildJsonObject { put("name", "Escritório"); put("latitude", SITE_LAT); put("longitude", SITE_LNG) })
        val id = http.send("POST", "/app/api/portal/time/punches", employeeToken, punch("IN", SITE_LAT, SITE_LNG)).obj()["shift"]!!.jsonObject.text("id")!!

        assertEquals(0, TimesheetLocationRetention(mongo, clock = now).purge())
        assertTrue(TimesheetLocationRetention(mongo, clock = { now() + TimesheetLocationRetention.RETENTION + 1.hours }).purge() >= 1)
        val stored = assertNotNull(ShiftRepository(mongo, c.id).findById(ObjectId(id))).punches.single()
        assertNull(stored.location?.latitude)
        assertNull(stored.location?.longitude)
        assertEquals(12.0, stored.location?.accuracyM)
        assertEquals("Escritório", stored.site?.siteName)
        assertEquals(true, stored.site?.inside)
    }

    /** [start] (`HH:mm`) plus [hours], on a 24-hour clock. */
    private fun laterTime(start: String, hours: Int): String {
        val (h, m) = start.split(":").map { it.toInt() }
        return "${((h + hours) % 24).toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
    }
}
