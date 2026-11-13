package com.rfm.edubot.mobile.core.network

import com.rfm.edubot.mobile.core.model.Account
import com.rfm.edubot.mobile.core.model.CatalogCategory
import com.rfm.edubot.mobile.core.model.ChangeOrder
import com.rfm.edubot.mobile.core.model.ChangeStatus
import com.rfm.edubot.mobile.core.model.CreateService
import com.rfm.edubot.mobile.core.model.DirectoryFields
import com.rfm.edubot.mobile.core.model.DueDate
import com.rfm.edubot.mobile.core.model.Measurement
import com.rfm.edubot.mobile.core.model.MeasurementInvoiced
import com.rfm.edubot.mobile.core.model.MySite
import com.rfm.edubot.mobile.core.model.MySiteDetail
import com.rfm.edubot.mobile.core.model.PhaseProgressChange
import com.rfm.edubot.mobile.core.model.PortalClient
import com.rfm.edubot.mobile.core.model.Project
import com.rfm.edubot.mobile.core.model.ProjectStatusChange
import com.rfm.edubot.mobile.core.model.QuoteToService
import com.rfm.edubot.mobile.core.model.RejectSubmission
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
import com.rfm.edubot.mobile.core.model.ApproveAction
import com.rfm.edubot.mobile.core.model.AssistantPrompt
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
import com.rfm.edubot.mobile.core.model.LocaleChange
import com.rfm.edubot.mobile.core.model.NewAssistantThread
import com.rfm.edubot.mobile.core.model.Notifications
import com.rfm.edubot.mobile.core.model.Overview
import com.rfm.edubot.mobile.core.model.Payment
import com.rfm.edubot.mobile.core.model.Persona
import com.rfm.edubot.mobile.core.model.PersonaReply
import com.rfm.edubot.mobile.core.model.PersonaSourceText
import com.rfm.edubot.mobile.core.model.PersonaTest
import com.rfm.edubot.mobile.core.model.PersonaUpdate
import com.rfm.edubot.mobile.core.model.Quote
import com.rfm.edubot.mobile.core.model.QuoteStatusChange
import com.rfm.edubot.mobile.core.model.RejectAction
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
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable

private const val APP = "app/api"
private const val BUILDING = "$APP/building"

class KtorSessionApi(private val http: DashboardHttpClient) : SessionApi {
    override suspend fun login(email: String, password: String): Session =
        http.postUnauthenticated("app/auth/login", LoginRequest(email.trim(), password))

    override suspend fun me(): DashboardIdentity = http.get("$APP/me")

    override suspend fun switchCompany(companyId: String): SwitchedCompany =
        http.post("$APP/companies/$companyId/switch")
}

class KtorOverviewApi(private val http: DashboardHttpClient) : OverviewApi {
    override suspend fun overview(extended: Boolean): Overview =
        http.get("$APP/overview", "extended" to if (extended) "1" else null)
}

class KtorInboxApi(private val http: DashboardHttpClient) : InboxApi {
    override suspend fun conversations(query: String?): List<Conversation> =
        http.get("$APP/conversations", "q" to query?.takeIf { it.isNotBlank() })

    override suspend fun messages(conversationId: String): List<ThreadMessage> =
        http.get("$APP/conversations/$conversationId/messages")

    override suspend fun updates(conversationId: String, since: String?): ThreadUpdates =
        http.get("$APP/conversations/$conversationId/updates", "since" to since)

    override suspend fun markRead(conversationId: String): Conversation =
        http.post("$APP/conversations/$conversationId/read")

    override suspend fun setAutoReply(conversationId: String, enabled: Boolean): Conversation =
        http.patch("$APP/conversations/$conversationId/auto-reply", AutoReplyRequest(enabled))

    override suspend fun sendMessage(conversationId: String, text: String, assetExternalId: String?): ThreadMessage =
        http.post("$APP/conversations/$conversationId/messages", OutboundMessageRequest(text, assetExternalId))

    override suspend fun retryMessage(conversationId: String, messageId: String): ThreadMessage =
        http.post("$APP/conversations/$conversationId/messages/$messageId/retry")

    override suspend fun sendTemplate(
        conversationId: String,
        name: String,
        language: String,
        params: Map<String, String>,
    ): ThreadMessage = http.post("$APP/conversations/$conversationId/template", TemplateSendRequest(name, language, params))

    override suspend fun startConversation(
        phone: String,
        name: String,
        language: String,
        params: Map<String, String>,
    ): StartedConversation = http.post("$APP/conversations/start", StartConversationRequest(phone, name, language, params))

