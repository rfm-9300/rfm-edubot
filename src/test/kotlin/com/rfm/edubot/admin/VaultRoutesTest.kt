package com.rfm.edubot.admin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.rfm.edubot.admin.vault.VAULT_AUTH_HEADER
import com.rfm.edubot.admin.vault.VaultService
import com.rfm.edubot.admin.vault.vaultRoutes
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.plugins.configureStatusPages
import com.rfm.edubot.tenant.TenantRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The password vault over HTTP. The server never decrypts anything, so random bytes of the right sizes stand in for
 * what the browser seals; what is checked is what the server enforces: one vault, sound parameters, and entries that
 * answer only to the token derived from the unlocked vault key.
 */
@Testcontainers
class VaultRoutesTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.stop()
        }

        private const val JWT_SECRET = "test-secret"
        private const val ANA = "ana@thebots.test"
        private const val RUI = "rui@thebots.test"
    }

    private val base = AppConfig(
        port = 8080,
        whatsapp = AppConfig.WhatsAppConfig(verifyToken = "v", appSecret = "s", phoneNumberId = "1", accessToken = "t"),
        instagram = AppConfig.InstagramConfig(appId = "ig", appSecret = "s", redirectUri = "https://example.com/cb"),
        openrouter = AppConfig.OpenRouterConfig(apiKey = "k", primaryModel = "a", fallbackModel = "b", maxTokens = 512),
        mongo = AppConfig.MongoConfig(uri = "unused", database = "unused"),
        rateLimit = AppConfig.RateLimitConfig(),
        // A Google session's email must be on the allowlist, or the admin sign-in refuses it.
        admin = AppConfig.AdminConfig(
            jwtSecret = JWT_SECRET,
            googleSignIn = AppConfig.GoogleSignInConfig(firebaseProjectId = "project", webApiKey = "key", allowedEmails = setOf(ANA, RUI)),
        ),
        pdfStoragePath = "/tmp/pdfs",
    )
    private val runtime = RuntimeConfig(base)
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    /** Each test gets a database of its own: there is one vault per platform. */
    private fun vaultTest(block: suspend (HttpClient) -> Unit) {
        val module = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "vault_${ObjectId().toHexString()}"))
        module.initialize()
        try {
            testApplication {
                application {
                    configureSerialization()
                    configureStatusPages()
                    configureAdminAuth(runtime, TenantRepository(module), DashboardUserRepository(module))
                    routing { vaultRoutes(VaultService.of(module)) }
                }
                block(client)
            }
        } finally {
            module.shutdown()
        }
    }

    private fun token(email: String?): String = JWT.create()
        .withIssuer(base.admin.jwtIssuer)
        .withSubject("admin")
        .apply { email?.let { withClaim("email", it) } }
        .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
        .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun bytes(n: Int): String = Base64.getEncoder().encodeToString(ByteArray(n).also(random::nextBytes))

    private suspend fun HttpClient.send(
        method: String,
        path: String,
        body: JsonElement? = null,
        vault: String? = null,
        email: String? = ANA,
    ): HttpResponse {
        val request: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            email?.let { bearerAuth(token(it)) }
            vault?.let { header(VAULT_AUTH_HEADER, it) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        return when (method) {
            "GET" -> get(path, request)
            "PUT" -> put(path, request)
            "DELETE" -> delete(path, request)
            else -> post(path, request)
        }
    }

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.arr(): JsonArray = json.parseToJsonElement(bodyAsText()).jsonArray
    private suspend fun HttpResponse.error(): String? = obj()["error"]?.jsonPrimitive?.content
    private fun JsonElement.text(name: String): String? = (jsonObject[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun kdf(iterations: Int = 600_000, salt: String = bytes(16), name: String = "PBKDF2") = buildJsonObject {
        put("name", name)
        put("hash", "SHA-256")
        put("iterations", iterations)
        put("salt", salt)
    }

    private fun sealed(iv: Int = 12, data: Int = 48) = buildJsonObject { put("iv", bytes(iv)); put("data", bytes(data)) }

    private fun createBody(kdf: JsonObject = kdf(), wrappedKey: JsonObject = sealed(), authToken: String = bytes(32)) = buildJsonObject {
        put("kdf", kdf)
        put("wrappedKey", wrappedKey)
        put("authToken", authToken)
    }

    /** Creates the vault and returns the token that unlocks it. */
    private suspend fun HttpClient.createVault(): String {
        val authToken = bytes(32)
        assertEquals(HttpStatusCode.Created, send("POST", "/admin/api/vault", createBody(authToken = authToken)).status)
        return authToken
    }

    @Test
    fun `the vault needs a backoffice sign-in`() = vaultTest { http ->
        assertEquals(HttpStatusCode.Unauthorized, http.send("GET", "/admin/api/vault", email = null).status)
    }

    @Test
    fun `the vault is created once, with a sound key derivation and a sealed vault key`() = vaultTest { http ->
        val empty = http.send("GET", "/admin/api/vault").obj()
        assertEquals("false", empty.text("exists"))
        assertEquals("invalid_kdf", http.send("POST", "/admin/api/vault", createBody(kdf = kdf(iterations = 1_000))).error())
        assertEquals("invalid_kdf", http.send("POST", "/admin/api/vault", createBody(kdf = kdf(salt = bytes(8)))).error())
        assertEquals("invalid_kdf", http.send("POST", "/admin/api/vault", createBody(kdf = kdf(name = "SCRYPT"))).error())
        assertEquals("invalid_sealed", http.send("POST", "/admin/api/vault", createBody(wrappedKey = sealed(data = 40))).error())
        assertEquals("invalid_sealed", http.send("POST", "/admin/api/vault", createBody(wrappedKey = sealed(iv = 16))).error())
        assertEquals("invalid_sealed", http.send("POST", "/admin/api/vault", createBody(wrappedKey = buildJsonObject { put("iv", "%%"); put("data", bytes(48)) })).error())
        assertEquals("invalid_auth_token", http.send("POST", "/admin/api/vault", createBody(authToken = bytes(16))).error())
        assertEquals("false", http.send("GET", "/admin/api/vault").obj().text("exists"), "a refused create leaves no vault")

        val salt = bytes(16)
        val created = http.send("POST", "/admin/api/vault", createBody(kdf = kdf(salt = salt)))
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(ANA, created.obj().text("createdBy"))
        assertEquals("vault_exists", http.send("POST", "/admin/api/vault", createBody(), email = RUI).error())

        val state = http.send("GET", "/admin/api/vault")
        assertEquals("no-store", state.headers[HttpHeaders.CacheControl])
        val body = state.obj()
        assertEquals("true", body.text("exists"))
        assertEquals(salt, body["kdf"]!!.text("salt"))
        assertEquals(600_000, body["kdf"]!!.jsonObject["iterations"]!!.jsonPrimitive.int)
        assertEquals(64, body["wrappedKey"]!!.text("data")!!.length, "48 bytes in base64")
    }

    @Test
    fun `entries answer only to the vault's token, and never with a 401`() = vaultTest { http ->
        assertEquals("no_vault", http.send("GET", "/admin/api/vault/entries", vault = bytes(32)).error())
        val token = http.createVault()
        val locked = http.send("GET", "/admin/api/vault/entries")
        assertEquals(HttpStatusCode.Forbidden, locked.status)
        assertEquals("vault_locked", locked.error())
        assertEquals("vault_auth_failed", http.send("GET", "/admin/api/vault/entries", vault = bytes(32)).error())
        assertEquals("vault_auth_failed", http.send("GET", "/admin/api/vault/entries", vault = "not base64!").error())
        assertEquals("vault_locked", http.send("POST", "/admin/api/vault/entries", sealed(data = 80)).error())
        assertEquals("vault_auth_failed", http.send("DELETE", "/admin/api/vault/entries/${ObjectId().toHexString()}", vault = bytes(32)).error())
        assertEquals(emptyList(), http.send("GET", "/admin/api/vault/entries", vault = token).arr().toList())
    }

    @Test
    fun `entries are added, changed and deleted, and a save over someone else's is refused`() = vaultTest { http ->
        val token = http.createVault()
        val added = http.send("POST", "/admin/api/vault/entries", sealed(data = 120), vault = token)
        assertEquals(HttpStatusCode.Created, added.status)
        val entry = added.obj()
        assertEquals(ANA, entry.text("createdBy"))
        val path = "/admin/api/vault/entries/${entry.text("id")}"
        val update: (String?) -> JsonObject = { expected ->
            buildJsonObject {
                put("iv", bytes(12))
                put("data", bytes(140))
                expected?.let { put("expectedUpdatedAt", it) }
            }
        }

        val changed = http.send("PUT", path, update(entry.text("updatedAt")), vault = token, email = RUI)
        assertEquals(HttpStatusCode.OK, changed.status)
        assertEquals(RUI, changed.obj().text("updatedBy"))
        assertEquals(ANA, changed.obj().text("createdBy"))
        val stale = http.send("PUT", path, update(entry.text("updatedAt")), vault = token)
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("stale_entry", stale.error())
        assertEquals(HttpStatusCode.OK, http.send("PUT", path, update(null), vault = token).status, "without expectedUpdatedAt the last save wins")
        assertEquals("entry_not_found", http.send("PUT", "/admin/api/vault/entries/${ObjectId().toHexString()}", update(null), vault = token).error())
        assertEquals("invalid_body", http.send("PUT", path, update("yesterday"), vault = token).error())
        assertEquals("invalid_sealed", http.send("POST", "/admin/api/vault/entries", sealed(data = 64 * 1024 + 17), vault = token).error())
        assertEquals("invalid_sealed", http.send("POST", "/admin/api/vault/entries", sealed(data = 16), vault = token).error(), "a tag with nothing sealed")

        http.send("POST", "/admin/api/vault/entries", sealed(data = 90), vault = token)
        assertEquals(2, http.send("GET", "/admin/api/vault/entries", vault = token).arr().size)
        assertEquals(2, http.send("GET", "/admin/api/vault").obj()["entryCount"]!!.jsonPrimitive.int)
        assertEquals(HttpStatusCode.OK, http.send("DELETE", path, vault = token).status)
        assertEquals("entry_not_found", http.send("DELETE", path, vault = token).error())
        assertEquals(1, http.send("GET", "/admin/api/vault/entries", vault = token).arr().size)
    }

    @Test
    fun `a new master password seals the same vault key again, and the token keeps working`() = vaultTest { http ->
        val token = http.createVault()
        http.send("POST", "/admin/api/vault/entries", sealed(data = 64), vault = token)
        val before = http.send("GET", "/admin/api/vault").obj()
        val change = buildJsonObject {
            put("kdf", kdf(iterations = 700_000))
            put("wrappedKey", sealed())
        }
        assertEquals("vault_locked", http.send("PUT", "/admin/api/vault/key", change).error())
        val changed = http.send("PUT", "/admin/api/vault/key", change, vault = token, email = RUI)
        assertEquals(HttpStatusCode.OK, changed.status)
        val after = http.send("GET", "/admin/api/vault").obj()
        assertNotEquals(before["kdf"]!!.text("salt"), after["kdf"]!!.text("salt"))
        assertNotEquals(before["wrappedKey"]!!.text("data"), after["wrappedKey"]!!.text("data"))
        assertEquals(700_000, after["kdf"]!!.jsonObject["iterations"]!!.jsonPrimitive.int)
        assertEquals(RUI, after.text("updatedBy"))
        assertEquals(1, http.send("GET", "/admin/api/vault/entries", vault = token).arr().size)
    }
}
