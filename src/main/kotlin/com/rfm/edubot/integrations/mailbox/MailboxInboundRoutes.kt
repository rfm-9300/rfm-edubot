package com.rfm.edubot.integrations.mailbox

import com.rfm.edubot.integrations.aws.SnsMessage
import com.rfm.edubot.integrations.aws.SnsVerifier
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("MailboxInboundRoutes")
private val json = Json { ignoreUnknownKeys = true }

/**
 * Public: Amazon SNS posts here what SES's receipt rule announces (DEPLOYMENT_RUNBOOK.md, "Company email
 * addresses (Amazon SES)").
 *  - POST /integrations/email/ses   SNS messages from the platform's topics only, with SNS's signature: the
 *                                   subscription's confirmation, then one notification per received email
 *
 * A notification that can't be handled now answers 503, so SNS delivers it again; anything that will
 * never work answers 200, so it doesn't.
 */
fun Route.mailboxInboundRoutes(mailbox: MailboxIntegration, inbound: MailboxInbound, verifier: SnsVerifier, httpClient: HttpClient) {
    post("/integrations/email/ses") {
        if (!mailbox.configured) return@post call.respond(HttpStatusCode.NotFound)
        if ((call.request.contentLength() ?: 0) > MAX_BODY) return@post call.respond(HttpStatusCode.PayloadTooLarge)
        val body = call.receiveText()
        if (body.length > MAX_BODY) return@post call.respond(HttpStatusCode.PayloadTooLarge)
        val message = SnsMessage.parse(body) ?: return@post call.respond(HttpStatusCode.BadRequest)
        if (message.topicArn !in mailbox.config.topicArns) {
            log.warn("SNS message from a topic that isn't the platform's refused")
            return@post call.respond(HttpStatusCode.Forbidden)
        }
        if (!verifier.verify(message)) return@post call.respond(HttpStatusCode.Forbidden)
        when (message.type) {
            SnsMessage.SUBSCRIPTION_CONFIRMATION -> {
                val url = message.subscribeUrl?.takeIf { SnsMessage.isSnsUrl(it) || sameOrigin(it, mailbox.config.trustedCertUrl) }
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val confirmed = try {
                    httpClient.get(url).status.isSuccess()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                log.info("SNS subscription {}: topic={}", if (confirmed) "confirmed" else "not confirmed", message.topicArn)
                call.respond(if (confirmed) HttpStatusCode.OK else HttpStatusCode.BadGateway)
            }
            SnsMessage.UNSUBSCRIBE_CONFIRMATION -> {
                log.warn("SNS unsubscribed the mailbox endpoint: topic={}", message.topicArn)
                call.respond(HttpStatusCode.OK)
            }
            SnsMessage.NOTIFICATION -> {
                val notification = runCatching { json.parseToJsonElement(message.message) as? JsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.OK)
                when (val outcome = inbound.received(notification)) {
                    is MailboxInbound.Outcome.Retry -> {
                        log.warn("Mailbox email not kept yet, SNS retries: reason={}", outcome.reason)
                        call.respond(HttpStatusCode.ServiceUnavailable)
                    }
                    is MailboxInbound.Outcome.Ignored -> {
                        if (outcome.reason != "not_received") log.info("Mailbox notification ignored: reason={}", outcome.reason)
                        call.respond(HttpStatusCode.OK)
                    }
                    is MailboxInbound.Outcome.Kept -> call.respond(HttpStatusCode.OK)
                }
            }
            else -> call.respond(HttpStatusCode.OK)
        }
    }
}

/** SNS messages are at most 256 KB. */
private const val MAX_BODY = 300_000

/** A local end-to-end run's stand-in confirms subscriptions on the host its certificate is on. */
private fun sameOrigin(url: String, trusted: String): Boolean {
    if (trusted.isBlank()) return false
    val a = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    val b = runCatching { java.net.URI(trusted) }.getOrNull() ?: return false
    return a.scheme == b.scheme && a.host == b.host && a.port == b.port
}
