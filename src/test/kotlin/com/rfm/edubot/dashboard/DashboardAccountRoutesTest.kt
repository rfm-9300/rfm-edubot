package com.rfm.edubot.dashboard

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.RSAKeyProvider
import com.rfm.edubot.admin.FirebaseIdTokenVerifier
import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
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
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The account endpoints over HTTP, with the real auth plugin and a verifier that trusts a test key. */
@Testcontainers
class DashboardAccountRoutesTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "dashboard_account"))
            mongoModule.initialize()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }

        private const val PROJECT = "thebotslab"
        private const val JWT_SECRET = "test-secret"
        private const val PASSWORD = "correct horse"
        private val googleKey: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    private val now = Clock.System.now()
    private val users get() = DashboardUserRepository(mongoModule)
    private val tenants get() = TenantRepository(mongoModule)
    private val verifier = FirebaseIdTokenVerifier(object : RSAKeyProvider {
        override fun getPublicKeyById(keyId: String?): RSAPublicKey? = if (keyId == "k1") googleKey.public as RSAPublicKey else null
        override fun getPrivateKey(): RSAPrivateKey? = null
        override fun getPrivateKeyId(): String? = null
    })
    private val runtime = RuntimeConfig(
        AppConfig(
            port = 8080,
            whatsapp = AppConfig.WhatsAppConfig(verifyToken = "v", appSecret = "s", phoneNumberId = "1", accessToken = "t"),
            instagram = AppConfig.InstagramConfig(appId = "ig", appSecret = "s", redirectUri = "https://example.com/cb"),
            openrouter = AppConfig.OpenRouterConfig(apiKey = "k", primaryModel = "a", fallbackModel = "b", maxTokens = 512),
            mongo = AppConfig.MongoConfig(uri = "unused", database = "unused"),
            rateLimit = AppConfig.RateLimitConfig(),
            admin = AppConfig.AdminConfig(
                jwtSecret = JWT_SECRET,
                googleSignIn = AppConfig.GoogleSignInConfig(firebaseProjectId = PROJECT, webApiKey = "web-key", authDomain = "$PROJECT.firebaseapp.com", appId = "app"),
            ),
            pdfStoragePath = "/tmp/pdfs",
        ),
    )

    private fun accountTest(block: suspend (HttpClient) -> Unit): Unit = testApplication {
        application {
            configureSerialization()
            configureAdminAuth(runtime, tenants, users)
            routing { dashboardAccountRoutes(tenants, users, runtime, verifier) }
        }
        block(client)
    }

    private suspend fun newUser(password: String? = PASSWORD): DashboardUser {
        val tenantId = ObjectId()
        tenants.create(Tenant(id = tenantId, slug = "t-${tenantId.toHexString()}", name = "Acme", channels = emptyList(), status = TenantStatus.ACTIVE, createdAt = now, updatedAt = now))
        val id = ObjectId()
        return users.create(
            DashboardUser(
                id = id, tenantId = tenantId, email = "owner-${id.toHexString()}@acme.test",
                passwordHash = password?.let { BCrypt.withDefaults().hashToString(4, it.toCharArray()) }, createdAt = now,
            ),
        )
    }

    private fun googleToken(uid: String, email: String, signedInSecondsAgo: Long = 5): String = JWT.create()
        .withKeyId("k1")
        .withIssuer("https://securetoken.google.com/$PROJECT")
        .withAudience(PROJECT)
        .withSubject(uid)
        .withIssuedAt(Date.from(Instant.now().minusSeconds(5)))
        .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
        .withClaim("auth_time", Instant.now().minusSeconds(signedInSecondsAgo).epochSecond)
        .withClaim("email", email)
        .withClaim("email_verified", true)
        .withClaim("firebase", mapOf("sign_in_provider" to "google.com"))
        .sign(Algorithm.RSA256(googleKey.public as RSAPublicKey, googleKey.private as RSAPrivateKey))

    private fun uid() = "uid-${ObjectId().toHexString()}"

    private suspend fun HttpClient.postJson(path: String, body: JsonObject, token: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        token?.let { bearerAuth(it) }
        setBody(body.toString())
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.error(): String? = json()["error"]?.jsonPrimitive?.content
    private suspend fun HttpResponse.field(name: String): String? = (json()[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private suspend fun HttpClient.passwordLogin(email: String, password: String) =
        postJson("/app/auth/login", buildJsonObject { put("email", email); put("password", password) })

    private suspend fun HttpClient.googleLogin(idToken: String) = postJson("/app/auth/google", buildJsonObject { put("idToken", idToken) })

    @Test
    fun `the auth config offers Google, and a forged token is rejected`() = accountTest { client ->
        val config = client.get("/app/auth/config").json()["google"]!!.jsonObject
        assertEquals(PROJECT, config["projectId"]!!.jsonPrimitive.content)

        val forged = googleToken(uid(), "x@gmail.com").dropLast(4) + "AAAA"
        val response = client.googleLogin(forged)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("invalid_token", response.error())
    }

    @Test
    fun `Google sign-in links by email and the token opens the account`() = accountTest { client ->
        val user = newUser()
        val googleUid = uid()

        val login = client.googleLogin(googleToken(googleUid, user.email))
        assertEquals(HttpStatusCode.OK, login.status)
        val token = assertNotNull(login.field("token"))

        val account = client.get("/app/api/account") { bearerAuth(token) }.json()
        assertEquals(user.email, account["googleEmail"]!!.jsonPrimitive.content)
        assertEquals(true, account["passwordEnabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(googleUid, users.findById(user.id)!!.googleUid)

        assertEquals("no_account", client.googleLogin(googleToken(uid(), "stranger@gmail.com")).error())
    }

    @Test
    fun `linking a Google account and changing the password need the current password`() = accountTest { client ->
        val user = newUser()
        val other = newUser()
        val othersGoogle = uid()
        users.linkGoogle(other.id, othersGoogle, "other@gmail.com")
        val token = client.passwordLogin(user.email, PASSWORD).field("token")!!
        val googleUid = uid()
        fun link(idToken: String, password: String) = buildJsonObject { put("idToken", idToken); put("currentPassword", password) }

        assertEquals("wrong_password", client.postJson("/app/api/account/google/link", link(googleToken(googleUid, "me@gmail.com"), "nope"), token).error())
        assertEquals("google_in_use", client.postJson("/app/api/account/google/link", link(googleToken(othersGoogle, "other@gmail.com"), PASSWORD), token).error())
        val stale = googleToken(googleUid, "me@gmail.com", signedInSecondsAgo = 1800)
        assertEquals("stale_sign_in", client.postJson("/app/api/account/google/link", link(stale, PASSWORD), token).error())
        val linked = client.postJson("/app/api/account/google/link", link(googleToken(googleUid, "me@gmail.com"), PASSWORD), token)
        assertEquals(HttpStatusCode.OK, linked.status)
        assertEquals("me@gmail.com", linked.field("googleEmail"))

        fun change(current: String, next: String) = buildJsonObject { put("currentPassword", current); put("newPassword", next) }
        assertEquals("wrong_password", client.postJson("/app/api/account/password", change("nope", "a new passphrase"), token).error())
        assertEquals("password_too_short", client.postJson("/app/api/account/password", change(PASSWORD, "short"), token).error())
        assertEquals(HttpStatusCode.OK, client.postJson("/app/api/account/password", change(PASSWORD, "a new passphrase"), token).status)
        assertEquals(HttpStatusCode.Unauthorized, client.passwordLogin(user.email, PASSWORD).status)
        assertEquals(HttpStatusCode.OK, client.passwordLogin(user.email, "a new passphrase").status)

        val unlinked = client.postJson("/app/api/account/google/unlink", buildJsonObject { put("currentPassword", "a new passphrase") }, token)
        assertEquals(HttpStatusCode.OK, unlinked.status)
        assertEquals(null, unlinked.field("googleEmail"))
    }

    @Test
    fun `Google only needs a fresh sign-in to the linked account, and a password can be set again the same way`() = accountTest { client ->
        val user = newUser()
        val googleUid = uid()
        val token = client.googleLogin(googleToken(googleUid, user.email)).field("token")!!
        fun confirm(idToken: String) = buildJsonObject { put("idToken", idToken) }

        assertEquals("other_google_account", client.postJson("/app/api/account/password/disable", confirm(googleToken(uid(), "x@gmail.com")), token).error())
        assertEquals("stale_sign_in", client.postJson("/app/api/account/password/disable", confirm(googleToken(googleUid, user.email, 1800)), token).error())
        val off = client.postJson("/app/api/account/password/disable", confirm(googleToken(googleUid, user.email)), token)
        assertEquals(HttpStatusCode.OK, off.status)
        assertEquals(false, off.json()["passwordEnabled"]!!.jsonPrimitive.content.toBoolean())

        assertEquals(HttpStatusCode.Unauthorized, client.passwordLogin(user.email, PASSWORD).status)
        assertEquals(HttpStatusCode.OK, client.googleLogin(googleToken(googleUid, user.email)).status)
        assertEquals("password_required", client.postJson("/app/api/account/google/unlink", buildJsonObject { put("currentPassword", PASSWORD) }, token).error())

        val next = buildJsonObject { put("newPassword", "back to passwords") }
        assertEquals("google_confirmation_required", client.postJson("/app/api/account/password", next, token).error())
        val withGoogle = buildJsonObject { put("newPassword", "back to passwords"); put("idToken", googleToken(googleUid, user.email)) }
        assertEquals(HttpStatusCode.OK, client.postJson("/app/api/account/password", withGoogle, token).status)
        assertEquals(HttpStatusCode.OK, client.passwordLogin(user.email, "back to passwords").status)
    }

    @Test
    fun `an operator opening the dashboard has no account to change`() = accountTest { client ->
        val user = newUser()
        val operator = JWT.create()
            .withIssuer("wabot-platform")
            .withSubject("operator")
            .withClaim("tenantId", user.tenantId.toHexString())
            .withClaim("role", "PLATFORM_ADMIN")
            .withClaim("typ", DashboardAccessPolicy.OPERATOR_IMPERSONATION)
            .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
            .sign(Algorithm.HMAC256(JWT_SECRET))

        val response = client.get("/app/api/account") { bearerAuth(operator) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("no_user_account", response.error())
    }
}
