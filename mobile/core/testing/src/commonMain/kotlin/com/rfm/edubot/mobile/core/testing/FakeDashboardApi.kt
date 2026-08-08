package com.rfm.edubot.mobile.core.testing

import com.rfm.edubot.mobile.core.model.AssistantMessage
import com.rfm.edubot.mobile.core.model.AssistantThread
import com.rfm.edubot.mobile.core.model.AssistantThreadDetail
import com.rfm.edubot.mobile.core.model.CatalogItem
import com.rfm.edubot.mobile.core.model.ChannelAsset
import com.rfm.edubot.mobile.core.model.Contact
import com.rfm.edubot.mobile.core.model.Conversation
import com.rfm.edubot.mobile.core.model.CreateInvoice
import com.rfm.edubot.mobile.core.model.CreateQuote
import com.rfm.edubot.mobile.core.model.CrmClient
import com.rfm.edubot.mobile.core.model.DashboardIdentity
import com.rfm.edubot.mobile.core.model.DashboardUser
import com.rfm.edubot.mobile.core.model.Invoice
import com.rfm.edubot.mobile.core.model.Overview
import com.rfm.edubot.mobile.core.model.Persona
import com.rfm.edubot.mobile.core.model.Quote
import com.rfm.edubot.mobile.core.model.Session
import com.rfm.edubot.mobile.core.model.Tenant
import com.rfm.edubot.mobile.core.model.ThreadMessage
import com.rfm.edubot.mobile.core.model.WebWidget
import com.rfm.edubot.mobile.core.network.DashboardApi
import kotlinx.coroutines.delay

class FakeDashboardApi(
    private val assistantDetail: AssistantThreadDetail? = null,
    private val localeUpdateDelay: Long = 0,
) : DashboardApi {
    val sentAssistantMessages = mutableListOf<String>()
    override suspend fun login(email: String, password: String): Session = Session("new-token")

    override suspend fun me(token: String): DashboardIdentity = DashboardIdentity(
        tenant = Tenant("tenant-1", "acme", "Acme", "en", listOf(ChannelAsset("WHATSAPP", "asset-1"))),
        user = DashboardUser("user-1", "user@acme.test", "TENANT_ADMIN", "ACTIVE"),
        modules = listOf("overview", "conversations"),
        principalType = "tenant",
    )

    override suspend fun overview(token: String): Overview = Overview(
        users = 2,
        conversations = 3,
        messages = 9,
        messagesToday = 1,
        quotes = 0,
        invoices = 0,
    )

    override suspend fun contacts(token: String): List<Contact> = emptyList()

    override suspend fun updateContactStatus(token: String, contactId: String, status: String): Contact =
        error("not used")

    override suspend fun conversations(token: String): List<Conversation> = emptyList()

    override suspend fun messages(token: String, conversationId: String): List<ThreadMessage> = emptyList()

    override suspend fun sendMessage(token: String, conversationId: String, text: String, assetExternalId: String): ThreadMessage =
        error("not used")

    override suspend fun assistantThreads(token: String): List<AssistantThread> = assistantDetail?.let { listOf(it.thread) }.orEmpty()

    override suspend fun createAssistantThread(token: String, title: String): AssistantThread = error("not used")

    override suspend fun assistantThread(token: String, threadId: String): AssistantThreadDetail = requireNotNull(assistantDetail)

    override suspend fun sendAssistantMessage(token: String, threadId: String, content: String): AssistantThreadDetail {
        sentAssistantMessages += content
        val detail = requireNotNull(assistantDetail)
        return detail.copy(messages = detail.messages + AssistantMessage("message-1", "user", content, "now"))
    }

    override suspend fun decideAssistantAction(token: String, threadId: String, actionId: String, decision: String): AssistantThreadDetail =
        error("not used")

    override suspend fun clients(token: String): List<CrmClient> = emptyList()

    override suspend fun createClient(token: String, name: String, phone: String, address: String?): CrmClient = error("not used")

    override suspend fun quotes(token: String): List<Quote> = emptyList()

    override suspend fun invoices(token: String): List<Invoice> = emptyList()

    override suspend fun catalog(token: String): List<CatalogItem> = emptyList()

    override suspend fun createCatalogItem(token: String, item: CatalogItem): CatalogItem = error("not used")

    override suspend fun createQuote(token: String, request: CreateQuote): Quote = error("not used")

    override suspend fun createInvoice(token: String, request: CreateInvoice): Invoice = error("not used")

    override suspend fun persona(token: String): Persona = error("not used")

    override suspend fun updatePersona(token: String, compiledInstructions: String): Persona = error("not used")

    override suspend fun webWidget(token: String): WebWidget = error("not used")

    override suspend fun updateLocale(token: String, locale: String): String {
        delay(localeUpdateDelay)
        return locale
    }
}