    override suspend fun templates(sendableOnly: Boolean): List<WhatsAppTemplate> =
        http.get<List<WhatsAppTemplate>>("$APP/whatsapp/templates", "all" to if (sendableOnly) null else "1")
            .filter { !sendableOnly || it.sendable }

    override suspend fun contacts(query: String?): List<Contact> =
        http.get("$APP/contacts", "q" to query?.takeIf { it.isNotBlank() })

    override suspend fun setContactStatus(contactId: String, status: String): Contact =
        http.patch("$APP/contacts/$contactId/status", ContactStatusRequest(status))
}

class KtorCrmApi(private val http: DashboardHttpClient) : CrmApi {
    override suspend fun clients(query: String?, archived: Boolean): List<CrmClient> = http.get(
        "$APP/crm/clients",
        "q" to query?.takeIf { it.isNotBlank() },
        "archived" to if (archived) "1" else null,
    )

    override suspend fun client(id: String): CrmClient = http.get("$APP/crm/clients/$id")

    override suspend fun clientFields(): DirectoryFields = http.get("$APP/crm/clients/fields")

    override suspend fun createClient(request: SaveClient): CrmClient = http.post("$APP/crm/clients", request)

    override suspend fun updateClient(id: String, request: SaveClient): CrmClient =
        http.patch("$APP/crm/clients/$id", request)

    override suspend fun archiveClient(id: String, archived: Boolean) {
        http.send(HttpMethod.Post, "$APP/crm/clients/$id/${if (archived) "archive" else "restore"}")
    }

    override suspend fun quotes(clientId: String?, status: String?): List<Quote> =
        http.get("$APP/crm/quotes", "clientId" to clientId, "status" to status)

    override suspend fun quote(id: String): Quote = http.get("$APP/crm/quotes/$id")

    override suspend fun createQuote(request: CreateQuote): Quote = http.post("$APP/crm/quotes", request)

    override suspend fun setQuoteStatus(id: String, status: String): Quote =
        http.patch("$APP/crm/quotes/$id", QuoteStatusChange(status))

    override suspend fun convertQuote(id: String, request: ConvertQuote): Invoice =
        http.post("$APP/crm/quotes/$id/invoice", request)

    override suspend fun quoteToService(id: String, request: QuoteToService): ClientService =
        http.post("$APP/crm/quotes/$id/service", request)

    override suspend fun deleteQuote(id: String) {
        http.send(HttpMethod.Delete, "$APP/crm/quotes/$id")
    }

    override suspend fun invoices(clientId: String?, status: String?): List<Invoice> =
        http.get("$APP/crm/invoices", "clientId" to clientId, "status" to status)

    override suspend fun invoice(id: String): Invoice = http.get("$APP/crm/invoices/$id")

    override suspend fun createInvoice(request: CreateInvoice): Invoice = http.post("$APP/crm/invoices", request)

    override suspend fun markInvoicePaid(id: String): Invoice = http.patch("$APP/crm/invoices/$id/paid")

    override suspend fun receiveInstallment(id: String, index: Int): Invoice =
        http.patch("$APP/crm/invoices/$id/installments/$index/paid")

    override suspend fun cancelInvoice(id: String): Invoice = http.post("$APP/crm/invoices/$id/cancel")

    override suspend fun deleteInvoice(id: String) {
        http.send(HttpMethod.Delete, "$APP/crm/invoices/$id")
    }

    override suspend fun catalog(query: String?): List<CatalogItem> =
        http.get("$APP/crm/standard-items", "q" to query?.takeIf { it.isNotBlank() })

    override suspend fun catalogCategories(): List<CatalogCategory> = http.get("$APP/crm/catalog-categories")

    override suspend fun saveCatalogItem(request: SaveCatalogItem, id: String?): CatalogItem =
        http.post(if (id == null) "$APP/crm/standard-items" else "$APP/crm/standard-items/$id", request)

    override suspend fun deleteCatalogItem(id: String) {
        http.send(HttpMethod.Delete, "$APP/crm/standard-items/$id")
    }

    override suspend fun services(
        clientId: String?,
        status: String?,
        employeeId: String?,
        from: String?,
        to: String?,
    ): List<ClientService> = http.get(
        "$APP/crm/services",
        "clientId" to clientId,
        "status" to status,
        "employeeId" to employeeId,
        "from" to from,
        "to" to to,
    )

