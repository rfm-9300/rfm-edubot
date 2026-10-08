package com.rfm.edubot.dashboard

import com.mongodb.client.model.Accumulators
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.OpenRouterFunctionCall
import com.rfm.edubot.ai.OpenRouterToolCall
import com.rfm.edubot.ai.TenantUsageRepository
import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.ai.tools.BookingToolPack
import com.rfm.edubot.ai.tools.CompositeToolPack
import com.rfm.edubot.ai.tools.CrmToolPack
import com.rfm.edubot.ai.tools.TokenCount
import com.rfm.edubot.ai.tools.ToolLoop
import com.rfm.edubot.ai.tools.ToolPack
import com.rfm.edubot.ai.tools.WriteDecision
import com.rfm.edubot.ai.tools.toolFailureMessage
import com.rfm.edubot.bookings.BookingTools
import com.rfm.edubot.bookings.bookingDeps
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.CrmTools
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Tenant
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.util.Date

internal data class AssistantThread(
    val id: ObjectId = ObjectId(),
    val title: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Named by the person, so the first message doesn't retitle it. */
    val renamed: Boolean = false,
    /** Proposed changes in it still waiting for a decision. */
    val pending: Int = 0,
)

/** Where a proposed change stands. Only PENDING ones can be confirmed or cancelled. */
internal object AssistantActionStatus {
    const val PENDING = "PENDING"
    const val EXECUTING = "EXECUTING"
    const val CONFIRMED = "CONFIRMED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    /** The person sent another message before deciding: nothing ran, and it can't be confirmed anymore. */
    const val EXPIRED = "EXPIRED"
}

/** Why a turn has no answer; the dashboard explains each one and offers to try again. */
internal object AssistantErrors {
    const val MODEL_UNAVAILABLE = "model_unavailable"
    const val BUDGET_EXCEEDED = "budget_exceeded"
    const val EMPTY_REPLY = "empty_reply"
}

internal data class AssistantAction(
    val id: String,
    val toolName: String,
    val arguments: JsonObject,
    val status: String,
    val result: JsonObject? = null,
    /** What the confirmation card shows beyond the arguments, captured when the write was proposed. */
    val preview: JsonObject? = null,
)

internal data class AssistantMessage(
    val id: ObjectId = ObjectId(),
    val role: String,
    val content: String,
    val createdAt: Instant,
    val action: AssistantAction? = null,
    /** Set on an assistant turn that has no answer: one of [AssistantErrors]. */
    val error: String? = null,
    /** The modules whose data the answer was read from. */
    val sources: List<String> = emptyList(),
)

/** Threads belong to one person in one company: [DashboardContext.assistantOwnerKey]. */
internal fun DashboardContext.assistantOwnerKey(): String = user?.id?.toHexString() ?: "impersonated:$principalType"

internal class DashboardAssistantRepository(private val mongo: MongoModule) {
    private val threads = mongo.database.getCollection<Document>("dashboard_assistant_threads")
    private val messages = mongo.database.getCollection<Document>("dashboard_assistant_messages")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** The person's most recent threads, those whose title contains [query] when given, with their pending counts. */
    suspend fun listThreads(tenantId: ObjectId, ownerKey: String, query: String? = null, limit: Int = 100): List<AssistantThread> {
        val search = query?.trim()?.takeIf { it.isNotEmpty() }?.let { Filters.regex("title", ".*${Regex.escape(it)}.*", "i") }
        val found = threads.find(scope(tenantId, ownerKey, *listOfNotNull(search).toTypedArray()))
            .sort(Document("updatedAt", -1)).limit(limit).toList().map { it.toThread() }
        val pending = pendingByThread(tenantId, ownerKey, found.map { it.id })
        return found.map { it.copy(pending = pending[it.id] ?: 0) }
    }

    suspend fun createThread(tenantId: ObjectId, ownerKey: String, title: String): AssistantThread {
        val now = SystemClock.now()
        val thread = AssistantThread(title = title.trim().ifBlank { DEFAULT_TITLE }.take(MAX_TITLE), createdAt = now, updatedAt = now)
        threads.insertOne(
            Document("_id", thread.id)
                .append("tenantId", tenantId)
                .append("ownerKey", ownerKey)
                .append("title", thread.title)
                .append("renamed", false)
                .append("createdAt", now.toDate())
                .append("updatedAt", now.toDate()),
        )
        return thread
    }

