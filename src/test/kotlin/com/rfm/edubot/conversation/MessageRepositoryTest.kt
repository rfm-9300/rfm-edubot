package com.rfm.edubot.conversation

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Platform
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
