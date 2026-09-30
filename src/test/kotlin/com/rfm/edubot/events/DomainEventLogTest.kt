package com.rfm.edubot.events

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.lineItem
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonPrimitive
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

@Testcontainers
class DomainEventLogTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "domain_events"))
            mongoModule.initialize()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }
    }

    private val log get() = DomainEventLog(mongoModule)

    @Test
    fun `a new client is recorded with whoever made it`() = runBlocking {
        val tenantId = ObjectId()
        val client = withContext(ActorContext(Actor(ActorType.USER, "user-7", "ana@example.pt"))) {
            ClientRepository(mongoModule, tenantId).create("Ana", "+351 911 000 111", email = "ana@example.pt")
        }

        val events = log.timeline(tenantId, SubjectRef.of(SubjectTypes.CLIENT, client.id))

        assertEquals(listOf(DomainEventTypes.CLIENT_CREATED), events.map { it.type })
        assertEquals(ActorType.USER, events.single().actor.type)
        assertEquals("user-7", events.single().actor.id)
        assertEquals("true", events.single().payload["hasEmail"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a quote status change records from and to and stamps when it was sent`() = runBlocking {
        val tenantId = ObjectId()
        val client = ClientRepository(mongoModule, tenantId).create("Rui", "+351 922 000 222")
        val quotes = QuoteRepository(mongoModule, tenantId)
        val quote = quotes.create(client.id, listOf(lineItem("Pintura", unitPriceEur = 100.0)), null, null)

        val sent = quotes.update(quote.id, null, null, null, QuoteStatus.SENT)!!
        quotes.update(quote.id, null, null, null, QuoteStatus.SENT)

        assertNotNull(sent.sentAt)
        val changes = log.timeline(tenantId, SubjectRef.of(SubjectTypes.QUOTE, quote.id)).filter { it.type == DomainEventTypes.QUOTE_STATUS_CHANGED }
        assertEquals(1, changes.size)
        assertEquals("PENDENTE", changes.single().payload["from"]?.jsonPrimitive?.content)
        assertEquals("SENT", changes.single().payload["to"]?.jsonPrimitive?.content)
        // The quote's events also show on its client.
        val onClient = log.timeline(tenantId, SubjectRef.of(SubjectTypes.CLIENT, client.id)).map { it.type }
        assertEquals(true, DomainEventTypes.QUOTE_STATUS_CHANGED in onClient)
    }

    @Test
    fun `paying an invoice twice records one payment`() = runBlocking {
        val tenantId = ObjectId()
        val client = ClientRepository(mongoModule, tenantId).create("Inês", "+351 933 000 333")
        val invoices = InvoiceRepository(mongoModule, tenantId)
        val invoice = invoices.create(client.id, null, listOf(lineItem("Obra", unitPriceEur = 50.0)), LocalDate(2026, 10, 1))

        val paid = invoices.markPaid(invoice.id)!!
        assertEquals(invoices.findById(invoice.id), paid)
        invoices.markPaid(invoice.id)

        val types = log.timeline(tenantId, SubjectRef.of(SubjectTypes.INVOICE, invoice.id)).map { it.type }
        assertEquals(listOf(DomainEventTypes.INVOICE_PAID, DomainEventTypes.INVOICE_CREATED), types)
    }

    @Test
    fun `chat messages are only recorded while an agent listens for them`() = runBlocking {
        val tenantId = ObjectId()
        val conversationId = ObjectId()
        val messages = MessageRepository(mongoModule, tenantId)
        fun message(text: String) = Message(
            tenantId = tenantId, conversationId = conversationId, waId = "351911000444", role = UserRole.USER,
            content = MessageContent.Text(text), createdAt = SystemClock.now(),
        )

        messages.insert(message("olá"))
        DomainEventInterest.set(tenantId, setOf(DomainEventTypes.MESSAGE_RECEIVED))
        messages.insert(message("quero um orçamento"))
        DomainEventInterest.set(tenantId, emptySet())

        val events = log.timeline(tenantId, SubjectRef.of(SubjectTypes.CONVERSATION, conversationId))
        assertEquals(listOf("quero um orçamento"), events.map { it.payload["text"]?.jsonPrimitive?.content })
    }

    @Test
    fun `the dispatcher claims each event once and takes over a stale claim`() = runBlocking {
        val tenantId = ObjectId()
        val events = log
        val appended = events.append(tenantId, DomainEventTypes.CLIENT_UPDATED, SubjectRef(SubjectTypes.CLIENT, ObjectId().toHexString()))!!

        // Other tests leave pending events too; drain until ours comes up.
        var claimed: DomainEvent? = events.claimNext(SystemClock.now() - 5.minutes)
        while (claimed != null && claimed.id != appended.id) {
            events.markDone(claimed.id)
            claimed = events.claimNext(SystemClock.now() - 5.minutes)
        }
        assertEquals(appended.id, claimed?.id)
        assertNull(events.claimNext(SystemClock.now() - 5.minutes)?.takeIf { it.id == appended.id })

        val takenOver = events.claimNext(SystemClock.now() + 1.minutes)
        assertEquals(appended.id, takenOver?.id)
        events.markDone(appended.id)
        assertNull(events.claimNext(SystemClock.now() + 1.minutes)?.takeIf { it.id == appended.id })
    }
}
