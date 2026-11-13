package com.rfm.edubot.messaging

import com.rfm.edubot.channel.ChannelCapabilities
import com.rfm.edubot.channel.OutboundClient
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.flows.ChatFlowRepository
import com.rfm.edubot.flows.ChatFlows
import com.rfm.edubot.flows.StartGate
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.TenantPipelineFactory
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.FakeModel
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.web.WebChannelRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A company's start gate through the real pipeline factory and MongoDB: a new chat whose first message holds
 * no start word is kept for the team and paused from the start, a chat that opens with one runs as usual,
 * and chats that already existed are left alone.
 */
class StartGatePipelineTest {
    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("start_gate")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() = mongo.shutdown()
    }

    private class RecordingResponder : OutboundClient {
        val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override val capabilities = ChannelCapabilities()
        override suspend fun sendText(to: String, text: String) { sent += text }
        override suspend fun sendDocument(to: String, bytes: ByteArray, filename: String, mimeType: String) = Unit
    }

    private val runtime = RuntimeConfig(
        AppConfig(
            port = 8080,
            whatsapp = AppConfig.WhatsAppConfig(verifyToken = "v", appSecret = "s", phoneNumberId = "1", accessToken = "t"),
            instagram = AppConfig.InstagramConfig(appId = "ig", appSecret = "s", redirectUri = "https://example.com/cb"),
            openrouter = AppConfig.OpenRouterConfig(apiKey = "k", primaryModel = "a", fallbackModel = "b", maxTokens = 512),
            mongo = AppConfig.MongoConfig(uri = "unused", database = "unused"),
            rateLimit = AppConfig.RateLimitConfig(),
            admin = AppConfig.AdminConfig(jwtSecret = "s"),
            pdfStoragePath = "/tmp/pdfs",
        ),
    )

    private val model = FakeModel { FakeModel.text("Olá! Em que posso ajudar?") }
    private val responder = RecordingResponder()
    private val tenant = Tenant(
        slug = "gate-${ObjectId().toHexString()}", name = "Clínica Sorriso",
        channels = listOf(ChannelBinding(Platform.WHATSAPP, "pn-gate-${ObjectId().toHexString()}", "token")),
        enabledModules = listOf(DashboardModules.CONVERSATIONS, DashboardModules.PERSONA),
        createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
    )
    private val factory = TenantPipelineFactory(mongo, model.client, DeduplicationService(mongo), HttpClient(MockEngine { respondOk() }), runtime, WebChannelRegistry())
    private var n = 0

    private suspend fun customer(waId: String, text: String, media: InboundMedia? = null) = factory.getOrCreate(tenant).handle(
        InboundMessage(
            tenantId = tenant.id, phoneNumberId = tenant.phoneNumberId, platform = Platform.WHATSAPP, waId = waId,
            waMessageId = "wamid.gate.${++n}.${ObjectId().toHexString()}", profileName = "Ana", messageText = text, timestamp = "0",
            eventId = "evt-gate-$n-${ObjectId().toHexString()}", media = media,
        ),
        responder,
    )

    private suspend fun gate(vararg words: String, enabled: Boolean = true) {
        ChatFlowRepository(mongo, tenant.id).save(ChatFlows(startGates = mapOf(Platform.WHATSAPP to StartGate(enabled, words.toList()))), "ana@sorriso.pt")
    }

    private suspend fun conversation(waId: String) = assertNotNull(ConversationRepository(mongo, tenant.id).findByWaId(waId, Platform.WHATSAPP))

    private suspend fun handoffEvents() = mongo.database.getCollection<Document>("domain_events")
        .find(Document("tenantId", tenant.id).append("type", DomainEventTypes.CONVERSATION_HANDOFF)).toList()

    @Test
    fun `a new chat without a start word is kept for the team and the bot stays quiet until a person resumes it`(): Unit = runBlocking {
        TenantRepository(mongo).create(tenant)
        gate("Orçamento", "Quero marcar")

        customer("351910000001", "Olá, boa tarde")
        assertEquals(0, model.requests.size, "the model is never asked")
        assertTrue(responder.sent.isEmpty())

        val convo = conversation("351910000001")
        assertFalse(convo.autoReplyEnabled)
        assertEquals(Conversation.START_GATE, convo.autoReplyPausedBy)
        assertNotNull(convo.autoReplyPausedAt)
        assertEquals(1, convo.unreadCount)
        val messages = MessageRepository(mongo, tenant.id)
        val last = messages.lastByConversationIds(listOf(convo.id))[convo.id]!!
        assertTrue(convo.needsTeamReply(last), "the customer's message waits in the inbox for the team")
        assertTrue(handoffEvents().isEmpty(), "nothing was handed over, so no handoff event")
        assertTrue(NotificationRepository(mongo).listFor(tenant.id, reader = "anyone", isAdmin = false).isEmpty())

        customer("351910000001", "Quero marcar uma consulta")
        assertEquals(0, model.requests.size, "a start word later in a gated chat doesn't wake the bot")
        assertTrue(responder.sent.isEmpty())
        assertEquals(2, conversation("351910000001").unreadCount)

        ConversationRepository(mongo, tenant.id).setAutoReplyEnabled(convo.id, true, null)
        customer("351910000001", "Alguém aí?")
        assertEquals(listOf("Olá! Em que posso ajudar?"), responder.sent, "once a person resumes it, the bot answers")
    }

    @Test
    fun `a chat that opens with a start word runs as usual, and the gate never asks again`(): Unit = runBlocking {
        TenantRepository(mongo).create(tenant)
        gate("Orçamento")

        customer("351910000002", "Queria um ORCAMENTO, por favor!")
        assertEquals(1, model.requests.size)
        assertEquals(1, responder.sent.size)
        assertTrue(conversation("351910000002").autoReplyEnabled)

        customer("351910000002", "Obrigada")
        assertEquals(2, model.requests.size, "later messages need no start word")
        assertEquals(2, responder.sent.size)
    }

    @Test
    fun `chats that existed before the gate, and chats on a gate that is off, are left alone`(): Unit = runBlocking {
        TenantRepository(mongo).create(tenant)

        customer("351910000003", "Olá")
        assertEquals(1, responder.sent.size)

        gate("Orçamento")
        customer("351910000003", "Ainda aí?")
        assertEquals(2, responder.sent.size, "an existing chat isn't gated")

        gate("Orçamento", enabled = false)
        customer("351910000004", "Olá")
        assertEquals(3, responder.sent.size, "a gate that is switched off lets every new chat in")
        assertTrue(conversation("351910000004").autoReplyEnabled)
    }

    @Test
    fun `a photo that opens a chat is gated by its caption, so a second message can't slip past`(): Unit = runBlocking {
        TenantRepository(mongo).create(tenant)
        gate("Orçamento")

        customer("351910000005", "", media = InboundMedia("image", "media-1"))
        val convo = conversation("351910000005")
        assertFalse(convo.autoReplyEnabled)
        assertEquals(Conversation.START_GATE, convo.autoReplyPausedBy)

        customer("351910000005", "Orçamento, por favor")
        assertEquals(0, model.requests.size)
        assertTrue(responder.sent.isEmpty())

        customer("351910000006", "Quanto custa?", media = InboundMedia("image", "media-2"))
        assertFalse(conversation("351910000006").autoReplyEnabled)

        customer("351910000007", "Orçamento", media = InboundMedia("image", "media-3"))
        assertTrue(conversation("351910000007").autoReplyEnabled, "a caption with a start word opens the chat; the photo itself gets no automatic reply either way")
        assertNull(conversation("351910000007").autoReplyPausedBy)
    }

    @Test
    fun `a gate with no word to look for is skipped, so a broken setting never silences the channel`(): Unit = runBlocking {
        TenantRepository(mongo).create(tenant)
        gate("?!", " ")

        customer("351910000008", "Olá")

        assertEquals(1, responder.sent.size)
        assertTrue(conversation("351910000008").autoReplyEnabled)
    }
}
