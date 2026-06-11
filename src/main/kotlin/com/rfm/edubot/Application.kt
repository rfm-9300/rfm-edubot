package com.rfm.edubot

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.admin.adminRoutes
import com.rfm.edubot.admin.authRoutes
import com.rfm.edubot.admin.backofficeRoutes
import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.admin.tenantAdminRoutes
import com.rfm.edubot.config.AppConfig
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
    val appConfig = AppConfig.load()
    val mongoModule = MongoModule(appConfig.mongo)
    mongoModule.initialize()

    val log = LoggerFactory.getLogger("Application")
    log.info("Starting WhatsApp AI Bot on port {}", appConfig.port)

    embeddedServer(Netty, port = appConfig.port, host = "0.0.0.0") {
        bootstrapModule(appConfig, mongoModule)
    }.start(wait = true)

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down...")
        mongoModule.shutdown()
    })
}

fun Application.module() {
    val appConfig = AppConfig.load()
    val mongoModule = MongoModule(appConfig.mongo)
    mongoModule.initialize()
    bootstrapModule(appConfig, mongoModule)
}

private fun Application.bootstrapModule(appConfig: AppConfig, mongoModule: MongoModule) {
    val tenantRepository = TenantRepository(mongoModule)
    val dashboardUserRepository = DashboardUserRepository(mongoModule)
    val defaultTenant = kotlinx.coroutines.runBlocking { TenantSeeder(mongoModule, tenantRepository, appConfig).run() }
    val tenantRegistry = TenantRegistry(tenantRepository)
    kotlinx.coroutines.runBlocking { tenantRegistry.initialize() }

    val deduplicationService = DeduplicationService(mongoModule)
    val aiClient = AiClient(
        apiKey = appConfig.openrouter.apiKey,
        primaryModel = appConfig.openrouter.primaryModel,
        fallbackModel = appConfig.openrouter.fallbackModel,
        maxTokens = appConfig.openrouter.maxTokens,
    )
    val whatsappHttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
        }
    }

    val messageQueue = MessageQueue()
    val pipelineFactory = TenantPipelineFactory(
        mongo = mongoModule,
        aiClient = aiClient,
        deduplicationService = deduplicationService,
        whatsappHttpClient = whatsappHttpClient,
        appConfig = appConfig,
    )

    val channelBindingService = ChannelBindingService(tenantRepository, tenantRegistry, pipelineFactory)
    val oauthState = OAuthState(secret = appConfig.admin.jwtSecret)
    val instagramOAuthClient = InstagramOAuthClient(appConfig.instagram, whatsappHttpClient)
    val whatsAppSignupClient = WhatsAppSignupClient(appConfig.whatsapp, whatsappHttpClient)

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
    configureAdminAuth(appConfig.admin)

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
            config = appConfig.whatsapp,
            instagramAppSecret = appConfig.instagram.appSecret,
            messageQueue = messageQueue,
            deduplicationService = deduplicationService,
            tenantRegistry = tenantRegistry,
        )
        authRoutes(appConfig.admin)
        backofficeRoutes()
        dashboardStaticRoutes()
        dashboardRoutes(
            mongo = mongoModule,
            tenantRepository = tenantRepository,
            dashboardUsers = dashboardUserRepository,
            pipelineFactory = pipelineFactory,
            personaCompiler = personaCompiler,
            aiClient = aiClient,
            appConfig = appConfig,
        )
        dashboardImpersonationRoute(
            tenantRepository = tenantRepository,
            dashboardUsers = dashboardUserRepository,
            appConfig = appConfig,
        )
        adminRoutes()
        tenantAdminRoutes(
            mongo = mongoModule,
            tenantRepository = tenantRepository,
            tenantRegistry = tenantRegistry,
            pipelineFactory = pipelineFactory,
            appConfig = appConfig,
        )
        instagramOAuthRoutes(
            config = appConfig.instagram,
            oauthState = oauthState,
            oauthClient = instagramOAuthClient,
            bindingService = channelBindingService,
            tenantRepository = tenantRepository,
        )
        whatsAppSignupRoutes(
            config = appConfig.whatsapp,
            signupClient = whatsAppSignupClient,
            bindingService = channelBindingService,
            tenantRepository = tenantRepository,
        )
        instagramMetaCallbacks(
            config = appConfig.instagram,
            tenantRegistry = tenantRegistry,
            bindingService = channelBindingService,
        )
        legalRoutes()
    }
}
