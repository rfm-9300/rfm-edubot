package com.rfm.edubot

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.messaging.DeduplicationService
import com.rfm.edubot.messaging.MessagePipeline
import com.rfm.edubot.messaging.MessageQueue
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureMonitoring
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.plugins.configureStatusPages
import com.rfm.edubot.ratelimit.RateLimiter
import com.rfm.edubot.webhook.webhookRoutes
import com.rfm.edubot.whatsapp.WhatsAppClient
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
    bootstrapModule(appConfig, mongoModule)
}

private fun Application.bootstrapModule(appConfig: AppConfig, mongoModule: MongoModule) {
    val userRepository = UserRepository(mongoModule)
    val conversationRepository = ConversationRepository(mongoModule)
    val messageRepository = MessageRepository(mongoModule)
    val deduplicationService = DeduplicationService(mongoModule)
    val rateLimiter = RateLimiter(
        perHour = appConfig.rateLimit.perUserPerHour,
        perDay = appConfig.rateLimit.perUserPerDay,
    )
    val aiClient = AiClient(
        apiKey = appConfig.openrouter.apiKey,
        primaryModel = appConfig.openrouter.primaryModel,
        fallbackModel = appConfig.openrouter.fallbackModel,
        maxTokens = appConfig.openrouter.maxTokens,
    )
    val whatsappClient = WhatsAppClient(
        accessToken = appConfig.whatsapp.accessToken,
        phoneNumberId = appConfig.whatsapp.phoneNumberId,
        apiVersion = appConfig.whatsapp.apiVersion,
    )

    val messageQueue = MessageQueue()
    val messagePipeline = MessagePipeline(
        users = userRepository,
        conversations = conversationRepository,
        messages = messageRepository,
        rateLimiter = rateLimiter,
        aiClient = aiClient,
        whatsappClient = whatsappClient,
        deduplicationService = deduplicationService,
    )

    val pipelineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    pipelineScope.launch {
        for (inbound in messageQueue.receiveChannel()) {
            try {
                messagePipeline.handle(inbound)
            } catch (e: Exception) {
                LoggerFactory.getLogger("PipelineConsumer").error(
                    "Pipeline failed for waId={}: {}",
                    inbound.waId,
                    e.message,
                    e
                )
            }
        }
    }

    configureMonitoring()
    configureSerialization()
    configureStatusPages()

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
            messageQueue = messageQueue,
            deduplicationService = deduplicationService,
        )
    }
}
