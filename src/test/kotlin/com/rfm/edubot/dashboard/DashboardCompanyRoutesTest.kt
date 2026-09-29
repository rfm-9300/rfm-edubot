package com.rfm.edubot.dashboard

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.admin.tenantAdminRoutes
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.tenant.TenantRegistry
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import kotlin.test.assertTrue

/** Companies inside a tenant over HTTP: the dashboard routes, and the backoffice side of the limit and lifecycle. */
@Testcontainers
class DashboardCompanyRoutesTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "dashboard_companies"))
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
    }

    private val now = Clock.System.now()
    private val users get() = DashboardUserRepository(mongoModule)
    private val tenants get() = TenantRepository(mongoModule)
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

    private fun companiesTest(block: suspend (HttpClient) -> Unit): Unit = testApplication {
        application {
            configureSerialization()
            configureAdminAuth(runtime, tenants, users)
            routing {
                dashboardAccountRoutes(tenants, users, runtime)
                dashboardCompanyRoutes(tenants, runtime)
                dashboardImpersonationRoute(tenants, users, runtime)
                tenantAdminRoutes(mongoModule, tenants, TenantRegistry(tenants), mockk(relaxed = true), runtime)
            }
        }
        block(client)
    }

    private suspend fun newTenant(
        maxCompanies: Int = 1,
        modules: List<String>? = null,
        parent: Tenant? = null,
        status: TenantStatus = TenantStatus.ACTIVE,
    ): Tenant {
        val id = ObjectId()
        return tenants.create(
            Tenant(
                id = id, slug = "t-${id.toHexString()}", name = "Acme ${id.toHexString().takeLast(4)}", channels = emptyList(),
                enabledModules = modules, maxCompanies = maxCompanies, parentTenantId = parent?.id, status = status,
                createdAt = now, updatedAt = now,
            ),
        )
    }

    private suspend fun newUser(tenant: Tenant, role: DashboardUserRole = DashboardUserRole.TENANT_ADMIN): DashboardUser {
        val id = ObjectId()
        return users.create(
            DashboardUser(
                id = id, tenantId = tenant.id, email = "owner-${id.toHexString()}@acme.test",
                passwordHash = BCrypt.withDefaults().hashToString(4, PASSWORD.toCharArray()), role = role, createdAt = now,
            ),
        )
    }

    private fun adminToken(): String = JWT.create()
        .withIssuer(runtime.get().admin.jwtIssuer)
        .withSubject("admin")
        .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
        .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun operatorToken(tenant: Tenant): String = JWT.create()
        .withIssuer(runtime.get().admin.jwtIssuer)
        .withSubject("operator")
        .withClaim("tenantId", tenant.id.toHexString())
        .withClaim("role", "PLATFORM_ADMIN")
        .withClaim("typ", DashboardAccessPolicy.OPERATOR_IMPERSONATION)
        .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
        .sign(Algorithm.HMAC256(JWT_SECRET))

    private suspend fun HttpClient.postJson(path: String, body: JsonObject, token: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        token?.let { bearerAuth(it) }
        setBody(body.toString())
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.error(): String? = json()["error"]?.jsonPrimitive?.content

    private suspend fun HttpClient.login(user: DashboardUser): String =
        postJson("/app/auth/login", buildJsonObject { put("email", user.email); put("password", PASSWORD) }).json()["token"]!!.jsonPrimitive.content

    private suspend fun HttpClient.addCompany(token: String, name: String) =
        postJson("/app/api/companies", buildJsonObject { put("name", name) }, token)

    private suspend fun HttpClient.switchTo(token: String, companyId: String) = post("/app/api/companies/$companyId/switch") { bearerAuth(token) }

    private val withSettings = listOf(DashboardModules.OVERVIEW, DashboardModules.SETTINGS, DashboardModules.CLIENTS)

    @Test
    fun `an administrator adds companies up to the limit, each starting empty with the first company's modules`() = companiesTest { client ->
        val first = newTenant(maxCompanies = 2, modules = withSettings)
        val token = client.login(newUser(first))

        val before = client.get("/app/api/companies") { bearerAuth(token) }.json()
        assertEquals(2, before["limit"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, before["companies"]!!.jsonArray.size)
        assertEquals("true", before["canManage"]!!.jsonPrimitive.content)

        assertEquals("name_required", client.addCompany(token, "   ").error())
        val created = client.addCompany(token, "Loja Nova Ç")
        assertEquals(HttpStatusCode.Created, created.status)
        val body = created.json()
        assertEquals("Loja Nova Ç", body["name"]!!.jsonPrimitive.content)
        assertEquals("${first.slug}-loja-nova-c", body["slug"]!!.jsonPrimitive.content)

        val company = assertNotNull(tenants.findById(ObjectId(body["id"]!!.jsonPrimitive.content)))
        assertEquals(first.id, company.parentTenantId)
        assertEquals(withSettings, company.enabledModules)
        assertTrue(company.channels.isEmpty())

        val third = client.addCompany(token, "Third")
        assertEquals(HttpStatusCode.Conflict, third.status)
        assertEquals("company_limit", third.error())
    }

    @Test
    fun `members can't add companies, and the list lives in Settings`() = companiesTest { client ->
        val first = newTenant(maxCompanies = 3, modules = withSettings)
        val member = client.login(newUser(first, DashboardUserRole.TENANT_MEMBER))
        assertEquals("not_allowed", client.addCompany(member, "Side business").error())
        assertEquals("false", client.get("/app/api/companies") { bearerAuth(member) }.json()["canManage"]!!.jsonPrimitive.content)

        val noSettings = newTenant(maxCompanies = 3, modules = listOf(DashboardModules.OVERVIEW))
        val admin = client.login(newUser(noSettings))
        assertEquals(HttpStatusCode.Forbidden, client.get("/app/api/companies") { bearerAuth(admin) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.addCompany(admin, "Side business").status)
    }

    @Test
    fun `switching moves the session to another company of the tenant and keeps its expiry, and sign-in opens the first company`() = companiesTest { client ->
        val first = newTenant(maxCompanies = 2, modules = withSettings)
        val second = newTenant(modules = withSettings, parent = first)
        val user = newUser(first)
        val token = client.login(user)

        val switched = client.switchTo(token, second.id.toHexString())
        assertEquals(HttpStatusCode.OK, switched.status)
        val next = switched.json()["token"]!!.jsonPrimitive.content
        val decoded = JWT.decode(next)
        assertEquals(second.id.toHexString(), decoded.getClaim("tenantId").asString())
        assertEquals(user.id.toHexString(), decoded.subject)
        assertEquals(JWT.decode(token).expiresAt, decoded.expiresAt)
        assertEquals(second.id.toHexString(), client.get("/app/api/companies") { bearerAuth(next) }.json()["current"]!!.jsonPrimitive.content)

        val back = client.switchTo(next, first.id.toHexString()).json()["token"]!!.jsonPrimitive.content
        assertEquals(first.id.toHexString(), JWT.decode(back).getClaim("tenantId").asString())
        assertEquals(first.id.toHexString(), JWT.decode(client.login(user)).getClaim("tenantId").asString())
    }

    @Test
    fun `a session can't switch to another tenant's company, a suspended one, or a bad id`() = companiesTest { client ->
        val first = newTenant(maxCompanies = 3, modules = withSettings)
        val suspended = newTenant(parent = first, status = TenantStatus.SUSPENDED)
        val stranger = newTenant(parent = newTenant(maxCompanies = 2))
        val token = client.login(newUser(first))

        assertEquals(HttpStatusCode.NotFound, client.switchTo(token, stranger.id.toHexString()).status)
        assertEquals(HttpStatusCode.NotFound, client.switchTo(token, ObjectId().toHexString()).status)
        assertEquals(HttpStatusCode.NotFound, client.switchTo(token, "not-an-id").status)
        val refused = client.switchTo(token, suspended.id.toHexString())
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("company_inactive", refused.error())
    }

    @Test
    fun `an operator opening the dashboard switches companies as an operator`() = companiesTest { client ->
        val first = newTenant(maxCompanies = 2)
        val second = newTenant(parent = first)

        val switched = client.switchTo(operatorToken(first), second.id.toHexString())
        assertEquals(HttpStatusCode.OK, switched.status)
        val decoded = JWT.decode(switched.json()["token"]!!.jsonPrimitive.content)
        assertEquals("operator", decoded.subject)
        assertEquals(DashboardAccessPolicy.OPERATOR_IMPERSONATION, decoded.getClaim("typ").asString())
        assertEquals(second.id.toHexString(), decoded.getClaim("tenantId").asString())
    }

    @Test
    fun `the backoffice sets the limit, the first company's lifecycle carries its companies, and users stay on the first company`() = companiesTest { client ->
        val admin = adminToken()
        val first = newTenant()
        val second = newTenant(parent = first)
        val user = newUser(first)

        val updated = client.put("/admin/api/tenants/${first.slug}") {
            bearerAuth(admin)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", first.name); put("maxCompanies", 99) }.toString())
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals(20, tenants.findById(first.id)!!.maxCompanies)

        client.post("/admin/api/tenants/${first.slug}/suspend") { bearerAuth(admin) }
        assertEquals(TenantStatus.SUSPENDED, tenants.findById(second.id)!!.status)
        client.post("/admin/api/tenants/${first.slug}/activate") { bearerAuth(admin) }
        assertEquals(TenantStatus.ACTIVE, tenants.findById(second.id)!!.status)

        val listed = client.get("/admin/api/tenants/${second.slug}/dashboard-users") { bearerAuth(admin) }.bodyAsText()
        assertTrue(user.email in listed)

        client.delete("/admin/api/tenants/${second.slug}") { bearerAuth(admin) }
        assertEquals(TenantStatus.DELETED, tenants.findById(second.id)!!.status)
        assertEquals(TenantStatus.ACTIVE, tenants.findById(first.id)!!.status)
    }
}
