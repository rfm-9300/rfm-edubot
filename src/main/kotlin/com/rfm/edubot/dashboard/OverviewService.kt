package com.rfm.edubot.dashboard

import com.mongodb.client.model.Filters
import com.rfm.edubot.dashboard.overview.AgentsOverview
import com.rfm.edubot.dashboard.overview.AttentionOverview
import com.rfm.edubot.dashboard.overview.CalendarOverview
import com.rfm.edubot.dashboard.overview.DirectoryOverview
import com.rfm.edubot.dashboard.overview.InboxOverview
import com.rfm.edubot.dashboard.overview.MoneyOverview
import com.rfm.edubot.dashboard.overview.ProjectsOverview
import com.rfm.edubot.dashboard.overview.RecentOverview
import com.rfm.edubot.dashboard.overview.SalesOverview
import com.rfm.edubot.integrations.IntegrationConnectionRepository
import com.rfm.edubot.integrations.email.EmailMessageRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.persona.PersonaRepository
import com.rfm.edubot.persona.PersonaStatus
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import java.util.Date
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.bson.Document

/** Home's figures for one company. Each card's queries live in `dashboard/overview`; [build] runs them together. */
class OverviewService(private val mongo: MongoModule) {
    private val moneyCard = MoneyOverview(mongo)
    private val salesCards = SalesOverview(mongo)
    private val directoryCards = DirectoryOverview(mongo)
    private val inboxCards = InboxOverview(mongo)
    private val calendarCard = CalendarOverview(mongo)
    private val agentsCard = AgentsOverview(mongo)
    private val projectsCard = ProjectsOverview(mongo)
    private val attentionList = AttentionOverview(mongo)
    private val recentFeed = RecentOverview(mongo)

