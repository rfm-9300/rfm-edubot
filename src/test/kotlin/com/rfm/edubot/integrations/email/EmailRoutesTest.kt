package com.rfm.edubot.integrations.email

import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.agents.store.AgentSettingsRepository
import com.rfm.edubot.agents.store.OutboundLogRepository
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.lineItem
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.dashboard.dashboardToken
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.integrations.EmailSettings
import com.rfm.edubot.integrations.IntegrationConnection
import com.rfm.edubot.integrations.IntegrationProviders
import com.rfm.edubot.integrations.TokenCipher
import com.rfm.edubot.integrations.google.FakeGoogle
import com.rfm.edubot.integrations.google.GoogleIntegration
import com.rfm.edubot.integrations.google.GoogleScopes
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
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
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.internet.InternetAddress
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class EmailRoutesTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("email_routes")
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
    private val cipher = TokenCipher.fromConfig(Base64.getEncoder().encodeToString(Random(5).nextBytes(32)))!!
    private val json = Json { ignoreUnknownKeys = true }
    private val google = FakeGoogle()
    private val integration = GoogleIntegration.create(mongo, { google.config }, cipher, google.http, NotificationRepository(mongo))
    private val service = EmailService(integration, EmailMessageRepository(mongo), OutboundLogRepository(mongo), DomainEventLog(mongo), AgentSettingsRepository(mongo))

    private class Company(val tenant: Tenant, val member: DashboardUser, val memberToken: String)

    private suspend fun company(modules: List<String> = listOf(DashboardModules.CLIENTS, DashboardModules.QUOTES, DashboardModules.INVOICES)): Company {
        val tenant = tenants.create(
            Tenant(slug = "t-${ObjectId().toHexString().takeLast(8)}", name = "Obras", channels = emptyList(), enabledModules = modules, createdAt = now, updatedAt = now),
        )
        val member = users.create(DashboardUser(tenantId = tenant.id, email = "member-${ObjectId()}@example.pt", passwordHash = null, role = DashboardUserRole.TENANT_MEMBER, createdAt = now))
        return Company(tenant, member, dashboardToken(admin, member, "tenant", 1))
    }

    private suspend fun gmail(company: Company): IntegrationConnection = integration.connections.connect(
        tenantId = company.tenant.id,
        provider = IntegrationProviders.GOOGLE,
        accountEmail = "obras@example.pt",
        scopes = listOf(GoogleScopes.GMAIL_SEND),
        accessToken = cipher.seal("access-1"),
        refreshToken = cipher.seal("refresh-1"),
        accessTokenExpiresAt = now + 1.hours,
        connectedByUserId = "u1",
        connectedByEmail = "admin@example.pt",
    )

    private suspend fun quote(company: Company, email: String? = "ana@example.pt"): Pair<ObjectId, ObjectId> {
        val client = ClientRepository(mongo, company.tenant.id).create("Ana Ribeiro", "+351 911 000 111", email = email)
        return client.id to QuoteRepository(mongo, company.tenant.id).create(client.id, listOf(lineItem("Telhado", unitPriceEur = 900.0)), null, null).id
    }

    private fun routes(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            configureSerialization()
            configureAdminAuth(runtimeConfig, tenants, users)
            routing { emailRoutes(service, mongo) }
        }
        block(createClient { })
    }

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.error(): String = obj()["error"]!!.jsonPrimitive.content
    private fun JsonObject.text(name: String): String = this[name]!!.jsonPrimitive.content
    private fun JsonObject.flag(name: String): Boolean = this[name]?.jsonPrimitive?.boolean ?: false

    private suspend fun HttpClient.send(company: Company, body: JsonObject): HttpResponse = post("/app/api/email/send") {
        bearerAuth(company.memberToken)
        contentType(ContentType.Application.Json)
        setBody(body.toString())
    }

    private fun sendBody(type: String, id: ObjectId, to: String = "ana@example.pt", requestId: String? = "drawer-0001", subject: String = "Orçamento ORC-001") = buildJsonObject {
        put("type", type)
        put("id", id.toHexString())
        put("to", to)
        put("subject", subject)
        put("text", "Olá Ana,\n\nSegue o orçamento.")
        if (requestId != null) put("requestId", requestId)
    }

    @Test
    fun `the status says whether the company can send and from where`() = routes { http ->
        val company = company()
        val before = http.get("/app/api/email") { bearerAuth(company.memberToken) }.obj()
        assertEquals(true, before.flag("configured"))
        assertEquals(false, before.flag("canSend"))
        gmail(company)
        val after = http.get("/app/api/email") { bearerAuth(company.memberToken) }.obj()
        assertEquals(true, after.flag("canSend"))
        assertEquals("obras@example.pt", after.text("from"))
        assertEquals(HttpStatusCode.Unauthorized, http.get("/app/api/email").status)
    }

    @Test
    fun `the first draft is in the company's language, to the client, and signs off only without a signature`() = routes { http ->
        val company = company()
        val connection = gmail(company)
        val (clientId, quoteId) = quote(company)
        val locale = company.tenant.locale

        val draft = http.get("/app/api/email/draft?type=quote&id=${quoteId.toHexString()}") { bearerAuth(company.memberToken) }.obj()
        assertEquals("ana@example.pt", draft.text("to"))
        assertEquals(EmailCopy.t(locale, "quote.subject", "number" to "ORC-001", "company" to "Obras"), draft.text("subject"))
        assertEquals(EmailCopy.documentBody(locale, "quote", "Ana", "ORC-001", "Obras", signed = false), draft.text("text"))
        assertEquals("ORC-001.pdf", draft.text("attachment"))
        assertEquals(true, draft.flag("marksSent"))
        assertEquals(true, draft.flag("canSend"))

        integration.connections.updateSettings(company.tenant.id, connection.id, EmailSettings(signature = "Rui\nObras"))
        val invoice = InvoiceRepository(mongo, company.tenant.id).create(clientId, null, listOf(lineItem("Obra", unitPriceEur = 250.0)), LocalDate(2026, 10, 30))
        val signed = http.get("/app/api/email/draft?type=invoice&id=${invoice.id.toHexString()}") { bearerAuth(company.memberToken) }.obj()
        assertEquals(EmailCopy.documentBody(locale, "invoice", "Ana", invoice.number, "Obras", signed = true), signed.text("text"))
        assertFalse(signed.text("text").contains("Obras"), "the signature signs it")
        assertEquals(false, signed.flag("marksSent"))
    }

    @Test
    fun `sending a quote attaches its PDF, marks it sent, and a second press doesn't email twice`() = routes { http ->
        val company = company()
        gmail(company)
        val (clientId, quoteId) = quote(company)

        val first = http.send(company, sendBody("quote", quoteId))
        assertEquals(HttpStatusCode.OK, first.status)
        val sent = first.obj()
        assertEquals("gm-1", sent.text("messageId"))
        assertEquals(true, sent.flag("markedSent"))
        val again = http.send(company, sendBody("quote", quoteId)).obj()
        assertEquals(true, again.flag("alreadySent"))
        assertEquals(false, again.flag("markedSent"))

        assertEquals(1, google.sends.size)
        val message = google.sends.single().parsed()
        assertEquals(listOf("ana@example.pt"), message.getRecipients(Message.RecipientType.TO).map { (it as InternetAddress).address })
        assertEquals("ORC-001.pdf", (message.content as Multipart).getBodyPart(1).fileName)
        val quote = QuoteRepository(mongo, company.tenant.id).findById(quoteId)!!
        assertEquals(QuoteStatus.SENT, quote.status)
        assertTrue(quote.sentAt != null)

        val list = http.get("/app/api/email/messages?clientId=${clientId.toHexString()}") { bearerAuth(company.memberToken) }.body()
        val row = list.jsonArray.single().jsonObject
        assertEquals("OUTBOUND", row.text("direction"))
        assertEquals("quote", row.text("recordType"))
        assertEquals(quoteId.toHexString(), row.text("recordId"))
        assertEquals(listOf("ORC-001.pdf"), row["attachments"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("USER", row.text("sentByType"))
        assertEquals(company.member.email, row.text("sentBy"))
        assertTrue(row.text("body").startsWith("Olá Ana,"))

        val other = http.send(company, sendBody("quote", quoteId, requestId = null))
        assertEquals(false, other.obj().flag("markedSent"), "the quote was already sent")
        assertEquals(2, google.sends.size, "without a drawer id each press is a new email")
    }

    @Test
    fun `what can't be sent is refused before gmail`() = routes { http ->
        val company = company()
        val (clientId, quoteId) = quote(company)
        assertEquals("no_email_account", http.send(company, sendBody("quote", quoteId)).error())
        gmail(company)

        assertEquals("invalid_type", http.send(company, sendBody("booking", quoteId)).error())
        assertEquals(HttpStatusCode.NotFound, http.send(company, sendBody("quote", ObjectId())).status)
        assertEquals(HttpStatusCode.NotFound, http.send(company(), sendBody("quote", quoteId)).status, "another company's quote")
        assertEquals("empty_subject", http.send(company, sendBody("quote", quoteId, subject = " ")).error())
        assertEquals("no_email", http.send(company, sendBody("quote", quoteId, to = "")).error())
        val bad = http.send(company, sendBody("quote", quoteId, to = "ana at example"))
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("invalid_recipient", bad.error())
        val noQuotes = company(listOf(DashboardModules.CLIENTS))
        assertEquals("module_disabled", http.send(noQuotes, sendBody("quote", quoteId)).error())
        assertEquals(HttpStatusCode.BadRequest, http.get("/app/api/email/messages") { bearerAuth(company.memberToken) }.status)
        val noClients = company(listOf(DashboardModules.QUOTES))
        assertEquals(HttpStatusCode.Forbidden, http.get("/app/api/email/messages?clientId=${clientId.toHexString()}") { bearerAuth(noClients.memberToken) }.status)
        assertTrue(google.sends.isEmpty())
    }

    private suspend fun HttpResponse.body(): JsonElement = json.parseToJsonElement(bodyAsText())
}