    override suspend fun service(id: String): ClientService = http.get("$APP/crm/services/$id")

    override suspend fun createService(request: CreateService): ClientService = http.post("$APP/crm/services", request)

    override suspend fun updateService(id: String, request: UpdateService): ClientService =
        http.patch("$APP/crm/services/$id", request)

    override suspend fun deleteService(id: String) {
        http.send(HttpMethod.Delete, "$APP/crm/services/$id")
    }

    override suspend fun invoiceServices(request: InvoiceClientServices): Invoice =
        http.post("$APP/crm/services/invoice", request)

    override suspend fun suppliers(archived: Boolean): List<Supplier> =
        http.get("$APP/crm/suppliers", "archived" to if (archived) "1" else null)

    override suspend fun supplier(id: String): Supplier = http.get("$APP/crm/suppliers/$id")

    override suspend fun employees(archived: Boolean): List<Employee> =
        http.get("$APP/crm/employees", "archived" to if (archived) "1" else null)

    override suspend fun employee(id: String): Employee = http.get("$APP/crm/employees/$id")

    override suspend fun payments(status: String?, supplierId: String?, employeeId: String?): List<Payment> =
        http.get("$APP/crm/payments", "status" to status, "supplierId" to supplierId, "employeeId" to employeeId)

    override suspend fun markPaymentPaid(id: String): Payment = http.patch("$APP/crm/payments/$id/paid")

    override suspend fun serviceSubmissions(employeeId: String?, status: String?): List<ServiceSubmission> =
        http.get("$APP/crm/service-submissions", "employeeId" to employeeId, "status" to status)

    // The response also carries the Serviços row the approval made; the review screen only needs the submission.
    override suspend fun approveSubmission(id: String): ServiceSubmission =
        http.post<ApprovedSubmission>("$APP/crm/service-submissions/$id/approve", ApproveSubmission()).submission

    override suspend fun rejectSubmission(id: String, reason: String?): ServiceSubmission =
        http.post("$APP/crm/service-submissions/$id/reject", RejectSubmission(reason?.takeIf { it.isNotBlank() }))
}

class KtorPortalApi(private val http: DashboardHttpClient) : PortalApi {
    override suspend fun clients(): List<PortalClient> = http.get("$APP/portal/clients")

    override suspend fun catalog(): List<CatalogItem> = http.get("$APP/portal/catalog")

    override suspend fun myServices(): List<ServiceSubmission> = http.get("$APP/portal/services")

    override suspend fun submit(request: SubmitService): ServiceSubmission = http.post("$APP/portal/services", request)

    override suspend fun update(id: String, request: SubmitService): ServiceSubmission =
        http.patch("$APP/portal/services/$id", request)

    override suspend fun withdraw(id: String) {
        http.send(HttpMethod.Delete, "$APP/portal/services/$id")
    }
}

class KtorMySitesApi(private val http: DashboardHttpClient) : MySitesApi {
    override suspend fun sites(): List<MySite> = http.get("$APP/portal/sites")

    override suspend fun site(id: String): MySiteDetail = http.get("$APP/portal/sites/$id")

    override suspend fun writeDiary(siteId: String, request: SaveSiteLog): SiteLog =
        http.post("$APP/portal/sites/$siteId/diary", request)

    override suspend fun editDiary(siteId: String, entryId: String, request: SaveSiteLog): SiteLog =
        http.patch("$APP/portal/sites/$siteId/diary/$entryId", request)

    override suspend fun logIssue(siteId: String, request: SaveIssue): SiteIssue =
        http.post("$APP/portal/sites/$siteId/issues", request)

    override suspend fun editIssue(siteId: String, issueId: String, request: SaveIssue): SiteIssue =
        http.patch("$APP/portal/sites/$siteId/issues/$issueId", request)
}

class KtorBuildingApi(private val http: DashboardHttpClient) : BuildingApi {
    override suspend fun projects(status: String?): List<Project> = http.get("$BUILDING/projects", "status" to status)

    override suspend fun project(id: String): Project = http.get("$BUILDING/projects/$id")

    override suspend fun setProjectStatus(id: String, status: String): Project =
        http.patch("$BUILDING/projects/$id", ProjectStatusChange(status))

    override suspend fun setPhaseProgress(projectId: String, phaseId: String, progressPct: Int): Project =
        http.patch("$BUILDING/projects/$projectId/phases/$phaseId", PhaseProgressChange(progressPct))

