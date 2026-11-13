package com.rfm.edubot.mobile.core.network

import com.rfm.edubot.mobile.core.model.Account
import com.rfm.edubot.mobile.core.model.CatalogCategory
import com.rfm.edubot.mobile.core.model.ChangeOrder
import com.rfm.edubot.mobile.core.model.ChangeStatus
import com.rfm.edubot.mobile.core.model.CreateService
import com.rfm.edubot.mobile.core.model.DirectoryFields
import com.rfm.edubot.mobile.core.model.Measurement
import com.rfm.edubot.mobile.core.model.MeasurementInvoiced
import com.rfm.edubot.mobile.core.model.MySite
import com.rfm.edubot.mobile.core.model.MySiteDetail
import com.rfm.edubot.mobile.core.model.PortalClient
import com.rfm.edubot.mobile.core.model.Project
import com.rfm.edubot.mobile.core.model.QuoteToService
import com.rfm.edubot.mobile.core.model.SaveIssue
import com.rfm.edubot.mobile.core.model.SaveSiteLog
import com.rfm.edubot.mobile.core.model.ServiceSubmission
import com.rfm.edubot.mobile.core.model.SiteIssue
import com.rfm.edubot.mobile.core.model.SiteLog
import com.rfm.edubot.mobile.core.model.SubmitService
import com.rfm.edubot.mobile.core.model.UpdateService
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
import com.rfm.edubot.mobile.core.model.CloseShift
import com.rfm.edubot.mobile.core.model.EnrollDevice
import com.rfm.edubot.mobile.core.model.PunchRequest
import com.rfm.edubot.mobile.core.model.PunchResult
import com.rfm.edubot.mobile.core.model.Shift
import com.rfm.edubot.mobile.core.model.ThreadMessage
import com.rfm.edubot.mobile.core.model.ThreadUpdates
import com.rfm.edubot.mobile.core.model.TimeChallenge
import com.rfm.edubot.mobile.core.model.TimeClockStatus
import com.rfm.edubot.mobile.core.model.TimeDevice
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
    suspend fun clientFields(): DirectoryFields
    suspend fun createClient(request: SaveClient): CrmClient
    suspend fun updateClient(id: String, request: SaveClient): CrmClient
    suspend fun archiveClient(id: String, archived: Boolean)

    suspend fun quotes(clientId: String? = null, status: String? = null): List<Quote>
    suspend fun quote(id: String): Quote
    suspend fun createQuote(request: CreateQuote): Quote
    suspend fun setQuoteStatus(id: String, status: String): Quote
    suspend fun convertQuote(id: String, request: ConvertQuote): Invoice
    suspend fun quoteToService(id: String, request: QuoteToService): ClientService
    suspend fun deleteQuote(id: String)

    suspend fun invoices(clientId: String? = null, status: String? = null): List<Invoice>
    suspend fun invoice(id: String): Invoice
    suspend fun createInvoice(request: CreateInvoice): Invoice
    suspend fun markInvoicePaid(id: String): Invoice
    suspend fun receiveInstallment(id: String, index: Int): Invoice
    suspend fun cancelInvoice(id: String): Invoice
    suspend fun deleteInvoice(id: String)

    suspend fun catalog(query: String? = null): List<CatalogItem>
    suspend fun catalogCategories(): List<CatalogCategory>
    suspend fun saveCatalogItem(request: SaveCatalogItem, id: String? = null): CatalogItem
    suspend fun deleteCatalogItem(id: String)

    /** [from] and [to] (`yyyy-MM-dd`) go together and narrow the rows to those days. */
    suspend fun services(
        clientId: String? = null,
        status: String? = null,
        employeeId: String? = null,
        from: String? = null,
        to: String? = null,
    ): List<ClientService>
    suspend fun service(id: String): ClientService
    suspend fun createService(request: CreateService): ClientService
    suspend fun updateService(id: String, request: UpdateService): ClientService
    suspend fun deleteService(id: String)
    suspend fun invoiceServices(request: InvoiceClientServices): Invoice

    suspend fun suppliers(archived: Boolean = false): List<Supplier>
    suspend fun supplier(id: String): Supplier
    suspend fun employees(archived: Boolean = false): List<Employee>
    suspend fun employee(id: String): Employee
    suspend fun payments(status: String? = null, supplierId: String? = null, employeeId: String? = null): List<Payment>
    suspend fun markPaymentPaid(id: String): Payment

    /** What employees registered from their own sign-in, for the team to approve. */
    suspend fun serviceSubmissions(employeeId: String? = null, status: String? = null): List<ServiceSubmission>
    suspend fun approveSubmission(id: String): ServiceSubmission
    suspend fun rejectSubmission(id: String, reason: String?): ServiceSubmission
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

