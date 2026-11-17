package com.rfm.edubot.messaging

import com.rfm.edubot.channel.ChannelCapabilities
import com.rfm.edubot.channel.OutboundClient
import com.rfm.edubot.channel.QuickReply
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.conversation.model.MessageAuthor
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.flows.ActionKind
import com.rfm.edubot.flows.AfterMisses
import com.rfm.edubot.flows.ChatFlow
import com.rfm.edubot.flows.ChatFlowRepository
import com.rfm.edubot.flows.ChatFlows
import com.rfm.edubot.flows.Expect
import com.rfm.edubot.flows.FlowButton
import com.rfm.edubot.flows.FlowSessionRepository
import com.rfm.edubot.flows.FlowStep
import com.rfm.edubot.flows.StepKind
import com.rfm.edubot.notifications.NotificationKinds
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A company's chat flows through the real pipeline factory and MongoDB: options go out as taps or numbered lines by
 * channel, a customer's place is kept between messages, answers become a client and a team notice, a hand-over pauses
 * the chat, and the AI is only asked when the flow says so.
 */
class ChatbotPipelineTest {
    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("chatbot_pipeline")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() = mongo.shutdown()
    }

    /** Records what a channel was asked to send: plain text, or text with taps. */
    private class Responder(override val capabilities: ChannelCapabilities) : OutboundClient {
        data class Sent(val text: String, val replies: List<QuickReply> = emptyList(), val listLabel: String? = null)

        val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
        override suspend fun sendText(to: String, text: String) { sent += Sent(text) }
        override suspend fun sendDocument(to: String, bytes: ByteArray, filename: String, mimeType: String) = Unit
        override suspend fun sendQuickReplies(to: String, text: String, replies: List<QuickReply>, listLabel: String?) { sent += Sent(text, replies, listLabel) }
    }

    /** A channel that refuses everything, as WhatsApp does for a bad token or a closed 24-hour window. */
    private class Refusing : OutboundClient {
        override val capabilities = ChannelCapabilities.WHATSAPP
        override suspend fun sendText(to: String, text: String) = throw com.rfm.edubot.channel.OutboundDeliveryException("refused")
        override suspend fun sendDocument(to: String, bytes: ByteArray, filename: String, mimeType: String) = Unit
        override suspend fun sendQuickReplies(to: String, text: String, replies: List<QuickReply>, listLabel: String?) = throw com.rfm.edubot.channel.OutboundDeliveryException("refused")
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

    private val model = FakeModel { FakeModel.text("Resposta da IA.") }
    private val whatsapp = Responder(ChannelCapabilities.WHATSAPP)
    private val web = Responder(ChannelCapabilities.WEB)
    private val refusing: OutboundClient = Refusing()
    private val tenant = Tenant(
        slug = "bot-${ObjectId().toHexString()}", name = "Clínica Sorriso",
        channels = listOf(ChannelBinding(Platform.WHATSAPP, "pn-bot-${ObjectId().toHexString()}", "token")),
        enabledModules = listOf(DashboardModules.CONVERSATIONS, DashboardModules.PERSONA, DashboardModules.CLIENTS),
        createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
    )
    private val factory = TenantPipelineFactory(mongo, model.client, DeduplicationService(mongo), HttpClient(MockEngine { respondOk() }), runtime, WebChannelRegistry())
    private var n = 0

    private suspend fun customer(waId: String, text: String, payload: String? = null, platform: Platform = Platform.WHATSAPP, responder: OutboundClient = whatsapp) =
        factory.getOrCreate(tenant).handle(
            InboundMessage(
                tenantId = tenant.id, phoneNumberId = tenant.phoneNumber(platform), platform = platform, waId = waId,
                waMessageId = "wamid.bot.${++n}.${ObjectId().toHexString()}", profileName = "Ana", messageText = text, timestamp = "0",
                eventId = "evt-bot-$n-${ObjectId().toHexString()}", quickReplyPayload = payload,
            ),
            responder,
        )

    private fun Tenant.phoneNumber(platform: Platform) = if (platform == Platform.WHATSAPP) phoneNumberId else "web"

    private fun step(id: String, text: String = "", vararg buttons: FlowButton, build: (FlowStep) -> FlowStep = { it }) = build(FlowStep(id, text, buttons.toList()))

    private fun flow(vararg steps: FlowStep, aiReads: Boolean = false, afterMisses: AfterMisses = AfterMisses.HAND_OVER, keywords: List<String> = listOf("olá")) = ChatFlow(
        id = "marcacoes", name = "Marcações", channels = setOf(Platform.WHATSAPP, Platform.WEB), keywords = keywords,
        steps = steps.toList(), aiReads = aiReads, afterMisses = afterMisses,
    )

    private suspend fun install(vararg flows: ChatFlow, teamOnly: Set<Platform> = emptySet()) {
        TenantRepository(mongo).create(tenant)
        ChatFlowRepository(mongo, tenant.id).save(ChatFlows(flows.toList(), teamOnly), "ana@sorriso.pt")
    }

    private val menu = flow(
        step("menu", "O que deseja?", FlowButton("Unhas", "unhas"), FlowButton("Pestanas", "pestanas")),
        step("unhas", "Unhas: 20 €."),
        step("pestanas", "Pestanas: 30 €."),
    )

    private suspend fun conversation(waId: String, platform: Platform = Platform.WHATSAPP) = assertNotNull(ConversationRepository(mongo, tenant.id).findByWaId(waId, platform))
    private suspend fun session(waId: String) = FlowSessionRepository(mongo, tenant.id).find(conversation(waId).id)

    @Test
    fun `a keyword gets its options as WhatsApp buttons, stored as the flow's message, and the AI is never asked`(): Unit = runBlocking {
        install(menu)
        customer("351910000101", "Olá!")

        val sent = whatsapp.sent.single()
        assertEquals("O que deseja?", sent.text)
        assertEquals(listOf("Unhas" to "flow:marcacoes:unhas:0", "Pestanas" to "flow:marcacoes:pestanas:1"), sent.replies.map { it.title to it.payload })
        assertEquals(0, model.requests.size)
        val stored = MessageRepository(mongo, tenant.id).lastByConversationIds(listOf(conversation("351910000101").id)).values.single()
        assertEquals(MessageAuthor.AUTOMATION, stored.author)
        assertEquals("Marcações", stored.agentName)
        assertEquals("flow:marcacoes:menu", stored.origin)
        assertEquals("menu", session("351910000101")?.stepId, "the customer's place is kept for their next message")
    }

    @Test
    fun `a typed number, a tap and a typed title all choose an option, and the flow ends there`(): Unit = runBlocking {
        install(menu)
        for ((waId, reply, payload) in listOf(Triple("351910000102", "2", null), Triple("351910000103", "Pestanas", null), Triple("351910000104", "Pestanas", "flow:marcacoes:pestanas:1"))) {
            customer(waId, "Olá")
            customer(waId, reply, payload)
            assertEquals("Pestanas: 30 €.", whatsapp.sent.last().text, "$reply / $payload")
            assertNull(session(waId), "$reply: the flow is over")
        }
        assertEquals(0, model.requests.size)
    }

    @Test
    fun `on the website, with no taps, the options are numbered lines and typing a number works`(): Unit = runBlocking {
        install(menu)
        customer("visitor-1", "olá", platform = Platform.WEB, responder = web)

        assertEquals("O que deseja?\n\n1. Unhas\n2. Pestanas", web.sent.single().text)
        assertEquals(emptyList(), web.sent.single().replies)
        customer("visitor-1", "1", platform = Platform.WEB, responder = web)
        assertEquals("Unhas: 20 €.", web.sent.last().text)
    }

    @Test
    fun `a team-only channel still answers a customer who is in a flow, and leaves everything else to the team`(): Unit = runBlocking {
        install(menu, teamOnly = setOf(Platform.WHATSAPP))
        customer("351910000105", "Preciso de ajuda")
        assertTrue(whatsapp.sent.isEmpty(), "no flow starts and the AI is off: it waits in the inbox")

        customer("351910000106", "olá")
        customer("351910000106", "1")
        assertEquals(listOf("O que deseja?", "Unhas: 20 €."), whatsapp.sent.map { it.text })
        assertEquals(0, model.requests.size)
    }

    @Test
    fun `a paused chat gets no flow, whatever the customer types`(): Unit = runBlocking {
        install(menu)
        customer("351910000107", "olá")
        ConversationRepository(mongo, tenant.id).setAutoReplyEnabled(conversation("351910000107").id, false, "ana@sorriso.pt")
        customer("351910000107", "1")
        assertEquals(1, whatsapp.sent.size)
    }

    @Test
    fun `messages a flow sends in one turn are stored a moment apart, so the inbox keeps their order`(): Unit = runBlocking {
        install(flow(step("one", "Um.") { it.copy(next = "two") }, step("two", "Dois.") { it.copy(next = "three") }, step("three", "Três.")))
        customer("351910000120", "olá")

        assertEquals(listOf("Um.", "Dois.", "Três."), whatsapp.sent.map { it.text })
        val stored = MessageRepository(mongo, tenant.id).lastN(conversation("351910000120").id, 10).filter { it.author == MessageAuthor.AUTOMATION }
        assertEquals(3, stored.map { it.createdAt }.toSet().size, "no two share a moment")
        assertEquals(listOf("Um.", "Dois.", "Três."), stored.sortedBy { it.createdAt }.map { (it.content as MessageContent.Text).body })
    }

    @Test
    fun `questions create the client from the answers and tell the team, then the flow says thanks`(): Unit = runBlocking {
        install(
            flow(
                step("name", "Como se chama?") { it.copy(kind = StepKind.ASK, key = "name", expect = Expect.NAME, next = "client") },
                step("client") { it.copy(kind = StepKind.ACTION, action = ActionKind.CREATE_CLIENT, next = "tell", failNext = "sorry") },
                step("tell") { it.copy(kind = StepKind.ACTION, action = ActionKind.NOTIFY_TEAM, next = "thanks") },
                step("thanks", "Obrigada, {name}!"),
                step("sorry", "Não consegui guardar os seus dados."),
            ),
        )
        customer("351910000108", "olá")
        customer("351910000108", "Sou a Joana Reis")

        assertEquals(listOf("Como se chama?", "Obrigada, Joana Reis!"), whatsapp.sent.map { it.text })
        val client = ClientRepository(mongo, tenant.id).findAllByPhone("351910000108").single()
        assertEquals("Joana Reis", client.name)
        val notice = NotificationRepository(mongo).listFor(tenant.id, reader = "anyone", isAdmin = true).single { it.kind == NotificationKinds.CHATBOT_ANSWERS }
        assertEquals("Joana Reis", notice.params["name"])
        assertEquals("Marcações", notice.params["flow"])
        assertEquals("name: Joana Reis", notice.params["answers"])
        assertNull(session("351910000108"))

        // The same customer again is not duplicated.
        customer("351910000108", "olá")
        customer("351910000108", "Joana")
        assertEquals(1, ClientRepository(mongo, tenant.id).findAllByPhone("351910000108").size)
    }

    @Test
    fun `a hand-over step pauses the chat the way the AI's does, tells the team and sends its words`(): Unit = runBlocking {
        install(flow(step("person", "Uma pessoa já lhe responde.") { it.copy(kind = StepKind.HANDOFF) }))
        customer("351910000109", "olá")

        assertEquals(listOf("Uma pessoa já lhe responde."), whatsapp.sent.map { it.text })
        val convo = conversation("351910000109")
        assertFalse(convo.autoReplyEnabled)
        assertEquals(Conversation.BOT_HANDOFF, convo.autoReplyPausedBy)
        val notice = NotificationRepository(mongo).listFor(tenant.id, reader = "anyone", isAdmin = false).single { it.kind == NotificationKinds.CONVERSATION_HANDOFF }
        assertEquals("“Marcações”", notice.params["reason"])
        assertNull(session("351910000109"))
        customer("351910000109", "olá outra vez")
        assertEquals(1, whatsapp.sent.size, "paused: the flow stays quiet until a person resumes the chat")
    }

    @Test
    fun `when a flow gives up on a customer it can leave them to the AI`(): Unit = runBlocking {
        install(menu.copy(afterMisses = AfterMisses.LET_AI))
        customer("351910000110", "olá")
        customer("351910000110", "hmm")
        assertEquals(0, model.requests.size)
        customer("351910000110", "hmm, só queria saber a morada")

        assertEquals(1, model.requests.size, "the second miss hands the message to the AI")
        assertEquals("Resposta da IA.", whatsapp.sent.last().text)
        assertNull(session("351910000110"))
    }

    @Test
    fun `an unreadable reply to a menu made before retries goes straight to the AI, or waits for the team where the AI is off`(): Unit = runBlocking {
        install(menu.copy(retries = 0, afterMisses = AfterMisses.LET_AI))
        customer("351910000113", "olá")
        customer("351910000113", "Têm vaga no sábado?")
        assertEquals(1, model.requests.size, "the AI answers at once, as it did before flows kept a customer's place")
        assertEquals("Resposta da IA.", whatsapp.sent.last().text)
        assertNull(session("351910000113"))
    }

    @Test
    fun `on a team-only channel a flow that gives up leaves the message for the team, with nothing paused`(): Unit = runBlocking {
        install(menu.copy(retries = 0, afterMisses = AfterMisses.LET_AI), teamOnly = setOf(Platform.WHATSAPP))
        customer("351910000114", "olá")
        val sentBefore = whatsapp.sent.size
        customer("351910000114", "Têm vaga no sábado?")
        assertEquals(sentBefore, whatsapp.sent.size, "no reply")
        assertEquals(0, model.requests.size, "and no AI")
        assertTrue(conversation("351910000114").autoReplyEnabled, "the chat isn't paused: it simply waits in the inbox")
        assertNull(session("351910000114"))
    }

    @Test
    fun `when the flow lets the AI read an answer, the model only says what it means, and its tokens are counted`(): Unit = runBlocking {
        model.answer = { FakeModel.text("""{"decision":"ACCEPT","option":2,"value":null}""", prompt = 80, completion = 10) }
        install(menu.copy(aiReads = true))
        customer("351910000111", "olá")
        customer("351910000111", "tipo as pestas")

        assertEquals("Pestanas: 30 €.", whatsapp.sent.last().text, "what the customer sees is the flow's own message, never the model's")
        assertEquals(1, model.requests.size)
        assertTrue("tipo as pestas" in model.last().lastUser!!)
        assertTrue(model.last().tools.isEmpty())
        val used = com.rfm.edubot.ai.TenantUsageRepository(mongo, tenant.id).tokensBySourceThisMonth()
        assertEquals(90L, used["chatbot"])
    }

    @Test
    fun `a channel that refuses the message still keeps the customer's place and a hand-over`(): Unit = runBlocking {
        install(
            flow(
                step("menu", "O que deseja?", FlowButton("Unhas", "unhas"), FlowButton("Pessoa", "person")),
                step("unhas", "Unhas: 20 €."),
                step("person", "Já vamos.") { it.copy(kind = StepKind.HANDOFF) },
            ),
        )
        customer("351910000112", "olá", responder = refusing)
        assertEquals("menu", session("351910000112")?.stepId, "the options went unsent but the customer is still at them")
        val stored = MessageRepository(mongo, tenant.id).lastByConversationIds(listOf(conversation("351910000112").id)).values.single()
        assertEquals("flow:marcacoes:menu", stored.origin)

        customer("351910000112", "2", responder = refusing)
        val convo = conversation("351910000112")
        assertFalse(convo.autoReplyEnabled, "the hand-over paused the chat even though its message wasn't delivered")
        assertEquals(Conversation.BOT_HANDOFF, convo.autoReplyPausedBy)
        assertNull(session("351910000112"))
    }
}