    override suspend fun diary(projectId: String?, from: String?, to: String?): List<SiteLog> =
        http.get("$BUILDING/diary", "projectId" to projectId, "from" to from, "to" to to)

    override suspend fun diaryEntry(id: String): SiteLog = http.get("$BUILDING/diary/$id")

    override suspend fun writeDiary(request: SaveSiteLog): SiteLog = http.post("$BUILDING/diary", request)

    override suspend fun editDiary(id: String, request: SaveSiteLog): SiteLog = http.patch("$BUILDING/diary/$id", request)

    override suspend fun issues(projectId: String?, status: String?): List<SiteIssue> =
        http.get("$BUILDING/issues", "projectId" to projectId, "status" to status)

    override suspend fun issue(id: String): SiteIssue = http.get("$BUILDING/issues/$id")

    override suspend fun logIssue(request: SaveIssue): SiteIssue = http.post("$BUILDING/issues", request)

    override suspend fun editIssue(id: String, request: SaveIssue): SiteIssue = http.patch("$BUILDING/issues/$id", request)

    override suspend fun changes(projectId: String?, status: String?): List<ChangeOrder> =
        http.get("$BUILDING/changes", "projectId" to projectId, "status" to status)

    override suspend fun change(id: String): ChangeOrder = http.get("$BUILDING/changes/$id")

    override suspend fun setChangeStatus(id: String, request: ChangeStatus): ChangeOrder =
        http.post("$BUILDING/changes/$id/status", request)

    override suspend fun measurements(projectId: String?, status: String?): List<Measurement> =
        http.get("$BUILDING/measurements", "projectId" to projectId, "status" to status)

    override suspend fun measurement(id: String): Measurement = http.get("$BUILDING/measurements/$id")

    override suspend fun approveMeasurement(id: String): Measurement = http.post("$BUILDING/measurements/$id/approve")

    override suspend fun reopenMeasurement(id: String): Measurement = http.post("$BUILDING/measurements/$id/reopen")

    override suspend fun invoiceMeasurement(id: String, dueDate: String): MeasurementInvoiced =
        http.post("$BUILDING/measurements/$id/invoice", DueDate(dueDate))
}

class KtorBookingsApi(private val http: DashboardHttpClient) : BookingsApi {
    override suspend fun bookings(from: String?, to: String?, status: String?): List<Booking> =
        http.get("$APP/bookings", "from" to from, "to" to to, "status" to status)

    override suspend fun booking(id: String): Booking = http.get("$APP/bookings/$id")

    override suspend fun createBooking(request: CreateBooking): Booking = http.post("$APP/bookings", request)

    // The dashboard updates a booking with POST, not PATCH.
    override suspend fun updateBooking(id: String, request: UpdateBooking): Booking =
        http.post("$APP/bookings/$id", request)

    override suspend fun services(activeOnly: Boolean): List<BookingService> =
        http.get("$APP/bookings/services", "active" to if (activeOnly) "true" else null)

    override suspend fun availability(): List<AvailabilityRule> = http.get("$APP/bookings/availability")

    override suspend fun slots(serviceId: String, from: String, to: String): List<TimeSlot> =
        http.get("$APP/bookings/slots", "serviceId" to serviceId, "from" to from, "to" to to)
}

class KtorAgentsApi(private val http: DashboardHttpClient) : AgentsApi {
    override suspend fun overview(): AgentsOverview = http.get("$APP/agents/overview")

    override suspend fun agents(archived: Boolean): List<Agent> =
        http.get("$APP/agents", "archived" to if (archived) "1" else null)

    override suspend fun pauseAgent(id: String): Agent = http.post("$APP/agents/$id/pause")

    override suspend fun activateAgent(id: String): Agent = http.post("$APP/agents/$id/activate")

    override suspend fun approvals(status: String?): List<AgentApproval> =
        http.get("$APP/agents/approvals", "status" to status)

    override suspend fun approve(id: String): AgentApproval =
        http.post("$APP/agents/approvals/$id/approve", ApproveAction())

    override suspend fun reject(id: String, reason: String?): AgentApproval =
        http.post("$APP/agents/approvals/$id/reject", RejectAction(reason))

    override suspend fun tasks(status: String?, mine: Boolean): List<AgentTask> =
        http.get("$APP/agents/tasks", "status" to status, "mine" to if (mine) "1" else null)

    override suspend fun saveTask(request: SaveTask, id: String?): AgentTask =
        if (id == null) http.post("$APP/agents/tasks", request) else http.patch("$APP/agents/tasks/$id", request)

