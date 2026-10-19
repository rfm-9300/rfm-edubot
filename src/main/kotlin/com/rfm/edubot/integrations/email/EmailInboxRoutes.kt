package com.rfm.edubot.integrations.email

import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.ModuleDenial
import com.rfm.edubot.dashboard.moduleContext
import com.rfm.edubot.dashboard.requireModules
import com.rfm.edubot.dashboard.toObjectIdOrNull
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * The Email page (`email` module), over the mail inbox sync keeps and the dashboard and agents send:
 *  - GET  /app/api/email/inbox                           accounts, whether reading is on, unread threads
 *  - GET  /app/api/email/inbox/threads?filter=&q=&account=  one row per thread, newest first
 *  - GET  /app/api/email/inbox/threads/{id}              a thread (any of its message ids) with its suggested actions
 *  - POST /app/api/email/inbox/threads/{id}/read|unread  the team's read state
 *  - POST /app/api/email/inbox/threads/{id}/insights     has the model read its newest received email (?refresh=1 again)
 *  - POST /app/api/email/inbox/threads/{id}/reply        answers it from its account, in its Gmail thread
 *  - POST /app/api/email/inbox/threads/{id}/actions      records a suggestion done (with its record) or dismissed
 */
internal fun Route.emailInboxRoutes(inbox: EmailInbox) {
    authenticate("dashboard") {
        route("/app/api/email/inbox") {
            requireModules(DashboardModules.EMAIL, denial = ModuleDenial.MODULE_DISABLED) {
                get {
                    val ctx = call.moduleContext()
                    call.respond(inbox.status(ctx))
                }

                get("/threads") {
                    val ctx = call.moduleContext()
                    val params = call.request.queryParameters
                    val account = params["account"]?.takeIf { it.isNotBlank() }?.let { it.toObjectIdOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_account")) }
                    call.respond(inbox.threads(ctx, EmailThreadFilter.of(params["filter"]), account, params["q"]))
                }

                get("/threads/{id}") {
                    val ctx = call.moduleContext()
                    val message = call.emailParam(inbox, ctx) ?: return@get
                    call.respond(inbox.thread(ctx, message))
                }

                post("/threads/{id}/read") {
                    val ctx = call.moduleContext()
                    val message = call.emailParam(inbox, ctx) ?: return@post
                    call.respond(UnreadDto(inbox.markRead(ctx.tenant, message)))
                }

                post("/threads/{id}/unread") {
                    val ctx = call.moduleContext()
                    val message = call.emailParam(inbox, ctx) ?: return@post
                    call.respond(UnreadDto(inbox.markUnread(ctx.tenant, message)))
                }

                post("/threads/{id}/insights") {
                    val ctx = call.moduleContext()
                    val message = call.emailParam(inbox, ctx) ?: return@post
                    call.respondResult(inbox.analyze(ctx, message, refresh = call.request.queryParameters["refresh"] in TRUTHY))
                }

                post("/threads/{id}/reply") {
                    val ctx = call.moduleContext()
                    val message = call.emailParam(inbox, ctx) ?: return@post
                    val request = runCatching { call.receive<EmailReplyRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                    call.respondResult(inbox.reply(ctx, message, request))
                }

                post("/threads/{id}/actions") {
                    val ctx = call.moduleContext()
                    val message = call.emailParam(inbox, ctx) ?: return@post
                    val request = runCatching { call.receive<EmailActionRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                    call.respondResult(inbox.record(ctx, message, request))
                }
            }
        }
    }
}

@Serializable
private data class UnreadDto(val unread: Int)

private val TRUTHY = setOf("1", "true", "yes")

private suspend fun ApplicationCall.respondResult(result: EmailInbox.Result) = when (result) {
    is EmailInbox.Result.Thread -> respond(result.thread)
    is EmailInbox.Result.Refused -> respond(HttpStatusCode.fromValue(result.status), mapOf("error" to result.key))
}

private suspend fun ApplicationCall.emailParam(inbox: EmailInbox, ctx: DashboardContext): EmailMessage? {
    val id = parameters["id"]?.toObjectIdOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_id"))
        return null
    }
    return inbox.find(ctx.tenant, id) ?: run {
        respond(HttpStatusCode.NotFound)
        return null
    }
}
