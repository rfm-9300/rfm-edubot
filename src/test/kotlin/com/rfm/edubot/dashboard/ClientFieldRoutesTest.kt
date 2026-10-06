package com.rfm.edubot.dashboard

import at.favre.lib.crypto.bcrypt.BCrypt
import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A company shaping its Clients directory, and staff saving clients against it, over HTTP with the real auth plugin. */
class ClientFieldRoutesTest {

    companion object {
        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongoModule = TestMongo.module("client_fields")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
        }

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
            admin = AppConfig.AdminConfig(jwtSecret = "test-secret"),
            pdfStoragePath = "/tmp/pdfs",
        ),
    )
    private val json = Json { ignoreUnknownKeys = true }

    private fun fieldsTest(block: suspend (HttpClient) -> Unit): Unit = testApplication {
        application {
            configureSerialization()
            configureAdminAuth(runtime, tenants, users)
            routing {
                dashboardAccountRoutes(tenants, users, runtime)
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
            }
        }
        block(client)
    }

    private inner class Company {
        val id = ObjectId()
        lateinit var admin: DashboardUser
        lateinit var member: DashboardUser

        suspend fun create(): Company {
            tenants.create(Tenant(id = id, slug = "t-${id.toHexString()}", name = "Pet Spa", channels = emptyList(), createdAt = now, updatedAt = now))
            admin = newUser(DashboardUserRole.TENANT_ADMIN)
            member = newUser(DashboardUserRole.TENANT_MEMBER)
            return this
        }

        private suspend fun newUser(role: DashboardUserRole): DashboardUser {
            val userId = ObjectId()
            return users.create(
                DashboardUser(
                    id = userId, tenantId = id, email = "${role.name.lowercase()}-${userId.toHexString()}@petspa.test",
                    passwordHash = BCrypt.withDefaults().hashToString(4, PASSWORD.toCharArray()), role = role, createdAt = now,
                ),
            )
        }

        /** A different phone per company and call, since phones are unique per tenant. */
        private var phones = 0
        fun phone() = "+351 91${(++phones).toString().padStart(2, '0')} ${id.toHexString().takeLast(6)}"
    }

    private suspend fun company() = Company().create()

    private suspend fun HttpClient.send(method: String, path: String, token: String, body: JsonObject? = null): HttpResponse {
        val request: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            bearerAuth(token)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        return when (method) {
            "GET" -> get(path, request)
            "PATCH" -> patch(path, request)
            "PUT" -> put(path, request)
            else -> post(path, request)
        }
    }

    private suspend fun HttpClient.token(email: String): String {
        val response = post("/app/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("email", email); put("password", PASSWORD) }.toString())
        }
        return response.obj().text("token")!!
    }

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.list(): JsonArray = json.parseToJsonElement(bodyAsText()).jsonArray
    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.content
    private suspend fun HttpResponse.error(): Pair<String?, String?> = obj().let { it.text("error") to it.text("detail") }

    private fun fields(required: List<String>, custom: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit = {}) = buildJsonObject {
        putJsonArray("required") { required.forEach { add(it) } }
        putJsonArray("custom", custom)
    }

    private fun client(phone: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("name", "Ana Ribeiro")
        put("phone", phone)
        extra()
    }

    private fun JsonObject.customKey(label: String): String =
        this["custom"]!!.jsonArray.map { it.jsonObject }.first { it.text("label") == label }.text("key")!!

    @Test
    fun `everyone with clients reads the fields, only admins change them`() = fieldsTest { http ->
        val c = company()
        val adminToken = http.token(c.admin.email)
        val memberToken = http.token(c.member.email)

        val defaults = http.send("GET", "/app/api/crm/clients/fields", memberToken).obj()
        assertEquals("false", defaults.text("canEdit"))
        val standard = defaults["standard"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("name", "phone", "taxId", "email", "contactPerson", "address", "postalCode", "city"), standard.map { it.text("key") })
        assertEquals(listOf("name", "phone", "taxId", "address"), standard.filter { it.text("required") == "true" }.map { it.text("key") })
        assertEquals(listOf("name", "phone"), standard.filter { it.text("locked") == "true" }.map { it.text("key") })

        val change = fields(listOf("email")) {
            addJsonObject { put("label", "Pet's name"); put("type", "TEXT"); put("required", true); put("showInList", true) }
            addJsonObject { put("label", "Size"); put("type", "SELECT"); putJsonArray("options") { add("Small"); add("Large") } }
        }
        val refused = http.send("PUT", "/app/api/crm/clients/fields", memberToken, change)
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("not_allowed", refused.error().first)

        val saved = http.send("PUT", "/app/api/crm/clients/fields", adminToken, change).obj()
        assertEquals("true", saved.text("canEdit"))
        val custom = saved["custom"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Pet's name", "Size"), custom.map { it.text("label") })
        assertTrue(custom.all { it.text("key")!!.startsWith("cf_") })

        val read = http.send("GET", "/app/api/crm/clients/fields", memberToken).obj()
        assertEquals(listOf("name", "phone", "email"), read["standard"]!!.jsonArray.map { it.jsonObject }.filter { it.text("required") == "true" }.map { it.text("key") })
        assertEquals(custom, read["custom"]!!.jsonArray.map { it.jsonObject })

        val duplicate = fields(emptyList()) {
            addJsonObject { put("label", "Size"); put("type", "TEXT") }
            addJsonObject { put("label", " size "); put("type", "NUMBER") }
        }
        assertEquals("duplicate_label" to null, http.send("PUT", "/app/api/crm/clients/fields", adminToken, duplicate).error())
    }

    @Test
    fun `staff saves follow the company's fields, and the values come back on the client`() = fieldsTest { http ->
        val c = company()
        val token = http.token(c.admin.email)

        assertEquals("tax_id_required" to null, http.send("POST", "/app/api/crm/clients", token, client(c.phone())).error())

        val shaped = http.send(
            "PUT", "/app/api/crm/clients/fields", token,
            fields(emptyList()) {
                addJsonObject { put("label", "Pet's name"); put("type", "TEXT"); put("required", true); put("showInList", true) }
                addJsonObject { put("label", "Size"); put("type", "SELECT"); putJsonArray("options") { add("Small"); add("Large") } }
                addJsonObject { put("label", "Visits"); put("type", "NUMBER") }
            },
        ).obj()
        val pet = shaped.customKey("Pet's name")
        val size = shaped.customKey("Size")
        val visits = shaped.customKey("Visits")

        assertEquals("custom_field_required" to pet, http.send("POST", "/app/api/crm/clients", token, client(c.phone())).error())
        val created = http.send("POST", "/app/api/crm/clients", token, client(c.phone()) { putJsonObject("customFields") { put(pet, " Rex "); put(visits, 3) } })
        assertEquals(HttpStatusCode.Created, created.status)
        val ana = created.obj()
        val id = ana.text("id")!!
        assertEquals("Rex", ana["customFields"]!!.jsonObject.text(pet))
        assertEquals(3.0, ana["customFields"]!!.jsonObject.text(visits)!!.toDouble())

        val renamed = http.send("PATCH", "/app/api/crm/clients/$id", token, buildJsonObject { put("name", "Ana R."); put("phone", ana.text("phone")) }).obj()
        assertEquals("Rex", renamed["customFields"]!!.jsonObject.text(pet))

        val bad = http.send("PATCH", "/app/api/crm/clients/$id", token, client(ana.text("phone")!!) { putJsonObject("customFields") { put(size, "Huge") } })
        assertEquals("custom_field_invalid" to size, bad.error())
        val cleared = http.send("PATCH", "/app/api/crm/clients/$id", token, client(ana.text("phone")!!) { putJsonObject("customFields") { put(pet, "") } })
        assertEquals("custom_field_required" to pet, cleared.error())

        val sized = http.send(
            "PATCH", "/app/api/crm/clients/$id", token,
            client(ana.text("phone")!!) { putJsonObject("customFields") { put(size, "Large"); put(visits, null as String?) } },
        ).obj()
        assertEquals("Large", sized["customFields"]!!.jsonObject.text(size))
        assertNull(sized["customFields"]!!.jsonObject[visits])

        assertEquals(listOf(id), http.send("GET", "/app/api/crm/clients?q=rex", token).list().map { it.jsonObject.text("id") })
        assertEquals(listOf(id), http.send("GET", "/app/api/crm/clients?q=large", token).list().map { it.jsonObject.text("id") })
    }

    @Test
    fun `the standard fields a company requires are the ones staff must fill in`() = fieldsTest { http ->
        val c = company()
        val token = http.token(c.admin.email)
        http.send("PUT", "/app/api/crm/clients/fields", token, fields(listOf("email", "city")))

        assertEquals("email_required" to null, http.send("POST", "/app/api/crm/clients", token, client(c.phone())).error())
        assertEquals("city_required" to null, http.send("POST", "/app/api/crm/clients", token, client(c.phone()) { put("email", "ana@example.pt") }).error())
        val created = http.send("POST", "/app/api/crm/clients", token, client(c.phone()) { put("email", "ana@example.pt"); put("city", "Lisboa") })
        assertEquals(HttpStatusCode.Created, created.status)
    }
}
