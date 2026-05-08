package com.rfm.edubot.webhook

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.messaging.DeduplicationService
import com.rfm.edubot.messaging.InboundMessage
import com.rfm.edubot.messaging.MessageQueue
import com.rfm.edubot.webhook.dto.WebhookPayload
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("WebhookRoutes")

private val json = Json { ignoreUnknownKeys = true }

fun Routing.webhookRoutes(
    config: AppConfig.WhatsAppConfig,
    messageQueue: MessageQueue,
    deduplicationService: DeduplicationService,
) {
    route("/webhook") {
        get {
            val mode = call.request.queryParameters["hub.mode"]
            val token = call.request.queryParameters["hub.verify_token"]
            val challenge = call.request.queryParameters["hub.challenge"]

            if (mode == "subscribe" && token == config.verifyToken) {
                log.info("Webhook verification successful")
                call.respondText(challenge.orEmpty())
            } else {
                log.warn("Webhook verification failed: mode={}, token={}", mode, token)
                call.respond(HttpStatusCode.Forbidden, "Verification failed")
            }
        }

        post {
            val signature = call.request.headers["X-Hub-Signature-256"]
                ?.removePrefix("sha256=")

            if (signature.isNullOrBlank()) {
                log.warn("Missing X-Hub-Signature-256 header")
                call.respond(HttpStatusCode.Unauthorized, "Missing signature")
                return@post
            }

            val body = call.receiveText()

            if (!WebhookVerifier.verifySignature(body, signature, config.appSecret)) {
                log.warn("Invalid webhook signature")
                call.respond(HttpStatusCode.Unauthorized, "Invalid signature")
                return@post
            }

            val payload = try {
                json.decodeFromString(WebhookPayload.serializer(), body)
            } catch (e: Exception) {
                log.error("Failed to parse webhook payload", e)
                call.respond(HttpStatusCode.BadRequest, "Invalid payload")
                return@post
            }

            log.info(
                "Webhook received: object={}, entries={}",
                payload.`object`,
                payload.entry.size
            )

            for (entry in payload.entry) {
                for (change in entry.changes) {
                    if (change.field != "messages") {
                        continue
                    }

                    val value = change.value
                    value.messages?.let { messages ->
                        for (message in messages) {
                            if (deduplicationService.isDuplicate(message.id, body)) {
                                log.debug("Skipping duplicate message: id={}", message.id)
                                continue
                            }

                            val profileName = value.contacts?.firstOrNull()?.profile?.name
                            val messageText = message.text?.body

                            if (messageText.isNullOrBlank()) {
                                log.debug("Skipping non-text message: type={}", message.type)
                                continue
                            }

                            val inboundMessage = InboundMessage(
                                waId = message.from,
                                waMessageId = message.id,
                                profileName = profileName,
                                messageText = messageText,
                                timestamp = message.timestamp,
                                eventId = message.id,
                            )

                            messageQueue.enqueue(inboundMessage)
                            log.info(
                                "Enqueued message: from={} id={}",
                                message.from,
                                message.id
                            )
                        }
                    }

                    value.statuses?.let { statuses ->
                        for (status in statuses) {
                            log.info(
                                "Status update: id={} status={}",
                                status.id,
                                status.status
                            )
                        }
                    }
                }
            }

            call.respond(HttpStatusCode.OK, "ok")
        }
    }
}
