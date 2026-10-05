package com.rfm.edubot.mobile.core.testing

import com.rfm.edubot.mobile.core.model.Agent
import com.rfm.edubot.mobile.core.model.AgentApproval
import com.rfm.edubot.mobile.core.model.AgentStatus
import com.rfm.edubot.mobile.core.model.AgentTask
import com.rfm.edubot.mobile.core.model.AssistantMessage
import com.rfm.edubot.mobile.core.model.AssistantThread
import com.rfm.edubot.mobile.core.model.AssistantThreadDetail
import com.rfm.edubot.mobile.core.model.Booking
import com.rfm.edubot.mobile.core.model.BookingStatus
import com.rfm.edubot.mobile.core.model.ChannelAsset
import com.rfm.edubot.mobile.core.model.Company
import com.rfm.edubot.mobile.core.model.Contact
import com.rfm.edubot.mobile.core.model.Conversation
import com.rfm.edubot.mobile.core.model.CrmClient
import com.rfm.edubot.mobile.core.model.DashboardIdentity
import com.rfm.edubot.mobile.core.model.DashboardUser
import com.rfm.edubot.mobile.core.model.Invoice
import com.rfm.edubot.mobile.core.model.Overview
import com.rfm.edubot.mobile.core.model.OverviewCash
import com.rfm.edubot.mobile.core.model.OverviewInbox
import com.rfm.edubot.mobile.core.model.Persona
import com.rfm.edubot.mobile.core.model.Quote
import com.rfm.edubot.mobile.core.model.Tenant
import com.rfm.edubot.mobile.core.model.ThreadMessage

/**
 * Representative records for tests, built the way the backend actually sends them — a tenant in
 * Lisbon on `pt-PT`, money in euros, instants in ISO-8601 UTC.
 *
 * Every field with a real value here is one a test does not have to invent, and one a reader does
 * not have to decode.
 */
object Samples {
    const val NOW = "2026-01-15T10:30:00Z"

    val tenant = Tenant(
        id = "tenant-1",
        slug = "acme",
        name = "Acme Lda",
        locale = "pt-PT",
        timezone = "Europe/Lisbon",
        channels = listOf(ChannelAsset(ChannelAsset.WHATSAPP, "wa-asset-1", "Acme Support")),
    )

    val user = DashboardUser(
        id = "user-1",
        email = "staff@acme.test",
        role = DashboardIdentity.ROLE_ADMIN,
        status = "ACTIVE",
    )

    val identity = DashboardIdentity(
        tenant = tenant,
        user = user,
        modules = listOf("overview", "conversations", "contacts", "clients", "invoices", "settings"),
        companies = listOf(Company("tenant-1", "Acme Lda", "acme", primary = true)),
        companyLimit = 3,
    )

    val overview = Overview(
        users = 42,
        conversations = 7,
        messages = 310,
        messagesToday = 12,
        quotes = 3,
        invoices = 5,
        generatedAt = NOW,
        timezone = "Europe/Lisbon",
        inbox = OverviewInbox(waiting = 2, conversations = 7, messagesToday = 12, contacts = 42),
        cash = OverviewCash(
            collectedThisMonthCents = 452_000,
            outstandingCents = 128_050,
            overdueCents = 35_000,
            overdueCount = 1,
        ),
    )

    val conversation = Conversation(
        id = "conv-1",
        waId = "351910000001",
        channel = ChannelAsset.WHATSAPP,
        displayName = "Maria Silva",
        state = "OPEN",
        lastMessageAt = NOW,
        messageCount = 4,
        lastPreview = "Bom dia, queria um orçamento",
        waiting = true,
        unreadCount = 2,
    )

    val inboundMessage = ThreadMessage(
        id = "msg-1",
        role = "USER",
        text = "Bom dia, queria um orçamento",
        status = "DELIVERED",
        createdAt = NOW,
    )

    val botReply = ThreadMessage(
        id = "msg-2",
        role = "ASSISTANT",
        text = "Bom dia! Com certeza.",
        status = "READ",
        createdAt = "2026-01-15T10:31:00Z",
    )

    val failedReply = botReply.copy(
        id = "msg-3",
        status = "FAILED",
        errorKey = "outside_hours",
        author = ThreadMessage.AUTHOR_AGENT,
    )

    val contact = Contact(
        id = "contact-1",
        waId = "351910000001",
        channel = ChannelAsset.WHATSAPP,
        displayName = "Maria Silva",
        status = "ACTIVE",
        lastSeenAt = NOW,
    )

    val client = CrmClient(
        id = "client-1",
        number = "C0001",
        name = "Maria Silva",
        phone = "351910000001",
        taxId = "123456789",
        address = "Rua das Flores 10",
        city = "Lisboa",
        postalCode = "1200-192",
        createdAt = NOW,
    )

    val quote = Quote(
        id = "quote-1",
        number = "O2026/001",
        clientId = client.id,
        clientName = client.name,
        status = "PENDENTE",
        totalEur = 1_250.0,
        validUntil = "2026-02-15",
        createdAt = NOW,
    )

    val invoice = Invoice(
        id = "invoice-1",
        number = "F2026/001",
        clientId = client.id,
        clientName = client.name,
        status = "PENDING",
        dueDate = "2026-01-31",
        totalEur = 1_250.0,
        createdAt = NOW,
    )

    val overdueInvoice = invoice.copy(id = "invoice-2", number = "F2025/099", dueDate = "2025-12-01")

    val booking = Booking(
        id = "booking-1",
        serviceId = "svc-1",
        serviceName = "Instalação",
        priceEur = 90.0,
        contactName = "Maria Silva",
        contactPhone = "351910000001",
        startAt = "2026-01-15T14:00:00Z",
        endAt = "2026-01-15T15:00:00Z",
        status = BookingStatus.PENDING,
        createdAt = NOW,
    )

    val agent = Agent(
        id = "agent-1",
        name = "Chase overdue invoices",
        description = "Emails a reminder three days after the due date",
        status = AgentStatus.ACTIVE,
        createdAt = NOW,
        updatedAt = NOW,
    )

    val approval = AgentApproval(
        id = "approval-1",
        agentId = agent.id,
        agentName = agent.name,
        runId = "run-1",
        action = "send_email",
        subjectLabel = "F2025/099 · Maria Silva",
        createdAt = NOW,
        expiresAt = "2026-01-18T10:30:00Z",
        canDecide = true,
    )

    val task = AgentTask(
        id = "task-1",
        title = "Call Maria about F2025/099",
        subjectLabel = "Maria Silva",
        dueAt = "2026-01-16",
        agentName = agent.name,
        createdAt = NOW,
    )

    val assistantThread = AssistantThread(
        id = "thread-1",
        title = "Orçamento para a Maria",
        createdAt = NOW,
        updatedAt = NOW,
    )

    val assistantDetail = AssistantThreadDetail(
        thread = assistantThread,
        messages = listOf(
            AssistantMessage("am-1", "user", "Cria um orçamento para a Maria", NOW),
            AssistantMessage("am-2", "assistant", "Feito. Queres enviar?", NOW),
        ),
    )

    val persona = Persona(
        compiledInstructions = "Responde em português de Portugal, de forma breve.",
        version = 4,
        tokenEstimate = 820,
        status = "READY",
        updatedAt = NOW,
    )
}
