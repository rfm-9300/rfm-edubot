package com.rfm.edubot.testing

import com.rfm.edubot.AppServices
import com.rfm.edubot.admin.AdminAccess
import com.rfm.edubot.admin.AdminEmailRepository
import com.rfm.edubot.admin.BackupControl
import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.actions.AgentActions
import com.rfm.edubot.agents.registry.AgentRegistry
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.agents.runtime.AgentRuntime
import com.rfm.edubot.agents.runtime.AgentServices
import com.rfm.edubot.agents.store.AgentApprovalRepository
import com.rfm.edubot.agents.store.AgentRunRepository
import com.rfm.edubot.agents.store.AgentSettingsRepository
import com.rfm.edubot.agents.store.OutboundLogRepository
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.PlatformSettingsRepository
import com.rfm.edubot.config.PlatformSettingsService
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.dashboard.DashboardAccessPolicy
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.dashboard.EmployeeLookup
import com.rfm.edubot.dashboard.dashboardToken
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.installRoutes
import com.rfm.edubot.instagram.InstagramSocialService
import com.rfm.edubot.integrations.email.EmailMessageRepository
import com.rfm.edubot.integrations.email.EmailService
import com.rfm.edubot.integrations.google.GoogleIntegration
import com.rfm.edubot.messaging.DeduplicationService
import com.rfm.edubot.messaging.MessageQueue
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.oauth.InstagramOAuthClient
import com.rfm.edubot.oauth.OAuthState
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.plugins.configureStatusPages
import com.rfm.edubot.plugins.configureWebSockets
import com.rfm.edubot.tenant.ChannelBindingService
import com.rfm.edubot.tenant.TenantPipelineFactory
import com.rfm.edubot.tenant.TenantRegistry
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.web.WebChannelRegistry
import com.rfm.edubot.whatsapp.signup.WhatsAppSignupClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bson.types.ObjectId
import java.nio.file.Files

/** An answer from the API: its status and body, with the JSON read when asked for. */
internal class Reply(val status: HttpStatusCode, val body: String) {
    val json: JsonElement by lazy { Json.parseToJsonElement(body) }
    val obj: JsonObject get() = json.jsonObject
    val array: JsonArray get() = json.jsonArray

