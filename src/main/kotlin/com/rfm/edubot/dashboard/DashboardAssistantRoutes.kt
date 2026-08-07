package com.rfm.edubot.dashboard

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.TenantRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.bson.types.ObjectId

internal fun Route.dashboardAssistantRoutes(
    mongo: MongoModule,
    tenantRepository: TenantRepository,
    dashboardUsers: DashboardUserRepository,
    aiClient: AiClient,
) {
    val service = DashboardAssistantService(mongo, aiClient)

    route("/assistant") {
        get("/threads") {
            val ctx = call.assistantContext(tenantRepository, dashboardUsers) ?: return@get
            call.respond(service.repository.listThreads(ctx.tenant.id, ctx.ownerKey).map { it.dto() })
        }
        post("/threads") {
            val ctx = call.assistantContext(tenantRepository, dashboardUsers) ?: return@post
            val request = runCatching { call.receive<CreateAssistantThreadRequest>() }.getOrDefault(CreateAssistantThreadRequest())
            call.respond(HttpStatusCode.Created, service.repository.createThread(ctx.tenant.id, ctx.ownerKey, request.title).dto())
        }
        get("/threads/{id}") {
            val ctx = call.assistantContext(tenantRepository, dashboardUsers) ?: return@get
            val threadId = call.parameters["id"].toObjectId() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val thread = service.repository.findThread(ctx.tenant.id, ctx.ownerKey, threadId)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(AssistantThreadDetailDto(thread.dto(), service.repository.listMessages(ctx.tenant.id, ctx.ownerKey, threadId).map { it.dto() }))
        }
        post("/threads/{id}/messages") {
            val ctx = call.assistantContext(tenantRepository, dashboardUsers) ?: return@post
            val threadId = call.parameters["id"].toObjectId() ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (service.repository.findThread(ctx.tenant.id, ctx.ownerKey, threadId) == null) return@post call.respond(HttpStatusCode.NotFound)
            val content = call.receive<AssistantMessageRequest>().content.trim()
            if (content.isBlank() || content.length > 4000) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "message must contain 1 to 4000 characters"))
            }
            service.reply(ctx.tenant, ctx.ownerKey, threadId, ctx.modules, content)
            call.respond(service.threadDetail(ctx, threadId) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
        post("/threads/{threadId}/actions/{actionId}/confirm") {
            val ctx = call.assistantContext(tenantRepository, dashboardUsers) ?: return@post
            val threadId = call.parameters["threadId"].toObjectId() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val actionId = call.parameters["actionId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (!service.confirm(ctx.tenant, ctx.ownerKey, threadId, ctx.modules, actionId)) {
                return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "action is no longer pending"))
            }
            call.respond(service.threadDetail(ctx, threadId) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
        post("/threads/{threadId}/actions/{actionId}/cancel") {
            val ctx = call.assistantContext(tenantRepository, dashboardUsers) ?: return@post
            val threadId = call.parameters["threadId"].toObjectId() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val actionId = call.parameters["actionId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (!service.repository.cancelAction(ctx.tenant.id, ctx.ownerKey, threadId, actionId)) {
                return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "action is no longer pending"))
            }
            call.respond(service.threadDetail(ctx, threadId) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
    }
}

private data class AssistantRouteContext(val context: DashboardContext) {
    val tenant get() = context.tenant
    val modules get() = DashboardModules.effectiveFor(tenant)
    val ownerKey get() = context.user?.id?.toHexString() ?: "impersonated:${context.principalType}"
}

private suspend fun io.ktor.server.application.ApplicationCall.assistantContext(
    tenantRepository: TenantRepository,
    dashboardUsers: DashboardUserRepository,
): AssistantRouteContext? {
    val ctx = dashboardContext(tenantRepository, dashboardUsers) ?: return null
    if (!ctx.requireModule(DashboardModules.AI_ASSISTANT)) {
        respond(HttpStatusCode.Forbidden)
        return null
    }
    return AssistantRouteContext(ctx)
}

private suspend fun DashboardAssistantService.threadDetail(ctx: AssistantRouteContext, threadId: ObjectId): AssistantThreadDetailDto? {
    val thread = repository.findThread(ctx.tenant.id, ctx.ownerKey, threadId) ?: return null
    return AssistantThreadDetailDto(thread.dto(), repository.listMessages(ctx.tenant.id, ctx.ownerKey, threadId).map { it.dto() })
}

@Serializable private data class CreateAssistantThreadRequest(val title: String = "")
@Serializable private data class AssistantMessageRequest(val content: String)
@Serializable private data class AssistantThreadDto(val id: String, val title: String, val createdAt: String, val updatedAt: String)
@Serializable private data class AssistantActionDto(val id: String, val toolName: String, val arguments: JsonObject, val status: String, val result: JsonObject? = null)
@Serializable private data class AssistantMessageDto(val id: String, val role: String, val content: String, val createdAt: String, val action: AssistantActionDto? = null)
@Serializable private data class AssistantThreadDetailDto(val thread: AssistantThreadDto, val messages: List<AssistantMessageDto>)

private fun AssistantThread.dto() = AssistantThreadDto(id.toHexString(), title, createdAt.toString(), updatedAt.toString())
private fun AssistantMessage.dto() = AssistantMessageDto(id.toHexString(), role, content, createdAt.toString(), action?.dto())
private fun AssistantAction.dto() = AssistantActionDto(id, toolName, arguments, status, result)
private fun String?.toObjectId(): ObjectId? = this?.let { runCatching { ObjectId(it) }.getOrNull() }
