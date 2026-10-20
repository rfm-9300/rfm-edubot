package com.rfm.edubot.dashboard.inbox

import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.InboxError
import com.rfm.edubot.dashboard.InboxService
import com.rfm.edubot.dashboard.moduleContext
import com.rfm.edubot.http.idParam
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.whatsapp.TemplateDraft
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.datetime.Instant
import org.bson.types.ObjectId
import kotlin.time.Duration.Companion.seconds

/** `/conversations` and `/whatsapp/templates`: the Conversations inbox, its replies, templates and media. */
internal object InboxRoutes : DashboardFeature(DashboardModules.CONVERSATIONS) {
    override fun Route.routes(deps: DashboardDeps) {
        val mongo = deps.mongo
        val inbox = deps.inbox
        get("/conversations") {
            val ctx = call.moduleContext()
            val query = call.request.queryParameters["q"]?.takeIf { it.isNotBlank() }
            val nameMatches = query?.let { q -> UserRepository(mongo, ctx.tenant.id).list(q).map { it.id } }.orEmpty()
            val conversations = ConversationRepository(mongo, ctx.tenant.id).list(query, userIds = nameMatches)
            call.respond(inboxDtos(mongo, ctx.tenant, inbox, conversations))
        }
        get("/conversations/{id}/updates") {
            val ctx = call.moduleContext()
            val conversation = call.inboxConversation(mongo, ctx) ?: return@get
            val since = call.request.queryParameters["since"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
            // Taken before the query and a little early: the client merges by id, so overlap is harmless and nothing slips between polls.
            val cursor = SystemClock.now() - INBOX_CURSOR_OVERLAP
            val messages = MessageRepository(mongo, ctx.tenant.id).let { repo ->
                if (since == null) repo.threadByConversation(conversation.id) else repo.changedSince(conversation.id, since)
            }
            call.respond(ThreadUpdatesDto(cursor.toString(), inboxDtos(mongo, ctx.tenant, inbox, listOf(conversation)).single(), messages.map { it.dto() }))
        }
        post("/conversations/{id}/read") {
            val ctx = call.moduleContext()
            val conversation = call.inboxConversation(mongo, ctx) ?: return@post
            val updated = ConversationRepository(mongo, ctx.tenant.id).markRead(conversation.id) ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(inboxDtos(mongo, ctx.tenant, inbox, listOf(updated)).single())
        }
        get("/conversations/{id}/messages") {
            val ctx = call.moduleContext()
            val convo = ConversationRepository(mongo, ctx.tenant.id).findById(call.idParam("id")) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(MessageRepository(mongo, ctx.tenant.id).threadByConversation(convo.id).map { it.dto() })
        }
        patch("/conversations/{id}/auto-reply") {
            val ctx = call.moduleContext()
            val conversationId = runCatching { ObjectId(call.parameters["id"]) }.getOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid conversation id"))
            val request = call.receive<AutoReplyRequest>()
            val conversation = ConversationRepository(mongo, ctx.tenant.id).setAutoReplyEnabled(conversationId, request.enabled, ctx.agent().name)
                ?: return@patch call.respond(HttpStatusCode.NotFound)
            call.respond(inboxDtos(mongo, ctx.tenant, inbox, listOf(conversation)).single())
        }
        post("/conversations/{id}/messages") {
            val ctx = call.moduleContext()
            val conversation = call.inboxConversation(mongo, ctx) ?: return@post
            val request = call.receive<OutboundMessageRequest>()
            val binding = ctx.tenant.binding(conversation.channel)
            if (request.assetExternalId != null && binding?.externalId != request.assetExternalId) {
                return@post call.respond(HttpStatusCode.BadRequest, InboxErrorDto("asset_mismatch"))
            }
            val message = call.inboxAction { inbox.sendText(ctx.tenant, conversation, request.text, ctx.agent()) } ?: return@post
            call.respond(HttpStatusCode.Created, message.dto())
        }
        post("/conversations/{id}/messages/{messageId}/retry") {
            val ctx = call.moduleContext()
            val conversation = call.inboxConversation(mongo, ctx) ?: return@post
            val messageId = runCatching { ObjectId(call.parameters["messageId"]) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, InboxErrorDto("not_found"))
            val message = call.inboxAction { inbox.retry(ctx.tenant, conversation, messageId) } ?: return@post
            call.respond(message.dto())
        }
        post("/conversations/{id}/template") {
            val ctx = call.moduleContext()
            val conversation = call.inboxConversation(mongo, ctx) ?: return@post
            val request = call.receive<TemplateSendRequest>()
            val message = call.inboxAction {
                inbox.sendTemplate(ctx.tenant, conversation, InboxService.TemplateRequest(request.name, request.language, request.params), ctx.agent())
            } ?: return@post
            call.respond(HttpStatusCode.Created, message.dto())
        }
        post("/conversations/start") {
            val ctx = call.moduleContext()
            val request = call.receive<StartConversationRequest>()
            val (conversation, message) = call.inboxAction {
                inbox.startConversation(ctx.tenant, request.phone, InboxService.TemplateRequest(request.name, request.language, request.params), ctx.agent())
            } ?: return@post
            call.respond(HttpStatusCode.Created, StartedConversationDto(inboxDtos(mongo, ctx.tenant, inbox, listOf(conversation)).single(), message.dto()))
        }
        get("/whatsapp/templates") {
            val ctx = call.moduleContext()
            val refresh = call.request.queryParameters["refresh"] == "1"
            val all = call.request.queryParameters["all"] == "1"
            val templates = call.inboxAction { if (all) inbox.allTemplates(ctx.tenant, refresh) else inbox.templates(ctx.tenant, refresh) } ?: return@get
            call.respond(templates.map { it.dto() })
        }
        post("/whatsapp/templates") {
            val ctx = call.moduleContext()
            val request = call.receive<TemplateDraftRequest>()
            val draft = TemplateDraft(
                name = request.name.trim(),
                language = request.language.trim(),
                category = request.category.trim().uppercase(),
                header = request.header?.trim()?.takeIf { it.isNotEmpty() },
                body = request.body,
                bodyExamples = request.examples.map { it.trim() },
                footer = request.footer?.trim()?.takeIf { it.isNotEmpty() },
                quickReplies = request.quickReplies.map { it.trim() }.filter { it.isNotEmpty() },
            )
            val created = call.inboxAction { inbox.createTemplate(ctx.tenant, draft) } ?: return@post
            call.respond(HttpStatusCode.Created, CreatedTemplateDto(created.id, draft.name, draft.language, created.status ?: "PENDING", created.category ?: draft.category))
        }
        delete("/whatsapp/templates/{name}") {
            val ctx = call.moduleContext()
            val name = call.parameters["name"].orEmpty()
            call.inboxAction { inbox.deleteTemplate(ctx.tenant, name, call.request.queryParameters["id"]?.takeIf { it.isNotBlank() }) } ?: return@delete
            call.respond(HttpStatusCode.NoContent)
        }
        get("/conversations/{id}/media/{messageId}") {
            val ctx = call.moduleContext()
            val conversation = call.inboxConversation(mongo, ctx) ?: return@get
            val messageId = runCatching { ObjectId(call.parameters["messageId"]) }.getOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, InboxErrorDto("not_found"))
            val (file, fileName) = call.inboxAction { inbox.media(ctx.tenant, conversation, messageId) } ?: return@get
            call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=3600")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            fileName?.let { name ->
                val safe = name.replace(Regex("""[^\w.\- ]"""), "_").take(120)
                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$safe\"")
            }
            val type = runCatching { ContentType.parse(file.mimeType) }.getOrDefault(ContentType.Application.OctetStream)
            call.respondBytes(file.bytes, type)
        }
    }
}

