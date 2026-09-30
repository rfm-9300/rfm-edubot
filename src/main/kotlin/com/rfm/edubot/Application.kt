package com.rfm.edubot

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.admin.AdminAccess
import com.rfm.edubot.admin.AdminEmailRepository
import com.rfm.edubot.admin.BackupControl
import com.rfm.edubot.admin.adminAccessRoutes
import com.rfm.edubot.admin.adminRoutes
import com.rfm.edubot.admin.authRoutes
import com.rfm.edubot.admin.backupRoutes
import com.rfm.edubot.admin.backofficeRoutes
import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.admin.platformSettingsRoutes
import com.rfm.edubot.admin.tenantAdminRoutes
import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.actions.AgentActions
import com.rfm.edubot.agents.agentAdminRoutes
import com.rfm.edubot.agents.agentRoutes
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.notifications.notificationRoutes
import com.rfm.edubot.agents.registry.AgentRegistry
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.agents.runtime.AgentRuntime
import com.rfm.edubot.agents.runtime.AgentRuntimeConfig
import com.rfm.edubot.agents.runtime.AgentServices
import com.rfm.edubot.agents.store.AgentSettingsRepository
import com.rfm.edubot.agents.store.OutboundLogRepository
import com.rfm.edubot.bookings.BookingCatalogMigration
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.PlatformSettingsRepository
import com.rfm.edubot.config.PlatformSettingsService
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.conversation.DeliveryStatusRecorder
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.dashboard.dashboardAccountRoutes
import com.rfm.edubot.dashboard.dashboardCompanyRoutes
import com.rfm.edubot.dashboard.dashboardImpersonationRoute
import com.rfm.edubot.dashboard.dashboardRoutes
import com.rfm.edubot.dashboard.dashboardStaticRoutes
import com.rfm.edubot.events.Actor
import com.rfm.edubot.events.ActorContext
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.integrations.TokenCipher
import com.rfm.edubot.integrations.email.EmailMessageRepository
import com.rfm.edubot.integrations.email.EmailService
import com.rfm.edubot.integrations.google.GoogleIntegration
import com.rfm.edubot.integrations.integrationRoutes
import com.rfm.edubot.messaging.ConversationLanes
import com.rfm.edubot.messaging.DeduplicationService
import com.rfm.edubot.messaging.MessageQueue
import com.rfm.edubot.legal.legalRoutes
import com.rfm.edubot.oauth.InstagramOAuthClient
import com.rfm.edubot.oauth.OAuthState
import com.rfm.edubot.oauth.instagramMetaCallbacks
import com.rfm.edubot.oauth.instagramOAuthRoutes
import com.rfm.edubot.instagram.InstagramSocialService
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.persona.PersonaCompiler
import com.rfm.edubot.persona.PersonaRepository
import com.rfm.edubot.tenant.ChannelBindingService
import com.rfm.edubot.plugins.configureMonitoring
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.plugins.configureStatusPages
import com.rfm.edubot.plugins.configureWebSockets
import com.rfm.edubot.web.WebChannelRegistry
import com.rfm.edubot.web.webChatRoutes
import com.rfm.edubot.web.widgetRoutes
import com.rfm.edubot.tenant.TenantPipelineFactory
import com.rfm.edubot.tenant.TenantRegistry
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.TenantSeeder
import com.rfm.edubot.shared.jobs.SchedulerLease
import com.rfm.edubot.webhook.webhookRoutes
import com.rfm.edubot.whatsapp.signup.WhatsAppSignupClient
import com.rfm.edubot.whatsapp.signup.whatsAppSignupRoutes
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    val baseConfig = AppConfig.load()
    val mongoModule = MongoModule(baseConfig.mongo)
    mongoModule.initialize()
    val runtimeConfig = RuntimeConfig(baseConfig)
    kotlinx.coroutines.runBlocking {
        PlatformSettingsService(PlatformSettingsRepository(mongoModule), runtimeConfig).initialize()
    }

    val log = LoggerFactory.getLogger("Application")
    log.info("Starting WhatsApp AI Bot on port {}", runtimeConfig.get().port)

    embeddedServer(Netty, port = runtimeConfig.get().port, host = "0.0.0.0") {
        bootstrapModule(runtimeConfig, mongoModule)
    }.start(wait = true)
}