    suspend fun findThread(tenantId: ObjectId, ownerKey: String, threadId: ObjectId): AssistantThread? {
        val thread = threads.find(scope(tenantId, ownerKey, Filters.eq("_id", threadId))).firstOrNull()?.toThread() ?: return null
        return thread.copy(pending = pendingByThread(tenantId, ownerKey, listOf(thread.id))[thread.id] ?: 0)
    }

    /** The person's own title: kept from then on. */
    suspend fun renameThread(tenantId: ObjectId, ownerKey: String, threadId: ObjectId, title: String): AssistantThread? =
        threads.findOneAndUpdate(
            scope(tenantId, ownerKey, Filters.eq("_id", threadId)),
            Updates.combine(Updates.set("title", title.trim().take(MAX_TITLE)), Updates.set("renamed", true)),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toThread()

    /** Removes the thread and everything in it, pending changes included. */
    suspend fun deleteThread(tenantId: ObjectId, ownerKey: String, threadId: ObjectId): Boolean {
        val deleted = threads.deleteOne(scope(tenantId, ownerKey, Filters.eq("_id", threadId))).deletedCount == 1L
        if (deleted) messages.deleteMany(scope(tenantId, ownerKey, Filters.eq("threadId", threadId)))
        return deleted
    }

    /** The newest [limit] messages of a thread, older than [before] when given, oldest first. */
    suspend fun listMessages(tenantId: ObjectId, ownerKey: String, threadId: ObjectId, limit: Int = PAGE, before: ObjectId? = null): List<AssistantMessage> =
        messages.find(scope(tenantId, ownerKey, *listOfNotNull(Filters.eq("threadId", threadId), before?.let { Filters.lt("_id", it) }).toTypedArray()))
            .sort(Document("createdAt", -1).append("_id", -1)).limit(limit).toList().asReversed().map { it.toMessage() }

    /** Whether the thread has messages older than [first]. */
    suspend fun hasOlder(tenantId: ObjectId, ownerKey: String, threadId: ObjectId, first: ObjectId): Boolean =
        messages.find(scope(tenantId, ownerKey, Filters.eq("threadId", threadId), Filters.lt("_id", first))).limit(1).firstOrNull() != null

    suspend fun addMessage(
        tenantId: ObjectId,
        ownerKey: String,
        threadId: ObjectId,
        role: String,
        content: String,
        action: AssistantAction? = null,
        error: String? = null,
        sources: List<String> = emptyList(),
    ): AssistantMessage {
        val now = SystemClock.now()
        val message = AssistantMessage(role = role, content = content, createdAt = now, action = action, error = error, sources = sources)
        val doc = Document("_id", message.id)
            .append("tenantId", tenantId)
            .append("ownerKey", ownerKey)
            .append("threadId", threadId)
            .append("role", role)
            .append("content", content)
            .append("createdAt", now.toDate())
        action?.let { doc.append("action", it.toDocument()) }
        error?.let { doc.append("error", it) }
        if (sources.isNotEmpty()) doc.append("sources", sources)
        messages.insertOne(doc)
        threads.updateOne(scope(tenantId, ownerKey, Filters.eq("_id", threadId)), Updates.set("updatedAt", now.toDate()))
        return message
    }

    suspend fun deleteMessage(tenantId: ObjectId, ownerKey: String, messageId: ObjectId) {
        messages.deleteOne(scope(tenantId, ownerKey, Filters.eq("_id", messageId)))
    }

    /** The first message names an untitled thread. */
    suspend fun updateThreadTitle(tenantId: ObjectId, ownerKey: String, threadId: ObjectId, title: String) {
        threads.updateOne(
            scope(tenantId, ownerKey, Filters.eq("_id", threadId), Filters.ne("renamed", true)),
            Updates.set("title", title.trim().replace(Regex("\\s+"), " ").take(MAX_TITLE)),
        )
    }

    suspend fun claimAction(tenantId: ObjectId, ownerKey: String, threadId: ObjectId, actionId: String): Pair<ObjectId, AssistantAction>? {
        val doc = messages.findOneAndUpdate(
            scope(
                tenantId,
                ownerKey,
                Filters.eq("threadId", threadId),
                Filters.eq("action.id", actionId),
                Filters.eq("action.status", AssistantActionStatus.PENDING),
            ),
            Updates.set("action.status", AssistantActionStatus.EXECUTING),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        ) ?: return null
        return doc.getObjectId("_id") to (doc.get("action", Document::class.java)?.toAction() ?: return null)
    }

    suspend fun finishAction(messageId: ObjectId, status: String, result: JsonObject) {
        messages.updateOne(
            Filters.eq("_id", messageId),
            Updates.combine(
                Updates.set("action.status", status),
                Updates.set("action.result", json.encodeToString(result)),
            ),
        )
    }

    suspend fun cancelAction(tenantId: ObjectId, ownerKey: String, threadId: ObjectId, actionId: String): Boolean =
        messages.updateOne(
            scope(
                tenantId,
                ownerKey,
                Filters.eq("threadId", threadId),
                Filters.eq("action.id", actionId),
                Filters.eq("action.status", AssistantActionStatus.PENDING),
            ),
            Updates.set("action.status", AssistantActionStatus.CANCELLED),
        ).modifiedCount == 1L

    /** The thread moved on: changes still waiting for a decision can't be confirmed anymore. */
    suspend fun expirePending(tenantId: ObjectId, ownerKey: String, threadId: ObjectId): Long =
        messages.updateMany(
            scope(tenantId, ownerKey, Filters.eq("threadId", threadId), Filters.eq("action.status", AssistantActionStatus.PENDING)),
            Updates.set("action.status", AssistantActionStatus.EXPIRED),
        ).modifiedCount

    private suspend fun pendingByThread(tenantId: ObjectId, ownerKey: String, threadIds: List<ObjectId>): Map<ObjectId, Int> {
        if (threadIds.isEmpty()) return emptyMap()
        return messages.aggregate<Document>(
            listOf(
                Aggregates.match(scope(tenantId, ownerKey, Filters.`in`("threadId", threadIds), Filters.eq("action.status", AssistantActionStatus.PENDING))),
                Aggregates.group("\$threadId", Accumulators.sum("count", 1)),
            ),
        ).toList().associate { it.getObjectId("_id") to (it.getInteger("count") ?: 0) }
    }

    private fun scope(tenantId: ObjectId, ownerKey: String, vararg filters: Bson): Bson =
        Filters.and(listOf(Filters.eq("tenantId", tenantId), Filters.eq("ownerKey", ownerKey)) + filters)

    private fun Document.toThread() = AssistantThread(
        id = getObjectId("_id"),
        title = getString("title"),
        createdAt = getDate("createdAt").toKotlinInstant(),
        updatedAt = getDate("updatedAt").toKotlinInstant(),
        renamed = getBoolean("renamed") ?: false,
    )

    private fun Document.toMessage() = AssistantMessage(
        id = getObjectId("_id"),
        role = getString("role"),
        content = getString("content").orEmpty(),
        createdAt = getDate("createdAt").toKotlinInstant(),
        action = get("action", Document::class.java)?.toAction(),
        error = getString("error"),
        sources = getList("sources", String::class.java).orEmpty(),
    )

    private fun AssistantAction.toDocument() = Document("id", id)
        .append("toolName", toolName)
        .append("arguments", json.encodeToString(arguments))
        .append("status", status)
        .append("result", result?.let { json.encodeToString(it) })
        .append("preview", preview?.let { json.encodeToString(it) })

    private fun Document.toAction() = AssistantAction(
        id = getString("id"),
        toolName = getString("toolName"),
        arguments = json.decodeFromString(getString("arguments")),
        status = getString("status"),
        result = getString("result")?.let { json.decodeFromString(it) },
        preview = getString("preview")?.let { json.decodeFromString(it) },
    )

    companion object {
        const val DEFAULT_TITLE = "New conversation"
        const val MAX_TITLE = 80
        const val PAGE = 100
    }
}

/** Tools and instructions another module adds to the assistant for one dashboard session; its writes are confirmed like the rest. */
internal interface AssistantExtension {
    fun tools(ctx: DashboardContext): ToolPack?
    fun prompts(ctx: DashboardContext, enabledModules: List<String>): List<String>
}

internal object DashboardAssistantToolPolicy {
    private val moduleByTool = CrmTools.MODULE_OF_TOOL + BookingTools.MODULE_OF_TOOL