private val INBOX_CURSOR_OVERLAP = 5.seconds

private fun DashboardContext.agent() = InboxService.Agent(userId = user?.id?.toHexString(), name = user?.email)

private suspend fun ApplicationCall.inboxConversation(mongo: MongoModule, ctx: DashboardContext): Conversation? {
    val id = runCatching { ObjectId(parameters["id"]) }.getOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, InboxErrorDto("not_found"))
        return null
    }
    return ConversationRepository(mongo, ctx.tenant.id).findById(id) ?: run {
        respond(HttpStatusCode.NotFound, InboxErrorDto("not_found"))
        null
    }
}

/** Runs an inbox action, answering its [InboxError] as `{ error, detail }` so the dashboard can explain it. */
private suspend fun <T> ApplicationCall.inboxAction(block: suspend () -> T): T? = try {
    block()
} catch (e: InboxError) {
    val status = when (e.key) {
        "invalid_text", "invalid_phone", "template_params", "templates_whatsapp_only", "web_read_only", "template_unsupported", "not_retryable",
        "template_name_invalid", "template_language_invalid", "template_category_invalid", "template_header_invalid", "template_footer_invalid",
        "template_body_invalid", "template_variables_invalid", "template_examples_missing", "template_buttons_invalid" -> HttpStatusCode.BadRequest
        "not_found", "template_not_found" -> HttpStatusCode.NotFound
        "window_closed", "no_channel", "no_waba" -> HttpStatusCode.Conflict
        "media_too_large" -> HttpStatusCode.PayloadTooLarge
        else -> HttpStatusCode.BadGateway
    }
    respond(status, InboxErrorDto(e.key, e.detail))
    null
}
