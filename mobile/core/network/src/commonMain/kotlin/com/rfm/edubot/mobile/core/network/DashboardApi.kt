package com.rfm.edubot.mobile.core.network

import com.rfm.edubot.mobile.core.model.Account
import com.rfm.edubot.mobile.core.model.Agent
import com.rfm.edubot.mobile.core.model.AgentApproval
import com.rfm.edubot.mobile.core.model.AgentRun
import com.rfm.edubot.mobile.core.model.AgentTask
import com.rfm.edubot.mobile.core.model.AgentsOverview
import com.rfm.edubot.mobile.core.model.AssistantThread
import com.rfm.edubot.mobile.core.model.AssistantThreadDetail
import com.rfm.edubot.mobile.core.model.AvailabilityRule
import com.rfm.edubot.mobile.core.model.Booking
import com.rfm.edubot.mobile.core.model.BookingService
import com.rfm.edubot.mobile.core.model.CatalogItem
import com.rfm.edubot.mobile.core.model.ClientService
import com.rfm.edubot.mobile.core.model.Contact
import com.rfm.edubot.mobile.core.model.Conversation
import com.rfm.edubot.mobile.core.model.ConvertQuote
import com.rfm.edubot.mobile.core.model.CreateBooking
import com.rfm.edubot.mobile.core.model.CreateInvoice
import com.rfm.edubot.mobile.core.model.CreateQuote
import com.rfm.edubot.mobile.core.model.CrmClient
import com.rfm.edubot.mobile.core.model.DashboardIdentity
import com.rfm.edubot.mobile.core.model.Employee
import com.rfm.edubot.mobile.core.model.Invoice
import com.rfm.edubot.mobile.core.model.InvoiceClientServices
import com.rfm.edubot.mobile.core.model.Notifications
import com.rfm.edubot.mobile.core.model.Overview
import com.rfm.edubot.mobile.core.model.Payment
import com.rfm.edubot.mobile.core.model.Persona
import com.rfm.edubot.mobile.core.model.PersonaTest
import com.rfm.edubot.mobile.core.model.Quote
import com.rfm.edubot.mobile.core.model.SaveCatalogItem
import com.rfm.edubot.mobile.core.model.SaveClient
import com.rfm.edubot.mobile.core.model.SaveTask
import com.rfm.edubot.mobile.core.model.Session
import com.rfm.edubot.mobile.core.model.StartedConversation
import com.rfm.edubot.mobile.core.model.Supplier
import com.rfm.edubot.mobile.core.model.SwitchedCompany
import com.rfm.edubot.mobile.core.model.ThreadMessage
import com.rfm.edubot.mobile.core.model.ThreadUpdates
import com.rfm.edubot.mobile.core.model.TimeSlot
import com.rfm.edubot.mobile.core.model.UpdateBooking
import com.rfm.edubot.mobile.core.model.WebWidget
import com.rfm.edubot.mobile.core.model.WhatsAppTemplate

/*
 * One interface per area of the dashboard instead of one interface for all of it. A fake only has
 * to answer the calls its screen makes, which is what makes `core:testing` usable.
 *
 * None of these take a token: see [DashboardHttpClient].
 */

interface SessionApi {
    suspend fun login(email: String, password: String): Session
    suspend fun me(): DashboardIdentity
    suspend fun switchCompany(companyId: String): SwitchedCompany
}

interface OverviewApi {
    /** [extended] fills the agenda and recent-activity lists the Home screen shows. */
    suspend fun overview(extended: Boolean = true): Overview
}

interface InboxApi {
    suspend fun conversations(query: String? = null): List<Conversation>
    suspend fun messages(conversationId: String): List<ThreadMessage>
    suspend fun updates(conversationId: String, since: String?): ThreadUpdates
    suspend fun markRead(conversationId: String): Conversation
    suspend fun setAutoReply(conversationId: String, enabled: Boolean): Conversation
    suspend fun sendMessage(conversationId: String, text: String, assetExternalId: String?): ThreadMessage
    suspend fun retryMessage(conversationId: String, messageId: String): ThreadMessage
    suspend fun sendTemplate(
        conversationId: String,
        name: String,
        language: String,
        params: Map<String, String>,
    ): ThreadMessage