    private val readOnlyToolNames = CrmTools.READ_ONLY_TOOL_NAMES + BookingTools.READ_ONLY_TOOL_NAMES

    /** [extra] is an [AssistantExtension]'s pack: its tools follow the module and read-only flag it declares. */
    fun filterDefinitions(definitions: List<ToolDefinition>, enabledModules: Collection<String>, extra: ToolPack? = null): List<ToolDefinition> =
        definitions.filter { moduleOf(it.name, extra) in enabledModules }

    fun canExecuteWrite(toolName: String, enabledModules: Collection<String>, extra: ToolPack? = null): Boolean =
        !isReadOnly(toolName, extra) && moduleOf(toolName, extra) in enabledModules

    fun isReadOnly(toolName: String, extra: ToolPack? = null): Boolean =
        toolName in readOnlyToolNames || (toolName !in moduleByTool && extra?.knows(toolName) == true && extra.isReadOnly(toolName))

    fun moduleOf(toolName: String, extra: ToolPack?): String? =
        moduleByTool[toolName] ?: extra?.takeIf { it.knows(toolName) }?.moduleOf(toolName)
}

/**
 * The conversation as the model reads it. A proposed change goes in as the tool call it was, followed by
 * its outcome, so the model knows what it asked for, what the person decided and what a confirmed change
 * returned (the new record's id included) on every later turn.
 */
internal object AssistantHistory {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private const val MAX_RESULT = 2_000