    /**
     * [extended] adds the trend/agenda/feed blocks the minimal Home layout draws. They cost a few
     * extra queries, so the classic layout never asks for them. Blocks for hidden cards are skipped.
     * [assistantOwnerKey] counts only that person's assistant changes waiting for a decision: no one else can see them.
     */
    suspend fun build(tenant: Tenant, extended: Boolean = false, assistantOwnerKey: String? = null): OverviewDto = coroutineScope {
        val modules = DashboardModules.effectiveFor(tenant).toSet()
        val window = OverviewMath.window(SystemClock.now(), TenantTimeZones.normalize(tenant.timezone))
        val tenantFilter = Filters.eq("tenantId", tenant.id)

        val usersCount = async { coll("users").countDocuments(tenantFilter) }
        val conversationsCount = async { coll("conversations").countDocuments(tenantFilter) }
        val messagesCount = async { coll("messages").countDocuments(tenantFilter) }
        val messagesTodayCount = async {
            coll("messages").countDocuments(Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.todayStart.toEpochMilliseconds()))))
        }
        val quotesCount = async { coll("crm.quotes").countDocuments(tenantFilter) }
        val invoicesCount = async { coll("crm.invoices").countDocuments(tenantFilter) }

        val cash = async { if (DashboardModules.INVOICES in modules) moneyCard.cash(tenant.id, window) else null }
        val pipeline = async { if (DashboardModules.QUOTES in modules) salesCards.pipeline(tenant.id, window) else null }
        val customers = async { if (DashboardModules.CLIENTS in modules) directoryCards.customers(tenant.id, window) else null }
        val waitingList = async {
            if (DashboardModules.CONVERSATIONS in modules) inboxCards.waitingConversations(tenant.id) else emptyList()
        }
        val inbox = async {
            if (DashboardModules.CONVERSATIONS in modules || DashboardModules.CONTACTS in modules) {
                inboxCards.inbox(tenant.id, window, conversationsCount.await(), usersCount.await(), messagesTodayCount.await(), waitingList.await().size)
            } else {
                null
            }
        }
        val calendar = async { if (DashboardModules.BOOKINGS in modules) calendarCard.calendar(tenant.id, window) else null }
        val social = async { if (DashboardModules.INSTAGRAM in modules) inboxCards.social(tenant) else null }
        val email = async { if (DashboardModules.EMAIL in modules) OverviewEmailDto(unread = EmailMessageRepository(mongo).unreadThreads(tenant.id)) else null }
        val catalog = async {
            if (DashboardModules.CATALOG in modules) OverviewCatalogDto(items = coll("crm.standard_items").countDocuments(tenantFilter)) else null
        }
        val services = async { if (DashboardModules.SERVICES in modules) salesCards.services(tenant.id, window) else null }
        val suppliers = async { if (DashboardModules.SUPPLIERS in modules) directoryCards.suppliers(tenant.id, window) else null }
        val employees = async {
            if (DashboardModules.EMPLOYEES in modules) directoryCards.employees(tenant.id, window, submissions = DashboardModules.SERVICES in modules) else null
        }
        val payments = async { if (DashboardModules.PAYMENTS in modules) moneyCard.payments(tenant.id, window) else null }
        val assistant = async {
            if (DashboardModules.AI_ASSISTANT in modules) {
                val owner = assistantOwnerKey?.let { Filters.eq("ownerKey", it) }
                OverviewAssistantDto(
                    pendingActions = coll("dashboard_assistant_messages").countDocuments(
                        Filters.and(listOfNotNull(tenantFilter, owner, Filters.eq("action.status", "PENDING"))),
                    ).toInt(),
                )
            } else {
                null
            }
        }
        val agents = async { if (DashboardModules.AGENTS in modules) agentsCard.agents(tenant.id, window) else null }
        val projects = async { if (DashboardModules.PROJECTS in modules) projectsCard.projects(tenant.id, window, modules) else null }
        val reconnects = async {
            if (DashboardModules.SETTINGS in modules) IntegrationConnectionRepository(mongo).needingReconnect(tenant.id) else emptyList()
        }
        val personaEmpty = async {
            if (DashboardModules.PERSONA in modules) {
                val persona = PersonaRepository(mongo).findByTenant(tenant.id)
                persona == null || persona.status == PersonaStatus.EMPTY || persona.compiledInstructions.isBlank()
            } else {
                false
            }
        }

        val hidden = OverviewHomeLayout.sanitize(tenant.overviewHiddenCards).toSet()
        fun wants(card: String) = extended && card !in hidden
        val cashFlow = async {
            val money = DashboardModules.INVOICES in modules || DashboardModules.PAYMENTS in modules
            if (money && wants(OverviewHomeLayout.FINANCEIRO)) moneyCard.cashFlow(tenant.id, window, modules) else emptyList()
        }
        val activity = async {
            val inboxOn = DashboardModules.CONVERSATIONS in modules || DashboardModules.CONTACTS in modules
            if (inboxOn && wants(OverviewHomeLayout.INBOX)) inboxCards.activity(tenant.id, window) else emptyList()
        }
        val agenda = async {
            if (DashboardModules.BOOKINGS in modules && wants(OverviewHomeLayout.CALENDAR)) calendarCard.agenda(tenant.id, window) else emptyList()
        }
        val topClients = async {
            val billing = DashboardModules.INVOICES in modules && DashboardModules.CLIENTS in modules
            if (billing && wants(OverviewHomeLayout.CUSTOMERS)) moneyCard.topClients(tenant.id, window) else emptyList()
        }
        val recent = async { if (extended) recentFeed.recent(tenant.id, modules, hidden) else emptyList() }

        val cashDto = cash.await()
        val pipelineDto = pipeline.await()
        val customersDto = customers.await()
        val inboxDto = inbox.await()
        val calendarDto = calendar.await()
        val socialDto = social.await()
        val catalogDto = catalog.await()
        val servicesDto = services.await()
        val suppliersDto = suppliers.await()
        val employeesDto = employees.await()
        val paymentsDto = payments.await()
        val assistantDto = assistant.await()
        val agentsBlock = agents.await()
        val projectsBlock = projects.await()

        val waiting = waitingList.await()
        val reconnectList = reconnects.await()
        val attention = attentionList.attentionItems(
            tenant.id, window, modules, cashDto, pipelineDto, inboxDto, calendarDto, socialDto, assistantDto, paymentsDto, agentsBlock, waiting, reconnectList, employeesDto,
            projectsBlock?.attention.orEmpty(),
        )
        val setup = OverviewMath.setupItems(
            modules = modules,
            hasWhatsApp = tenant.binding(Platform.WHATSAPP) != null,
            hasInstagram = tenant.binding(Platform.INSTAGRAM) != null,
            hasWidget = tenant.binding(Platform.WEB)?.externalId?.isNotBlank() == true,
            personaEmpty = personaEmpty.await(),
        )
        val health = OverviewMath.health(
            overdueCount = (cashDto?.overdueCount ?: 0) + (paymentsDto?.overdueCount ?: 0),
            waiting = inboxDto?.waiting ?: 0,
            pendingBookings = calendarDto?.pending ?: 0,
            unreplied = socialDto?.unreplied ?: 0,
            dueSoonCount = (cashDto?.dueSoonCount ?: 0) + (paymentsDto?.dueSoonCount ?: 0),
            expiringQuotes = pipelineDto?.expiringSoonCount ?: 0,
            pendingAssistant = assistantDto?.pendingActions ?: 0,
            agentAttention = agentsBlock?.let { (it.dto.pendingApprovals + it.dto.tasksDue).toInt() + it.failed.size } ?: 0,
            reconnects = reconnectList.size,
            pendingSubmissions = (employeesDto?.pendingSubmissions ?: 0).toInt(),
            buildingAttention = projectsBlock?.attention?.size ?: 0,
        )
        OverviewHomeLayout.apply(
            OverviewDto(
                users = usersCount.await(),
                conversations = conversationsCount.await(),
                messages = messagesCount.await(),
                messagesToday = messagesTodayCount.await(),
                quotes = quotesCount.await(),
                invoices = invoicesCount.await(),
                instagramUnreplied = (socialDto?.unreplied ?: 0).toLong(),
                generatedAt = window.now.toString(),
                timezone = window.zone.id,
                today = window.today.toString(),
                attentionCount = attention.size,
                health = health,
                highlights = OverviewMath.pickHighlights(modules, cashDto, pipelineDto, inboxDto, calendarDto, customersDto, socialDto),
                attention = attention,
                cash = cashDto,
                pipeline = pipelineDto,
                customers = customersDto,
                inbox = inboxDto,
                calendar = calendarDto,
                social = socialDto,
                email = email.await(),
                catalog = catalogDto,
                services = servicesDto,
                suppliers = suppliersDto,
                employees = employeesDto,
                payments = paymentsDto,
                assistant = assistantDto,
                agents = agentsBlock?.dto,
                projects = projectsBlock?.dto,
                setup = setup,
                cashFlow = cashFlow.await(),
                activity = activity.await(),
                agenda = agenda.await(),
                recent = recent.await(),
                topClients = topClients.await(),
            ),
            tenant.overviewHiddenCards,
        )
    }


    private fun coll(name: String) = mongo.database.getCollection<Document>(name)
}
