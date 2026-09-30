package com.rfm.edubot.admin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.tenant.TenantRepository
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
import io.ktor.http.encodeURLPathPart
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import java.time.Instant
import java.util.Date
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Backoffice admins (Google allowlist) and manual backups over HTTP. */
@Testcontainers
class BackofficeOpsRoutesTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "backoffice_ops"))
            mongoModule.initialize()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }

        private const val JWT_SECRET = "test-secret"
        private const val OWNER = "owner@acme.test"
    }

    @TempDir
    lateinit var root: Path

    private val base = AppConfig(
        port = 8080,
        whatsapp = AppConfig.WhatsAppConfig(verifyToken = "v", appSecret = "s", phoneNumberId = "1", accessToken = "t"),
        instagram = AppConfig.InstagramConfig(appId = "ig", appSecret = "s", redirectUri = "https://example.com/cb"),
        openrouter = AppConfig.OpenRouterConfig(apiKey = "k", primaryModel = "a", fallbackModel = "b", maxTokens = 512),
        mongo = AppConfig.MongoConfig(uri = "unused", database = "unused"),
        rateLimit = AppConfig.RateLimitConfig(),
        admin = AppConfig.AdminConfig(
            jwtSecret = JWT_SECRET,
            googleSignIn = AppConfig.GoogleSignInConfig(firebaseProjectId = "project", webApiKey = "key", allowedEmails = setOf(OWNER)),
        ),
        pdfStoragePath = "/tmp/pdfs",
    )
    private val runtime = RuntimeConfig(base)
    private val repository = AdminEmailRepository(mongoModule)
    private val access = AdminAccess(repository, runtime)

    private fun opsTest(backups: BackupControl = BackupControl("", ""), block: suspend (HttpClient) -> Unit): Unit = testApplication {
        application {
            configureSerialization()
            configureAdminAuth(runtime, TenantRepository(mongoModule), DashboardUserRepository(mongoModule))
            routing {
                adminAccessRoutes(access, runtime)
                backupRoutes(backups)
            }
        }
        block(client)
    }

    private fun token(email: String?): String = JWT.create()
        .withIssuer(base.admin.jwtIssuer)
        .withSubject("admin")
        .apply { email?.let { withClaim("email", it) } }
        .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
        .sign(Algorithm.HMAC256(JWT_SECRET))

    private fun unique(name: String) = "$name-${ObjectId().toHexString().takeLast(6)}@acme.test"

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.error(): String? = json()["error"]?.jsonPrimitive?.content
    private suspend fun HttpClient.admins(email: String?) = get("/admin/api/admins") { bearerAuth(token(email)) }
    private suspend fun HttpClient.addAdmin(email: String, by: String?) = post("/admin/api/admins") {
        bearerAuth(token(by))
        contentType(ContentType.Application.Json)
        setBody("""{"email":"$email"}""")
    }
    private suspend fun HttpClient.removeAdmin(email: String, by: String?) =
        delete("/admin/api/admins/${email.encodeURLPathPart()}") { bearerAuth(token(by)) }

    @Test
    fun `the list shows the env's admins first, not removable, and who is signed in`() = opsTest { client ->
        val body = client.admins(OWNER).json()
        assertEquals("true", body["googleReady"]!!.jsonPrimitive.content)
        assertEquals(OWNER, body["me"]!!.jsonPrimitive.content)
        val first = body["admins"]!!.jsonArray.first().jsonObject
        assertEquals(OWNER, first["email"]!!.jsonPrimitive.content)
        assertEquals("env", first["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an added email is stored in lower case and can sign in right away`() = opsTest { client ->
        val email = unique("New.Admin")
        val added = client.addAdmin("  $email ", OWNER)
        assertEquals(HttpStatusCode.Created, added.status)
        val entry = added.json()
        assertEquals(email.lowercase(), entry["email"]!!.jsonPrimitive.content)
        assertEquals("backoffice", entry["source"]!!.jsonPrimitive.content)
        assertEquals(OWNER, entry["addedBy"]!!.jsonPrimitive.content)

        assertTrue(email.lowercase() in runtime.get().admin.googleSignIn.allowedEmails)
        assertEquals(HttpStatusCode.OK, client.admins(email.lowercase()).status)

        assertEquals("already_allowed", client.addAdmin(email, OWNER).error())
        assertEquals("already_allowed", client.addAdmin(OWNER.uppercase(), OWNER).error())
        val invalid = client.addAdmin("not-an-email", OWNER)
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals("invalid_email", invalid.error())
    }

    @Test
    fun `removing an admin ends their session at the next request, but env admins and yourself stay`() = opsTest { client ->
        val leaver = unique("leaver")
        client.addAdmin(leaver, OWNER)
        assertEquals(HttpStatusCode.OK, client.admins(leaver).status)

        assertEquals("from_env", client.removeAdmin(OWNER, leaver).error())
        assertEquals("cannot_remove_self", client.removeAdmin(leaver, leaver).error())

        assertEquals(HttpStatusCode.OK, client.removeAdmin(leaver, OWNER).status)
        assertEquals(HttpStatusCode.Unauthorized, client.admins(leaver).status)
        assertEquals(HttpStatusCode.NotFound, client.removeAdmin(leaver, OWNER).status)
    }

    @Test
    fun `a password session has no email and keeps working, while an email nobody allowed is refused`() = opsTest { client ->
        val password = client.admins(null)
        assertEquals(HttpStatusCode.OK, password.status)
        assertEquals("null", password.json()["me"].toString())
        assertEquals(HttpStatusCode.Unauthorized, client.admins(unique("stranger")).status)
    }

    @Test
    fun `added emails come back after a restart and survive a platform settings reload`() = opsTest {
        val keeper = unique("keeper")
        access.add(keeper, OWNER)

        val restarted = RuntimeConfig(base)
        AdminAccess(repository, restarted).initialize()
        assertTrue(keeper in restarted.get().admin.googleSignIn.allowedEmails)
        assertTrue(OWNER in restarted.get().admin.googleSignIn.allowedEmails)

        restarted.applyOverrides(mapOf("RATE_LIMIT_PER_HOUR" to "5"))
        assertTrue(keeper in restarted.get().admin.googleSignIn.allowedEmails)
        assertEquals(5, restarted.get().rateLimit.perUserPerHour)
    }

    @Test
    fun `backups answer 503 when not set up, then 202 and 409 while a request waits`() {
        opsTest { client ->
            val unavailable = client.post("/admin/api/backups") { bearerAuth(token(OWNER)) }
            assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
            assertEquals("backups_unavailable", unavailable.error())
            assertEquals("false", client.get("/admin/api/backups") { bearerAuth(token(OWNER)) }.json()["available"]!!.jsonPrimitive.content)
        }

        val control = root.resolve("control").createDirectories()
        val backups = BackupControl(root.resolve("backups").createDirectories().toString(), control.toString())
        opsTest(backups) { client ->
            val requested = client.post("/admin/api/backups") { bearerAuth(token(OWNER)) }
            assertEquals(HttpStatusCode.Accepted, requested.status)
            val current = requested.json()["current"]!!.jsonObject
            assertEquals("requested", current["state"]!!.jsonPrimitive.content)
            assertEquals(OWNER, current["requestedBy"]!!.jsonPrimitive.content)
            assertTrue(OWNER in control.resolve("request.json").readText())

            val again = client.post("/admin/api/backups") { bearerAuth(token(OWNER)) }
            assertEquals(HttpStatusCode.Conflict, again.status)
            assertEquals("backup_in_progress", again.error())
        }
    }
}