    fun toChat(messages: List<AssistantMessage>): List<ChatMessage> = messages.flatMap { message ->
        val action = message.action
        when {
            message.error != null -> emptyList()
            action != null -> listOf(
                ChatMessage(
                    role = "assistant",
                    content = message.content.takeIf { it.isNotBlank() },
                    toolCalls = listOf(OpenRouterToolCall(action.id, function = OpenRouterFunctionCall(action.toolName, json.encodeToString(action.arguments)))),
                ),
                ChatMessage(role = "tool", content = outcome(action), toolCallId = action.id),
            )
            message.role == "user" -> listOf(ChatMessage(role = "user", content = message.content))
            else -> listOf(ChatMessage(role = "assistant", content = message.content))
        }
    }

    private fun outcome(action: AssistantAction): String {
        val result = when (action.status) {
            AssistantActionStatus.CONFIRMED, AssistantActionStatus.FAILED -> action.result ?: buildJsonObject { put("status", action.status.lowercase()) }
            AssistantActionStatus.PENDING -> note("waiting_for_confirmation", "The person hasn't decided yet; don't propose it again.")
            AssistantActionStatus.EXECUTING -> note("running", "It is being carried out.")
            AssistantActionStatus.CANCELLED -> note("cancelled", "The person declined it: nothing changed.")
            else -> note("expired", "The person moved on before deciding: nothing changed.")
        }
        return json.encodeToString(result).take(MAX_RESULT)
    }