    override suspend fun runs(agentId: String?, limit: Int): List<AgentRun> =
        http.get("$APP/agents/runs", "agentId" to agentId, "limit" to limit.toString())
}

class KtorAssistantApi(private val http: DashboardHttpClient) : AssistantApi {
    override suspend fun threads(): List<AssistantThread> = http.get("$APP/assistant/threads")

    override suspend fun createThread(title: String): AssistantThread =
        http.post("$APP/assistant/threads", NewAssistantThread(title))

    override suspend fun thread(threadId: String): AssistantThreadDetail =
        http.get("$APP/assistant/threads/$threadId")

    override suspend fun sendMessage(threadId: String, content: String): AssistantThreadDetail =
        http.post("$APP/assistant/threads/$threadId/messages", AssistantPrompt(content))

    override suspend fun confirmAction(threadId: String, actionId: String): AssistantThreadDetail =
        http.post("$APP/assistant/threads/$threadId/actions/$actionId/confirm")

    override suspend fun cancelAction(threadId: String, actionId: String): AssistantThreadDetail =
        http.post("$APP/assistant/threads/$threadId/actions/$actionId/cancel")
}

class KtorPersonaApi(private val http: DashboardHttpClient) : PersonaApi {
    override suspend fun persona(): Persona = http.get("$APP/persona")

    override suspend fun updatePersona(compiledInstructions: String): Persona =
        http.put("$APP/persona", PersonaUpdate(compiledInstructions))

    override suspend fun addSource(content: String): Persona =
        http.post("$APP/persona/sources", PersonaSourceText(content))

    override suspend fun deleteSource(id: String) {
        http.send(HttpMethod.Delete, "$APP/persona/sources/$id")
    }

    override suspend fun rebuild(): Persona = http.post("$APP/persona/rebuild")

    override suspend fun test(request: PersonaTest): String =
        http.post<PersonaReply>("$APP/persona/test", request).reply
}

class KtorNotificationsApi(private val http: DashboardHttpClient) : NotificationsApi {
    override suspend fun notifications(): Notifications = http.get("$APP/notifications")

    override suspend fun markRead(id: String) {
        http.send(HttpMethod.Post, "$APP/notifications/$id/read")
    }

    override suspend fun markAllRead() {
        http.send(HttpMethod.Post, "$APP/notifications/read-all")
    }
}

class KtorTimeClockApi(private val http: DashboardHttpClient) : TimeClockApi {
    override suspend fun status(): TimeClockStatus = http.get("$APP/portal/time")

    override suspend fun shifts(): List<Shift> = http.get("$APP/portal/time/shifts")

    override suspend fun challenge(): TimeChallenge = http.post("$APP/portal/time/challenge")

    override suspend fun punch(request: PunchRequest): PunchResult = http.post("$APP/portal/time/punches", request)

    override suspend fun closeForgotten(shiftId: String, request: CloseShift): PunchResult =
        http.post("$APP/portal/time/shifts/$shiftId/close", request)

    override suspend fun enroll(request: EnrollDevice): TimeDevice = http.post("$APP/portal/time/devices", request)

    override suspend fun removeDevice(keyId: String) {
        http.send(HttpMethod.Delete, "$APP/portal/time/devices/$keyId")
    }
}

class KtorSettingsApi(private val http: DashboardHttpClient) : SettingsApi {
    override suspend fun webWidget(): WebWidget = http.get("$APP/web-widget")

    override suspend fun updateLocale(locale: String): String =
        http.post<LocaleChange>("$APP/settings/locale", LocaleChange(locale)).locale

    override suspend fun account(): Account = http.get("$APP/account")
}

/** Approve as the employee sent it; the team's changes go through the web's form. */
@Serializable
private data class ApproveSubmission(val changes: SubmitService? = null)

@Serializable
private data class ApprovedSubmission(val submission: ServiceSubmission)

@Serializable
private data class LoginRequest(val email: String, val password: String)

@Serializable
private data class ContactStatusRequest(val status: String)

@Serializable
private data class OutboundMessageRequest(val text: String, val assetExternalId: String? = null)

@Serializable
private data class AutoReplyRequest(val enabled: Boolean)

@Serializable
private data class TemplateSendRequest(
    val name: String,
    val language: String,
    val params: Map<String, String> = emptyMap(),
)

@Serializable
private data class StartConversationRequest(
    val phone: String,
    val name: String,
    val language: String,
    val params: Map<String, String> = emptyMap(),
)
