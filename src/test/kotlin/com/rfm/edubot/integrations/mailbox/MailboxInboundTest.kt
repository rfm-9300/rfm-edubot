package com.rfm.edubot.integrations.mailbox

import com.mongodb.client.model.Filters
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.integrations.IntegrationConnection
import com.rfm.edubot.integrations.IntegrationConnectionRepository
import com.rfm.edubot.integrations.aws.FakeAws
import com.rfm.edubot.integrations.aws.S3Client
import com.rfm.edubot.integrations.aws.SesClient
import com.rfm.edubot.integrations.email.EmailDirection
import com.rfm.edubot.integrations.email.EmailMessage
import com.rfm.edubot.integrations.email.EmailMessageRepository
import com.rfm.edubot.integrations.email.ReceivedEmails
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.DocumentTemplate
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class MailboxInboundTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("mailbox_inbound")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private var now = Instant.parse("2026-11-04T10:00:00Z")
    private val clock = { now }
    private val aws = FakeAws()
    private val connections = IntegrationConnectionRepository(mongo, clock)
    private val messages = EmailMessageRepository(mongo, clock)
    private val tenants = TenantRepository(mongo)
    private val mailbox = MailboxIntegration({ aws.config }, connections, SesClient(aws.http, { aws.config }, clock), S3Client(aws.http, { aws.config }, clock))
    private val inbound = MailboxInbound(
        mailbox, messages, ReceivedEmails(messages, DomainEventLog(mongo, clock), mongo, clock),
        tenants = { tenants.findById(it) },
        team = { setOf("rui@obras.pt") },
        clock = clock,
    )

    private class Company(val tenant: Tenant, val address: IntegrationConnection)

    private suspend fun company(): Company {
        val tenant = tenants.create(
            Tenant(
                slug = "obras-${ObjectId().toHexString().takeLast(8)}", name = "Obras", channels = emptyList(),
                documentTemplate = DocumentTemplate(companyName = "Obras Silva", email = "geral@obras.pt"), createdAt = now, updatedAt = now,
            ),
        )
        return Company(tenant, mailbox.create(tenant, "u1", "admin@obras.pt")!!)
    }

    private fun email(from: String, subject: String = "Pedido de orçamento", body: String = "Bom dia, queria um orçamento.", headers: String? = null, messageId: String = "<${ObjectId()}@cliente.pt>") = buildString {
        append("From: $from\n")
        append("To: Obras <geral@obras.pt>\n")
        append("Subject: $subject\n")
        append("Message-ID: $messageId\n")
        headers?.let { append(it).append('\n') }
        append("Content-Type: text/plain; charset=UTF-8\n\n")
        append(body)
    }

    /** The newest email of each thread, whole (the list leaves the text out). */
    private suspend fun kept(company: Company): List<EmailMessage> =
        messages.threads(company.tenant.id).map { messages.find(company.tenant.id, it.latest.id)!! }

    private suspend fun received(company: Company): Long =
        mongo.database.getCollection<Document>(DomainEventLog.COLLECTION)
            .countDocuments(Filters.and(Filters.eq("tenantId", company.tenant.id), Filters.eq("type", DomainEventTypes.EMAIL_RECEIVED)))

    @Test
    fun `mail forwarded to a company's address lands on its Email page, filed under the client`(): Unit = runBlocking {
        val company = company()
        val joao = ClientRepository(mongo, company.tenant.id).create(name = "João", phone = "912000000", email = "joao@cliente.pt")
        val notification = aws.received(
            email("João <Joao@Cliente.pt>", headers = "X-Forwarded-For: geral@obras.pt ${company.address.accountEmail}"),
            recipients = listOf(company.address.accountEmail.uppercase()),
            sesId = "ses-a",
            timestamp = now,
        )

        assertEquals(MailboxInbound.Outcome.Kept(1), inbound.received(notification))

        val stored = kept(company).single()
        assertEquals(EmailDirection.INBOUND, stored.direction)
        assertEquals("joao@cliente.pt", stored.from)
        assertEquals("João", stored.fromName)
        assertEquals("ses-a", stored.providerMessageId)
        assertEquals(joao.id, stored.clientId)
        assertEquals("Bom dia, queria um orçamento.", stored.bodyText)
        assertEquals(now, stored.date)
        assertTrue(stored.threadId!!.startsWith("mb-"))
        assertEquals(1L, received(company))
        assertTrue("ses-a" !in aws.objects && "ses-a" in aws.deletes, "SES's copy is deleted once kept")
        val state = connections.findById(company.address.id)!!.mailbox
        assertEquals(now, state.lastReceivedAt)
        assertEquals(1, state.received)
        assertEquals(listOf("geral@obras.pt"), state.forwarders)
    }

    @Test
    fun `a reply to an email the company sent stays in that thread`(): Unit = runBlocking {
        val company = company()
        messages.insert(
            EmailMessage(
                tenantId = company.tenant.id, connectionId = company.address.id, providerMessageId = "0102018f-sent-1",
                threadId = "mb-thread", messageIdHeader = "<0102018f-sent-1@eu-west-1.amazonses.com>", direction = EmailDirection.OUTBOUND,
                from = company.address.accountEmail, to = listOf("joao@cliente.pt"), subject = "Orçamento", snippet = "", date = now - 30.minutes, createdAt = now,
            ),
        )
        inbound.received(aws.received(email("joao@cliente.pt", "Re: Orçamento", headers = "In-Reply-To: <0102018f-sent-1@eu-west-1.amazonses.com>"), listOf(company.address.accountEmail)))
        // A mail program that only kept SES's id, on another of SES's domains, still finds it.
        inbound.received(aws.received(email("joao@cliente.pt", "Re: Orçamento", headers = "References: <0102018f-sent-1@email.amazonses.com>"), listOf(company.address.accountEmail)))

        val thread = messages.thread(messages.threads(company.tenant.id).single().latest)
        assertEquals(3, thread.size)
        assertTrue(thread.all { it.threadId == "mb-thread" })
    }

    @Test
    fun `the same email is kept once, however it arrives twice`(): Unit = runBlocking {
        val company = company()
        val raw = email("joao@cliente.pt", messageId = "<twice@cliente.pt>")
        assertEquals(MailboxInbound.Outcome.Kept(1), inbound.received(aws.received(raw, listOf(company.address.accountEmail), sesId = "ses-1")))
        // SNS delivering the notification again: the object is gone and the email kept already.
        assertEquals(MailboxInbound.Outcome.Kept(0), inbound.received(aws.received(null, listOf(company.address.accountEmail), sesId = "ses-1")))
        // Forwarded twice (a filter and a hand forward): another SES id, the same Message-ID.
        assertEquals(MailboxInbound.Outcome.Kept(0), inbound.received(aws.received(raw, listOf(company.address.accountEmail), sesId = "ses-2")))
        assertEquals(1, kept(company).size)
    }

    @Test
    fun `viruses, spam, forged senders and mail for nobody are dropped`(): Unit = runBlocking {
        val company = company()
        val to = listOf(company.address.accountEmail)
        assertEquals(MailboxInbound.Outcome.Ignored("virus"), inbound.received(aws.received(email("a@x.pt"), to, sesId = "v", virus = "FAIL")))
        assertEquals(MailboxInbound.Outcome.Ignored("spam"), inbound.received(aws.received(email("a@x.pt"), to, sesId = "s", spam = "FAIL")))
        assertEquals(MailboxInbound.Outcome.Ignored("dmarc_reject"), inbound.received(aws.received(email("ceo@bank.example"), to, sesId = "d", dmarc = "FAIL", dmarcPolicy = "reject")))
        assertEquals(MailboxInbound.Outcome.Ignored("unknown_recipient"), inbound.received(aws.received(email("a@x.pt"), listOf("nobody.aaaaaa@${aws.domain}"), sesId = "n")))
        assertEquals(MailboxInbound.Outcome.Ignored("unknown_recipient"), inbound.received(aws.received(email("a@x.pt"), listOf("someone@other.example"), sesId = "o")))
        assertEquals(MailboxInbound.Outcome.Ignored("no_content"), inbound.received(aws.received(email("a@x.pt"), to, sesId = "b", bucket = "someone-elses")))
        assertTrue(listOf("v", "s", "d", "n", "o").all { it in aws.deletes })
        // DMARC failing for a domain that doesn't ask for rejection is kept (forwarding can break SPF).
        assertEquals(MailboxInbound.Outcome.Kept(1), inbound.received(aws.received(email("a@x.pt"), to, sesId = "g", dmarc = "FAIL", dmarcPolicy = "none")))
        assertEquals(1, kept(company).size)
    }

    @Test
    fun `mail forwarded by hand is kept as from whoever wrote it, and the company's own mail isn't kept`(): Unit = runBlocking {
        val company = company()
        val to = listOf(company.address.accountEmail)
        val forwarded = email(
            "Rui Silva <rui@obras.pt>",
            subject = "Fwd: Pedido de orçamento",
            body = "Vê isto\n\n---------- Forwarded message ---------\nFrom: Maria Costa <maria@cliente.pt>\nDate: Mon, 2 Nov 2026\nSubject: Pedido de orçamento\nTo: <geral@obras.pt>\n\nQueria um orçamento para a cozinha.",
        )
        assertEquals(MailboxInbound.Outcome.Kept(1), inbound.received(aws.received(forwarded, to)))
        val stored = kept(company).single()
        assertEquals("maria@cliente.pt", stored.from)
        assertEquals("Maria Costa", stored.fromName)
        assertEquals("rui@obras.pt", stored.forwardedBy)
        assertEquals("Pedido de orçamento", stored.subject)
        assertTrue(stored.bodyText!!.contains("Queria um orçamento para a cozinha."))

        // The company writing from its own address, with nothing forwarded inside.
        assertEquals(MailboxInbound.Outcome.Kept(0), inbound.received(aws.received(email("geral@obras.pt", "Nota interna"), to)))
        // A teammate writing in is kept as their own email.
        assertEquals(MailboxInbound.Outcome.Kept(1), inbound.received(aws.received(email("rui@obras.pt", "Pergunta"), to)))
        assertEquals(2, kept(company).size)
    }

    @Test
    fun `Gmail's forwarding confirmation waits on the setup screen until forwarding works`(): Unit = runBlocking {
        val company = company()
        val to = listOf(company.address.accountEmail)
        val confirmation = email(
            "Gmail Team <forwarding-noreply@google.com>",
            subject = "(#123456789) Gmail Forwarding Confirmation - Receive Mail from maria.obras@gmail.com",
            body = "maria.obras@gmail.com has requested to automatically forward mail to your email address.\nConfirmation code: 123456789\nhttps://mail-settings.google.com/mail/vf-%5BABC%5D-def",
        )
        inbound.received(aws.received(confirmation, to, dkim = "PASS"))

        val pending = assertNotNull(connections.findById(company.address.id)!!.mailbox.confirmation)
        assertEquals("123456789", pending.code)
        assertEquals("maria.obras@gmail.com", pending.account)
        val stored = kept(company).single()
        assertTrue(stored.automated, "Gmail's own email is a machine's")
        assertEquals(0L, received(company), "it wakes no automation")

        now += 5.minutes
        inbound.received(aws.received(email("joao@cliente.pt", headers = "X-Forwarded-For: maria.obras@gmail.com ${company.address.accountEmail}"), to))
        assertNull(connections.findById(company.address.id)!!.mailbox.confirmation, "mail forwarded from that Gmail means it was confirmed")
    }

    @Test
    fun `S3 being unreachable has SNS try again, keeping SES's copy`(): Unit = runBlocking {
        val company = company()
        aws.s3Status = HttpStatusCode.ServiceUnavailable
        val notification = aws.received(email("joao@cliente.pt"), listOf(company.address.accountEmail), sesId = "later")
        assertEquals(MailboxInbound.Outcome.Retry("s3_unavailable"), inbound.received(notification))
        aws.s3Status = null
        assertTrue("later" in aws.objects)
        assertEquals(MailboxInbound.Outcome.Kept(1), inbound.received(notification))
    }

    @Test
    fun `an address belongs to one company and each company has one`(): Unit = runBlocking {
        val company = company()
        assertEquals(company.address.id, mailbox.create(company.tenant, "u2", "other@obras.pt")!!.id)
        val other = tenants.create(Tenant(slug = "outra-${ObjectId().toHexString().takeLast(6)}", name = "Outra", channels = emptyList(), createdAt = now, updatedAt = now))
        assertNull(connections.createMailbox(other.id, company.address.accountEmail, null, null, null), "someone else's address is refused")
        assertEquals("geral@obras.pt", company.address.settings.replyTo, "replies start going to the address on the company's documents")
        val all = mongo.database.getCollection<Document>(IntegrationConnectionRepository.COLLECTION).find(Filters.eq("tenantId", company.tenant.id)).toList()
        assertEquals(1, all.size)
    }
}
