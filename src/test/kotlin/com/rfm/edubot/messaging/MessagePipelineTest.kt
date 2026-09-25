package com.rfm.edubot.messaging

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.AiResponse
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.TenantUsageRepository
import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.channel.ChannelCapabilities
import com.rfm.edubot.channel.OutboundClient
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.User
import com.rfm.edubot.conversation.model.UserStatus
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.CrmTools
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PdfGenerator
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.LineItem
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.ratelimit.RateDecision
import com.rfm.edubot.ratelimit.RateLimiter
import com.rfm.edubot.tenant.model.Platform
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * MessagePipeline.handle() is the single path every inbound message from every tenant and
 * every channel goes through (see docs/architecture.md). It previously had zero test coverage.
 * These tests mock every collaborator and assert on the observable side effects (what got sent
 * back to the user, what got persisted, whether the webhook event was marked processed/failed) -
 * not on internal call order.
 */
class MessagePipelineTest {
    private val tenantId = ObjectId()

    private lateinit var users: UserRepository
    private lateinit var conversations: ConversationRepository
    private lateinit var messages: MessageRepository
    private lateinit var rateLimiter: RateLimiter
    private lateinit var aiClient: AiClient
    private lateinit var deduplicationService: DeduplicationService
    private lateinit var crmTools: CrmTools
    private lateinit var clientRepository: ClientRepository
    private lateinit var quoteRepository: QuoteRepository
    private lateinit var invoiceRepository: InvoiceRepository
    private lateinit var pdfGenerator: PdfGenerator
    private lateinit var responder: OutboundClient
    private lateinit var pipeline: MessagePipeline

    private fun user(status: UserStatus = UserStatus.ACTIVE) = User(
        id = ObjectId(),
        tenantId = tenantId,
        waId = "5511999999999",
        status = status,
        createdAt = Clock.System.now(),
        lastSeenAt = Clock.System.now(),
    )

    private fun conversation(userId: ObjectId, autoReplyEnabled: Boolean = true) = Conversation(
        id = ObjectId(),
        tenantId = tenantId,
        userId = userId,
        waId = "5511999999999",
        autoReplyEnabled = autoReplyEnabled,
        lastMessageAt = Clock.System.now(),
        createdAt = Clock.System.now(),
    )

    private fun inbound(text: String = "oi, tudo bem?", eventId: String = "evt-1") = InboundMessage(
        tenantId = tenantId,
        phoneNumberId = "pn-1",
        platform = Platform.WHATSAPP,
        waId = "5511999999999",
        waMessageId = "wamid.1",
        messageText = text,
        timestamp = "0",
        eventId = eventId,
    )

    @BeforeTest
    fun setUp() {
        users = mockk()
        conversations = mockk()
        messages = mockk()
        rateLimiter = mockk()
        aiClient = mockk()
        deduplicationService = mockk()
        crmTools = mockk()
        clientRepository = mockk()
        quoteRepository = mockk()
        invoiceRepository = mockk()
        pdfGenerator = mockk()
        responder = mockk()

        // Defaults every test relies on unless it overrides them.
        coEvery { messages.insert(any()) } answers { firstArg() }
        coEvery { messages.lastNByWaId(any(), any(), any()) } returns emptyList()
        coEvery { conversations.bumpActivity(any(), any()) } returns Unit
        coEvery { deduplicationService.markProcessed(any()) } returns Unit
        coEvery { deduplicationService.markFailed(any()) } returns Unit
        coEvery { responder.sendText(any(), any()) } returns Unit
        every { responder.capabilities } returns ChannelCapabilities(supportsDocuments = false)
        every { crmTools.definitionsFor(any()) } returns emptyList()

        pipeline = MessagePipeline(
            users = users,
            conversations = conversations,
            messages = messages,
            rateLimiter = rateLimiter,
            aiClient = aiClient,
            deduplicationService = deduplicationService,
            crmTools = crmTools,
            bookingTools = null,
            clientRepository = clientRepository,
            quoteRepository = quoteRepository,
            invoiceRepository = invoiceRepository,
            pdfGenerator = pdfGenerator,
        )
    }

    @AfterTest
    fun tearDown() = io.mockk.clearAllMocks()

