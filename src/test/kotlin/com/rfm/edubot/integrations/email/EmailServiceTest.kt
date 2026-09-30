package com.rfm.edubot.integrations.email

import com.rfm.edubot.agents.model.OutboundStatus
import com.rfm.edubot.agents.model.PlatformAgentLimits
import com.rfm.edubot.agents.store.AgentSettingsRepository
import com.rfm.edubot.agents.store.OutboundLogRepository
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.events.Actor
import com.rfm.edubot.events.ActorContext
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.integrations.ConnectionStatus
import com.rfm.edubot.integrations.EmailSettings
import com.rfm.edubot.integrations.IntegrationConnection
import com.rfm.edubot.integrations.IntegrationConnectionRepository
import com.rfm.edubot.integrations.IntegrationProviders
import com.rfm.edubot.integrations.TokenCipher
import com.rfm.edubot.integrations.google.FakeGoogle
import com.rfm.edubot.integrations.google.GmailClient
import com.rfm.edubot.integrations.google.GoogleIntegration
import com.rfm.edubot.integrations.google.GoogleScopes
import com.rfm.edubot.integrations.google.GoogleTokenProvider
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.DocumentTemplate
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
import io.ktor.http.HttpStatusCode
import jakarta.mail.Multipart
import jakarta.mail.internet.InternetAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonPrimitive
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class EmailServiceTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("email_service")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val cipher = TokenCipher.fromConfig(Base64.getEncoder().encodeToString(Random(7).nextBytes(32)))!!
    // 23:30 in Lisbon (UTC+1 in summer): an hour later the company's day turns while UTC's is still the 30th.
    private var now = Instant.parse("2026-09-30T22:30:00Z")
    private val clock = { now }
    private val connections = IntegrationConnectionRepository(mongo, clock)
    private val notifications = NotificationRepository(mongo, clock)
    private val messages = EmailMessageRepository(mongo, clock)
    private val outboundLog = OutboundLogRepository(mongo, clock)
    private val events = DomainEventLog(mongo, clock)
    private val settings = AgentSettingsRepository(mongo, clock)

    private fun service(google: FakeGoogle, configured: Boolean = true): EmailService {
        val oauth = google.client()
        val config = if (configured) google.config else AppConfig.GoogleConfig()
        val tokens = GoogleTokenProvider(connections, oauth, cipher, notifications, clock)
        return EmailService(GoogleIntegration({ config }, cipher, oauth, connections, tokens, GmailClient(google.http)), messages, outboundLog, events, settings, clock)
    }

    private fun company(template: DocumentTemplate = DocumentTemplate()) = Tenant(
        id = ObjectId(),
        slug = "t-${ObjectId().toHexString().takeLast(8)}",
        name = "Obras Silva",
        channels = emptyList(),
        timezone = "Europe/Lisbon",
        documentTemplate = template,
        createdAt = now,
        updatedAt = now,
    )

    private suspend fun account(tenant: Tenant, email: String = "obras@example.pt", scopes: List<String> = listOf(GoogleScopes.GMAIL_SEND)): IntegrationConnection =
        connections.connect(
            tenantId = tenant.id,
            provider = IntegrationProviders.GOOGLE,
            accountEmail = email,
            scopes = scopes,
            accessToken = cipher.seal("access-1"),
            refreshToken = cipher.seal("refresh-1"),
            accessTokenExpiresAt = now + 1.hours,
            connectedByUserId = "u1",
            connectedByEmail = "admin@example.pt",
        )

    private fun email(to: String = "Cliente@Example.PT", subject: String = "Orçamento Q-7") =
        OutgoingEmail(to = listOf(to), subject = subject, text = "Olá Rui,\n\nSegue o orçamento.")

    private suspend fun sentToday(connection: IntegrationConnection, day: String = "2026-09-30") = connections.findById(connection.id)!!.sentOn(day)

    @Test
    fun `an email goes out from the default account in the company's branding and is kept`(): Unit = runBlocking {
        val google = FakeGoogle()
        val tenant = company(DocumentTemplate(companyName = "Obras Silva Lda", accentColor = "#0f766e", phone = "+351 210 000 000"))
        val connection = account(tenant)
        connections.updateSettings(tenant.id, connection.id, EmailSettings(senderName = "Obras Silva · Orçamentos", replyTo = "Geral@Obras.pt", signature = "Rui Silva\nObras Silva"))
        val clientId = ObjectId()
        val quote = SubjectRef(SubjectTypes.QUOTE, ObjectId().toHexString())
        val pdf = EmailAttachment("Q-7.pdf", "application/pdf", "%PDF-1.4 orçamento".toByteArray())
        val outgoing = email().copy(cc = listOf("cliente@example.pt", "socio@example.pt"), attachments = listOf(pdf), clientId = clientId, record = quote)

        val sent = service(google).send(tenant, outgoing, "run-1:step-1")

        assertEquals(EmailSendResult.Sent("gm-1", "th-1", "obras@example.pt"), sent)
        val call = google.sends.single()
        assertEquals("access-1", call.accessToken)
        val message = call.parsed()
        val from = message.from.single() as InternetAddress
        assertEquals("obras@example.pt", from.address)
        assertEquals("Obras Silva · Orçamentos", from.personal)
        assertEquals(listOf("cliente@example.pt"), message.getRecipients(jakarta.mail.Message.RecipientType.TO).map { (it as InternetAddress).address })
        assertEquals(listOf("socio@example.pt"), message.getRecipients(jakarta.mail.Message.RecipientType.CC).map { (it as InternetAddress).address }, "the client isn't copied on their own email")
        assertEquals("geral@obras.pt", (message.replyTo.single() as InternetAddress).address)
        assertEquals("Orçamento Q-7", message.subject)
        val parts = message.content as Multipart
        assertEquals("Q-7.pdf", parts.getBodyPart(1).fileName)
        val alternative = parts.getBodyPart(0).content as Multipart
        val text = (alternative.getBodyPart(0).content as String).replace("\r\n", "\n")
        assertEquals("Olá Rui,\n\nSegue o orçamento.\n\n-- \nRui Silva\nObras Silva", text)
        val html = alternative.getBodyPart(1).content as String
        assertTrue("#0f766e" in html.lowercase() && "Obras Silva Lda" in html && "+351 210 000 000" in html, html)

        val log = outboundLog.find("run-1:step-1")!!
        assertEquals(OutboundStatus.SENT, log.status)
        assertEquals("gm-1", log.providerMessageId)
        assertEquals("email", log.channel)
        assertEquals("cliente@example.pt", log.recipient)

        val stored = messages.forClient(tenant.id, clientId).single()
        assertEquals(EmailDirection.OUTBOUND, stored.direction)
        assertEquals("th-1", stored.threadId)
        assertEquals(listOf("cliente@example.pt"), stored.to)
        assertEquals(listOf("socio@example.pt"), stored.cc)
        assertEquals("Olá Rui, Segue o orçamento.", stored.snippet)
        assertTrue(stored.bodyText!!.endsWith("-- \nRui Silva\nObras Silva"), "the body keeps what the client read")
        assertEquals(listOf(EmailAttachmentInfo("Q-7.pdf", "application/pdf", pdf.bytes.size)), stored.attachments)
        assertEquals(quote, stored.record)
        assertEquals("SYSTEM", stored.sentByType)
        assertTrue(stored.messageIdHeader!!.endsWith("@example.pt>"))

        val event = events.timeline(tenant.id, SubjectRef.of(SubjectTypes.CLIENT, clientId)).single()
        assertEquals(DomainEventTypes.EMAIL_SENT, event.type)
        assertEquals(SubjectRef.of(SubjectTypes.EMAIL, stored.id), event.subject)
        assertEquals("Orçamento Q-7", event.payload["subject"]!!.jsonPrimitive.content)
        assertEquals("true", event.payload["hasPdf"]!!.jsonPrimitive.content)
        assertTrue(quote in event.related)
        assertEquals(1, sentToday(connection))
    }

    @Test
    fun `the same key never sends twice and an agent's email is signed by its run`(): Unit = runBlocking {
        val google = FakeGoogle()
        val tenant = company()
        val connection = account(tenant)
        val service = service(google)
        val agentId = ObjectId()
        val runId = ObjectId()
        val clientId = ObjectId()

        val first = withContext(ActorContext(Actor.agent(agentId, runId, "Follow-up"))) { service.send(tenant, email().copy(clientId = clientId), "$runId:s1") }
        val again = withContext(ActorContext(Actor.agent(agentId, runId, "Follow-up"))) { service.send(tenant, email().copy(clientId = clientId), "$runId:s1") }

        assertEquals(EmailSendResult.Sent("gm-1", "th-1", "obras@example.pt"), first)
        assertEquals(EmailSendResult.Sent("gm-1", "th-1", "obras@example.pt", alreadySent = true), again)
        assertEquals(1, google.sends.size)
        assertEquals(1, sentToday(connection))
        val stored = messages.forClient(tenant.id, clientId).single()
        assertEquals("AGENT", stored.sentByType)
        assertEquals(agentId.toHexString(), stored.sentById)
        assertEquals("Follow-up", stored.sentByName)
        assertEquals(runId.toHexString(), stored.runId)
        val log = outboundLog.find("$runId:s1")!!
        assertEquals(runId, log.runId)
        assertEquals(agentId, log.agentId)

        service.send(tenant, email())
        service.send(tenant, email())
        assertEquals(3, google.sends.size, "without a key every call is a new email")
    }

    @Test
    fun `the daily allowance stops sends and a refused send gives its place back`(): Unit = runBlocking {
        val google = FakeGoogle()
        val tenant = company()
        val connection = account(tenant)
        settings.savePlatform(tenant.id, PlatformAgentLimits(emailSendsPerDay = 2))
        val service = service(google)

        assertIs<EmailSendResult.Sent>(service.send(tenant, email(), "a"))
        google.onSend = { HttpStatusCode.ServiceUnavailable to "<html>down</html>" }
        assertEquals(EmailSendResult.Failed("send_failed", retryable = true), service.send(tenant, email(), "b"))
        assertEquals(1, sentToday(connection), "the failed send doesn't count")
        assertEquals(OutboundStatus.FAILED, outboundLog.find("b")!!.status)

        google.onSend = { HttpStatusCode.OK to """{"id":"gm-b","threadId":"th-b"}""" }
        assertEquals(EmailSendResult.Sent("gm-b", "th-b", "obras@example.pt"), service.send(tenant, email(), "b"), "a failed key may be tried again")
        assertEquals(EmailSendResult.Failed("daily_send_limit"), service.send(tenant, email(), "c"))
        assertEquals(3, google.sends.size, "the third email of the day never reached gmail")
        assertEquals(2, sentToday(connection))

        now = Instant.parse("2026-09-30T23:30:00Z")
        google.onSend = { HttpStatusCode.OK to """{"id":"gm-c","threadId":"th-c"}""" }
        assertIs<EmailSendResult.Sent>(service.send(tenant, email(), "c"), "Lisbon's new day brings a new allowance")
        assertEquals(1, sentToday(connection, "2026-10-01"))
    }

    @Test
    fun `nothing is sent without a working account or with bad addresses`(): Unit = runBlocking {
        val google = FakeGoogle()
        val service = service(google)
        val none = company()
        assertEquals(EmailSendResult.Failed("no_email_account"), service.send(none, email()))
        assertFalse(service.isAvailable(none))
        assertFalse(service.availability(none).send)

        val tenant = company()
        val connection = account(tenant)
        assertTrue(service.isAvailable(tenant))
        assertEquals(EmailSendResult.Failed("no_email_account"), service(google, configured = false).send(tenant, email()))
        assertFalse(service(google, configured = false).isAvailable(tenant))
        assertFalse(service(google, configured = false).availability(tenant).send)

        assertEquals(EmailSendResult.Failed("invalid_recipient"), service.send(tenant, email(to = "rui at obras")))
        assertEquals(EmailSendResult.Failed("invalid_recipient"), service.send(tenant, email().copy(cc = listOf("x@y"))))
        assertEquals(EmailSendResult.Failed("invalid_recipient"), service.send(tenant, email().copy(replyTo = "nobody")))
        assertEquals(EmailSendResult.Failed("invalid_recipient"), service.send(tenant, email().copy(to = listOf(" "))))
        val crowd = (1..20).map { "p$it@example.pt" }
        assertEquals(EmailSendResult.Failed("too_many_recipients"), service.send(tenant, email().copy(bcc = crowd)))
        val huge = EmailAttachment("big.pdf", "application/pdf", ByteArray(GmailClient.MAX_RAW_BYTES))
        assertEquals(EmailSendResult.Failed("message_too_large"), service.send(tenant, email().copy(attachments = listOf(huge))))
        assertTrue(google.sends.isEmpty())
        assertEquals(0, sentToday(connection))

        connections.markNeedsReconnect(connection.id, "invalid_grant")
        assertEquals(EmailSendResult.Failed("needs_reconnect"), service.send(tenant, email()))
        assertFalse(service.isAvailable(tenant))
        assertTrue(service.availability(tenant).send, "automations stay valid while an admin reconnects")

        val readOnly = company()
        account(readOnly, scopes = listOf(GoogleScopes.OPENID, GoogleScopes.EMAIL))
        assertEquals(EmailSendResult.Failed("needs_reconnect"), service.send(readOnly, email()))
        assertFalse(service.availability(readOnly).send)
        assertTrue(google.sends.isEmpty())
    }

    @Test
    fun `a token gmail refuses early is renewed once and a grant without sending asks for a reconnect`(): Unit = runBlocking {
        val google = FakeGoogle()
        google.onSend = { send ->
            if (send.accessToken == "access-1") HttpStatusCode.Unauthorized to google.googleError(401, "authError") else HttpStatusCode.OK to """{"id":"gm-2","threadId":"th-2"}"""
        }
        val tenant = company()
        account(tenant)
        assertEquals(EmailSendResult.Sent("gm-2", "th-2", "obras@example.pt"), service(google).send(tenant, email()))
        assertEquals(listOf("access-1", "access-refreshed"), google.sends.map { it.accessToken })
        assertEquals(1, google.tokenCalls.size)

        val narrowed = company()
        val connection = account(narrowed)
        google.onSend = { HttpStatusCode.Forbidden to google.googleError(403, "insufficientPermissions") }
        assertEquals(EmailSendResult.Failed("needs_reconnect"), service(google).send(narrowed, email(), "k"))
        val after = connections.findById(connection.id)!!
        assertEquals(ConnectionStatus.NEEDS_RECONNECT, after.status)
        assertEquals("insufficient_permissions", after.lastError)
        assertEquals(1, notifications.listFor(narrowed.id, reader = "admin-1", isAdmin = true).size)
        assertEquals(0, sentToday(connection))
    }

    @Test
    fun `an email gmail took without saying so is never sent again`(): Unit = runBlocking {
        val google = FakeGoogle()
        google.onSend = { HttpStatusCode.OK to "{}" }
        val tenant = company()
        val connection = account(tenant)
        val service = service(google)

        assertEquals(EmailSendResult.Failed("send_in_doubt"), service.send(tenant, email(), "k"))
        assertEquals(OutboundStatus.SENDING, outboundLog.find("k")!!.status)
        assertEquals(EmailSendResult.Failed("send_in_doubt"), service.send(tenant, email(), "k"))
        assertEquals(1, google.sends.size)
        assertEquals(1, sentToday(connection), "it may have gone out, so it counts")
    }
}
