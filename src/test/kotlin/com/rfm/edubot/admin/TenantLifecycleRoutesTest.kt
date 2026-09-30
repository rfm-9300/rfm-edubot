package com.rfm.edubot.admin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.tenant.TenantRegistry
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days

/** Deleting and restoring tenants from the backoffice, companies included. */
@Testcontainers
class TenantLifecycleRoutesTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "tenant_lifecycle"))
            mongoModule.initialize()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }

        private const val JWT_SECRET = "test-secret"
    }

    private val now = Clock.System.now()
    private val tenants get() = TenantRepository(mongoModule)
    private val registry = TenantRegistry(TenantRepository(mongoModule))
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

    private fun lifecycleTest(block: suspend (HttpClient) -> Unit): Unit = testApplication {
        application {
            configureSerialization()
            configureAdminAuth(runtime, tenants, DashboardUserRepository(mongoModule))
            routing {
                tenantAdminRoutes(mongoModule, tenants, registry, mockk(relaxed = true), runtime)
            }
        }
        block(client)
    }

    private suspend fun newTenant(
        maxCompanies: Int = 1,
        parent: Tenant? = null,
        status: TenantStatus = TenantStatus.ACTIVE,
        deletedAt: kotlinx.datetime.Instant? = null,
        channels: List<ChannelBinding> = emptyList(),
    ): Tenant {
        val id = ObjectId()
        val tenant = tenants.create(
            Tenant(
                id = id, slug = "t-${id.toHexString()}", name = "Acme ${id.toHexString().takeLast(4)}", channels = channels,
                maxCompanies = maxCompanies, parentTenantId = parent?.id, status = status, deletedAt = deletedAt,
                createdAt = now, updatedAt = now,
            ),
        )
        if (status != TenantStatus.DELETED) registry.put(tenant)
        return tenant
    }

    private val admin: String = JWT.create()
        .withIssuer(runtime.get().admin.jwtIssuer)
        .withSubject("admin")
        .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
        .sign(Algorithm.HMAC256(JWT_SECRET))

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.error(): String? = json()["error"]?.jsonPrimitive?.content

    private suspend fun HttpClient.action(tenant: Tenant, action: String) = post("/admin/api/tenants/${tenant.slug}/$action") { bearerAuth(admin) }
    private suspend fun HttpClient.remove(tenant: Tenant) = delete("/admin/api/tenants/${tenant.slug}") { bearerAuth(admin) }
    private suspend fun reloaded(tenant: Tenant) = assertNotNull(tenants.findById(tenant.id))

    @Test
    fun `deleting keeps the tenant, stamps it and its companies with one deletedAt, and a second delete changes nothing`() = lifecycleTest { client ->
        val first = newTenant(maxCompanies = 2)
        val company = newTenant(parent = first)

        assertEquals(HttpStatusCode.OK, client.remove(first).status)
        val deletedAt = assertNotNull(reloaded(first).deletedAt)
        assertEquals(TenantStatus.DELETED, reloaded(company).status)
        assertEquals(deletedAt, reloaded(company).deletedAt)

        val listed = client.get("/admin/api/tenants") { bearerAuth(admin) }
        val row = Json.parseToJsonElement(listed.bodyAsText()).jsonArray.map { it.jsonObject }
            .single { it["slug"]!!.jsonPrimitive.content == first.slug }
        assertEquals("DELETED", row["status"]!!.jsonPrimitive.content)
        assertEquals(deletedAt.toString(), row["deletedAt"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.OK, client.remove(first).status)
        assertEquals(deletedAt, reloaded(first).deletedAt)
    }

    @Test
    fun `restoring a first company brings back the companies deleted with it, but not one deleted on its own before`() = lifecycleTest { client ->
        val first = newTenant(maxCompanies = 3)
        val withIt = newTenant(parent = first)
        val before = newTenant(parent = first, status = TenantStatus.DELETED, deletedAt = now - 2.days)
        client.remove(first)

        val restored = client.action(first, "restore")
        assertEquals(HttpStatusCode.OK, restored.status)
        assertEquals("ACTIVE", restored.json()["status"]!!.jsonPrimitive.content)
        assertNull(reloaded(first).deletedAt)
        assertEquals(TenantStatus.ACTIVE, reloaded(withIt).status)
        assertNull(reloaded(withIt).deletedAt)
        assertEquals(TenantStatus.DELETED, reloaded(before).status)
    }

    @Test
    fun `a company comes back only while its first company is live and has a free slot`() = lifecycleTest { client ->
        val first = newTenant(maxCompanies = 2)
        val live = newTenant(parent = first)
        val gone = newTenant(parent = first, status = TenantStatus.DELETED, deletedAt = now - 1.days)

        val full = client.action(gone, "restore")
        assertEquals(HttpStatusCode.Conflict, full.status)
        assertEquals("company_limit", full.error())

        client.remove(live)
        assertEquals(HttpStatusCode.OK, client.action(gone, "restore").status)
        assertEquals(TenantStatus.ACTIVE, reloaded(gone).status)

        val deletedFirst = newTenant(maxCompanies = 2, status = TenantStatus.DELETED, deletedAt = now - 1.days)
        val orphan = newTenant(parent = deletedFirst, status = TenantStatus.DELETED, deletedAt = now - 1.days)
        val refused = client.action(orphan, "restore")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("parent_deleted", refused.error())
    }

    @Test
    fun `only a deleted tenant can be restored, and a deleted one can't be suspended or activated`() = lifecycleTest { client ->
        val active = newTenant()
        val notDeleted = client.action(active, "restore")
        assertEquals(HttpStatusCode.Conflict, notDeleted.status)
        assertEquals("not_deleted", notDeleted.error())

        val deleted = newTenant(status = TenantStatus.DELETED, deletedAt = now)
        for (action in listOf("suspend", "activate")) {
            val refused = client.action(deleted, action)
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertEquals("tenant_deleted", refused.error())
        }
        assertEquals(TenantStatus.DELETED, reloaded(deleted).status)

        assertEquals(HttpStatusCode.NotFound, client.post("/admin/api/tenants/no-such-tenant/restore") { bearerAuth(admin) }.status)
    }

    @Test
    fun `a restored tenant answers on its channels again`() = lifecycleTest { client ->
        val phone = "phone-${ObjectId().toHexString()}"
        val tenant = newTenant(channels = listOf(ChannelBinding(Platform.WHATSAPP, phone, "token")))

        client.remove(tenant)
        assertNull(registry.byPhoneNumberId(phone))

        client.action(tenant, "restore")
        assertEquals(tenant.id, registry.byPhoneNumberId(phone)?.id)
        assertEquals(TenantStatus.ACTIVE, registry.byPhoneNumberId(phone)?.status)
    }

    @Test
    fun `a new tenant can't take a slug that another tenant holds, deleted or not`() = lifecycleTest { client ->
        val deleted = newTenant(status = TenantStatus.DELETED, deletedAt = now)
        val live = newTenant()

        for ((holder, status) in listOf(deleted to "DELETED", live to "ACTIVE")) {
            val response = client.post("/admin/api/tenants") {
                bearerAuth(admin)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("name", "Copycat"); put("slug", " ${holder.slug} ") }.toString())
            }
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("slug_taken", response.error())
            assertEquals(status, response.json()["detail"]!!.jsonPrimitive.content)
        }
    }
}