/**
 * An employee's own My services (`/app/api/portal/…`): the clients and catalog to pick from, and the
 * services they registered, which they can change or withdraw while nobody has decided on them.
 */
interface PortalApi {
    suspend fun clients(): List<PortalClient>
    suspend fun catalog(): List<CatalogItem>
    suspend fun myServices(): List<ServiceSubmission>
    suspend fun submit(request: SubmitService): ServiceSubmission
    suspend fun update(id: String, request: SubmitService): ServiceSubmission
    suspend fun withdraw(id: String)
}

/** A site manager's My sites (`/app/api/portal/sites…`): the projects they run, with no money anywhere. */
interface MySitesApi {
    suspend fun sites(): List<MySite>
    suspend fun site(id: String): MySiteDetail
    suspend fun writeDiary(siteId: String, request: SaveSiteLog): SiteLog
    suspend fun editDiary(siteId: String, entryId: String, request: SaveSiteLog): SiteLog
    suspend fun logIssue(siteId: String, request: SaveIssue): SiteIssue
    suspend fun editIssue(siteId: String, issueId: String, request: SaveIssue): SiteIssue
}

/** The Building category's team pages (`/app/api/building/…`). `status` filters take several, comma-separated. */
interface BuildingApi {
    suspend fun projects(status: String? = null): List<Project>
    suspend fun project(id: String): Project
    suspend fun setProjectStatus(id: String, status: String): Project
    suspend fun setPhaseProgress(projectId: String, phaseId: String, progressPct: Int): Project

    suspend fun diary(projectId: String? = null, from: String? = null, to: String? = null): List<SiteLog>
    suspend fun diaryEntry(id: String): SiteLog
    suspend fun writeDiary(request: SaveSiteLog): SiteLog
    suspend fun editDiary(id: String, request: SaveSiteLog): SiteLog

    suspend fun issues(projectId: String? = null, status: String? = null): List<SiteIssue>
    suspend fun issue(id: String): SiteIssue
    suspend fun logIssue(request: SaveIssue): SiteIssue
    suspend fun editIssue(id: String, request: SaveIssue): SiteIssue

    suspend fun changes(projectId: String? = null, status: String? = null): List<ChangeOrder>
    suspend fun change(id: String): ChangeOrder
    suspend fun setChangeStatus(id: String, request: ChangeStatus): ChangeOrder

    suspend fun measurements(projectId: String? = null, status: String? = null): List<Measurement>
    suspend fun measurement(id: String): Measurement
    suspend fun approveMeasurement(id: String): Measurement
    suspend fun reopenMeasurement(id: String): Measurement
    suspend fun invoiceMeasurement(id: String, dueDate: String): MeasurementInvoiced
}

/** An employee's own time clock (`/app/api/portal/time…`). */
interface TimeClockApi {
    suspend fun status(): TimeClockStatus
    suspend fun shifts(): List<Shift>
    suspend fun challenge(): TimeChallenge
    suspend fun punch(request: PunchRequest): PunchResult
    suspend fun closeForgotten(shiftId: String, request: CloseShift): PunchResult
    suspend fun enroll(request: EnrollDevice): TimeDevice
    suspend fun removeDevice(keyId: String)
}
