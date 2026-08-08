package com.rfm.edubot

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.admin.adminRoutes
import com.rfm.edubot.admin.authRoutes
import com.rfm.edubot.admin.backofficeRoutes
import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.admin.platformSettingsRoutes
import com.rfm.edubot.admin.tenantAdminRoutes
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.PlatformSettingsRepository
import com.rfm.edubot.config.PlatformSettingsService
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.dashboard.dashboardImpersonationRoute
import com.rfm.edubot.dashboard.dashboardRoutes
import com.rfm.edubot.dashboard.dashboardStaticRoutes
import com.rfm.edubot.messaging.DeduplicationService
import com.rfm.edubot.messaging.MessageQueue
import com.rfm.edubot.legal.legalRoutes
import com.rfm.edubot.oauth.InstagramOAuthClient
import com.rfm.edubot.oauth.OAuthState
import com.rfm.edubot.oauth.instagramMetaCallbacks
import com.rfm.edubot.oauth.instagramOAuthRoutes
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
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.slf4j.LoggerFactory

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

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down...")
        mongoModule.shutdown()
    })
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

private fun Application.bootstrapModule(runtimeConfig: RuntimeConfig, mongoModule: MongoModule) {
    val appConfig = runtimeConfig.get()
    val tenantRepository = TenantRepository(mongoModule)
    val dashboardUserRepository = DashboardUserRepository(mongoModule)
    val defaultTenant = kotlinx.coroutines.runBlocking { TenantSeeder(mongoModule, tenantRepository, appConfig).run() }
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
    val whatsAppSignupClient = WhatsAppSignupClient({ runtimeConfig.get().whatsapp }, whatsappHttpClient)

    val pipelineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val personaCompiler = PersonaCompiler(
        repository = PersonaRepository(mongoModule),
        aiClient = aiClient,
        scope = pipelineScope,
        onCompiled = { tenantId -> pipelineFactory.evict(tenantId) },
    )

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
            launch {
                try {
                    pipeline.handle(inbound, responder)
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

    configureMonitoring()
    configureSerialization()
    configureStatusPages()
    configureWebSockets()
    configureAdminAuth(runtimeConfig)

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
        )
        authRoutes(runtimeConfig)
        platformSettingsRoutes(platformSettingsService)
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
        )
        dashboardImpersonationRoute(
            tenantRepository = tenantRepository,
            dashboardUsers = dashboardUserRepository,
            runtimeConfig = runtimeConfig,
        )
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
