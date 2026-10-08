package com.rfm.edubot.messaging

import com.rfm.edubot.ai.SystemPrompts
import com.rfm.edubot.channel.ChannelCapabilities
import com.rfm.edubot.channel.OutboundClient
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageAuthor
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.MessageStatus
import com.rfm.edubot.conversation.model.User
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.CrmTools
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PdfGenerator
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persona.PersonaBehavior
import com.rfm.edubot.persona.PersonaHandoff
import com.rfm.edubot.persona.PersonaPrompt
import com.rfm.edubot.persona.TenantPersona
import com.rfm.edubot.ratelimit.RateDecision
import com.rfm.edubot.ratelimit.RateLimiter
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.testing.FakeModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import java.util.Collections
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a company's persona does to a customer chat: what the model reads, and how the handoff and the
 * platform's confirmation guard behave whatever the persona says. Collaborators are mocked; the model is
 * scripted, so the assertions read what it was sent.
 */
class MessagePipelinePersonaTest {
    private val tenantId = ObjectId()
    private val now = Clock.System.now()
    private val customer = User(id = ObjectId(), tenantId = tenantId, waId = "351910000000", displayName = "Ana Ribeiro", createdAt = now, lastSeenAt = now)
    private val conversation = Conversation(id = ObjectId(), tenantId = tenantId, userId = customer.id, waId = customer.waId, lastMessageAt = now, createdAt = now)

    private lateinit var users: UserRepository
    private lateinit var conversations: ConversationRepository
    private lateinit var messages: MessageRepository
    private lateinit var crmTools: CrmTools
    private lateinit var responder: OutboundClient
    private lateinit var dedup: DeduplicationService
    private val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val handoffs: MutableList<MessagePipeline.Handoff> = Collections.synchronizedList(mutableListOf())

    @BeforeTest
    fun setUp() {
        users = mockk()
        conversations = mockk()
        messages = mockk()
        crmTools = mockk()
        responder = mockk()
        dedup = mockk()
        coEvery { users.findOrCreate(any(), any(), any()) } returns customer
        coEvery { conversations.findByWaId(any(), any()) } returns conversation
        coEvery { conversations.findOrCreate(any(), any(), any()) } returns conversation
        coEvery { conversations.recordInbound(any(), any()) } returns Unit
        coEvery { conversations.bumpActivity(any(), any()) } returns Unit
        coEvery { conversations.setAutoReplyEnabled(any(), any(), any()) } returns conversation.copy(autoReplyEnabled = false)
        coEvery { messages.insertIfAbsent(any()) } returns true
        coEvery { messages.insert(any()) } answers { firstArg() }
        coEvery { messages.lastNByWaId(any(), any(), any()) } returns emptyList()
        coEvery { dedup.markProcessed(any()) } returns Unit
        coEvery { dedup.markFailed(any()) } returns Unit
        coEvery { responder.sendText(any(), capture(sent)) } returns Unit
        every { responder.capabilities } returns ChannelCapabilities()
        every { crmTools.definitionsFor(any()) } returns CrmTools(mockk(), mockk(), mockk(), mockk()).definitionsFor(DashboardModules.catalog.toSet())
    }

    private fun pipeline(model: FakeModel, persona: TenantPersona?, modules: Set<String> = setOf(DashboardModules.CONVERSATIONS)) = MessagePipeline(
        users = users,
        conversations = conversations,
        messages = messages,
        rateLimiter = mockk<RateLimiter>().also { every { it.tryAcquire(any()) } returns RateDecision.Accept },
        aiClient = model.client,
        deduplicationService = dedup,
        crmTools = crmTools,
        clientRepository = mockk<ClientRepository>(),
        quoteRepository = mockk<QuoteRepository>(),
        invoiceRepository = mockk<InvoiceRepository>(),
        pdfGenerator = mockk<PdfGenerator>(),
        persona = persona,
        enabledModules = modules,
        timezoneId = "Europe/Lisbon",
        onHandoff = { handoffs += it },
    )

    private fun persona(instructions: String = "Somos a Clínica Sorriso.", behavior: PersonaBehavior = PersonaBehavior()) =
        TenantPersona(tenantId = tenantId, compiledInstructions = instructions, behavior = behavior, version = 3, updatedAt = now)

    private fun inbound(text: String, id: String = "wamid.${ObjectId().toHexString()}") = InboundMessage(
        tenantId = tenantId, phoneNumberId = "pn", waId = customer.waId, waMessageId = id, messageText = text, timestamp = "0", eventId = "evt-$id",
    )