fun Application.module() {
    val baseConfig = AppConfig.load()
    val mongoModule = MongoModule(baseConfig.mongo)
    mongoModule.initialize()
    val runtimeConfig = RuntimeConfig(baseConfig)
    kotlinx.coroutines.runBlocking {
        PlatformSettingsService(PlatformSettingsRepository(mongoModule), runtimeConfig).initialize()
    }
    bootstrapModule(runtimeConfig, mongoModule)
}

/**
 * How far back startup looks for messages a previous run accepted but never finished. Older ones are
 * left alone so a restart never answers a customer's long-stale message.
 */
private val INBOUND_REPLAY_WINDOW = 30.minutes

private fun Application.bootstrapModule(runtimeConfig: RuntimeConfig, mongoModule: MongoModule) {
    val bootedAt = Clock.System.now()
    val appConfig = runtimeConfig.get()
    val tenantRepository = TenantRepository(mongoModule)
    val dashboardUserRepository = DashboardUserRepository(mongoModule)
    val defaultTenant = kotlinx.coroutines.runBlocking { TenantSeeder(mongoModule, tenantRepository, appConfig).run() }
    kotlinx.coroutines.runBlocking {
        try {
            BookingCatalogMigration(mongoModule).run()
        } catch (e: Exception) {
            LoggerFactory.getLogger("Application").warn("Booking catalog migration failed; bookings keep reading legacy services: {}", e.message)
        }
    }
    val tenantRegistry = TenantRegistry(tenantRepository)
    kotlinx.coroutines.runBlocking { tenantRegistry.initialize() }

    val platformSettingsService = PlatformSettingsService(PlatformSettingsRepository(mongoModule), runtimeConfig)

    val deduplicationService = DeduplicationService(mongoModule)
    val aiClient = AiClient(openRouter = { runtimeConfig.get().openrouter })
    val whatsappHttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
        }
    }

    val messageQueue = MessageQueue()
    val webChannelRegistry = WebChannelRegistry()
    val pipelineFactory = TenantPipelineFactory(
        mongo = mongoModule,
        aiClient = aiClient,
        deduplicationService = deduplicationService,
        whatsappHttpClient = whatsappHttpClient,
        runtimeConfig = runtimeConfig,
        webChannelRegistry = webChannelRegistry,
    )

    val channelBindingService = ChannelBindingService(tenantRepository, tenantRegistry, pipelineFactory)
    val oauthState = OAuthState(secretProvider = { runtimeConfig.get().admin.jwtSecret })
    val instagramOAuthClient = InstagramOAuthClient({ runtimeConfig.get().instagram }, whatsappHttpClient)
    val instagramSocial = InstagramSocialService(mongoModule, whatsappHttpClient) { runtimeConfig.get().instagram.graphVersion }
    val whatsAppSignupClient = WhatsAppSignupClient({ runtimeConfig.get().whatsapp }, whatsappHttpClient)

    val pipelineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val personaCompiler = PersonaCompiler(
        repository = PersonaRepository(mongoModule),
        aiClient = aiClient,
        scope = pipelineScope,
        onCompiled = { tenantId -> pipelineFactory.evict(tenantId) },
    )

    val tokenCipher = TokenCipher.fromConfig(appConfig.integrations.encryptionKey)
    if (tokenCipher == null && appConfig.integrations.encryptionKey.isNotBlank()) {
        LoggerFactory.getLogger("Application").error("INTEGRATIONS_ENCRYPTION_KEY must be base64 32-byte keys separated by commas; Google accounts can't be connected")
    } else if (tokenCipher == null && appConfig.google.oauthEnabled) {
        LoggerFactory.getLogger("Application").warn("Google OAuth is set but INTEGRATIONS_ENCRYPTION_KEY isn't; Google accounts can't be connected")
    }
    val google = GoogleIntegration.create(
        mongo = mongoModule,
        configProvider = { runtimeConfig.get().google },
        cipher = tokenCipher,
        httpClient = whatsappHttpClient,
        notifications = NotificationRepository(mongoModule),
    )
    val emailService = EmailService(
        google = google,
        messages = EmailMessageRepository(mongoModule),
        outboundLog = OutboundLogRepository(mongoModule),
        events = DomainEventLog(mongoModule),
        agentSettings = AgentSettingsRepository(mongoModule),
    )

    val agentServices = AgentServices(
        mongo = mongoModule,
        aiClient = aiClient,
        outbound = { tenant, platform -> runCatching { pipelineFactory.responderFor(tenant, platform) }.getOrNull() },
        whatsApp = { pipelineFactory.whatsAppFor(it) },
        instagramSocial = instagramSocial,
        email = emailService,
        pdfStoragePath = { runtimeConfig.get().pdfStoragePath },
    )
    val agentsModule = AgentsModule(
        mongoModule,
        AgentRegistry(AgentActions.builtIn, TriggerTypes.all),
        agentServices,
        emailAvailability = { emailService.availability(it) },
    )
    val agentRuntime = AgentRuntime(
        module = agentsModule,
        tenants = { id -> tenantRepository.findById(id) },
        scope = pipelineScope,
        lease = SchedulerLease(mongoModule),
        config = AgentRuntimeConfig(
            tick = appConfig.agents.tickSeconds.seconds,
            lanes = appConfig.agents.lanes,
            maxConcurrentPerCompany = appConfig.agents.maxConcurrentRunsPerCompany,
        ),
    )
    agentRuntime.start()

    val conversationLanes = ConversationLanes(pipelineScope)
    pipelineScope.launch {
        for (inbound in messageQueue.receiveChannel()) {
            val tenant = tenantRegistry.byExternalId(inbound.platform, inbound.channelExternalId)
            if (tenant == null) {
                LoggerFactory.getLogger("PipelineConsumer").warn("Skipping queued message for unknown binding platform={} externalId={}", inbound.platform, inbound.channelExternalId)
                continue
            }
            val responder = try {
                pipelineFactory.responderFor(tenant, inbound.platform)
            } catch (e: Exception) {
                LoggerFactory.getLogger("PipelineConsumer").warn("Skipping queued message without responder: tenant={} platform={} error={}", tenant.slug, inbound.platform, e.message)
                continue
            }
            val pipeline = pipelineFactory.getOrCreate(tenant)
            conversationLanes.submit("${tenant.id}:${inbound.platform}:${inbound.waId}") {
                try {
                    withContext(ActorContext(Actor.bot(inbound.platform.name))) { pipeline.handle(inbound, responder) }
                } catch (e: Exception) {
                    LoggerFactory.getLogger("PipelineConsumer").error(
                        "Pipeline failed for tenant={} waId={}: {}",
                        tenant.slug,
                        inbound.waId,
                        e.message,
                        e
                    )
                }
            }
        }
    }
    // Runs before routing is installed so re-queued messages stay ahead of new ones from the same customer.
    kotlinx.coroutines.runBlocking {
        val log = LoggerFactory.getLogger("PipelineConsumer")
        try {
            val unfinished = deduplicationService.unprocessedInbound(bootedAt - INBOUND_REPLAY_WINDOW, bootedAt)
            if (unfinished.isNotEmpty()) log.warn("Re-queuing {} inbound messages a previous run accepted but never finished", unfinished.size)
            unfinished.forEach { messageQueue.enqueue(it) }
        } catch (e: Exception) {
            log.error("Could not re-queue unfinished inbound messages: {}", e.message, e)
        }
    }
    // Background work stops before Mongo closes; unfinished messages and agent runs resume at the next boot.
    monitor.subscribe(ApplicationStopping) {
        pipelineScope.cancel()
    }
    monitor.subscribe(ApplicationStopped) {
        LoggerFactory.getLogger("Application").info("Shutting down...")
        mongoModule.shutdown()
    }

    configureMonitoring()
    configureSerialization()
    configureStatusPages()
    configureWebSockets()
    val adminAccess = AdminAccess(AdminEmailRepository(mongoModule), runtimeConfig)
    kotlinx.coroutines.runBlocking { adminAccess.initialize() }
    val backupControl = BackupControl(appConfig.backups.archiveDir, appConfig.backups.controlDir)
    configureAdminAuth(runtimeConfig, tenantRepository, dashboardUserRepository)

    routing {
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
                mongoModule.client.getDatabase("admin").runCommand(org.bson.Document("ping", 1))
                call.respond(mapOf("status" to "ready"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "not ready", "error" to e.message))
            }
        }

        webhookRoutes(
            configProvider = { runtimeConfig.get().whatsapp },
            instagramAppSecretProvider = { runtimeConfig.get().instagram.appSecret },
            messageQueue = messageQueue,
            deduplicationService = deduplicationService,
            tenantRegistry = tenantRegistry,
            instagramSocial = instagramSocial,
            deliveryStatuses = DeliveryStatusRecorder(mongoModule),
        )
        authRoutes(runtimeConfig)
        platformSettingsRoutes(platformSettingsService)
        adminAccessRoutes(adminAccess, runtimeConfig)
        backupRoutes(backupControl)
        backofficeRoutes()
        dashboardStaticRoutes()
        dashboardRoutes(
            mongo = mongoModule,
            tenantRepository = tenantRepository,
            dashboardUsers = dashboardUserRepository,
            pipelineFactory = pipelineFactory,
            personaCompiler = personaCompiler,
            aiClient = aiClient,
            runtimeConfig = runtimeConfig,
            channelBindingService = channelBindingService,
            instagramSocial = instagramSocial,
        )
        dashboardImpersonationRoute(
            tenantRepository = tenantRepository,
            dashboardUsers = dashboardUserRepository,
            runtimeConfig = runtimeConfig,
        )
        dashboardAccountRoutes(
            tenantRepository = tenantRepository,
            dashboardUsers = dashboardUserRepository,
            runtimeConfig = runtimeConfig,
        )
        dashboardCompanyRoutes(
            tenantRepository = tenantRepository,
            runtimeConfig = runtimeConfig,
        )
        agentRoutes(agentsModule, agentRuntime, tenantRepository)
        agentAdminRoutes(agentsModule, agentRuntime, tenantRepository)
        notificationRoutes(agentServices.notifications)
        adminRoutes()
        tenantAdminRoutes(
            mongo = mongoModule,
            tenantRepository = tenantRepository,
            tenantRegistry = tenantRegistry,
            pipelineFactory = pipelineFactory,
            runtimeConfig = runtimeConfig,
        )
        instagramOAuthRoutes(
            configProvider = { runtimeConfig.get().instagram },
            oauthState = oauthState,
            oauthClient = instagramOAuthClient,
            bindingService = channelBindingService,
            tenantRepository = tenantRepository,
        )
        integrationRoutes(
            google = google,
            oauthState = oauthState,
            tenants = tenantRepository,
            users = dashboardUserRepository,
        )
        whatsAppSignupRoutes(
            configProvider = { runtimeConfig.get().whatsapp },
            signupClient = whatsAppSignupClient,
            bindingService = channelBindingService,
            tenantRepository = tenantRepository,
        )
        instagramMetaCallbacks(
            configProvider = { runtimeConfig.get().instagram },
            tenantRegistry = tenantRegistry,
            bindingService = channelBindingService,
        )
        legalRoutes()
        widgetRoutes()
        webChatRoutes(
            messageQueue = messageQueue,
            tenantRegistry = tenantRegistry,
            webChannelRegistry = webChannelRegistry,
        )
    }
}
