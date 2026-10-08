package com.rfm.edubot.dashboard

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.TenantUsageRepository
import com.rfm.edubot.ai.UsageSources
import com.rfm.edubot.persistence.MongoModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.bson.types.ObjectId

internal fun Route.dashboardAssistantRoutes(
    mongo: MongoModule,
    aiClient: AiClient,
    extension: AssistantExtension? = null,
    inbox: InboxService? = null,
) {
    val service = DashboardAssistantService(
        mongo,
        aiClient,
        extension = extension,
        inbox = inbox,
        onUsage = { tenant, usage -> TenantUsageRepository(mongo, tenant.id).recordUsage(usage.total.toLong(), UsageSources.ASSISTANT) },
    )

    route("/assistant") {
        get("/threads") {
            val ctx = call.assistantContext() ?: return@get
            call.respond(service.repository.listThreads(ctx.tenant.id, ctx.ownerKey, call.request.queryParameters["q"]).map { it.dto() })
        }
        post("/threads") {
            val ctx = call.assistantContext() ?: return@post
            val request = runCatching { call.receive<CreateAssistantThreadRequest>() }.getOrDefault(CreateAssistantThreadRequest())
            call.respond(HttpStatusCode.Created, service.repository.createThread(ctx.tenant.id, ctx.ownerKey, request.title).dto())
        }
        get("/threads/{id}") {
            val ctx = call.assistantContext() ?: return@get
            val threadId = call.parameters["id"].toObjectId() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val before = call.request.queryParameters["before"]?.let { it.toObjectId() ?: return@get call.respond(HttpStatusCode.BadRequest) }
            call.respond(service.threadDetail(ctx, threadId, before) ?: return@get call.respond(HttpStatusCode.NotFound))
        }
        patch("/threads/{id}") {
            val ctx = call.assistantContext() ?: return@patch
            val threadId = call.parameters["id"].toObjectId() ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val title = runCatching { call.receive<RenameAssistantThreadRequest>() }.getOrNull()?.title?.trim().orEmpty()
            if (title.isEmpty() || title.length > DashboardAssistantRepository.MAX_TITLE) {
                return@patch call.respond(HttpStatusCode.BadRequest, AssistantErrorDto("invalid_title", "title", DashboardAssistantRepository.MAX_TITLE))
            }
            val thread = service.repository.renameThread(ctx.tenant.id, ctx.ownerKey, threadId, title) ?: return@patch call.respond(HttpStatusCode.NotFound)
            call.respond(thread.dto())
        }
        delete("/threads/{id}") {
            val ctx = call.assistantContext() ?: return@delete
            val threadId = call.parameters["id"].toObjectId() ?: return@delete call.respond(HttpStatusCode.BadRequest)
            if (!service.repository.deleteThread(ctx.tenant.id, ctx.ownerKey, threadId)) return@delete call.respond(HttpStatusCode.NotFound)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/threads/{id}/messages") {
            val ctx = call.assistantContext() ?: return@post
            val threadId = call.parameters["id"].toObjectId() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val thread = service.repository.findThread(ctx.tenant.id, ctx.ownerKey, threadId) ?: return@post call.respond(HttpStatusCode.NotFound)
            val content = runCatching { call.receive<AssistantMessageRequest>() }.getOrNull()?.content?.trim().orEmpty()
            if (content.isBlank() || content.length > MAX_MESSAGE) {
                return@post call.respond(HttpStatusCode.BadRequest, AssistantErrorDto("invalid_message", "content", MAX_MESSAGE))
            }
            service.reply(ctx.context, ctx.ownerKey, thread, ctx.modules, content)
            call.respond(service.threadDetail(ctx, threadId) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
        post("/threads/{id}/retry") {
            val ctx = call.assistantContext() ?: return@post
            val threadId = call.parameters["id"].toObjectId() ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (service.repository.findThread(ctx.tenant.id, ctx.ownerKey, threadId) == null) return@post call.respond(HttpStatusCode.NotFound)
            if (!service.retry(ctx.context, ctx.ownerKey, threadId, ctx.modules)) {
                return@post call.respond(HttpStatusCode.Conflict, AssistantErrorDto("nothing_to_retry"))
            }
            call.respond(service.threadDetail(ctx, threadId) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
        post("/threads/{threadId}/actions/{actionId}/confirm") {
            val ctx = call.assistantContext() ?: return@post
            val threadId = call.parameters["threadId"].toObjectId() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val actionId = call.parameters["actionId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            when (service.confirm(ctx.context, ctx.ownerKey, threadId, ctx.modules, actionId)) {
                ConfirmOutcome.NOT_PENDING -> return@post call.respond(HttpStatusCode.Conflict, AssistantErrorDto("action_not_pending"))
                ConfirmOutcome.CHANGES_OFF -> return@post call.respond(HttpStatusCode.Forbidden, AssistantErrorDto("changes_off"))
                ConfirmOutcome.DONE -> Unit
            }
            call.respond(service.threadDetail(ctx, threadId) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
        post("/threads/{threadId}/actions/{actionId}/cancel") {
            val ctx = call.assistantContext() ?: return@post
            val threadId = call.parameters["threadId"].toObjectId() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val actionId = call.parameters["actionId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (!service.repository.cancelAction(ctx.tenant.id, ctx.ownerKey, threadId, actionId)) {
                return@post call.respond(HttpStatusCode.Conflict, AssistantErrorDto("action_not_pending"))
            }
            call.respond(service.threadDetail(ctx, threadId) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
        get("/settings") {
            val ctx = call.assistantContext() ?: return@get
            call.respond(service.settings.find(ctx.tenant.id).dto(ctx))
        }
        put("/settings") {
            val ctx = call.assistantContext() ?: return@put
            if (!ctx.context.isAdmin()) return@put call.respond(HttpStatusCode.Forbidden, AssistantErrorDto("not_allowed"))
            val request = runCatching { call.receive<AssistantSettingsRequest>() }.getOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, AssistantErrorDto("invalid_request"))
            val (settings, problem) = AssistantSettingsRules.parse(
                request.instructions, request.replyStyle, request.language, request.allowChanges, request.disabledModules,
            )
            if (problem != null || settings == null) {
                return@put call.respond(HttpStatusCode.BadRequest, AssistantErrorDto(problem?.error ?: "invalid_request", problem?.field, problem?.limit))
            }
            val saved = service.settings.save(ctx.tenant.id, settings, ctx.context.user?.email ?: "operator")
            call.respond(saved.dto(ctx))
        }
    }
}

private const val MAX_MESSAGE = 4_000

private data class AssistantRouteContext(val context: DashboardContext) {
    val tenant get() = context.tenant
    val modules get() = DashboardModules.effectiveFor(tenant)
    val ownerKey get() = context.assistantOwnerKey()
}

private suspend fun ApplicationCall.assistantContext(): AssistantRouteContext? {
    val ctx = dashboardContext() ?: return null
    if (!ctx.requireModule(DashboardModules.AI_ASSISTANT)) {
        respond(HttpStatusCode.Forbidden)
        return null
    }
    return AssistantRouteContext(ctx)
}

private suspend fun DashboardAssistantService.threadDetail(ctx: AssistantRouteContext, threadId: ObjectId, before: ObjectId? = null): AssistantThreadDetailDto? {
    val thread = repository.findThread(ctx.tenant.id, ctx.ownerKey, threadId) ?: return null
    val messages = repository.listMessages(ctx.tenant.id, ctx.ownerKey, threadId, before = before)
    val hasMore = messages.firstOrNull()?.let { repository.hasOlder(ctx.tenant.id, ctx.ownerKey, threadId, it.id) } ?: false
    return AssistantThreadDetailDto(thread.dto(), messages.map { it.dto() }, hasMore)
}

@Serializable private data class CreateAssistantThreadRequest(val title: String = "")
@Serializable private data class RenameAssistantThreadRequest(val title: String = "")
@Serializable private data class AssistantMessageRequest(val content: String = "")
@Serializable private data class AssistantErrorDto(val error: String, val field: String? = null, val limit: Int? = null)
@Serializable private data class AssistantThreadDto(val id: String, val title: String, val createdAt: String, val updatedAt: String, val pending: Int = 0)
@Serializable private data class AssistantActionDto(
    val id: String,
    val toolName: String,
    val arguments: JsonObject,
    val status: String,
    val result: JsonObject? = null,
    val preview: JsonObject? = null,
)
@Serializable private data class AssistantMessageDto(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: String,
    val action: AssistantActionDto? = null,
    val error: String? = null,
    val sources: List<String> = emptyList(),
)
@Serializable private data class AssistantThreadDetailDto(val thread: AssistantThreadDto, val messages: List<AssistantMessageDto>, val hasMore: Boolean = false)
@Serializable private data class AssistantSettingsRequest(
    val instructions: String? = null,
    val replyStyle: String? = null,
    val language: String? = null,
    val allowChanges: Boolean? = null,
    val disabledModules: List<String>? = null,
)
@Serializable private data class AssistantSettingsDto(
    val instructions: String,
    val replyStyle: String,
    val language: String?,
    val allowChanges: Boolean,
    val disabledModules: List<String>,
    /** The modules on for the company that the assistant has tools for: what the settings can switch off. */
    val areas: List<String>,
    val canEdit: Boolean,
    val maxInstructions: Int,
    val updatedAt: String?,
    val updatedBy: String?,
)

private fun AssistantSettings.dto(ctx: AssistantRouteContext) = AssistantSettingsDto(
    instructions = instructions,
    replyStyle = replyStyle.name,
    language = language,
    allowChanges = allowChanges,
    disabledModules = disabledModules.sorted(),
    areas = AssistantAreas.available(ctx.modules),
    canEdit = ctx.context.isAdmin(),
    maxInstructions = AssistantSettingsRules.MAX_INSTRUCTIONS,
    updatedAt = updatedAt?.toString(),
    updatedBy = updatedBy,
)

private fun AssistantThread.dto() = AssistantThreadDto(id.toHexString(), title, createdAt.toString(), updatedAt.toString(), pending)
private fun AssistantMessage.dto() = AssistantMessageDto(id.toHexString(), role, content, createdAt.toString(), action?.dto(), error, sources)
private fun AssistantAction.dto() = AssistantActionDto(id, toolName, arguments, status, result, preview)
private fun String?.toObjectId(): ObjectId? = this?.let { runCatching { ObjectId(it) }.getOrNull() }
