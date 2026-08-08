package com.rfm.edubot.mobile.core.network

import com.rfm.edubot.mobile.core.model.AssistantThread
import com.rfm.edubot.mobile.core.model.AssistantThreadDetail
import com.rfm.edubot.mobile.core.model.CatalogItem
import com.rfm.edubot.mobile.core.model.Contact
import com.rfm.edubot.mobile.core.model.Conversation
import com.rfm.edubot.mobile.core.model.CreateInvoice
import com.rfm.edubot.mobile.core.model.CreateQuote
import com.rfm.edubot.mobile.core.model.CrmClient
import com.rfm.edubot.mobile.core.model.DashboardIdentity
import com.rfm.edubot.mobile.core.model.Invoice
import com.rfm.edubot.mobile.core.model.Overview
import com.rfm.edubot.mobile.core.model.Persona
import com.rfm.edubot.mobile.core.model.Quote
import com.rfm.edubot.mobile.core.model.Session
import com.rfm.edubot.mobile.core.model.ThreadMessage
import com.rfm.edubot.mobile.core.model.WebWidget

interface DashboardApi {
    suspend fun login(email: String, password: String): Session
    suspend fun me(token: String): DashboardIdentity
    suspend fun overview(token: String): Overview
    suspend fun contacts(token: String): List<Contact>
    suspend fun updateContactStatus(token: String, contactId: String, status: String): Contact
    suspend fun conversations(token: String): List<Conversation>
    suspend fun messages(token: String, conversationId: String): List<ThreadMessage>
    suspend fun sendMessage(token: String, conversationId: String, text: String, assetExternalId: String): ThreadMessage
    suspend fun assistantThreads(token: String): List<AssistantThread>
    suspend fun createAssistantThread(token: String, title: String): AssistantThread
    suspend fun assistantThread(token: String, threadId: String): AssistantThreadDetail
    suspend fun sendAssistantMessage(token: String, threadId: String, content: String): AssistantThreadDetail
    suspend fun decideAssistantAction(token: String, threadId: String, actionId: String, decision: String): AssistantThreadDetail
    suspend fun clients(token: String): List<CrmClient>
    suspend fun createClient(token: String, name: String, phone: String, address: String?): CrmClient
    suspend fun quotes(token: String): List<Quote>
    suspend fun invoices(token: String): List<Invoice>
    suspend fun catalog(token: String): List<CatalogItem>
    suspend fun createCatalogItem(token: String, item: CatalogItem): CatalogItem
    suspend fun createQuote(token: String, request: CreateQuote): Quote
    suspend fun createInvoice(token: String, request: CreateInvoice): Invoice
    suspend fun persona(token: String): Persona
    suspend fun updatePersona(token: String, compiledInstructions: String): Persona
    suspend fun webWidget(token: String): WebWidget
    suspend fun updateLocale(token: String, locale: String): String
}

class DashboardApiException(val status: Int, message: String) : IllegalStateException(message)