    /** The `error` code of a refusal, or null when the body has none. */
    val error: String? get() = runCatching { obj["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    val detail: String? get() = runCatching { obj["detail"]?.jsonPrimitive?.contentOrNull }.getOrNull()

    fun text(name: String): String? = (obj[name] as? JsonPrimitive)?.contentOrNull
    fun ids(): List<String> = array.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    override fun toString() = "$status $body"
}

/** A file the API sends (a PDF, a photo): its status, its type and its bytes as they came. */
internal class Download(val status: HttpStatusCode, val contentType: ContentType?, val bytes: ByteArray, val headers: Headers)

/** A company with an admin and a member signed in, plus a token an operator would open it with. */
internal class TestCompany(val tenant: Tenant, val admin: DashboardUser, val token: String, val memberToken: String, val operatorToken: String)

/** The server's routes as the application mounts them, with doubles for the outside world, over MongoDB. */
internal class DashboardApi(val mongo: MongoModule, private val http: HttpClient, private val config: AppConfig.AdminConfig, val routes: RoutingRoot) {
    val tenants = TenantRepository(mongo)
    val users = DashboardUserRepository(mongo)

    /** A new company with [modules] (null: every module a legacy company has). */
    suspend fun company(modules: List<String>? = DashboardModules.catalog, name: String = "Obras Silva", channels: List<ChannelBinding> = emptyList()): TestCompany {
        val now = Clock.System.now()
        val id = ObjectId()
        val tenant = tenants.create(Tenant(id = id, slug = "t-${id.toHexString()}", name = name, channels = channels, enabledModules = modules, createdAt = now, updatedAt = now))
        val admin = users.create(DashboardUser(tenantId = tenant.id, email = "admin-$id@obras.test", passwordHash = null, role = DashboardUserRole.TENANT_ADMIN, createdAt = now))
        val member = users.create(DashboardUser(tenantId = tenant.id, email = "member-$id@obras.test", passwordHash = null, role = DashboardUserRole.TENANT_MEMBER, createdAt = now))
        val operator = dashboardToken(config, tenant, DashboardAccessPolicy.OPERATOR_IMPERSONATION, 1)
        return TestCompany(tenant, admin, dashboardToken(config, admin, DashboardAccessPolicy.TENANT_USER, 1), dashboardToken(config, member, DashboardAccessPolicy.TENANT_USER, 1), operator)
    }

    /** A sign-in for [employeeId] of [company], as the employee portal's. */
    suspend fun employeeToken(company: TestCompany, employeeId: ObjectId): String {
        val user = users.create(
            DashboardUser(
                tenantId = company.tenant.id, email = "staff-${ObjectId()}@obras.test", passwordHash = null, role = DashboardUserRole.TENANT_EMPLOYEE,
                createdAt = Clock.System.now(), employeeId = employeeId, employeeTenantId = company.tenant.id,
            ),
        )
        return dashboardToken(config, user, DashboardAccessPolicy.TENANT_USER, 1)
    }

    /** Sends [body] as it is: JSON unless [type] says otherwise, nothing when it is null. */
    suspend fun call(method: String, path: String, token: String?, body: String? = null, type: ContentType? = ContentType.Application.Json): Reply {
        val response = http.request(path) {
            this.method = HttpMethod.parse(method)
            token?.let { bearerAuth(it) }
            if (body != null) {
                type?.let { contentType(it) }
                setBody(body)
            }
        }
        return Reply(response.status, response.bodyAsText())
    }

    /** [bytes] posted as a file named [fileName], the way a browser's file input sends it, with the form's [fields]. */
    suspend fun TestCompany.upload(path: String, fileName: String, bytes: ByteArray, fields: Map<String, String> = emptyMap()): Reply {
        val form = formData {
            append("file", bytes, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"") })
            fields.forEach { (name, value) -> append(name, value) }
        }
        val response = http.submitFormWithBinaryData(path, form) { bearerAuth(token) }
        return Reply(response.status, response.bodyAsText())
    }

    suspend fun TestCompany.download(path: String, token: String = this.token): Download {
        val response = http.request(path) { bearerAuth(token) }
        return Download(response.status, response.contentType(), response.readRawBytes(), response.headers)
    }

    suspend fun TestCompany.get(path: String, token: String = this.token) = call("GET", path, token)
    suspend fun TestCompany.delete(path: String, token: String = this.token) = call("DELETE", path, token)
    suspend fun TestCompany.post(path: String, token: String = this.token, body: JsonObjectBuilder.() -> Unit = {}) = call("POST", path, token, buildJsonObject(body).toString())
    suspend fun TestCompany.patch(path: String, token: String = this.token, body: JsonObjectBuilder.() -> Unit = {}) = call("PATCH", path, token, buildJsonObject(body).toString())
    suspend fun TestCompany.put(path: String, token: String = this.token, body: JsonObjectBuilder.() -> Unit = {}) = call("PUT", path, token, buildJsonObject(body).toString())

    /** [method] [path] with a body that isn't JSON at all. */
    suspend fun TestCompany.sendMalformed(method: String, path: String) = call(method, path, token, "{\"name\": ")
}

