package com.rfm.edubot.messaging

import com.rfm.edubot.channel.ChannelCapabilities
import com.rfm.edubot.channel.OutboundClient
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.model.MessageAuthor
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.notifications.NotificationKinds
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.persona.PersonaBehavior
import com.rfm.edubot.persona.PersonaChange
import com.rfm.edubot.persona.PersonaHandoff
import com.rfm.edubot.persona.PersonaPrompt
import com.rfm.edubot.persona.PersonaRepository
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
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One customer's WhatsApp chat with a company's bot, through the real pipeline factory and MongoDB:
 * the persona as the pipeline caches it, a change going live, a handoff to the team and the bot staying
 * quiet until a person resumes it.
 */
class PersonaChatFlowTest {
    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("persona_flow")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() = mongo.shutdown()

        private val NAME = Regex("""Your name is ([^.]+)\.""")
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

    @Test
    fun `a customer chat follows the persona as it changes, hands over to the team, and waits for a person`(): Unit = runBlocking {
        val now = Clock.System.now()
        val tenant = Tenant(
            slug = "flow-${ObjectId().toHexString()}", name = "Clínica Sorriso",
            channels = listOf(ChannelBinding(Platform.WHATSAPP, "pn-flow-${ObjectId().toHexString()}", "token")),
            enabledModules = listOf(DashboardModules.CONVERSATIONS, DashboardModules.PERSONA),
            createdAt = now, updatedAt = now,
        )
        TenantRepository(mongo).create(tenant)
        val personas = PersonaRepository(mongo)
        personas.saveInstructions(tenant.id, "Somos a Clínica Sorriso, em Lisboa.", PersonaChange.MANUAL, "ana@sorriso.pt")
        val handoff = PersonaHandoff(enabled = true, triggers = "o cliente pede para falar com uma pessoa", message = "Um colega já lhe responde por aqui.")
        personas.saveBehavior(tenant.id, PersonaBehavior(botName = "Sofia", handoff = handoff), "ana@sorriso.pt")

        val model = FakeModel {
            when {
                offers(PersonaPrompt.HANDOFF_TOOL) && "pessoa" in lastUser.orEmpty() -> FakeModel.handoff("pediu para falar com uma pessoa")
                else -> FakeModel.text("Olá, sou a ${NAME.find(system[0])?.groupValues?.get(1) ?: "assistente"}.")
            }
        }
        val factory = TenantPipelineFactory(mongo, model.client, DeduplicationService(mongo), HttpClient(MockEngine { respondOk() }), runtime, WebChannelRegistry())
        val responder = RecordingResponder()
        var n = 0
        suspend fun customerSays(text: String) = factory.getOrCreate(tenant).handle(
            InboundMessage(
                tenantId = tenant.id, phoneNumberId = tenant.phoneNumberId, platform = Platform.WHATSAPP, waId = "351910000001",
                waMessageId = "wamid.flow.${++n}.${ObjectId().toHexString()}", profileName = "Ana Ribeiro", messageText = text, timestamp = "0", eventId = "evt-flow-$n-${ObjectId().toHexString()}",
            ),
            responder,
        )

        customerSays("Olá")
        assertEquals("Olá, sou a Sofia.", responder.sent.last())
        assertTrue("Somos a Clínica Sorriso, em Lisboa." in model.last().system[0])

        personas.saveBehavior(tenant.id, PersonaBehavior(botName = "Rita", handoff = handoff), "ana@sorriso.pt")
        customerSays("Ainda aí?")
        assertEquals("Olá, sou a Sofia.", responder.sent.last(), "the pipeline keeps the persona it was built with")
        factory.evict(tenant.id)
        customerSays("E agora?")
        assertEquals("Olá, sou a Rita.", responder.sent.last(), "after the dashboard's evict the change is live")

        val asked = model.requests.size
        customerSays("Quero falar com uma pessoa, por favor")
        assertEquals(asked + 1, model.requests.size)
        assertEquals("Um colega já lhe responde por aqui.", responder.sent.last())

        val conversations = ConversationRepository(mongo, tenant.id)
        val convo = assertNotNull(conversations.findByWaId("351910000001", Platform.WHATSAPP))
        assertFalse(convo.autoReplyEnabled)
        assertEquals(PersonaHandoff.PAUSED_BY, convo.autoReplyPausedBy)
        val last = MessageRepository(mongo, tenant.id).lastByConversationIds(listOf(convo.id))[convo.id]!!
        assertEquals(MessageAuthor.AI, last.author)
        assertTrue(convo.needsTeamReply(last), "a handed-over chat needs a person even though the bot spoke last")

        val notice = NotificationRepository(mongo).listFor(tenant.id, reader = "anyone", isAdmin = false).single()
        assertEquals(NotificationKinds.CONVERSATION_HANDOFF, notice.kind)
        assertEquals("Ana Ribeiro", notice.params["name"])
        assertEquals("pediu para falar com uma pessoa", notice.params["reason"])
        assertEquals(convo.id.toHexString(), notice.subject?.id)
        assertEquals(DashboardModules.CONVERSATIONS, notice.link)

        val sentBefore = responder.sent.size
        customerSays("Está aí alguém?")
        assertEquals(asked + 1, model.requests.size, "the bot stays quiet while the team has the chat")
        assertEquals(sentBefore, responder.sent.size)
        assertTrue(MessageRepository(mongo, tenant.id).lastByConversationIds(listOf(convo.id))[convo.id]!!.let { convo.needsTeamReply(it) })

        conversations.setAutoReplyEnabled(convo.id, true, null)
        customerSays("Olá?")
        assertEquals("Olá, sou a Rita.", responder.sent.last(), "once a person resumes it, the bot answers again")
    }
}