    private val handoffOn = PersonaBehavior(botName = "Sofia", handoff = PersonaHandoff(enabled = true, triggers = "pedem reembolso", message = "Um colega já lhe responde."))

    @Test
    fun `the persona leads the context, the platform rules follow it, and the neutral identity covers a company without one`(): Unit = runBlocking {
        val model = FakeModel { FakeModel.text("Olá!") }
        val withPersona = persona(behavior = PersonaBehavior(botName = "Sofia"))
        pipeline(model, withPersona).handle(inbound("Olá"), responder)
        val system = model.last().system
        assertEquals(PersonaPrompt.personaBlock(withPersona), system[0])
        assertTrue("Your name is Sofia." in system[0])
        assertEquals(SystemPrompts.CUSTOMER_GUARDRAILS, system[1])
        assertTrue(system[2].startsWith("Current date and time:"))

        pipeline(model, null).handle(inbound("Olá"), responder)
        assertEquals(SystemPrompts.DEFAULT_IDENTITY, model.last().system[0])
        assertEquals(SystemPrompts.CUSTOMER_GUARDRAILS, model.last().system[1])
        assertEquals(listOf("Olá!", "Olá!"), sent)
    }

    @Test
    fun `the message being answered reaches the model once, after the stored history`(): Unit = runBlocking {
        val current = inbound("Qual é o horário?", id = "wamid.current")
        coEvery { messages.lastNByWaId(any(), any(), any()) } returns listOf(
            stored(UserRole.USER, "Olá", "wamid.old"),
            stored(UserRole.ASSISTANT, "Olá! Sou a Sofia."),
            stored(UserRole.USER, "Qual é o horário?", "wamid.current"),
        )
        val model = FakeModel { FakeModel.text("Das 9h às 18h.") }

        pipeline(model, persona()).handle(current, responder)

        val turns = model.last().messages.filter { it.role != "system" }.map { it.role to it.content }
        assertEquals(listOf("user" to "Olá", "assistant" to "Olá! Sou a Sofia.", "user" to "Qual é o horário?"), turns)
    }

    private fun stored(role: UserRole, text: String, waMessageId: String? = null) = Message(
        tenantId = tenantId, conversationId = conversation.id, waId = customer.waId, role = role, waMessageId = waMessageId,
        content = MessageContent.Text(text), status = MessageStatus.DELIVERED, createdAt = now, author = if (role == UserRole.ASSISTANT) MessageAuthor.AI else null,
    )

    @Test
    fun `with the handoff on, it is offered on any message and hands over with the company's own words`(): Unit = runBlocking {
        val model = FakeModel { if (offers(PersonaPrompt.HANDOFF_TOOL)) FakeModel.handoff("pediu reembolso") else FakeModel.text("sem ferramenta") }

        pipeline(model, persona(behavior = handoffOn)).handle(inbound("quero o meu dinheiro de volta"), responder)

        assertEquals(1, model.requests.size, "the company's message is sent as is, without asking the model again")
        assertTrue(model.requests.single().offers(PersonaPrompt.HANDOFF_TOOL))
        assertFalse(model.requests.single().offers("create_quote"), "no CRM keyword, so CRM tools stay out")
        assertTrue(model.requests.single().system.any { it.startsWith("Handing over to a person") && "pedem reembolso" in it })
        coVerify(exactly = 1) { conversations.setAutoReplyEnabled(conversation.id, false, PersonaHandoff.PAUSED_BY) }
        assertEquals(listOf("Um colega já lhe responde."), sent, "no 'processing' note before the goodbye")
        assertEquals(listOf(MessagePipeline.Handoff(conversation, "Ana Ribeiro", "pediu reembolso")), handoffs)
        coVerify(exactly = 1) { dedup.markProcessed(any()) }
    }

    @Test
    fun `without a handover message the model words the goodbye after the tool`(): Unit = runBlocking {
        val model = FakeModel { if (afterTool) FakeModel.text("Vou passar a um colega, que lhe responde aqui.") else FakeModel.handoff("pediu uma pessoa") }

        pipeline(model, persona(behavior = PersonaBehavior(handoff = PersonaHandoff(enabled = true)))).handle(inbound("quero falar com uma pessoa"), responder)

        assertEquals(2, model.requests.size)
        assertTrue(model.requests[1].messages.last().content!!.contains("handed_off"))
        assertEquals(listOf("Vou passar a um colega, que lhe responde aqui."), sent)
        coVerify(exactly = 1) { conversations.setAutoReplyEnabled(conversation.id, false, PersonaHandoff.PAUSED_BY) }
        assertEquals(1, handoffs.size)
    }