/** Runs [block] against the whole server: StatusPages, the `dashboard` and `admin-jwt` sign-ins, and every route. */
internal fun dashboardApiTest(mongo: MongoModule, block: suspend DashboardApi.() -> Unit): Unit = testApplication {
    val config = AppConfig.AdminConfig(jwtSecret = "test-secret")
    val runtime = RuntimeConfig(
        AppConfig(
            port = 8080,
            whatsapp = AppConfig.WhatsAppConfig(verifyToken = "v", appSecret = "s", phoneNumberId = "1", accessToken = "t"),
            instagram = AppConfig.InstagramConfig(appId = "ig", appSecret = "s", redirectUri = "https://example.com/cb"),
            openrouter = AppConfig.OpenRouterConfig(apiKey = "k", primaryModel = "a", fallbackModel = "b", maxTokens = 512),
            mongo = AppConfig.MongoConfig(uri = "unused", database = "unused"),
            rateLimit = AppConfig.RateLimitConfig(),
            admin = config,
            pdfStoragePath = Files.createTempDirectory("pdfs").toString(),
        ),
    )
    val tenants = TenantRepository(mongo)
    val users = DashboardUserRepository(mongo)
    val lookup: EmployeeLookup = { tenantId, id -> EmployeeRepository(mongo, tenantId).findById(id) }
    lateinit var root: RoutingRoot
    application {
        configureSerialization()
        configureStatusPages()
        configureWebSockets()
        configureAdminAuth(runtime, tenants, users, lookup)
        root = routing { installRoutes(testServices(mongo, runtime, tenants, users, lookup)) }
    }
    startApplication()
    DashboardApi(mongo, client, config, root).block()
}

/** The application's services, with nothing reaching Meta, Google or OpenRouter. */
private fun testServices(mongo: MongoModule, runtime: RuntimeConfig, tenants: TenantRepository, users: DashboardUserRepository, lookup: EmployeeLookup): AppServices {
    val offline = HttpClient(MockEngine) { engine { addHandler { respond("{}", HttpStatusCode.ServiceUnavailable) } } }
    val model = FakeModel()
    val deduplication = DeduplicationService(mongo)
    val webChannels = WebChannelRegistry()
    val pipelines = TenantPipelineFactory(mongo, model.client, deduplication, offline, runtime, webChannels)
    val registry = TenantRegistry(tenants)
    val notifications = NotificationRepository(mongo)
    val agents = AgentsModule(mongo, AgentRegistry(AgentActions.builtIn, TriggerTypes.all), AgentServices(mongo, model.client))
    val google = GoogleIntegration.create(mongo, { AppConfig.GoogleConfig() }, null, offline, notifications)
    val backups = Files.createTempDirectory("backups")
    return AppServices(
        mongo = mongo,
        runtimeConfig = runtime,
        tenants = tenants,
        tenantRegistry = registry,
        dashboardUsers = users,
        employeeLookup = lookup,
        pipelineFactory = pipelines,
        channelBindings = ChannelBindingService(tenants, registry, pipelines),
        personaCompiler = mockk(relaxed = true),
        aiClient = model.client,
        messageQueue = MessageQueue(),
        deduplication = deduplication,
        instagramSocial = InstagramSocialService(mongo, offline),
        platformSettings = PlatformSettingsService(PlatformSettingsRepository(mongo), runtime),
        adminAccess = AdminAccess(AdminEmailRepository(mongo), runtime),
        backups = BackupControl(backups.resolve("archive").toString(), backups.resolve("control").toString()),
        notifications = notifications,
        agents = agents,
        agentRuntime = AgentRuntime(agents, { tenants.findById(it) }, CoroutineScope(Dispatchers.Default + SupervisorJob())),
        google = google,
        email = EmailService(
            google, EmailMessageRepository(mongo), OutboundLogRepository(mongo), DomainEventLog(mongo),
            AgentSettingsRepository(mongo), AgentRunRepository(mongo), AgentApprovalRepository(mongo),
        ),
        oauthState = OAuthState("state-secret"),
        instagramOAuth = InstagramOAuthClient(runtime.get().instagram, offline),
        whatsAppSignup = WhatsAppSignupClient(runtime.get().whatsapp, offline),
        webChannels = webChannels,
    )
}
