package com.rfm.edubot

import com.rfm.edubot.admin.AdminAccess
import com.rfm.edubot.admin.BackupControl
import com.rfm.edubot.admin.adminAccessRoutes
import com.rfm.edubot.admin.adminRoutes
import com.rfm.edubot.admin.authRoutes
import com.rfm.edubot.admin.backofficeRoutes
import com.rfm.edubot.admin.backupRoutes
import com.rfm.edubot.admin.board.BoardService
import com.rfm.edubot.admin.board.boardRoutes
import com.rfm.edubot.admin.platformSettingsRoutes
import com.rfm.edubot.admin.tenantAdminRoutes
import com.rfm.edubot.admin.vault.VaultService
import com.rfm.edubot.admin.vault.vaultRoutes
import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.agentAdminRoutes
import com.rfm.edubot.agents.agentRoutes
import com.rfm.edubot.agents.ai.AgentAssistant
import com.rfm.edubot.agents.runtime.AgentRuntime
import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.config.PlatformSettingsService
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.conversation.DeliveryStatusRecorder
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.dashboard.EmployeeLookup
import com.rfm.edubot.dashboard.building.changeApprovalRoutes
import com.rfm.edubot.dashboard.building.projectShareRoutes
import com.rfm.edubot.dashboard.dashboardAccountRoutes
import com.rfm.edubot.dashboard.dashboardCompanyRoutes
import com.rfm.edubot.dashboard.dashboardImpersonationRoute
import com.rfm.edubot.dashboard.dashboardRoutes
import com.rfm.edubot.dashboard.dashboardStaticRoutes
import com.rfm.edubot.dashboard.employeeWorkRoutes
import com.rfm.edubot.instagram.InstagramSocialService
import com.rfm.edubot.integrations.email.EmailInbox
import com.rfm.edubot.integrations.email.EmailInsightsService
import com.rfm.edubot.integrations.email.EmailService
import com.rfm.edubot.integrations.email.emailInboxRoutes
import com.rfm.edubot.integrations.email.emailRoutes
import com.rfm.edubot.integrations.google.GoogleIntegration
import com.rfm.edubot.integrations.integrationRoutes
import com.rfm.edubot.legal.legalRoutes
import com.rfm.edubot.messaging.DeduplicationService
import com.rfm.edubot.messaging.MessageQueue
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.notifications.notificationRoutes
import com.rfm.edubot.oauth.InstagramOAuthClient
import com.rfm.edubot.oauth.OAuthState
import com.rfm.edubot.oauth.instagramMetaCallbacks
import com.rfm.edubot.oauth.instagramOAuthRoutes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.persona.PersonaCompiler
import com.rfm.edubot.tenant.ChannelBindingService
import com.rfm.edubot.tenant.TenantPipelineFactory
import com.rfm.edubot.tenant.TenantRegistry
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.timesheets.timeClockRoutes
import com.rfm.edubot.timesheets.timesheetRoutes
import com.rfm.edubot.web.WebChannelRegistry
import com.rfm.edubot.web.webChatRoutes
import com.rfm.edubot.web.widgetRoutes
import com.rfm.edubot.webhook.webhookRoutes
import com.rfm.edubot.whatsapp.signup.WhatsAppSignupClient
import com.rfm.edubot.whatsapp.signup.whatsAppSignupRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.datetime.Clock

/** What the HTTP routes are built from. The application builds it at startup; tests build one with doubles. */
internal class AppServices(
    val mongo: MongoModule,
    val runtimeConfig: RuntimeConfig,
    val tenants: TenantRepository,
    val tenantRegistry: TenantRegistry,
    val dashboardUsers: DashboardUserRepository,
    val employeeLookup: EmployeeLookup,
    val pipelineFactory: TenantPipelineFactory,
    val channelBindings: ChannelBindingService,
    val personaCompiler: PersonaCompiler,
    val aiClient: AiClient,
    val messageQueue: MessageQueue,
    val deduplication: DeduplicationService,
    val instagramSocial: InstagramSocialService,
    val platformSettings: PlatformSettingsService,
    val adminAccess: AdminAccess,
    val backups: BackupControl,
    val notifications: NotificationRepository,
    val agents: AgentsModule,
    val agentRuntime: AgentRuntime,
    val google: GoogleIntegration,
    val email: EmailService,
    val oauthState: OAuthState,
    val instagramOAuth: InstagramOAuthClient,
    val whatsAppSignup: WhatsAppSignupClient,
    val webChannels: WebChannelRegistry,
)

