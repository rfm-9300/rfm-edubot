package com.rfm.edubot.messaging

import com.rfm.edubot.channel.ChannelCapabilities
import com.rfm.edubot.channel.OutboundClient
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.flows.ChatFlowRepository
import com.rfm.edubot.flows.ChatFlows
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.persona.PersonaBehavior
import com.rfm.edubot.persona.PersonaCapabilities
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
 * Two of the Persona page's settings through the real pipeline factory and MongoDB: what the bot can do
 * (a capability turned off takes its tools and notes away) and the AI switched off on WhatsApp.
 */
class BotSettingsPipelineTest {
    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("bot_settings")
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
        slug = "bot-${ObjectId().toHexString()}", name = "Clínica Sorriso",
        channels = listOf(ChannelBinding(Platform.WHATSAPP, "pn-bot-${ObjectId().toHexString()}", "token")),
        enabledModules = listOf(DashboardModules.CONVERSATIONS, DashboardModules.PERSONA, DashboardModules.CATALOG, DashboardModules.CLIENTS, DashboardModules.QUOTES),
        createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
    )
    private val factory = TenantPipelineFactory(mongo, model.client, DeduplicationService(mongo), HttpClient(MockEngine { respondOk() }), runtime, WebChannelRegistry())
    private var n = 0

    private suspend fun customer(waId: String, text: String) = factory.getOrCreate(tenant).handle(
        InboundMessage(
            tenantId = tenant.id, phoneNumberId = tenant.phoneNumberId, platform = Platform.WHATSAPP, waId = waId,
            waMessageId = "wamid.bot.${++n}.${ObjectId().toHexString()}", profileName = "Ana", messageText = text, timestamp = "0",
            eventId = "evt-bot-$n-${ObjectId().toHexString()}",
        ),
        responder,
    )

    @Test
    fun `turning clients, quotes and invoices off leaves the bot the catalog only, from the next message on`(): Unit = runBlocking {
        TenantRepository(mongo).create(tenant)

        customer("351920000001", "Quanto custa um orçamento de limpeza?")
        val before = model.requests.last()
        assertTrue(before.offers("list_standard_items") && before.offers("create_quote"), "everything is on by default")

        PersonaRepository(mongo).saveBehavior(tenant.id, PersonaBehavior(capabilities = PersonaCapabilities(records = false)), "ana@sorriso.pt")
        factory.evict(tenant.id) // what saving on the Persona page does
        customer("351920000001", "Quanto custa um orçamento de limpeza?")
        val after = model.requests.last()
        assertTrue(after.offers("list_standard_items"))
        listOf("search_clients", "create_client", "create_quote", "list_quotes").forEach { assertFalse(after.offers(it), "$it is still offered") }
        // The tool list is the gate (the shared CRM rules may still name other tools: SystemPromptsTest).
        val toolList = after.system.joinToString("\n").substringAfter("Ferramentas disponiveis:").substringBefore("Regras:")
        assertTrue("list_standard_items" in toolList && "create_quote" !in toolList && "search_clients" !in toolList, toolList)
        assertEquals(2, responder.sent.size)
    }

    @Test
    fun `with the AI off on WhatsApp a message waits for the team, without pausing the chat`(): Unit = runBlocking {
        TenantRepository(mongo).create(tenant)
        ChatFlowRepository(mongo, tenant.id).save(ChatFlows(teamOnlyChannels = setOf(Platform.WHATSAPP)), "ana@sorriso.pt")

        customer("351920000002", "Olá")
        assertTrue(model.requests.isEmpty(), "the model is never asked")
        assertTrue(responder.sent.isEmpty())
        val convo = assertNotNull(ConversationRepository(mongo, tenant.id).findByWaId("351920000002", Platform.WHATSAPP))
        assertTrue(convo.autoReplyEnabled, "nothing is paused: the switch is the company's, not the chat's")
        assertEquals(1, MessageRepository(mongo, tenant.id).lastNByWaId("351920000002", 5, Platform.WHATSAPP).size)
    }
}