    suspend fun startConversation(
        phone: String,
        name: String,
        language: String,
        params: Map<String, String>,
    ): StartedConversation

    suspend fun templates(sendableOnly: Boolean = true): List<WhatsAppTemplate>
    suspend fun contacts(query: String? = null): List<Contact>
    suspend fun setContactStatus(contactId: String, status: String): Contact
}

interface CrmApi {
    suspend fun clients(query: String? = null, archived: Boolean = false): List<CrmClient>
    suspend fun client(id: String): CrmClient
    suspend fun createClient(request: SaveClient): CrmClient
    suspend fun updateClient(id: String, request: SaveClient): CrmClient
    suspend fun archiveClient(id: String, archived: Boolean)

    suspend fun quotes(clientId: String? = null, status: String? = null): List<Quote>
    suspend fun quote(id: String): Quote
    suspend fun createQuote(request: CreateQuote): Quote
    suspend fun setQuoteStatus(id: String, status: String): Quote
    suspend fun convertQuote(id: String, request: ConvertQuote): Invoice

    suspend fun invoices(clientId: String? = null, status: String? = null): List<Invoice>
    suspend fun invoice(id: String): Invoice
    suspend fun createInvoice(request: CreateInvoice): Invoice
    suspend fun markInvoicePaid(id: String): Invoice

    suspend fun catalog(query: String? = null): List<CatalogItem>
    suspend fun saveCatalogItem(request: SaveCatalogItem, id: String? = null): CatalogItem
    suspend fun deleteCatalogItem(id: String)

    suspend fun services(clientId: String? = null, status: String? = null): List<ClientService>
    suspend fun invoiceServices(request: InvoiceClientServices): Invoice

    suspend fun suppliers(archived: Boolean = false): List<Supplier>
    suspend fun employees(archived: Boolean = false): List<Employee>
    suspend fun payments(status: String? = null): List<Payment>
    suspend fun markPaymentPaid(id: String): Payment
}

interface BookingsApi {
    suspend fun bookings(from: String? = null, to: String? = null, status: String? = null): List<Booking>
    suspend fun booking(id: String): Booking
    suspend fun createBooking(request: CreateBooking): Booking
    suspend fun updateBooking(id: String, request: UpdateBooking): Booking
    suspend fun services(activeOnly: Boolean = true): List<BookingService>
    suspend fun availability(): List<AvailabilityRule>
    suspend fun slots(serviceId: String, from: String, to: String): List<TimeSlot>
}

interface AgentsApi {
    suspend fun overview(): AgentsOverview
    suspend fun agents(archived: Boolean = false): List<Agent>
    suspend fun pauseAgent(id: String): Agent
    suspend fun activateAgent(id: String): Agent
    suspend fun approvals(status: String? = "PENDING"): List<AgentApproval>
    suspend fun approve(id: String): AgentApproval
    suspend fun reject(id: String, reason: String?): AgentApproval
    suspend fun tasks(status: String? = null, mine: Boolean = false): List<AgentTask>
    suspend fun saveTask(request: SaveTask, id: String? = null): AgentTask
    suspend fun runs(agentId: String? = null, limit: Int = 30): List<AgentRun>
}

interface AssistantApi {
    suspend fun threads(): List<AssistantThread>
    suspend fun createThread(title: String): AssistantThread
    suspend fun thread(threadId: String): AssistantThreadDetail
    suspend fun sendMessage(threadId: String, content: String): AssistantThreadDetail
    suspend fun confirmAction(threadId: String, actionId: String): AssistantThreadDetail
    suspend fun cancelAction(threadId: String, actionId: String): AssistantThreadDetail
}

interface PersonaApi {
    suspend fun persona(): Persona
    suspend fun updatePersona(compiledInstructions: String): Persona
    suspend fun addSource(content: String): Persona
    suspend fun deleteSource(id: String)
    suspend fun rebuild(): Persona
    suspend fun test(request: PersonaTest): String
}

interface NotificationsApi {
    suspend fun notifications(): Notifications
    suspend fun markRead(id: String)
    suspend fun markAllRead()
}

interface SettingsApi {
    suspend fun webWidget(): WebWidget
    suspend fun updateLocale(locale: String): String
    suspend fun account(): Account
}