    @Test
    fun `blocked user is never processed and the event is still marked processed`() = runBlocking {
        val blockedUser = user(status = UserStatus.BLOCKED)
        coEvery { users.findOrCreate(any(), any(), any()) } returns blockedUser

        pipeline.handle(inbound(), responder)

        coVerify(exactly = 0) { conversations.findOrCreate(any(), any(), any()) }
        coVerify(exactly = 0) { aiClient.complete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { responder.sendText(any(), any()) }
        coVerify(exactly = 1) { deduplicationService.markProcessed("evt-1") }
    }

    @Test
    fun `registerOnly with no existing conversation stores the message without replying`() = runBlocking {
        val u = user()
        coEvery { users.findOrCreate(any(), any(), any()) } returns u
        coEvery { conversations.findByWaId(any(), any()) } returns null
        val convo = conversation(u.id)
        coEvery { conversations.findOrCreate(u.id, any(), any()) } returns convo
        coEvery { conversations.bumpActivity(convo.id) } returns Unit

        pipeline.handle(inbound(eventId = "evt-register").copy(registerOnly = true), responder)

        coVerify(exactly = 1) { messages.insert(any()) }
        coVerify(exactly = 0) { aiClient.complete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { responder.sendText(any(), any()) }
        coVerify(exactly = 1) { deduplicationService.markProcessed("evt-register") }
        coVerify(exactly = 0) { deduplicationService.markFailed(any()) }
    }

    @Test
    fun `auto-reply paused conversation stores the inbound message and does not call the AI`() = runBlocking {
        val u = user()
        coEvery { users.findOrCreate(any(), any(), any()) } returns u
        val pausedConvo = conversation(u.id, autoReplyEnabled = false)
        coEvery { conversations.findByWaId(any(), any()) } returns pausedConvo

        pipeline.handle(inbound(), responder)

        coVerify(exactly = 1) { messages.insert(any()) }
        coVerify(exactly = 0) { aiClient.complete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { responder.sendText(any(), any()) }
        coVerify(exactly = 1) { deduplicationService.markProcessed("evt-1") }
        coVerify(exactly = 0) { deduplicationService.markFailed(any()) }
    }

    @Test
    fun `rate limited user gets the rejection message and the AI is never called`() = runBlocking {
        val u = user()
        coEvery { users.findOrCreate(any(), any(), any()) } returns u
        coEvery { conversations.findByWaId(any(), any()) } returns null
        every { rateLimiter.tryAcquire(any()) } returns RateDecision.Reject("Hourly rate limit exceeded. Try again later.")

        pipeline.handle(inbound(), responder)

        coVerify(exactly = 1) { responder.sendText(u.waId, "Hourly rate limit exceeded. Try again later.") }
        coVerify(exactly = 0) { aiClient.complete(any(), any(), any(), any()) }
        coVerify(exactly = 1) { deduplicationService.markProcessed("evt-1") }
        coVerify(exactly = 0) { deduplicationService.markFailed(any()) }
    }

    @Test
    fun `happy path text reply is persisted and sent back, then the event is marked processed`() = runBlocking {
        val u = user()
        coEvery { users.findOrCreate(any(), any(), any()) } returns u
        coEvery { conversations.findByWaId(any(), any()) } returns null
        val convo = conversation(u.id)
        coEvery { conversations.findOrCreate(u.id, any(), any()) } returns convo
        every { rateLimiter.tryAcquire(any()) } returns RateDecision.Accept
        coEvery { aiClient.complete(any(), any(), any(), any()) } returns
            AiResponse.Text(content = "Oi! Tudo bem, e você?", usage = null, responseId = "resp-1")

        pipeline.handle(inbound("oi, tudo bem?"), responder)

        coVerify(exactly = 1) { responder.sendText(u.waId, "Oi! Tudo bem, e você?") }
        coVerify(exactly = 1) { conversations.bumpActivity(convo.id, any()) }
        coVerify(exactly = 1) { deduplicationService.markProcessed("evt-1") }
        coVerify(exactly = 0) { deduplicationService.markFailed(any()) }
    }

    @Test
    fun `an AI failure marks the event failed instead of processed, and nothing is sent`() = runBlocking {
        val u = user()
        coEvery { users.findOrCreate(any(), any(), any()) } returns u
        coEvery { conversations.findByWaId(any(), any()) } returns null
        val convo = conversation(u.id)
        coEvery { conversations.findOrCreate(u.id, any(), any()) } returns convo
        every { rateLimiter.tryAcquire(any()) } returns RateDecision.Accept
        coEvery { aiClient.complete(any(), any(), any(), any()) } throws RuntimeException("OpenRouter unavailable")

        pipeline.handle(inbound("oi"), responder)

        coVerify(exactly = 0) { responder.sendText(any(), any()) }
        coVerify(exactly = 1) { deduplicationService.markFailed("evt-1") }
        coVerify(exactly = 0) { deduplicationService.markProcessed(any()) }
    }

    @Test
    fun `a confirmed CRM tool call creates a quote and the created document is surfaced to the user`() = runBlocking {
        val u = user()
        coEvery { users.findOrCreate(any(), any(), any()) } returns u
        coEvery { conversations.findByWaId(any(), any()) } returns null
        val convo = conversation(u.id)
        coEvery { conversations.findOrCreate(u.id, any(), any()) } returns convo
        every { rateLimiter.tryAcquire(any()) } returns RateDecision.Accept

        val quoteId = ObjectId()
        val clientId = ObjectId()
        val toolCall = ToolCall(id = "call_1", name = "create_quote", arguments = buildJsonObject { })
        val toolUseResponse = AiResponse.ToolUse(
            calls = listOf(toolCall),
            usage = null,
            responseId = "resp-1",
            message = ChatMessage(role = "assistant", content = null),
        )
        val finalResponse = AiResponse.Text(
            content = "Pronto! Orçamento QT-0001 criado.",
            usage = null,
            responseId = "resp-2",
        )
        coEvery { aiClient.complete(any(), any(), any(), any()) } returnsMany listOf(toolUseResponse, finalResponse)

        coEvery { crmTools.execute(toolCall) } returns buildJsonObject {
            put("type", "quote")
            put("id", quoteId.toHexString())
            put("created", "true")
        }

        val quote = Quote(
            id = quoteId,
            tenantId = tenantId,
            number = "QT-0001",
            clientId = clientId,
            items = listOf(LineItem("Serviço", 1.0, "un", 10000, 10000)),
            status = QuoteStatus.PENDENTE,
            totalCents = 10000,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        val client = Client(
            id = clientId,
            tenantId = tenantId,
            number = "CLI-0001",
            name = "Maria",
            phone = "5511988887777",
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        coEvery { quoteRepository.findById(quoteId) } returns quote
        coEvery { clientRepository.findById(clientId) } returns client
        // generateQuote runs unconditionally in sendCreatedDocuments (the PDF is generated and
        // stored regardless of whether this channel can deliver it as a document) - it's not
        // gated by responder.capabilities.supportsDocuments, only the delivery method is.
        every { pdfGenerator.generateQuote(quote, client, any()) } returns byteArrayOf(1, 2, 3)

        pipeline.handle(inbound("Pode gerar o orçamento, confirmo os dados"), responder)

        coVerify(exactly = 1) { crmTools.execute(toolCall) }
        coVerify(exactly = 1) { responder.sendText(u.waId, "Pronto! Orçamento QT-0001 criado.") }
        // supportsDocuments = false, so the created quote is surfaced as text, not a document send.
        coVerify(exactly = 1) { responder.sendText(u.waId, match { it.contains("QT-0001") && it.contains("gerado") }) }
        coVerify(exactly = 1) { deduplicationService.markProcessed("evt-1") }
        coVerify(exactly = 0) { deduplicationService.markFailed(any()) }
    }

    @Test
    fun `a write tool without explicit confirmation is refused and the AI is told to ask first`() = runBlocking {
        val u = user()
        coEvery { users.findOrCreate(any(), any(), any()) } returns u
        coEvery { conversations.findByWaId(any(), any()) } returns null
        val convo = conversation(u.id)
        coEvery { conversations.findOrCreate(u.id, any(), any()) } returns convo
        every { rateLimiter.tryAcquire(any()) } returns RateDecision.Accept

        val toolCall = ToolCall(id = "call_1", name = "create_client", arguments = buildJsonObject { })
        val toolUseResponse = AiResponse.ToolUse(
            calls = listOf(toolCall),
            usage = null,
            responseId = "resp-1",
            message = ChatMessage(role = "assistant", content = null),
        )
        val finalResponse = AiResponse.Text(content = "Confirma a criação do cliente?", usage = null, responseId = "resp-2")
        coEvery { aiClient.complete(any(), any(), any(), any()) } returnsMany listOf(toolUseResponse, finalResponse)

        // Contains CRM keywords ("cliente", "orçamento", so useTools=true) but no confirmation phrase.
        pipeline.handle(inbound("cria um cliente novo pra mim, orçamento depois"), responder)

        // create_client must never actually be executed without an explicit confirmation.
        coVerify(exactly = 0) { crmTools.execute(toolCall) }
        coVerify(exactly = 1) { responder.sendText(u.waId, "Confirma a criação do cliente?") }
        coVerify(exactly = 1) { deduplicationService.markProcessed("evt-1") }
        coVerify(exactly = 0) { deduplicationService.markFailed(any()) }
    }

    @Test
    fun `a tenant over its monthly token budget gets a fixed reply and the LLM is never called`() = runBlocking {
        val u = user()
        coEvery { users.findOrCreate(any(), any(), any()) } returns u
        coEvery { conversations.findByWaId(any(), any()) } returns null
        val convo = conversation(u.id)
        coEvery { conversations.findOrCreate(u.id, any(), any()) } returns convo
        every { rateLimiter.tryAcquire(any()) } returns RateDecision.Accept

        val tenantUsage = mockk<TenantUsageRepository>()
        coEvery { tenantUsage.tokensUsedThisMonth() } returns 2_000_000L

        val cappedPipeline = MessagePipeline(
            users = users,
            conversations = conversations,
            messages = messages,
            rateLimiter = rateLimiter,
            aiClient = aiClient,
            deduplicationService = deduplicationService,
            crmTools = crmTools,
            bookingTools = null,
            clientRepository = clientRepository,
            quoteRepository = quoteRepository,
            invoiceRepository = invoiceRepository,
            pdfGenerator = pdfGenerator,
            tenantUsage = tenantUsage,
            monthlyTokenBudget = 2_000_000L,
        )

        cappedPipeline.handle(inbound("oi"), responder)

        coVerify(exactly = 0) { aiClient.complete(any(), any(), any(), any()) }
        coVerify(exactly = 1) { responder.sendText(u.waId, match { it.contains("limite de uso") }) }
        coVerify(exactly = 1) { deduplicationService.markProcessed("evt-1") }
        coVerify(exactly = 0) { deduplicationService.markFailed(any()) }
    }

    @Test
    fun `a tenant under budget is unaffected and usage is recorded after a successful reply`() = runBlocking {
        val u = user()
        coEvery { users.findOrCreate(any(), any(), any()) } returns u
        coEvery { conversations.findByWaId(any(), any()) } returns null
        val convo = conversation(u.id)
        coEvery { conversations.findOrCreate(u.id, any(), any()) } returns convo
        every { rateLimiter.tryAcquire(any()) } returns RateDecision.Accept
        coEvery { aiClient.complete(any(), any(), any(), any()) } returns
            AiResponse.Text(content = "Oi!", usage = com.rfm.edubot.ai.UsageInfo(prompt_tokens = 100, completion_tokens = 50), responseId = "resp-1")

        val tenantUsage = mockk<TenantUsageRepository>()
        coEvery { tenantUsage.tokensUsedThisMonth() } returns 10L
        coEvery { tenantUsage.recordUsage(any()) } returns Unit

        val cappedPipeline = MessagePipeline(
            users = users,
            conversations = conversations,
            messages = messages,
            rateLimiter = rateLimiter,
            aiClient = aiClient,
            deduplicationService = deduplicationService,
            crmTools = crmTools,
            bookingTools = null,
            clientRepository = clientRepository,
            quoteRepository = quoteRepository,
            invoiceRepository = invoiceRepository,
            pdfGenerator = pdfGenerator,
            tenantUsage = tenantUsage,
            monthlyTokenBudget = 2_000_000L,
        )

        cappedPipeline.handle(inbound("oi"), responder)

        coVerify(exactly = 1) { aiClient.complete(any(), any(), any(), any()) }
        coVerify(exactly = 1) { responder.sendText(u.waId, "Oi!") }
        coVerify(exactly = 1) { tenantUsage.recordUsage(150L) }
        coVerify(exactly = 1) { deduplicationService.markProcessed("evt-1") }
    }
}