    private fun note(status: String, text: String) = buildJsonObject {
        put("status", status)
        put("note", text)
    }
}

/** What confirming a proposed change came to. */
internal enum class ConfirmOutcome { DONE, NOT_PENDING, CHANGES_OFF }

internal class DashboardAssistantService(
    private val mongo: MongoModule,
    private val aiClient: AiClient,
    val repository: DashboardAssistantRepository = DashboardAssistantRepository(mongo),
    /** More tools for the session (the agents pack when that module is on). */
    private val extension: AssistantExtension? = null,
    /** Sends replies to customer chats; without it the assistant can't reply. */
    private val inbox: InboxService? = null,
    val settings: AssistantSettingsRepository = AssistantSettingsRepository(mongo),
    /** Whether the company still has tokens left this month; the assistant stops at the same budget as the bot. */
    private val withinBudget: suspend (Tenant) -> Boolean = { tenant -> TenantUsageRepository(mongo, tenant.id).tokensUsedThisMonth() < tenant.monthlyTokenBudget },
    private val onUsage: suspend (Tenant, TokenCount) -> Unit = { _, _ -> },
) {
    private val log = LoggerFactory.getLogger("DashboardAssistantService")

    suspend fun reply(ctx: DashboardContext, ownerKey: String, thread: AssistantThread, enabledModules: List<String>, content: String) {
        val tenant = ctx.tenant
        repository.expirePending(tenant.id, ownerKey, thread.id)
        val first = repository.listMessages(tenant.id, ownerKey, thread.id, limit = 1).isEmpty()
        if (first && !thread.renamed) repository.updateThreadTitle(tenant.id, ownerKey, thread.id, content)
        repository.addMessage(tenant.id, ownerKey, thread.id, "user", content)
        completeTurn(ctx, ownerKey, thread.id, enabledModules)
    }

    /** Answers again after a turn that failed (or never finished). False when the thread's last message is an answer. */
    suspend fun retry(ctx: DashboardContext, ownerKey: String, threadId: ObjectId, enabledModules: List<String>): Boolean {
        val tenant = ctx.tenant
        val last = repository.listMessages(tenant.id, ownerKey, threadId, limit = 1).lastOrNull() ?: return false
        when {
            last.role == "assistant" && last.error != null -> repository.deleteMessage(tenant.id, ownerKey, last.id)
            last.role != "user" -> return false
        }
        completeTurn(ctx, ownerKey, threadId, enabledModules)
        return true
    }

    suspend fun confirm(ctx: DashboardContext, ownerKey: String, threadId: ObjectId, enabledModules: List<String>, actionId: String): ConfirmOutcome {
        val tenant = ctx.tenant
        val settings = settings.find(tenant.id)
        if (!settings.allowChanges) return ConfirmOutcome.CHANGES_OFF
        val (messageId, action) = repository.claimAction(tenant.id, ownerKey, threadId, actionId) ?: return ConfirmOutcome.NOT_PENDING
        val session = session(ctx, settings, enabledModules)
        val result = if (!DashboardAssistantToolPolicy.canExecuteWrite(action.toolName, session.usable, session.policy)) {
            buildJsonObject { put("error", "action_not_allowed"); put("message", "This change isn't allowed in the assistant now.") }
        } else {
            runCatching { session.tools.execute(ToolCall(action.id, action.toolName, action.arguments)) }
                .getOrElse {
                    log.warn("Confirmed dashboard action {} ({}) failed: {}", action.id, action.toolName, it.message)
                    buildJsonObject { put("error", "tool_failed"); put("message", toolFailureMessage(it)) }
                }
        }
        repository.finishAction(messageId, if ("error" in result) AssistantActionStatus.FAILED else AssistantActionStatus.CONFIRMED, result)
        try {
            completeTurn(ctx, ownerKey, threadId, enabledModules, FOLLOW_UP)
        } catch (e: Exception) {
            // The change and its result are saved; a missing follow-up must not turn a done change into a failed request.
            log.warn("Could not generate follow-up for confirmed dashboard action {}: {}", action.id, e.message)
        }
        return ConfirmOutcome.DONE
    }

    private suspend fun completeTurn(ctx: DashboardContext, ownerKey: String, threadId: ObjectId, enabledModules: List<String>, note: String? = null) {
        val tenant = ctx.tenant
        if (!withinBudget(tenant)) {
            repository.addMessage(tenant.id, ownerKey, threadId, "assistant", "", error = AssistantErrors.BUDGET_EXCEEDED)
            return
        }
        val settings = settings.find(tenant.id)
        val session = session(ctx, settings, enabledModules)
        val history = repository.listMessages(tenant.id, ownerKey, threadId, limit = HISTORY)
        val context = AssistantPrompt.messages(ctx, settings, session.usable, extensionPrompts(ctx, session.usable)).map { ChatMessage(role = "system", content = it) } +
            AssistantHistory.toChat(history) +
            listOfNotNull(note?.let { ChatMessage(role = "system", content = it) })

        // Every write waits for the person's confirmation in the dashboard, and only one that can run is put to them:
        // the others go back to the model with what's wrong, so it can ask for what's missing.
        val refused = mutableMapOf<String, JsonObject>()
        val result = try {
            ToolLoop(aiClient).run(
                messages = context,
                tools = session.tools,
                definitions = session.definitions,
                maxIterations = MAX_STEPS,
                modelOverride = tenant.openrouterModel,
                decide = { call -> session.tools.check(call)?.let { refused[call.id] = it; WriteDecision.DENY } ?: WriteDecision.PROPOSE },
                deniedResult = { call ->
                    refused[call.id] ?: if (!settings.allowChanges && !DashboardAssistantToolPolicy.isReadOnly(call.name, session.policy)) {
                        buildJsonObject { put("error", "changes_off"); put("message", "Changes are switched off in the assistant's settings.") }
                    } else {
                        buildJsonObject { put("error", "tool_not_allowed"); put("message", "That isn't available here.") }
                    }
                },
            )
        } catch (e: Exception) {
            log.warn("Dashboard assistant turn failed for tenant={}: {}", tenant.id, e.message)
            repository.addMessage(tenant.id, ownerKey, threadId, "assistant", "", error = AssistantErrors.MODEL_UNAVAILABLE)
            return
        }
        onUsage(tenant, result.usage)
        val sources = result.trace
            .filter { it.decision == WriteDecision.EXECUTE && DashboardAssistantToolPolicy.isReadOnly(it.call.name, session.policy) }
            .mapNotNull { DashboardAssistantToolPolicy.moduleOf(it.call.name, session.policy) }
            .distinct()
        if (result.proposals.isNotEmpty()) {
            result.proposals.forEachIndexed { index, call ->
                val preview = runCatching { session.tools.describe(call) }
                    .onFailure { log.warn("Could not preview {} for tenant={}: {}", call.name, tenant.id, it.message, it) }
                    .getOrNull()
                repository.addMessage(
                    tenant.id,
                    ownerKey,
                    threadId,
                    "assistant",
                    if (index == 0) result.note.orEmpty() else "",
                    // Providers don't promise unique call ids across conversations; the action id must be.
                    AssistantAction("act_${ObjectId().toHexString()}", call.name, call.arguments, AssistantActionStatus.PENDING, preview = preview),
                    sources = if (index == 0) sources else emptyList(),
                )
            }
            return
        }
        val text = result.text?.trim()
        if (text.isNullOrEmpty()) {
            repository.addMessage(tenant.id, ownerKey, threadId, "assistant", "", error = AssistantErrors.EMPTY_REPLY, sources = sources)
        } else {
            repository.addMessage(tenant.id, ownerKey, threadId, "assistant", text, sources = sources)
        }
    }

    private fun extensionPrompts(ctx: DashboardContext, usable: List<String>): List<String> =
        if (DashboardModules.AGENTS in usable) extension?.prompts(ctx, usable).orEmpty() else emptyList()

    /** The tools of one turn: the assistant's own first, so they take the place of the bot's versions of the same name. */
    private class Session(val usable: List<String>, val tools: ToolPack, val policy: ToolPack, val definitions: List<ToolDefinition>)

    private fun session(ctx: DashboardContext, settings: AssistantSettings, enabledModules: List<String>): Session {
        val tenant = ctx.tenant
        val usable = settings.usableModules(enabledModules)
        val own = AssistantTools(mongo, ctx, usable, inbox)
        val extra = extension?.tools(ctx)?.takeIf { DashboardModules.AGENTS in usable }
        val crm = CrmTools(
            ClientRepository(mongo, tenant.id),
            QuoteRepository(mongo, tenant.id),
            InvoiceRepository(mongo, tenant.id),
            StandardItemRepository(mongo, tenant.id),
        )
        val tools = CompositeToolPack(own, CrmToolPack(crm), BookingToolPack(bookingDeps(mongo, tenant, BookingSource.ASSISTANT).tools()), extra)
        val policy = CompositeToolPack(own, extra)
        val definitions = DashboardAssistantToolPolicy.filterDefinitions(tools.definitions.distinctBy { it.name }, usable, policy)
            .filter { settings.allowChanges || DashboardAssistantToolPolicy.isReadOnly(it.name, policy) }
        return Session(usable, tools, policy, definitions)
    }

    companion object {
        private const val HISTORY = 30
        private const val MAX_STEPS = 6
        private val FOLLOW_UP = """
            The person just confirmed the change proposed above; its outcome is the last tool result.
            Tell them what happened in a sentence or two, with the new record's number or name when there is one. If it failed, say why in plain words.
            If their request needs another change, propose it now with its tool. Never propose this same change again.
        """.trimIndent()
    }
}

private fun Instant.toDate() = Date(toEpochMilliseconds())
private fun Date.toKotlinInstant() = Instant.fromEpochMilliseconds(time)