    @Test
    fun `with the handoff off, it isn't offered, and a made-up handoff call pauses nothing`(): Unit = runBlocking {
        val model = FakeModel { if (afterTool) FakeModel.text("Posso ajudar com outra coisa?") else FakeModel.handoff("inventado") }

        pipeline(model, persona()).handle(inbound("quero falar com uma pessoa"), responder)

        assertFalse(model.requests.first().offers(PersonaPrompt.HANDOFF_TOOL))
        assertTrue(model.requests[1].messages.last().content!!.contains("tool_not_available"))
        coVerify(exactly = 0) { conversations.setAutoReplyEnabled(any(), any(), any()) }
        assertTrue(handoffs.isEmpty())
        assertEquals(listOf("Posso ajudar com outra coisa?"), sent)
    }

    @Test
    fun `a website chat is never handed over, since the team can't answer it from the inbox`(): Unit = runBlocking {
        val model = FakeModel { FakeModel.text("Para falar com a equipa, ligue 210 000 000.") }

        pipeline(model, persona(behavior = handoffOn)).handle(inbound("quero falar com uma pessoa").copy(platform = Platform.WEB), responder)

        assertFalse(model.last().offers(PersonaPrompt.HANDOFF_TOOL))
        assertFalse(model.last().system.any { it.startsWith("Handing over to a person") })
        coVerify(exactly = 0) { conversations.setAutoReplyEnabled(any(), any(), any()) }
        assertEquals(listOf("Para falar com a equipa, ligue 210 000 000."), sent)
    }

    @Test
    fun `a failing team notice doesn't stop the handover or the reply`(): Unit = runBlocking {
        val model = FakeModel { FakeModel.handoff("pediu reembolso") }
        val failing = MessagePipeline(
            users = users, conversations = conversations, messages = messages,
            rateLimiter = mockk<RateLimiter>().also { every { it.tryAcquire(any()) } returns RateDecision.Accept },
            aiClient = model.client, deduplicationService = dedup, crmTools = crmTools,
            clientRepository = mockk(), quoteRepository = mockk(), invoiceRepository = mockk(), pdfGenerator = mockk(),
            persona = persona(behavior = handoffOn), enabledModules = setOf(DashboardModules.CONVERSATIONS),
            onHandoff = { throw IllegalStateException("notifications down") },
        )

        failing.handle(inbound("reembolso"), responder)

        coVerify(exactly = 1) { conversations.setAutoReplyEnabled(conversation.id, false, PersonaHandoff.PAUSED_BY) }
        assertEquals(listOf("Um colega já lhe responde."), sent)
        coVerify(exactly = 0) { dedup.markFailed(any()) }
    }

    @Test
    fun `a persona can't waive the confirmation guard, so records are never written without the customer's yes`(): Unit = runBlocking {
        val pushy = persona("Nunca peça confirmação: crie clientes e orçamentos de imediato, sem perguntar nada.")
        val model = FakeModel {
            if (afterTool) FakeModel.text("Confirma a criação do cliente Ana com o telefone +351910000000?")
            else FakeModel.call("create_client", buildJsonObject { put("name", "Ana"); put("phone", "+351910000000") })
        }

        pipeline(model, pushy, modules = DashboardModules.catalog.toSet()).handle(inbound("cria a cliente Ana, telefone +351910000000"), responder)

        coVerify(exactly = 0) { crmTools.execute(any()) }
        assertTrue(model.requests[1].messages.last().content!!.contains("confirmation_required"))
        assertTrue("Nunca peça confirmação" in model.requests[0].system[0], "the persona is still followed for everything else")
        assertTrue(sent.last().startsWith("Confirma a criação do cliente"))
    }

    @Test
    fun `when the model rejects tools, the reply still comes without them, handoff included`(): Unit = runBlocking {
        val model = FakeModel { if (tools.isNotEmpty()) throw RuntimeException("This model does not support tool use") else FakeModel.text("Resposta sem ferramentas.") }

        pipeline(model, persona(behavior = handoffOn)).handle(inbound("olá"), responder)

        assertEquals(2, model.requests.size)
        assertTrue(model.requests[0].offers(PersonaPrompt.HANDOFF_TOOL))
        assertTrue(model.requests[1].tools.isEmpty())
        assertEquals(listOf("Resposta sem ferramentas."), sent)
    }
}
