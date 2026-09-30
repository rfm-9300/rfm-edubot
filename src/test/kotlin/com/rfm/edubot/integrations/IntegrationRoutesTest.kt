package com.rfm.edubot.integrations

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.mongodb.client.model.Filters
import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.agents.model.PlatformAgentLimits
import com.rfm.edubot.agents.store.AgentSettingsRepository
import com.rfm.edubot.agents.store.OutboundLogRepository
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.DashboardAccessPolicy
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.dashboard.dashboardToken
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.dashboard.model.DashboardUserStatus
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.integrations.email.EmailCopy
import com.rfm.edubot.integrations.email.EmailMessageRepository
import com.rfm.edubot.integrations.email.EmailService
import com.rfm.edubot.integrations.google.FakeGoogle
import com.rfm.edubot.integrations.google.GoogleIntegration
import com.rfm.edubot.integrations.google.GoogleScopes
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.oauth.InstagramOAuthClient
import com.rfm.edubot.oauth.OAuthState
import com.rfm.edubot.oauth.instagramOAuthRoutes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.Base64
import java.util.Date
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class IntegrationRoutesTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("integration_routes")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val now = Clock.System.now()
    private val tenants = TenantRepository(mongo)
    private val users = DashboardUserRepository(mongo)
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
    private val cipher = TokenCipher.fromConfig(Base64.getEncoder().encodeToString(Random(11).nextBytes(32)))!!
    private val oauthState = OAuthState("state-secret")
    private val settings = AgentSettingsRepository(mongo)
    private val json = Json { ignoreUnknownKeys = true }

    private fun integration(google: FakeGoogle, configured: Boolean = true, cipher: TokenCipher? = this.cipher, inbox: Boolean = false): GoogleIntegration {
        val config = if (configured) google.config.copy(inboxEnabled = inbox) else AppConfig.GoogleConfig()
        return GoogleIntegration.create(mongo, { config }, cipher, google.http, NotificationRepository(mongo))
    }

    private class Company(val tenant: Tenant, val admin: DashboardUser, val adminToken: String, val memberToken: String, val operatorToken: String)

    private suspend fun company(modules: List<String> = listOf(DashboardModules.SETTINGS, DashboardModules.CLIENTS)): Company {
        val tenant = tenants.create(
            Tenant(slug = "t-${ObjectId().toHexString().takeLast(8)}", name = "Obras", channels = emptyList(), enabledModules = modules, createdAt = now, updatedAt = now),
        )
        val adminUser = users.create(DashboardUser(tenantId = tenant.id, email = "admin-${ObjectId()}@example.pt", passwordHash = null, role = DashboardUserRole.TENANT_ADMIN, createdAt = now))
        val memberUser = users.create(DashboardUser(tenantId = tenant.id, email = "member-${ObjectId()}@example.pt", passwordHash = null, role = DashboardUserRole.TENANT_MEMBER, createdAt = now))
        val operator = JWT.create()
            .withIssuer(admin.jwtIssuer)
            .withSubject("operator")
            .withClaim("tenantId", tenant.id.toHexString())
            .withClaim("role", "PLATFORM_ADMIN")
            .withClaim("typ", DashboardAccessPolicy.OPERATOR_IMPERSONATION)
            .withExpiresAt(Date(System.currentTimeMillis() + 600_000))
            .sign(Algorithm.HMAC256(admin.jwtSecret))
        return Company(tenant, adminUser, dashboardToken(admin, adminUser, "tenant", 1), dashboardToken(admin, memberUser, "tenant", 1), operator)
    }

    private fun emailFor(google: GoogleIntegration) =
        EmailService(google, EmailMessageRepository(mongo), OutboundLogRepository(mongo), DomainEventLog(mongo), settings)

    private fun routes(google: GoogleIntegration, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application {
            configureSerialization()
            configureAdminAuth(runtimeConfig, tenants, users)
            routing { integrationRoutes(google, emailFor(google), oauthState, tenants, users) }
        }
        block(createClient { followRedirects = false })
    }

    private suspend fun keptMail(connection: IntegrationConnection): Long =
        mongo.database.getCollection<Document>(EmailMessageRepository.COLLECTION).countDocuments(Filters.eq("connectionId", connection.id))

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpClient.list(token: String): JsonObject = get("/app/api/integrations") { bearerAuth(token) }.obj()

    /** Flags left at false aren't in the JSON. */
    private fun JsonObject.flag(name: String): Boolean = this[name]?.jsonPrimitive?.boolean ?: false

    /** The state inside the consent url the admin is sent to. */
    private suspend fun HttpClient.consent(company: Company, inbox: Boolean = false): String = consentUrl(company, inbox).parameters["state"]!!

    private suspend fun HttpClient.consentUrl(company: Company, inbox: Boolean = false): Url {
        val response = get("/app/api/integrations/google/connect" + if (inbox) "?inbox=1" else "") { bearerAuth(company.adminToken) }
        assertEquals(HttpStatusCode.OK, response.status)
        return Url(response.obj()["authorizeUrl"]!!.jsonPrimitive.content)
    }

    private suspend fun HttpClient.callback(query: String): String? {
        val response = get("/integrations/google/callback?$query")
        assertEquals(HttpStatusCode.Found, response.status)
        return response.headers[HttpHeaders.Location]
    }

    private suspend fun HttpClient.patchJson(path: String, token: String, body: JsonObject): HttpResponse = patch(path) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body.toString())
    }

    private suspend fun GoogleIntegration.connected(company: Company, email: String, scopes: List<String> = listOf(GoogleScopes.GMAIL_SEND)): IntegrationConnection = connections.connect(
        tenantId = company.tenant.id,
        provider = IntegrationProviders.GOOGLE,
        accountEmail = email,
        scopes = scopes,
        accessToken = this@IntegrationRoutesTest.cipher.seal("access-1"),
        refreshToken = this@IntegrationRoutesTest.cipher.seal("refresh-1"),
        accessTokenExpiresAt = now + 1.hours,
        connectedByUserId = company.admin.id.toHexString(),
        connectedByEmail = company.admin.email,
    )

    @Test
    fun `members see the company's accounts but only admins manage them`() = routes(integration(FakeGoogle())) { http ->
        val company = company()
        val member = http.list(company.memberToken)
        assertEquals(true, member["google"]!!.jsonObject["configured"]!!.jsonPrimitive.boolean)
        assertEquals(false, member["google"]!!.jsonObject["canConnect"]!!.jsonPrimitive.boolean)
        assertEquals(false, member["canManage"]!!.jsonPrimitive.boolean)
        assertEquals(0, member["connections"]!!.jsonArray.size)

        val asAdmin = http.list(company.adminToken)
        assertEquals(true, asAdmin["google"]!!.jsonObject["canConnect"]!!.jsonPrimitive.boolean)
        assertEquals(true, asAdmin["canManage"]!!.jsonPrimitive.boolean)
        val operator = http.list(company.operatorToken)
        assertEquals(false, operator["google"]!!.jsonObject["canConnect"]!!.jsonPrimitive.boolean)
        assertEquals(true, operator["canManage"]!!.jsonPrimitive.boolean)

        val noSettings = company(listOf(DashboardModules.CLIENTS))
        assertEquals(HttpStatusCode.Forbidden, http.get("/app/api/integrations") { bearerAuth(noSettings.adminToken) }.status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("/app/api/integrations").status)
    }

    @Test
    fun `only a company admin signed in as themselves gets a consent url`() = routes(integration(FakeGoogle())) { http ->
        val company = company()
        val member = http.get("/app/api/integrations/google/connect") { bearerAuth(company.memberToken) }
        assertEquals(HttpStatusCode.Forbidden, member.status)
        assertEquals("not_allowed", member.obj()["error"]!!.jsonPrimitive.content)
        val operator = http.get("/app/api/integrations/google/connect") { bearerAuth(company.operatorToken) }
        assertEquals(HttpStatusCode.Forbidden, operator.status)
        assertEquals("operator_not_allowed", operator.obj()["error"]!!.jsonPrimitive.content)

        val verified = oauthState.verify(http.consent(company))!!
        assertEquals(OAuthState.PURPOSE_GOOGLE, verified.purpose)
        assertEquals(company.tenant.id.toHexString(), verified.tenantId)
        assertEquals(company.admin.id.toHexString(), verified.userId)
    }

    @Test
    fun `reconnecting opens google on that account, and only on one of the company's`() {
        val google = integration(FakeGoogle())
        routes(google) { http ->
            val company = company()
            // Not the fake's account: other tests count which companies still use that grant.
            google.connected(company, "recepcao@example.pt")
            suspend fun hint(account: String): String? {
                val response = http.get("/app/api/integrations/google/connect?account=${account.encodeURLParameter()}") { bearerAuth(company.adminToken) }
                return Url(response.obj()["authorizeUrl"]!!.jsonPrimitive.content).parameters["login_hint"]
            }
            assertEquals("recepcao@example.pt", hint(" Recepcao@Example.pt "))
            assertNull(hint("someone@else.pt"))
        }
    }

    @Test
    fun `without a google client or a token key connecting is unavailable`() {
        for (google in listOf(integration(FakeGoogle(), configured = false), integration(FakeGoogle(), cipher = null))) {
            routes(google) { http ->
                val company = company()
                assertEquals(false, http.list(company.adminToken)["google"]!!.jsonObject["configured"]!!.jsonPrimitive.boolean)
                val response = http.get("/app/api/integrations/google/connect") { bearerAuth(company.adminToken) }
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                assertEquals("google_not_configured", response.obj()["error"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `the callback connects the account with its tokens sealed`() {
        val google = FakeGoogle()
        val integration = integration(google)
        routes(integration) { http ->
            val company = company()
            val state = http.consent(company)
            assertEquals("/app/?google=connected", http.callback("code=code-1&state=${state.encodeURLParameter()}"))

            val saved = integration.connections.list(company.tenant.id).single()
            assertEquals("obras@example.pt", saved.accountEmail)
            assertEquals(ConnectionStatus.ACTIVE, saved.status)
            assertNotEquals("access-1", saved.accessToken)
            assertEquals("access-1", cipher.open(saved.accessToken!!))
            assertEquals("refresh-1", cipher.open(saved.refreshToken!!))
            assertEquals(company.admin.email, saved.connectedByEmail)
            assertTrue(saved.isDefault)

            val listed = http.list(company.memberToken)["connections"]!!.jsonArray.single().jsonObject
            assertEquals("obras@example.pt", listed["accountEmail"]!!.jsonPrimitive.content)
            assertEquals(true, listed["canSend"]!!.jsonPrimitive.boolean)
            assertFalse("accessToken" in listed || "refreshToken" in listed, "tokens never leave the server")

            assertEquals("/app/?google=error&reason=invalid_state", http.callback("code=code-2&state=${state.encodeURLParameter()}"), "a state is used once")
            assertEquals(1, google.tokenCalls.size)
        }
    }

    @Test
    fun `the callback refuses what it can't use and revokes only grants nobody relies on`() {
        val google = FakeGoogle()
        val integration = integration(google)
        routes(integration) { http ->
            val company = company()
            suspend fun connectWith(answer: String): String? {
                google.onToken = { HttpStatusCode.OK to answer }
                return http.callback("code=c&state=${http.consent(company).encodeURLParameter()}")
            }
            assertEquals("/app/?google=error&reason=access_denied", http.callback("error=access_denied&state=x"))
            assertEquals("/app/?google=error&reason=consent_failed", http.callback("error=${"<b>odd</b>".encodeURLParameter()}"))
            assertEquals("/app/?google=error&reason=missing_params", http.callback("state=${http.consent(company).encodeURLParameter()}"))
            val instagram = oauthState.mint(company.tenant.slug, origin = OAuthState.ORIGIN_DASHBOARD)
            assertEquals("/app/?google=error&reason=invalid_state", http.callback("code=c&state=${instagram.encodeURLParameter()}"))

            assertEquals("/app/?google=error&reason=missing_scope", connectWith(google.grant(scope = "openid https://www.googleapis.com/auth/userinfo.email")))
            assertEquals(listOf("access-1"), google.revoked)
            assertEquals("/app/?google=error&reason=no_email", connectWith(google.grant(verified = false)))
            assertEquals(1, google.revoked.size, "without a verified address there's no telling whose grant it is")
            assertEquals("/app/?google=error&reason=no_refresh_token", connectWith(google.grant(refreshToken = null)))
            assertEquals(2, google.revoked.size)

            val other = company()
            integration.connected(other, "obras@example.pt")
            assertEquals("/app/?google=error&reason=missing_scope", connectWith(google.grant(scope = "openid")))
            assertEquals(2, google.revoked.size, "another company still sends from that account")

            google.onToken = { HttpStatusCode.BadRequest to """{"error":"invalid_grant"}""" }
            assertEquals("/app/?google=error&reason=exchange_failed", http.callback("code=c&state=${http.consent(company).encodeURLParameter()}"))

            val state = http.consent(company)
            users.setStatus(company.admin.id, company.tenant.id, DashboardUserStatus.DISABLED)
            assertEquals("/app/?google=error&reason=not_allowed", http.callback("code=c&state=${state.encodeURLParameter()}"))
            assertEquals(0, integration.connections.list(company.tenant.id).size)
        }
    }

    @Test
    fun `admins name the sender and pick the default, members can't`() {
        val integration = integration(FakeGoogle())
        routes(integration) { http ->
            val company = company()
            val first = integration.connected(company, "geral@example.pt")
            val second = integration.connected(company, "faturas@example.pt")
            val path = "/app/api/integrations/${first.id.toHexString()}"

            val named = http.patchJson(path, company.adminToken, buildJsonObject { put("senderName", " Obras Silva "); put("replyTo", "Geral@Example.pt"); put("signature", "Até breve") })
            assertEquals(HttpStatusCode.OK, named.status)
            val body = named.obj()
            assertEquals("Obras Silva", body["senderName"]!!.jsonPrimitive.content)
            assertEquals("geral@example.pt", body["replyTo"]!!.jsonPrimitive.content)

            suspend fun error(response: HttpResponse) = response.obj()["error"]!!.jsonPrimitive.content
            assertEquals("invalid_reply_to", error(http.patchJson(path, company.adminToken, buildJsonObject { put("replyTo", "not an address") })))
            assertEquals("invalid_sender_name", error(http.patchJson(path, company.adminToken, buildJsonObject { put("senderName", "Obras\r\nBcc: x@y.pt") })))
            assertEquals("signature_too_long", error(http.patchJson(path, company.adminToken, buildJsonObject { put("signature", "x".repeat(2001)) })))

            val cleared = http.patchJson(path, company.adminToken, buildJsonObject { put("replyTo", "") }).obj()
            assertNull(cleared["replyTo"])
            assertEquals("Obras Silva", cleared["senderName"]!!.jsonPrimitive.content, "fields left out stay as they were")

            val madeDefault = http.patchJson("/app/api/integrations/${second.id.toHexString()}", company.operatorToken, buildJsonObject { put("isDefault", true) })
            assertEquals(true, madeDefault.obj()["isDefault"]!!.jsonPrimitive.boolean)
            assertEquals(false, integration.connections.findById(first.id)!!.isDefault)

            assertEquals(HttpStatusCode.Forbidden, http.patchJson(path, company.memberToken, buildJsonObject { put("senderName", "X") }).status)
            assertEquals(HttpStatusCode.NotFound, http.patchJson(path, company().adminToken, buildJsonObject { put("senderName", "X") }).status)
            assertEquals(HttpStatusCode.BadRequest, http.patchJson("/app/api/integrations/nope", company.adminToken, buildJsonObject {}).status)
        }
    }

    @Test
    fun `reading the inbox is offered where the platform reads inboxes, and asks google for it on top of sending`() {
        routes(integration(FakeGoogle())) { http ->
            val company = company()
            assertFalse(http.list(company.adminToken)["google"]!!.jsonObject.flag("inbox"))
            val refused = http.get("/app/api/integrations/google/connect?inbox=1") { bearerAuth(company.adminToken) }
            assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
            assertEquals("inbox_not_available", refused.obj()["error"]!!.jsonPrimitive.content)
        }
        routes(integration(FakeGoogle(), inbox = true)) { http ->
            val company = company()
            assertTrue(http.list(company.adminToken)["google"]!!.jsonObject.flag("inbox"))
            val inbox = http.consentUrl(company, inbox = true)
            assertEquals(GoogleScopes.inbox, inbox.parameters["scope"]!!.split(' '))
            assertEquals("true", inbox.parameters["include_granted_scopes"])
            assertEquals(OAuthState.PURPOSE_GOOGLE_INBOX, oauthState.verify(inbox.parameters["state"]!!)!!.purpose)
            assertEquals(GoogleScopes.send, http.consentUrl(company).parameters["scope"]!!.split(' '), "connecting alone doesn't ask to read")
        }
    }

    @Test
    fun `the inbox consent turns inbox sync on, unless reading was unticked on google's screen`() {
        val google = FakeGoogle()
        val integration = integration(google, inbox = true)
        val reading = "openid email ${GoogleScopes.GMAIL_SEND} ${GoogleScopes.GMAIL_READONLY} ${GoogleScopes.GMAIL_MODIFY}"
        routes(integration) { http ->
            val company = company()
            google.onToken = { HttpStatusCode.OK to google.grant(email = "caixa@example.pt", scope = reading) }
            assertEquals("/app/?google=inbox", http.callback("code=c&state=${http.consent(company, inbox = true).encodeURLParameter()}"))
            val reader = integration.connections.list(company.tenant.id).single()
            assertTrue(reader.settings.inboxSync)
            assertTrue(reader.inbox.enabledAt != null)
            val listed = http.list(company.memberToken)["connections"]!!.jsonArray.single().jsonObject
            assertEquals(true, listed["canRead"]!!.jsonPrimitive.boolean)
            assertEquals(true, listed["inboxSync"]!!.jsonPrimitive.boolean)

            assertEquals("/app/?google=connected", http.callback("code=c&state=${http.consent(company).encodeURLParameter()}"))
            assertTrue(integration.connections.findById(reader.id)!!.settings.inboxSync, "reconnecting to send keeps the inbox read")

            google.onToken = { HttpStatusCode.OK to google.grant(email = "balcao@example.pt", scope = "openid email ${GoogleScopes.GMAIL_SEND}") }
            assertEquals("/app/?google=error&reason=missing_inbox_scope", http.callback("code=c&state=${http.consent(company, inbox = true).encodeURLParameter()}"))
            val sender = integration.connections.list(company.tenant.id).single { it.accountEmail == "balcao@example.pt" }
            assertFalse(sender.settings.inboxSync)
            assertTrue(GoogleScopes.canSend(sender.scopes), "what was allowed still sends")
            assertEquals(emptyList(), google.revoked)
        }
    }

    @Test
    fun `a company admin turns inbox sync on once google lets the account be read, and anyone managing can turn it off`() {
        val integration = integration(FakeGoogle(), inbox = true)
        routes(integration) { http ->
            val company = company()
            val sendOnly = integration.connected(company, "geral@example.pt")
            val reader = integration.connected(company, "caixa@example.pt", scopes = GoogleScopes.inbox)
            val path = "/app/api/integrations/${reader.id.toHexString()}"
            val on = buildJsonObject { put("inboxSync", true) }
            val off = buildJsonObject { put("inboxSync", false) }

            val consent = http.patchJson("/app/api/integrations/${sendOnly.id.toHexString()}", company.adminToken, on)
            assertEquals(HttpStatusCode.Conflict, consent.status)
            assertEquals("needs_consent", consent.obj()["error"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.Forbidden, http.patchJson(path, company.operatorToken, on).status)
            assertEquals(HttpStatusCode.Forbidden, http.patchJson(path, company.memberToken, on).status)

            val turnedOn = http.patchJson(path, company.adminToken, on)
            assertEquals(HttpStatusCode.OK, turnedOn.status)
            assertEquals(true, turnedOn.obj()["inboxSync"]!!.jsonPrimitive.boolean)
            val since = integration.connections.findById(reader.id)!!.inbox.enabledAt
            assertTrue(since != null)
            val renamed = http.patchJson(path, company.adminToken, buildJsonObject { put("senderName", "Caixa") }).obj()
            assertEquals(true, renamed["inboxSync"]!!.jsonPrimitive.boolean, "other settings leave the inbox alone")
            assertEquals(since, integration.connections.findById(reader.id)!!.inbox.enabledAt)

            assertFalse(http.patchJson(path, company.operatorToken, off).obj().flag("inboxSync"))
            assertNull(integration.connections.findById(reader.id)!!.inbox.enabledAt, "turning it off forgets where reading got to")
        }

        val closed = integration(FakeGoogle())
        routes(closed) { http ->
            val company = company()
            val reading = closed.connected(company, "caixa@example.pt", scopes = GoogleScopes.inbox)
            closed.connections.setInboxSync(company.tenant.id, reading.id, true)
            val idle = closed.connected(company, "outra@example.pt", scopes = GoogleScopes.inbox)
            val unavailable = http.patchJson("/app/api/integrations/${idle.id.toHexString()}", company.adminToken, buildJsonObject { put("inboxSync", true) })
            assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
            assertEquals("inbox_not_available", unavailable.obj()["error"]!!.jsonPrimitive.content)
            val stopped = http.patchJson("/app/api/integrations/${reading.id.toHexString()}", company.adminToken, buildJsonObject { put("inboxSync", false) })
            assertFalse(stopped.obj().flag("inboxSync"), "an inbox can always stop being read")
        }
    }

    @Test
    fun `disconnecting deletes the tokens and the kept mail, and revokes the grant once no company uses the account`() {
        val google = FakeGoogle()
        val integration = integration(google)
        routes(integration) { http ->
            val a = company()
            val b = company()
            val inA = integration.connected(a, "partilhada@example.pt")
            val inB = integration.connected(b, "partilhada@example.pt")
            assertEquals(HttpStatusCode.OK, http.post("/app/api/integrations/${inA.id.toHexString()}/test-email") { bearerAuth(a.adminToken) }.status)
            assertEquals(HttpStatusCode.OK, http.post("/app/api/integrations/${inB.id.toHexString()}/test-email") { bearerAuth(b.adminToken) }.status)
            assertEquals(1, keptMail(inA))

            assertEquals(HttpStatusCode.Forbidden, http.delete("/app/api/integrations/${inA.id.toHexString()}") { bearerAuth(a.memberToken) }.status)
            assertEquals(HttpStatusCode.NotFound, http.delete("/app/api/integrations/${inA.id.toHexString()}") { bearerAuth(b.adminToken) }.status)

            assertEquals(HttpStatusCode.NoContent, http.delete("/app/api/integrations/${inA.id.toHexString()}") { bearerAuth(a.adminToken) }.status)
            assertNull(integration.connections.findById(inA.id))
            assertEquals(0, keptMail(inA))
            assertEquals(1, keptMail(inB), "company B's mail stays")
            assertEquals(emptyList(), google.revoked, "company B still sends from it")

            assertEquals(HttpStatusCode.NoContent, http.delete("/app/api/integrations/${inB.id.toHexString()}") { bearerAuth(b.operatorToken) }.status)
            assertEquals(listOf("refresh-1"), google.revoked)
        }
    }

    @Test
    fun `a test email goes to the admin asking, or to the account for an operator, and counts toward the day`() {
        val google = FakeGoogle()
        val integration = integration(google)
        routes(integration) { http ->
            val company = company()
            val account = integration.connected(company, "geral@example.pt")
            val path = "/app/api/integrations/${account.id.toHexString()}/test-email"

            val byAdmin = http.post(path) { bearerAuth(company.adminToken) }
            assertEquals(HttpStatusCode.OK, byAdmin.status)
            assertEquals(company.admin.email, byAdmin.obj()["to"]!!.jsonPrimitive.content)
            val message = google.sends.single().parsed()
            assertEquals(listOf(company.admin.email), message.getRecipients(Message.RecipientType.TO).map { (it as InternetAddress).address })
            assertEquals(EmailCopy.t(company.tenant.locale, "test.subject", "company" to "Obras"), message.subject)
            assertEquals("geral@example.pt", http.post(path) { bearerAuth(company.operatorToken) }.obj()["to"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.Forbidden, http.post(path) { bearerAuth(company.memberToken) }.status)
            assertEquals(HttpStatusCode.NotFound, http.post(path) { bearerAuth(company().adminToken) }.status)

            val listed = http.list(company.memberToken)["connections"]!!.jsonArray.single().jsonObject
            assertEquals(2, listed["sentToday"]!!.jsonPrimitive.int)
            assertEquals(300, listed["dailyLimit"]!!.jsonPrimitive.int)

            settings.savePlatform(company.tenant.id, PlatformAgentLimits(emailSendsPerDay = 2))
            val spent = http.post(path) { bearerAuth(company.adminToken) }
            assertEquals(HttpStatusCode.TooManyRequests, spent.status)
            assertEquals("daily_send_limit", spent.obj()["error"]!!.jsonPrimitive.content)
            integration.connections.markNeedsReconnect(account.id, "invalid_grant")
            val stale = http.post(path) { bearerAuth(company.adminToken) }
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals("needs_reconnect", stale.obj()["error"]!!.jsonPrimitive.content)
            assertEquals(2, google.sends.size)
        }
    }

    @Test
    fun `the instagram callback doesn't take a google state`() = testApplication {
        application {
            configureSerialization()
            configureAdminAuth(runtimeConfig, tenants, users)
            routing {
                instagramOAuthRoutes(
                    configProvider = { AppConfig.InstagramConfig(appId = "ig", appSecret = "s", redirectUri = "https://bots.example/cb") },
                    oauthState = oauthState,
                    oauthClient = InstagramOAuthClient(AppConfig.InstagramConfig(), HttpClient(MockEngine) { engine { addHandler { error("Instagram isn't called") } } }),
                    bindingService = mockk(),
                    tenantRepository = tenants,
                )
            }
        }
        val state = oauthState.mint("acme", origin = OAuthState.ORIGIN_DASHBOARD, purpose = OAuthState.PURPOSE_GOOGLE, tenantId = "t", userId = "u")
        val response = createClient { followRedirects = false }.get("/admin/api/instagram/callback?code=c&state=${state.encodeURLParameter()}")
        assertEquals("/backoffice/?ig=error&reason=invalid_state", response.headers[HttpHeaders.Location])
    }
}
