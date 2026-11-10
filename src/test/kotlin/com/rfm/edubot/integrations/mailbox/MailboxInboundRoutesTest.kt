package com.rfm.edubot.integrations.mailbox

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.integrations.IntegrationConnectionRepository
import com.rfm.edubot.integrations.aws.FakeAws
import com.rfm.edubot.integrations.aws.S3Client
import com.rfm.edubot.integrations.aws.SesClient
import com.rfm.edubot.integrations.aws.SnsMessage
import com.rfm.edubot.integrations.aws.SnsVerifier
import com.rfm.edubot.integrations.email.EmailMessageRepository
import com.rfm.edubot.integrations.email.ReceivedEmails
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.Clock
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MailboxInboundRoutesTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("mailbox_routes")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val aws = FakeAws()
    private val tenants = TenantRepository(mongo)
    private val messages = EmailMessageRepository(mongo)

    private fun routes(config: AppConfig.MailboxConfig = aws.config, block: suspend (HttpClient, MailboxIntegration) -> Unit) = testApplication {
        val connections = IntegrationConnectionRepository(mongo)
        val mailbox = MailboxIntegration({ config }, connections, SesClient(aws.http, { config }), S3Client(aws.http, { config }))
        val inbound = MailboxInbound(mailbox, messages, ReceivedEmails(messages, DomainEventLog(mongo), mongo), tenants = { tenants.findById(it) })
        application { routing { mailboxInboundRoutes(mailbox, inbound, SnsVerifier(aws.http), aws.http) } }
        block(client, mailbox)
    }

    private suspend fun HttpClient.sns(body: String, type: String = SnsMessage.NOTIFICATION) = post("/integrations/email/ses") {
        header("x-amz-sns-message-type", type)
        // SNS posts JSON as text/plain.
        contentType(ContentType.Text.Plain)
        setBody(body)
    }

    @Test
    fun `confirms SNS's subscription for the platform's topic only`() = routes { http, _ ->
        assertEquals(HttpStatusCode.OK, http.sns(aws.signed(type = SnsMessage.SUBSCRIPTION_CONFIRMATION, message = "subscribe"), SnsMessage.SUBSCRIPTION_CONFIRMATION).status)
        assertEquals(1, aws.confirmations.size)
        assertTrue(aws.confirmations.single().startsWith("https://sns.eu-west-1.amazonaws.com/?Action=ConfirmSubscription"))

        val other = aws.signed(type = SnsMessage.SUBSCRIPTION_CONFIRMATION, message = "subscribe", topicArn = "arn:aws:sns:eu-west-1:999:someone-elses")
        assertEquals(HttpStatusCode.Forbidden, http.sns(other, SnsMessage.SUBSCRIPTION_CONFIRMATION).status)
        assertEquals(1, aws.confirmations.size)
    }

    @Test
    fun `refuses what SNS didn't sign, and anything but SNS`() = routes { http, _ ->
        assertEquals(HttpStatusCode.Forbidden, http.sns(aws.signed(message = "{}") { it.copy(message = "{\"forged\":true}") }).status)
        assertEquals(HttpStatusCode.BadRequest, http.sns("{\"hello\":1}").status)
    }

    @Test
    fun `a received email is kept, and one that can't be read now is asked again`() = routes { http, mailbox ->
        val now = Clock.System.now()
        val tenant = tenants.create(Tenant(slug = "r-${ObjectId().toHexString().takeLast(8)}", name = "Obras", channels = emptyList(), createdAt = now, updatedAt = now))
        val address = mailbox.create(tenant, null, "operator")!!
        val raw = "From: joao@cliente.pt\nTo: geral@obras.pt\nSubject: Olá\nMessage-ID: <r1@cliente.pt>\nContent-Type: text/plain\n\nBom dia"

        aws.s3Status = HttpStatusCode.InternalServerError
        val notification = aws.received(raw, listOf(address.accountEmail), sesId = "route-1").toString()
        assertEquals(HttpStatusCode.ServiceUnavailable, http.sns(aws.signed(message = notification)).status)
        aws.s3Status = null
        assertEquals(HttpStatusCode.OK, http.sns(aws.signed(message = notification)).status)
        assertEquals("joao@cliente.pt", messages.findByProviderId(tenant.id, address.id, "route-1")!!.from)

        // SES's test message when a topic is set on the rule, and anything else that isn't an email: taken, nothing kept.
        assertEquals(HttpStatusCode.OK, http.sns(aws.signed(message = "{\"notificationType\":\"AmazonSnsSubscriptionSucceeded\"}")).status)
    }

    @Test
    fun `nothing answers while the platform has no mail domain`() = routes(AppConfig.MailboxConfig()) { http, _ ->
        assertEquals(HttpStatusCode.NotFound, http.sns(aws.signed(message = "{}")).status)
    }
}
