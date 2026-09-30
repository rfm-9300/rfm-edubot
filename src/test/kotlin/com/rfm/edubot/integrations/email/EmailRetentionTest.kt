package com.rfm.edubot.integrations.email

import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.TestMongo
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class EmailRetentionTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("email_retention")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private var now = Instant.parse("2026-09-30T12:00:00Z")
    private val clock = { now }
    private val messages = EmailMessageRepository(mongo, clock)
    private val retention = EmailRetention(messages, clock = clock)
    private val tenantId = ObjectId()
    private val clientId = ObjectId()

    private fun email(date: Instant, body: String? = "Olá Maria,\n\nSegue em anexo o orçamento ORC-001.") = EmailMessage(
        tenantId = tenantId,
        connectionId = ObjectId(),
        providerMessageId = ObjectId().toHexString(),
        direction = EmailDirection.OUTBOUND,
        from = "obras@example.pt",
        to = listOf("maria@example.pt"),
        subject = "Orçamento ORC-001",
        snippet = EmailMessage.snippetOf(body ?: "Obrigada! Podem começar na próxima semana?"),
        bodyText = body,
        attachments = listOf(EmailAttachmentInfo("ORC-001.pdf", "application/pdf", 1200)),
        clientId = clientId,
        record = SubjectRef(SubjectTypes.QUOTE, ObjectId().toHexString()),
        date = date,
        createdAt = date,
    )

    @Test
    fun `an email's text goes after 90 days while its subject, recipients and attachment names stay`(): Unit = runBlocking {
        val old = messages.insert(email(now - 91.days))
        val recent = messages.insert(email(now - 89.days))
        // Kept without a body, its snippet is still the start of the text.
        val bodiless = messages.insert(email(now - 120.days, body = null))

        assertEquals(2, retention.purge())

        val purged = messages.find(tenantId, old.id)!!
        assertNull(purged.bodyText)
        assertEquals("", purged.snippet)
        assertEquals(now, purged.bodyPurgedAt)
        assertEquals(old.subject, purged.subject)
        assertEquals(old.to, purged.to)
        assertEquals(listOf("ORC-001.pdf"), purged.attachments.map { it.filename })
        assertEquals(old.record, purged.record)
        assertEquals("", messages.find(tenantId, bodiless.id)!!.snippet)

        val kept = messages.find(tenantId, recent.id)!!
        assertEquals(recent.bodyText, kept.bodyText)
        assertEquals(recent.snippet, kept.snippet)
        assertNull(kept.bodyPurgedAt)

        val firstPurge = now
        now += 1.hours
        assertEquals(0, retention.purge())
        assertEquals(firstPurge, messages.find(tenantId, old.id)!!.bodyPurgedAt)

        now += 2.days
        assertEquals(1, retention.purge())
        assertNull(messages.find(tenantId, recent.id)!!.bodyText)
    }

    @Test
    fun `the retention job purges on its tick`(): Unit = runBlocking {
        val old = messages.insert(email(now - 200.days))

        assertTrue(retention.job(lease = null).runOnce())

        assertNull(messages.find(tenantId, old.id)!!.bodyText)
        assertEquals(1, messages.forClient(tenantId, clientId).count { it.bodyPurgedAt != null })
    }
}
