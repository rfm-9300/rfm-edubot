package com.rfm.edubot.dashboard

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.mongodb.client.model.Updates
import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.dashboard.model.DashboardUserStatus
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.notifications.notificationRoutes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
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
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Employees signing in to register their services, and the team approving them, over HTTP with the real auth plugin. */
@Testcontainers
class EmployeeWorkRoutesTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "employee_work"))
            mongoModule.initialize()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }

        private const val JWT_SECRET = "test-secret"
        private const val PASSWORD = "correct horse"

        // Made up per run: GitGuardian blocks the pull request on a password-like literal.
        private val EMPLOYEE_PASSWORD = ObjectId().toHexString()
    }

    private val now = Clock.System.now()
    private val users get() = DashboardUserRepository(mongoModule)
    private val tenants get() = TenantRepository(mongoModule)
    private val lookup: EmployeeLookup = { tenantId, id -> EmployeeRepository(mongoModule, tenantId).findById(id) }
    private val runtime = RuntimeConfig(
        AppConfig(
            port = 8080,
            whatsapp = AppConfig.WhatsAppConfig(verifyToken = "v", appSecret = "s", phoneNumberId = "1", accessToken = "t"),
            instagram = AppConfig.InstagramConfig(appId = "ig", appSecret = "s", redirectUri = "https://example.com/cb"),
            openrouter = AppConfig.OpenRouterConfig(apiKey = "k", primaryModel = "a", fallbackModel = "b", maxTokens = 512),
            mongo = AppConfig.MongoConfig(uri = "unused", database = "unused"),
            rateLimit = AppConfig.RateLimitConfig(),
            admin = AppConfig.AdminConfig(jwtSecret = JWT_SECRET),
            pdfStoragePath = "/tmp/pdfs",
        ),
    )
    private val json = Json { ignoreUnknownKeys = true }

    private fun workTest(block: suspend (HttpClient) -> Unit): Unit = testApplication {
        application {
            configureSerialization()
            configureAdminAuth(runtime, tenants, users, lookup)
            routing {
                dashboardAccountRoutes(tenants, users, runtime, employees = lookup)
                dashboardRoutes(
                    mongo = mongoModule,
                    tenantRepository = tenants,
                    dashboardUsers = users,
                    pipelineFactory = mockk(relaxed = true),
                    personaCompiler = mockk(relaxed = true),
                    aiClient = mockk(relaxed = true),
                    runtimeConfig = runtime,
                    channelBindingService = mockk(relaxed = true),
                    instagramSocial = mockk(relaxed = true),
                )
                dashboardImpersonationRoute(tenants, users, runtime)
                employeeWorkRoutes(mongoModule, users, NotificationRepository(mongoModule))
                notificationRoutes(NotificationRepository(mongoModule))
            }
        }
        block(client)
    }

    /** A company with an admin, a member, an employee and a client, everything the work needs. */
    private inner class Company(private val modules: List<String>? = null) {
        val id = ObjectId()
        lateinit var tenant: Tenant
        lateinit var admin: DashboardUser
        lateinit var member: DashboardUser
        lateinit var employee: Employee
        lateinit var client: Client

        suspend fun create(): Company {
            tenant = tenants.create(
                Tenant(id = id, slug = "t-${id.toHexString()}", name = "Obras Silva", channels = emptyList(), enabledModules = modules, status = TenantStatus.ACTIVE, createdAt = now, updatedAt = now),
            )
            admin = newUser(DashboardUserRole.TENANT_ADMIN)
            member = newUser(DashboardUserRole.TENANT_MEMBER)
            employee = EmployeeRepository(mongoModule, id).create("Ana Costa", "+351 910 ${id.toHexString().takeLast(6)}", "Pintora")
            client = ClientRepository(mongoModule, id).create("Casa Martins", "+351 920 ${id.toHexString().takeLast(6)}", "Rua do Sol 4", taxId = "123456789")
            return this
        }

        private suspend fun newUser(role: DashboardUserRole): DashboardUser {
            val userId = ObjectId()
            return users.create(
                DashboardUser(
                    id = userId, tenantId = id, email = "${role.name.lowercase()}-${userId.toHexString()}@obras.test",
                    passwordHash = BCrypt.withDefaults().hashToString(4, PASSWORD.toCharArray()), role = role, createdAt = now,
                ),
            )
        }

        val employeeEmail get() = "ana-${id.toHexString()}@obras.test"
    }

    private suspend fun company(modules: List<String>? = null) = Company(modules).create()

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
            "DELETE" -> delete(path, request)
            else -> post(path, request)
        }
    }

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.list(): JsonArray = json.parseToJsonElement(bodyAsText()).jsonArray
    private suspend fun HttpResponse.error(): String? = obj()["error"]?.jsonPrimitive?.content
    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.content

    private suspend fun HttpClient.login(email: String, password: String = PASSWORD): HttpResponse =
        send("POST", "/app/auth/login", null, buildJsonObject { put("email", email); put("password", password) })

    private suspend fun HttpClient.token(email: String, password: String = PASSWORD): String = login(email, password).obj().text("token")!!

    private fun access(email: String, password: String = EMPLOYEE_PASSWORD) = buildJsonObject { put("email", email); put("password", password) }

    private suspend fun HttpClient.giveAccess(c: Company, adminToken: String): String {
        assertEquals(HttpStatusCode.Created, send("POST", "/app/api/crm/employees/${c.employee.id}/access", adminToken, access(c.employeeEmail)).status)
        return token(c.employeeEmail, EMPLOYEE_PASSWORD)
    }

    private val today get() = Clock.System.todayIn(TimeZone.of("Europe/Lisbon"))

    private fun work(c: Company, hours: Double = 3.0, day: String = today.toString(), extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("clientId", c.client.id.toHexString())
        put("performedAt", day)
        putJsonArray("items") {
            addJsonObject { put("description", "Pintura interior"); put("quantity", hours); put("unit", "h"); put("unitPriceEur", 18.5) }
            addJsonObject { put("description", "Tinta"); put("quantity", 1); put("unitPriceEur", 24.9) }
        }
        extra()
    }

    @Test
    fun `an admin gives an employee a sign-in, which only reaches the employee's own pages`() = workTest { http ->
        val c = company()
        val adminToken = http.token(c.admin.email)
        val memberToken = http.token(c.member.email)
        val accessPath = "/app/api/crm/employees/${c.employee.id}/access"

        assertNull(http.send("GET", accessPath, memberToken).obj().text("email"))
        assertEquals("not_allowed", http.send("POST", accessPath, memberToken, access(c.employeeEmail)).error())
        assertEquals("password_too_short", http.send("POST", accessPath, adminToken, access(c.employeeEmail, "short")).error())
        assertEquals("invalid_email", http.send("POST", accessPath, adminToken, access("not-an-email")).error())
        assertEquals("email_taken", http.send("POST", accessPath, adminToken, access(c.member.email)).error())
        val given = http.send("POST", accessPath, adminToken, access(c.employeeEmail.uppercase()))
        assertEquals(HttpStatusCode.Created, given.status)
        assertEquals(c.employeeEmail, given.obj().text("email"))
        assertEquals("already_has_access", http.send("POST", accessPath, adminToken, access("other@obras.test")).error())

        val employeeToken = http.token(c.employeeEmail, EMPLOYEE_PASSWORD)
        val me = http.send("GET", "/app/api/me", employeeToken).obj()
        assertEquals(listOf(EmployeePortal.MODULE), me["modules"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Ana Costa", me["employee"]!!.jsonObject.text("name"))
        assertEquals("TENANT_EMPLOYEE", me["user"]!!.jsonObject.text("role"))

        listOf("/app/api/overview", "/app/api/crm/clients", "/app/api/crm/services", "/app/api/crm/service-submissions", "/app/api/notifications")
            .forEach { assertEquals(HttpStatusCode.Unauthorized, http.send("GET", it, employeeToken).status, it) }
        assertEquals(HttpStatusCode.OK, http.send("GET", "/app/api/account", employeeToken).status)

        val clients = http.send("GET", "/app/api/portal/clients", employeeToken).list()
        assertEquals(listOf("Casa Martins"), clients.map { it.jsonObject.text("name") })
        assertNull(clients.single().jsonObject["phone"], "employees pick a client by name, without its details")
        assertEquals("employees_only", http.send("GET", "/app/api/portal/services", adminToken).error())
    }

    @Test
    fun `a registered service is saved as a real service once approved, and only once`() = workTest { http ->
        val c = company()
        val adminToken = http.token(c.admin.email)
        val employeeToken = http.giveAccess(c, adminToken)

        assertEquals("date_in_future", http.send("POST", "/app/api/portal/services", employeeToken, work(c, day = today.plus(2, DateTimeUnit.DAY).toString())).error())
        assertEquals("client_not_found", http.send("POST", "/app/api/portal/services", employeeToken, work(c) { put("clientId", ObjectId().toHexString()) }).error())
        val sent = http.send("POST", "/app/api/portal/services", employeeToken, work(c))
        assertEquals(HttpStatusCode.Created, sent.status)
        val submission = sent.obj()
        val id = submission.text("id")!!
        assertEquals("PENDING", submission.text("status"))
        assertEquals("Pintura interior + Tinta", submission.text("name"))
        assertEquals(80.4, submission.text("totalEur")!!.toDouble())

        val bell = http.send("GET", "/app/api/notifications", adminToken).obj()["items"]!!.jsonArray.map { it.jsonObject }
        val notice = bell.single { it.text("kind") == "service_submitted" }
        assertEquals("submission:$id", notice.text("ref"))
        assertEquals("Ana Costa", notice["params"]!!.jsonObject.text("employee"))

        val changed = http.send("PATCH", "/app/api/portal/services/$id", employeeToken, work(c, hours = 2.0))
        assertEquals(61.9, changed.obj().text("totalEur")!!.toDouble())

        val pending = http.send("GET", "/app/api/crm/service-submissions?status=PENDING&employeeId=${c.employee.id}", adminToken).list()
        assertEquals(listOf("Ana Costa"), pending.map { it.jsonObject.text("employeeName") })

        val approved = http.send("POST", "/app/api/crm/service-submissions/$id/approve", adminToken, JsonObject(emptyMap()))
        assertEquals(HttpStatusCode.OK, approved.status)
        val result = approved.obj()
        val service = result["service"]!!.jsonObject
        assertEquals(c.employee.id.toHexString(), service.text("employeeId"))
        assertEquals(c.client.id.toHexString(), service.text("clientId"))
        assertEquals("OPEN", service.text("status"))
        assertEquals(61.9, service.text("totalEur")!!.toDouble())
        assertEquals(2, service["items"]!!.jsonArray.size)
        assertEquals(service.text("id"), result["submission"]!!.jsonObject.text("serviceId"))
        val saved = assertNotNull(ClientServiceRepository(mongoModule, c.id).findById(ObjectId(service.text("id"))))
        assertEquals(c.employee.id, saved.employeeId)

        assertEquals("not_pending", http.send("POST", "/app/api/crm/service-submissions/$id/approve", adminToken, JsonObject(emptyMap())).error())
        assertEquals(1, ClientServiceRepository(mongoModule, c.id).list(c.client.id).size)
        assertEquals("not_pending", http.send("PATCH", "/app/api/portal/services/$id", employeeToken, work(c)).error())
        assertEquals("not_pending", http.send("DELETE", "/app/api/portal/services/$id", employeeToken).error())

        val mine = http.send("GET", "/app/api/portal/services", employeeToken).list().single().jsonObject
        assertEquals("APPROVED", mine.text("status"))
        assertEquals(c.admin.email, mine.text("reviewedBy"))

        val removal = http.send("DELETE", "/app/api/crm/employees/${c.employee.id}", adminToken)
        assertEquals("in_use", removal.error(), "an employee with registered work is archived, not deleted")
    }

    @Test
    fun `the team rejects with a reason, or changes the work before approving it`() = workTest { http ->
        val c = company()
        val memberToken = http.token(c.member.email)
        val employeeToken = http.giveAccess(c, http.token(c.admin.email))

        val first = http.send("POST", "/app/api/portal/services", employeeToken, work(c)).obj().text("id")!!
        val rejected = http.send("POST", "/app/api/crm/service-submissions/$first/reject", memberToken, buildJsonObject { put("reason", "Foram 2 horas") })
        assertEquals("REJECTED", rejected.obj().text("status"))
        val seen = http.send("GET", "/app/api/portal/services", employeeToken).list().single().jsonObject
        assertEquals("Foram 2 horas", seen.text("rejectionReason"))
        assertEquals("not_pending", http.send("POST", "/app/api/crm/service-submissions/$first/approve", memberToken, JsonObject(emptyMap())).error())

        val second = http.send("POST", "/app/api/portal/services", employeeToken, work(c) { put("notes", "Sala e corredor") }).obj().text("id")!!
        val changes = buildJsonObject { put("changes", work(c, hours = 2.5) { put("name", "Pintura da sala") }) }
        val approved = http.send("POST", "/app/api/crm/service-submissions/$second/approve", memberToken, changes).obj()
        assertEquals("Pintura da sala", approved["service"]!!.jsonObject.text("name"))
        assertEquals(71.15, approved["service"]!!.jsonObject.text("totalEur")!!.toDouble())
        assertEquals("true", approved["submission"]!!.jsonObject.text("adjusted"))
        assertNull(approved["service"]!!.jsonObject["notes"], "the approver's changes replace the content, notes included")
    }

    @Test
    fun `turning the sign-in off, archiving the employee or removing the module locks them out`() = workTest { http ->
        val c = company()
        val adminToken = http.token(c.admin.email)
        val employeeToken = http.giveAccess(c, adminToken)
        val accessPath = "/app/api/crm/employees/${c.employee.id}/access"
        val me = suspend { http.send("GET", "/app/api/me", employeeToken).status }

        assertEquals(HttpStatusCode.OK, http.send("PATCH", accessPath, adminToken, buildJsonObject { put("active", false) }).status)
        assertEquals(DashboardUserStatus.DISABLED, users.findByEmployee(c.employee.id)?.status)
        assertEquals(HttpStatusCode.Unauthorized, me())
        assertEquals(HttpStatusCode.Unauthorized, http.login(c.employeeEmail, EMPLOYEE_PASSWORD).status)
        val reset = ObjectId().toHexString()
        http.send("PATCH", accessPath, adminToken, buildJsonObject { put("active", true); put("password", reset) })
        assertEquals(HttpStatusCode.OK, me())
        assertEquals(HttpStatusCode.OK, http.login(c.employeeEmail, reset).status)

        http.send("POST", "/app/api/crm/employees/${c.employee.id}/archive", adminToken)
        assertEquals(HttpStatusCode.Unauthorized, me())
        assertEquals(HttpStatusCode.Forbidden, http.login(c.employeeEmail, reset).status)
        http.send("POST", "/app/api/crm/employees/${c.employee.id}/restore", adminToken)
        assertEquals(HttpStatusCode.OK, me())

        tenants.update(c.tenant.slug, Updates.set("enabledModules", listOf(DashboardModules.EMPLOYEES)))
        assertEquals(HttpStatusCode.Unauthorized, me(), "without the services module there is nothing to register")
        tenants.update(c.tenant.slug, Updates.unset("enabledModules"))
        assertEquals(HttpStatusCode.OK, me())

        assertEquals(HttpStatusCode.OK, http.send("DELETE", accessPath, adminToken).status)
        assertEquals(HttpStatusCode.Unauthorized, me())
        assertNull(users.findByEmployee(c.employee.id))
    }

    @Test
    fun `an employee's sign-in is only given from their record, not from the backoffice`() = workTest { http ->
        val c = company()
        val operator = JWT.create()
            .withIssuer(runtime.get().admin.jwtIssuer)
            .withSubject("admin")
            .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
            .sign(Algorithm.HMAC256(JWT_SECRET))
        val create = { role: String -> buildJsonObject { put("email", "x-$role-${c.id}@obras.test"); put("password", PASSWORD); put("role", role) } }
        assertEquals("invalid_role", http.send("POST", "/admin/api/tenants/${c.tenant.slug}/dashboard-users", operator, create("TENANT_EMPLOYEE")).error())
        assertEquals("invalid_role", http.send("POST", "/admin/api/tenants/${c.tenant.slug}/dashboard-users", operator, create("OWNER")).error())
        assertEquals(HttpStatusCode.Created, http.send("POST", "/admin/api/tenants/${c.tenant.slug}/dashboard-users", operator, create("TENANT_MEMBER")).status)
        assertTrue(users.listByTenant(c.id).none { it.isEmployee })
    }
}
