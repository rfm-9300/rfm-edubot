package com.rfm.edubot.conversation

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageAuthor
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.MessageStatus
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Platform
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.bson.types.ObjectId
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class MessageRepositoryTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "messages"))
            mongoModule.initialize()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }
    }

    private fun customerMessage(tenantId: ObjectId, conversationId: ObjectId, waMessageId: String) = Message(
        tenantId = tenantId,
        conversationId = conversationId,
        channel = Platform.WHATSAPP,
        waId = "351900000001",
        role = UserRole.USER,
        waMessageId = waMessageId,
        content = MessageContent.Text("Preciso de um orçamento"),
        createdAt = Clock.System.now(),
    )

    @Test
    fun `storing the same inbound message twice keeps one copy and reports the repeat`() = runBlocking {
        val tenantId = ObjectId()
        val conversationId = ObjectId()
        val repository = MessageRepository(mongoModule, tenantId)
        val message = customerMessage(tenantId, conversationId, "wamid.replayed")

        assertTrue(repository.insertIfAbsent(message))
        assertFalse(repository.insertIfAbsent(message.copy(id = ObjectId())))
        assertEquals(1, repository.threadByConversation(conversationId).size)
    }

    @Test
    fun `the same waMessageId in another tenant is a different message`() = runBlocking {
        val conversationId = ObjectId()
        val first = ObjectId()
        val second = ObjectId()

        assertTrue(MessageRepository(mongoModule, first).insertIfAbsent(customerMessage(first, conversationId, "wamid.shared")))
        assertTrue(MessageRepository(mongoModule, second).insertIfAbsent(customerMessage(second, conversationId, "wamid.shared")))
    }

    private fun agentMessage(tenantId: ObjectId, conversationId: ObjectId, waMessageId: String?, createdAt: Instant = Clock.System.now()) = Message(
        tenantId = tenantId,
        conversationId = conversationId,
        channel = Platform.WHATSAPP,
        waId = "351900000001",
        role = UserRole.ASSISTANT,
        waMessageId = waMessageId,
        content = MessageContent.Text("Bom dia!"),
        status = MessageStatus.SENT,
        createdAt = createdAt,
        author = MessageAuthor.AGENT,
        agentUserId = "user-1",
        agentName = "ana@example.com",
    )

    @Test
    fun `delivery statuses only move forward, whatever order the webhooks arrive in`() = runBlocking {
        val tenantId = ObjectId()
        val repository = MessageRepository(mongoModule, tenantId)
        val message = repository.insert(agentMessage(tenantId, ObjectId(), "wamid.forward"))

        assertEquals(MessageStatus.READ, repository.applyDeliveryStatus("wamid.forward", MessageStatus.READ)?.status)
        assertNull(repository.applyDeliveryStatus("wamid.forward", MessageStatus.DELIVERED))
        assertEquals(MessageStatus.READ, repository.findById(message.id)?.status)
        assertNull(MessageRepository(mongoModule, ObjectId()).applyDeliveryStatus("wamid.forward", MessageStatus.FAILED))
    }

    @Test
    fun `a failed delivery keeps Meta's error and ignores later statuses`() = runBlocking {
        val tenantId = ObjectId()
        val repository = MessageRepository(mongoModule, tenantId)
        val message = repository.insert(agentMessage(tenantId, ObjectId(), "wamid.failed"))

        val failed = repository.applyDeliveryStatus("wamid.failed", MessageStatus.FAILED, 131047, "More than 24 hours have passed")
        assertNull(repository.applyDeliveryStatus("wamid.failed", MessageStatus.READ))

        assertEquals(MessageStatus.FAILED, failed?.status)
        val stored = repository.findById(message.id)!!
        assertEquals(MessageStatus.FAILED, stored.status)
        assertEquals(131047, stored.errorCode)
        assertEquals("More than 24 hours have passed", stored.errorText)

        val resent = repository.markResent(message.id, "wamid.resent")!!
        assertEquals(MessageStatus.SENT, resent.status)
        assertEquals("wamid.resent", resent.waMessageId)
        assertNull(resent.errorCode)
    }

    @Test
    fun `a reply whose send failed turns FAILED with Meta's error and shows up in the inbox updates`() = runBlocking {
        val tenantId = ObjectId()
        val conversationId = ObjectId()
        val repository = MessageRepository(mongoModule, tenantId)
        val reply = repository.insert(
            agentMessage(tenantId, conversationId, null, createdAt = Clock.System.now() - 1.hours)
                .copy(status = MessageStatus.DELIVERED, author = MessageAuthor.AI, agentUserId = null, agentName = null),
        )
        val cursor = Clock.System.now() - 1.seconds

        val failed = repository.markSendFailed(reply.id, 131037, "WhatsApp provided number needs display name approval")!!

        assertEquals(MessageStatus.FAILED, failed.status)
        assertEquals(131037, failed.errorCode)
        assertEquals("WhatsApp provided number needs display name approval", failed.errorText)
        assertEquals(listOf(reply.id), repository.changedSince(conversationId, cursor).map { it.id })
        assertNull(MessageRepository(mongoModule, ObjectId()).markSendFailed(reply.id, null, null))
    }

    @Test
    fun `changes since a cursor include new messages and status updates`() = runBlocking {
        val tenantId = ObjectId()
        val conversationId = ObjectId()
        val repository = MessageRepository(mongoModule, tenantId)
        val old = repository.insert(agentMessage(tenantId, conversationId, "wamid.old", createdAt = Clock.System.now() - 1.hours))
        val cursor = Clock.System.now() - 1.seconds
        val fresh = repository.insert(customerMessage(tenantId, conversationId, "wamid.new"))

        assertEquals(listOf(fresh.id), repository.changedSince(conversationId, cursor).map { it.id })

        repository.applyDeliveryStatus("wamid.old", MessageStatus.DELIVERED)
        assertEquals(setOf(old.id, fresh.id), repository.changedSince(conversationId, cursor).map { it.id }.toSet())
    }

    @Test
    fun `a long thread shows its newest messages, oldest first`() = runBlocking {
        val tenantId = ObjectId()
        val conversationId = ObjectId()
        val repository = MessageRepository(mongoModule, tenantId)
        val start = Clock.System.now() - 1.hours
        val ids = (0 until 5).map { i -> repository.insert(agentMessage(tenantId, conversationId, null, createdAt = start + i.seconds)).id }

        assertEquals(ids.takeLast(3), repository.threadByConversation(conversationId, limit = 3).map { it.id })
    }

    @Test
    fun `template messages and their author survive a round trip`() = runBlocking {
        val tenantId = ObjectId()
        val repository = MessageRepository(mongoModule, tenantId)
        val message = repository.insert(
            agentMessage(tenantId, ObjectId(), "wamid.template").copy(content = MessageContent.Template("hello_world", "en_US", "Hello World")),
        )

        val stored = repository.findById(message.id)!!
        assertEquals(MessageContent.Template("hello_world", "en_US", "Hello World"), stored.content)
        assertEquals(MessageAuthor.AGENT, stored.author)
        assertEquals("ana@example.com", stored.agentName)
    }

    @Test
    fun `conversations count unread customer messages and remember who paused the AI`() = runBlocking {
        val tenantId = ObjectId()
        val conversations = ConversationRepository(mongoModule, tenantId)
        val conversation = conversations.findOrCreate(ObjectId(), "351900000002")

        conversations.recordInbound(conversation.id)
        conversations.recordInbound(conversation.id)
        val unread = conversations.findById(conversation.id)!!
        assertEquals(2, unread.unreadCount)
        assertNotNull(unread.lastInboundAt)
        assertEquals(0, conversations.markRead(conversation.id)!!.unreadCount)

        val paused = conversations.setAutoReplyEnabled(conversation.id, false, "ana@example.com")!!
        assertFalse(paused.autoReplyEnabled)
        assertEquals("ana@example.com", paused.autoReplyPausedBy)
        assertNotNull(paused.autoReplyPausedAt)
        val resumed = conversations.setAutoReplyEnabled(conversation.id, true)!!
        assertTrue(resumed.autoReplyEnabled)
        assertNull(resumed.autoReplyPausedBy)
    }
}
