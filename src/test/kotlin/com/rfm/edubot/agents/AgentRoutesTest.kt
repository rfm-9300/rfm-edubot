package com.rfm.edubot.agents

import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.agents.actions.AgentActions
import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.model.AgentApproval
import com.rfm.edubot.agents.model.Approvers
import com.rfm.edubot.agents.registry.AgentRegistry
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.agents.runtime.AgentRuntime
import com.rfm.edubot.agents.runtime.AgentServices
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.dashboard.dashboardToken
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.notifications.notificationRoutes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class AgentRoutesTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("agent_routes")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val now = Clock.System.now()
    private val tenants get() = TenantRepository(mongo)
    private val users get() = DashboardUserRepository(mongo)
    private val admin = AppConfig.AdminConfig(jwtSecret = "test-secret")
    private val runtimeConfig = RuntimeConfig(
        AppConfig(
            port = 8080,
            whatsapp = AppConfig.WhatsAppConfig(verifyToken = "v", appSecret = "s", phoneNumberId = "1", accessToken = "t"),
            instagram = AppConfig.InstagramConfig(),
            openrouter = AppConfig.OpenRouterConfig(apiKey = "k"),
            mongo = AppConfig.MongoConfig(uri = "unused"),
            rateLimit = AppConfig.RateLimitConfig(),
            admin = admin,
            pdfStoragePath = "/tmp/pdfs",
        ),
    )
    private val services = AgentServices(mongo)
    private val module = AgentsModule(mongo, AgentRegistry(AgentActions.builtIn, TriggerTypes.all), services)
    private val runtime = AgentRuntime(module, { tenants.findById(it) }, CoroutineScope(Dispatchers.Default + SupervisorJob()))
    private val json = Json { ignoreUnknownKeys = true }

    private class Company(val tenant: Tenant, val adminToken: String, val memberToken: String)

    private suspend fun company(modules: List<String> = listOf(DashboardModules.CLIENTS, DashboardModules.INVOICES, DashboardModules.AGENTS)): Company {
        val tenant = tenants.create(
            Tenant(slug = "t-${ObjectId().toHexString().takeLast(8)}", name = "Obras", channels = emptyList(), enabledModules = modules, createdAt = now, updatedAt = now),
        )
        val adminUser = users.create(DashboardUser(tenantId = tenant.id, email = "admin-${ObjectId()}@example.pt", passwordHash = null, role = DashboardUserRole.TENANT_ADMIN, createdAt = now))
        val memberUser = users.create(DashboardUser(tenantId = tenant.id, email = "member-${ObjectId()}@example.pt", passwordHash = null, role = DashboardUserRole.TENANT_MEMBER, createdAt = now))
        return Company(tenant, dashboardToken(admin, adminUser, "tenant", 1), dashboardToken(admin, memberUser, "tenant", 1))
    }

    private fun routes(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            configureSerialization()
            configureAdminAuth(runtimeConfig, tenants, users)
            routing {
                agentRoutes(module, runtime, tenants)
                notificationRoutes(module.notifications)
            }
        }
        block(client)
    }

    private suspend fun HttpClient.send(method: String, path: String, token: String, body: JsonObject? = null): HttpResponse = when (method) {
        "GET" -> get(path) { bearerAuth(token) }
        "PUT" -> put(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body.toString()) }
        else -> post(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody((body ?: JsonObject(emptyMap())).toString()) }
    }

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject

    private val validDefinition = buildJsonObject {
        put("triggers", json.parseToJsonElement("""[{"id":"t1","type":"event","config":{"event":"invoice.created"}}]"""))
        put("steps", json.parseToJsonElement("""[{"id":"s1","action":"team.notify","input":{"message":"Nova fatura {{invoice.number}}"}}]"""))
    }

    @Test
    fun `the module gate and the admin role decide who may do what`() = routes { http ->
        val company = company()
        val without = company(listOf(DashboardModules.CLIENTS))
        assertEquals(HttpStatusCode.Forbidden, http.send("GET", "/app/api/agents", without.adminToken).status)

        assertEquals(HttpStatusCode.Forbidden, http.send("POST", "/app/api/agents", company.memberToken, buildJsonObject { put("name", "X") }).status)
        val created = http.send("POST", "/app/api/agents", company.adminToken, buildJsonObject { put("name", "Faturas") })
        assertEquals(HttpStatusCode.Created, created.status)
        val draft = created.obj()
        assertEquals("DRAFT", draft["status"]!!.jsonPrimitive.content)
        assertTrue(draft["problems"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }.containsAll(listOf("no_trigger", "no_steps")))
        val id = draft["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.OK, http.send("GET", "/app/api/agents", company.memberToken).status, "members see the agents")
        assertEquals(HttpStatusCode.UnprocessableEntity, http.send("POST", "/app/api/agents/$id/activate", company.adminToken).status, "a draft with problems can't run")

        val saved = http.send("PUT", "/app/api/agents/$id", company.adminToken, buildJsonObject { put("definition", validDefinition) }).obj()
        assertEquals(0, saved["problems"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.Forbidden, http.send("POST", "/app/api/agents/$id/activate", company.memberToken).status)
        val active = http.send("POST", "/app/api/agents/$id/activate", company.adminToken).obj()
        assertEquals("ACTIVE", active["status"]!!.jsonPrimitive.content)

        val other = company()
        assertEquals(HttpStatusCode.NotFound, http.send("GET", "/app/api/agents/$id", other.adminToken).status, "another company never sees it")
    }

    @Test
    fun `the backoffice limit caps active agents`() = routes { http ->
        val company = company()
        module.settings.savePlatform(company.tenant.id, module.settings.get(company.tenant.id).platform.copy(maxActiveAgents = 1))
        val ids = (1..2).map {
            val created = http.send("POST", "/app/api/agents", company.adminToken, buildJsonObject { put("name", "A$it"); put("definition", validDefinition) }).obj()
            created["id"]!!.jsonPrimitive.content
        }
        assertEquals(HttpStatusCode.OK, http.send("POST", "/app/api/agents/${ids[0]}/activate", company.adminToken).status)
        val second = http.send("POST", "/app/api/agents/${ids[1]}/activate", company.adminToken)
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertEquals("agent_limit", second.obj()["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `members decide approvals unless the agent asks for admins, and a decision happens once`() = routes { http ->
        val company = company()
        fun approval(approvers: Approvers) = AgentApproval(
            tenantId = company.tenant.id, agentId = ObjectId(), agentName = "Lembretes", runId = ObjectId(), stepId = "s1",
            action = "team.notify", input = buildJsonObject { put("message", "Olá") },
            preview = ActionPreview(kind = "notify", body = "Olá", editable = listOf("message")),
            approvers = approvers, createdAt = now, expiresAt = now + 3.days,
        )
        val open = runBlocking { module.approvals.insert(approval(Approvers.ANY_MEMBER)) }
        val adminsOnly = runBlocking { module.approvals.insert(approval(Approvers.ADMINS)) }

        val listed = json.parseToJsonElement(http.send("GET", "/app/api/agents/approvals", company.memberToken).bodyAsText()).jsonArray
        assertEquals(setOf(true, false), listed.map { it.jsonObject["canDecide"]!!.jsonPrimitive.content.toBoolean() }.toSet())
        assertEquals(HttpStatusCode.Forbidden, http.send("POST", "/app/api/agents/approvals/${adminsOnly.id}/approve", company.memberToken).status)
        val approved = http.send("POST", "/app/api/agents/approvals/${open.id}/approve", company.memberToken, buildJsonObject { put("input", buildJsonObject { put("message", "Olá, Ana"); put("audience", "everyone") }) })
        assertEquals(HttpStatusCode.OK, approved.status)
        val input = approved.obj()["input"]!!.jsonObject
        assertEquals("Olá, Ana", input["message"]!!.jsonPrimitive.content)
        assertEquals(null, input["audience"], "fields that aren't editable are ignored")
        assertEquals(HttpStatusCode.Conflict, http.send("POST", "/app/api/agents/approvals/${open.id}/reject", company.adminToken).status)
    }

    @Test
    fun `notifications reach their readers and can be marked read`() = routes { http ->
        val company = company()
        runBlocking { module.notifications.notify(company.tenant.id, "agent_failed", params = mapOf("agent" to "Lembretes")) }

        val adminView = http.send("GET", "/app/api/notifications", company.adminToken).obj()
        assertEquals(1L, adminView["unread"]!!.jsonPrimitive.content.toLong())
        assertEquals(0L, http.send("GET", "/app/api/notifications", company.memberToken).obj()["unread"]!!.jsonPrimitive.content.toLong(), "admin notices stay with admins")
        http.send("POST", "/app/api/notifications/read-all", company.adminToken)
        assertEquals(0L, http.send("GET", "/app/api/notifications", company.adminToken).obj()["unread"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `the catalog says what a company can't use yet and why`() = routes { http ->
        val company = company()
        val catalog = http.send("GET", "/app/api/agents/catalog", company.adminToken).obj()
        val whatsapp = catalog["actions"]!!.jsonArray.map { it.jsonObject }.single { it["key"]!!.jsonPrimitive.content == "whatsapp.send" }
        assertEquals("false", whatsapp["available"]!!.jsonPrimitive.content)
        assertEquals("needs_integration:WHATSAPP", whatsapp["reason"]!!.jsonPrimitive.content)
        val bookingEvent = catalog["events"]!!.jsonArray.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "booking.created" }
        assertEquals("needs_module:bookings", bookingEvent["reason"]!!.jsonPrimitive.content)
    }
}