/** Every route the server answers. */
internal fun Routing.installRoutes(s: AppServices) {
    get("/health") {
        call.respond(
            mapOf(
                "status" to "ok",
                "timestamp" to Clock.System.now().toString()
            )
        )
    }

    get("/ready") {
        try {
            s.mongo.client.getDatabase("admin").runCommand(org.bson.Document("ping", 1))
            call.respond(mapOf("status" to "ready"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "not ready", "error" to e.message))
        }
    }

    webhookRoutes(
        configProvider = { s.runtimeConfig.get().whatsapp },
        instagramAppSecretProvider = { s.runtimeConfig.get().instagram.appSecret },
        messageQueue = s.messageQueue,
        deduplicationService = s.deduplication,
        tenantRegistry = s.tenantRegistry,
        instagramSocial = s.instagramSocial,
        deliveryStatuses = DeliveryStatusRecorder(s.mongo),
    )
    authRoutes(s.runtimeConfig)
    platformSettingsRoutes(s.platformSettings)
    adminAccessRoutes(s.adminAccess, s.runtimeConfig)
    backupRoutes(s.backups)
    boardRoutes(BoardService.of(s.mongo, s.tenants))
    vaultRoutes(VaultService.of(s.mongo))
    backofficeRoutes()
    dashboardStaticRoutes()
    projectShareRoutes(s.mongo, s.tenants) { s.runtimeConfig.get().pdfStoragePath }
    changeApprovalRoutes(s.mongo, s.tenants)
    dashboardRoutes(
        mongo = s.mongo,
        tenantRepository = s.tenants,
        dashboardUsers = s.dashboardUsers,
        pipelineFactory = s.pipelineFactory,
        personaCompiler = s.personaCompiler,
        aiClient = s.aiClient,
        runtimeConfig = s.runtimeConfig,
        channelBindingService = s.channelBindings,
        instagramSocial = s.instagramSocial,
        assistantExtension = AgentAssistant(s.agents, s.agentRuntime),
    )
    dashboardImpersonationRoute(
        tenantRepository = s.tenants,
        dashboardUsers = s.dashboardUsers,
        runtimeConfig = s.runtimeConfig,
    )
    dashboardAccountRoutes(
        tenantRepository = s.tenants,
        dashboardUsers = s.dashboardUsers,
        runtimeConfig = s.runtimeConfig,
        employees = s.employeeLookup,
    )
    employeeWorkRoutes(s.mongo, s.dashboardUsers, s.notifications)
    timeClockRoutes(s.mongo, s.notifications)
    timesheetRoutes(s.mongo)
    dashboardCompanyRoutes(
        tenantRepository = s.tenants,
        runtimeConfig = s.runtimeConfig,
    )
    agentRoutes(s.agents, s.agentRuntime, s.tenants)
    agentAdminRoutes(s.agents, s.agentRuntime, s.tenants)
    notificationRoutes(s.notifications)
    adminRoutes()
    tenantAdminRoutes(
        mongo = s.mongo,
        tenantRepository = s.tenants,
        tenantRegistry = s.tenantRegistry,
        pipelineFactory = s.pipelineFactory,
        runtimeConfig = s.runtimeConfig,
    )
    instagramOAuthRoutes(
        configProvider = { s.runtimeConfig.get().instagram },
        oauthState = s.oauthState,
        oauthClient = s.instagramOAuth,
        bindingService = s.channelBindings,
        tenantRepository = s.tenants,
    )
    integrationRoutes(
        google = s.google,
        email = s.email,
        oauthState = s.oauthState,
        tenants = s.tenants,
        users = s.dashboardUsers,
    )
    emailRoutes(s.email, s.mongo)
    emailInboxRoutes(EmailInbox(s.mongo, s.email, s.google, EmailInsightsService(s.aiClient, s.mongo)))
    whatsAppSignupRoutes(
        configProvider = { s.runtimeConfig.get().whatsapp },
        signupClient = s.whatsAppSignup,
        bindingService = s.channelBindings,
        tenantRepository = s.tenants,
    )
    instagramMetaCallbacks(
        configProvider = { s.runtimeConfig.get().instagram },
        tenantRegistry = s.tenantRegistry,
        bindingService = s.channelBindings,
    )
    legalRoutes()
    widgetRoutes()
    webChatRoutes(
        messageQueue = s.messageQueue,
        tenantRegistry = s.tenantRegistry,
        webChannelRegistry = s.webChannels,
    )
}
