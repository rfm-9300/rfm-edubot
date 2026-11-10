package com.rfm.edubot.integrations.email

import com.rfm.edubot.agents.model.OutboundStatus
import com.rfm.edubot.agents.store.AgentApprovalRepository
import com.rfm.edubot.agents.store.AgentRunRepository
import com.rfm.edubot.agents.store.AgentSettingsRepository
import com.rfm.edubot.agents.store.OutboundLogRepository
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.integrations.EmailSettings
import com.rfm.edubot.integrations.IntegrationConnection
import com.rfm.edubot.integrations.IntegrationConnectionRepository
import com.rfm.edubot.integrations.IntegrationProviders
import com.rfm.edubot.integrations.TokenCipher
import com.rfm.edubot.integrations.aws.FakeAws
import com.rfm.edubot.integrations.aws.S3Client
import com.rfm.edubot.integrations.aws.SesClient
import com.rfm.edubot.integrations.google.FakeGoogle
import com.rfm.edubot.integrations.google.GmailClient
import com.rfm.edubot.integrations.google.GoogleIntegration
import com.rfm.edubot.integrations.google.GoogleScopes
import com.rfm.edubot.integrations.google.GoogleTokenProvider
import com.rfm.edubot.integrations.mailbox.MailboxIntegration
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.DocumentTemplate
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
import io.ktor.http.HttpStatusCode
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Sending from a company's own address through Amazon SES. */
class MailboxEmailServiceTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("mailbox_email")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private var now = Instant.parse("2026-11-04T10:00:00Z")
    private val clock = { now }
    private val connections = IntegrationConnectionRepository(mongo, clock)
    private val messages = EmailMessageRepository(mongo, clock)
    private val outboundLog = OutboundLogRepository(mongo, clock)
    private val cipher = TokenCipher.fromConfig(Base64.getEncoder().encodeToString(Random(3).nextBytes(32)))!!

    private fun service(aws: FakeAws, mailboxConfig: AppConfig.MailboxConfig = aws.config, google: FakeGoogle? = null): EmailService {
        val fake = google ?: FakeGoogle()
        val oauth = fake.client()
        val googleConfig = if (google != null) fake.config else AppConfig.GoogleConfig()
        val tokens = GoogleTokenProvider(connections, oauth, cipher, NotificationRepository(mongo, clock), clock)
        val integration = GoogleIntegration({ googleConfig }, cipher, oauth, connections, tokens, GmailClient(fake.http))
        val mailbox = MailboxIntegration({ mailboxConfig }, connections, SesClient(aws.http, { mailboxConfig }, clock), S3Client(aws.http, { mailboxConfig }, clock))
        return EmailService(
            integration, messages, outboundLog, DomainEventLog(mongo, clock), AgentSettingsRepository(mongo, clock),
            AgentRunRepository(mongo, clock), AgentApprovalRepository(mongo, clock), clock, mailbox,
        )
    }

    private fun company() = Tenant(
        id = ObjectId(),
        slug = "obras-${ObjectId().toHexString().takeLast(6)}",
        name = "Obras Silva",
        channels = emptyList(),
        timezone = "Europe/Lisbon",
        documentTemplate = DocumentTemplate(companyName = "Obras Silva Lda", email = "geral@obras.pt"),
        createdAt = now,
        updatedAt = now,
    )

    private suspend fun address(tenant: Tenant, aws: FakeAws): IntegrationConnection =
        connections.createMailbox(tenant.id, "${tenant.slug}.k7p2x9@${aws.domain}", "geral@obras.pt", "u1", "admin@obras.pt")!!

    @Test
    fun `an email goes out from the company's address through SES, its Bcc in the envelope only`(): Unit = runBlocking {
        val aws = FakeAws()
        val tenant = company()
        val connection = address(tenant, aws)
        connections.updateSettings(tenant.id, connection.id, EmailSettings(senderName = "Obras Silva", replyTo = "geral@obras.pt", signature = "Rui"))
        val service = service(aws)
        assertTrue(service.isAvailable(tenant))
        assertEquals(connection.id, service.sender(tenant)!!.id)

        val clientId = ObjectId()
        val sent = service.send(
            tenant,
            OutgoingEmail(to = listOf("cliente@example.pt"), subject = "Orçamento ORC-7", text = "Olá", cc = listOf("socio@example.pt"), bcc = listOf("arquivo@obras.pt"), clientId = clientId),
            "ui:1",
        )

        val ok = assertIs<EmailSendResult.Sent>(sent)
        assertEquals("0102018f-ses-1-000000", ok.messageId)
        assertEquals(connection.accountEmail, ok.from)
        assertTrue(ok.threadId!!.startsWith("mb-"))
        val call = aws.sends.single()
        assertEquals(listOf("cliente@example.pt"), call.to)
        assertEquals(listOf("socio@example.pt"), call.cc)
        assertEquals(listOf("arquivo@obras.pt"), call.bcc)
        assertNull(call.header("Bcc"), "a Bcc header would show the hidden copy to everyone")
        assertTrue(call.authorization!!.contains("/eu-west-1/ses/aws4_request"))
        val message = call.parsed()
        val from = message.from.single() as InternetAddress
        assertEquals(connection.accountEmail, from.address)
        assertEquals("Obras Silva", from.personal)
        assertEquals("geral@obras.pt", (message.replyTo.single() as InternetAddress).address)
        assertEquals(listOf("cliente@example.pt"), message.getRecipients(Message.RecipientType.TO).map { (it as InternetAddress).address })

        val stored = messages.forClient(tenant.id, clientId).single()
        assertEquals("0102018f-ses-1-000000", stored.providerMessageId)
        assertEquals("<0102018f-ses-1-000000@eu-west-1.amazonses.com>", stored.messageIdHeader, "SES replaces the Message-ID; replies name SES's")
        assertEquals(ok.threadId, stored.threadId)
        assertEquals(OutboundStatus.SENT, outboundLog.find("ui:1")!!.status)
        assertEquals(1, connections.findById(connection.id)!!.sentOn("2026-11-04"))
    }

    @Test
    fun `a reply stays in its thread and names what it answers`(): Unit = runBlocking {
        val aws = FakeAws()
        val tenant = company()
        val connection = address(tenant, aws)
        val sent = service(aws).sendFrom(
            tenant,
            connection.id,
            OutgoingEmail(
                to = listOf("cliente@example.pt"), subject = "Re: Pedido", text = "Claro",
                threadId = "mb-thread-1", inReplyTo = "<abc@cliente.pt>", references = listOf("<first@obras.pt>", "<abc@cliente.pt>"),
            ),
        )
        assertEquals("mb-thread-1", assertIs<EmailSendResult.Sent>(sent).threadId)
        val call = aws.sends.single()
        assertEquals("<abc@cliente.pt>", call.header("In-Reply-To"))
        assertEquals(listOf("<first@obras.pt>", "<abc@cliente.pt>"), call.header("References")!!.split(Regex("\\s+")).filter { it.isNotBlank() })
    }

    @Test
    fun `the company's address is its sender over a Google account, and automations may read and send with it`(): Unit = runBlocking {
        val aws = FakeAws()
        val google = FakeGoogle()
        val tenant = company()
        connections.connect(tenant.id, IntegrationProviders.GOOGLE, "obras@gmail.com", listOf(GoogleScopes.GMAIL_SEND), cipher.seal("a"), cipher.seal("r"), now + 1.hours, "u1", "admin@obras.pt")
        val service = service(aws, google = google)
        assertEquals(IntegrationProviders.GOOGLE, service.sender(tenant)!!.provider)
        assertFalse(service.availability(tenant).inbox)

        address(tenant, aws)
        assertEquals(IntegrationProviders.MAILBOX, service.sender(tenant)!!.provider)
        val availability = service.availability(tenant)
        assertTrue(availability.send && availability.inbox)
        service.send(tenant, OutgoingEmail(to = listOf("cliente@example.pt"), subject = "Olá", text = "Olá"))
        assertEquals(1, aws.sends.size)
        assertTrue(google.sends.isEmpty())
    }

    @Test
    fun `SES's refusals keep their meaning`(): Unit = runBlocking {
        val aws = FakeAws()
        val tenant = company()
        val connection = address(tenant, aws)
        val service = service(aws)
        fun email() = OutgoingEmail(to = listOf("cliente@example.pt"), subject = "Olá", text = "Olá")

        aws.onSend = { Triple(HttpStatusCode.TooManyRequests, """{"message":"Maximum sending rate exceeded."}""", mapOf("x-amzn-ErrorType" to "TooManyRequestsException:")) }
        assertEquals(EmailSendResult.Failed(SesClient.RATE_LIMITED, retryable = true), service.send(tenant, email(), "k-1"))
        assertEquals(0, connections.findById(connection.id)!!.sentOn("2026-11-04"), "a refused send gives its allowance back")
        assertEquals(OutboundStatus.FAILED, outboundLog.find("k-1")!!.status)

        aws.onSend = {
            Triple(
                HttpStatusCode.BadRequest,
                """{"message":"Email address is not verified. The following identities failed the check in region EU-WEST-1: cliente@example.pt"}""",
                mapOf("x-amzn-ErrorType" to "MessageRejected:http://internal.amazon.com/coral/com.amazonaws.sesv2/"),
            )
        }
        assertEquals(EmailSendResult.Failed(SesClient.RECIPIENT_NOT_VERIFIED), service.send(tenant, email(), "k-2"))

        aws.onSend = { Triple(HttpStatusCode.BadRequest, """{"message":"Sending paused for this account."}""", mapOf("x-amzn-ErrorType" to "SendingPausedException:")) }
        assertEquals(EmailSendResult.Failed(SesClient.SENDING_PAUSED), service.send(tenant, email(), "k-3"))

        // SES took it without an id: it may have gone out, so the log stays claimed and nobody sends it again.
        aws.onSend = { Triple(HttpStatusCode.OK, "{}", emptyMap()) }
        assertEquals(EmailSendResult.Failed(EmailService.IN_DOUBT), service.send(tenant, email(), "k-4"))
        assertEquals(OutboundStatus.SENDING, outboundLog.find("k-4")!!.status)
    }

    @Test
    fun `without SES set up nothing goes out from the address`(): Unit = runBlocking {
        val aws = FakeAws()
        val tenant = company()
        val connection = address(tenant, aws)
        val service = service(aws, mailboxConfig = AppConfig.MailboxConfig())
        assertFalse(service.isAvailable(tenant))
        assertFalse(service.canSendFrom(connection))
        assertEquals(EmailSendResult.Failed(EmailService.NO_ACCOUNT), service.sendFrom(tenant, connection.id, OutgoingEmail(to = listOf("a@b.pt"), subject = "x", text = "x")))
        assertTrue(aws.sends.isEmpty())
    }
}
