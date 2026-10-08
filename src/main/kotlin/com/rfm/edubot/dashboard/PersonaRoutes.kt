package com.rfm.edubot.dashboard

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.SystemPrompts
import com.rfm.edubot.ai.TenantUsageRepository
import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.ai.UsageSources
import com.rfm.edubot.ai.tools.BookingToolPack
import com.rfm.edubot.ai.tools.CompositeToolPack
import com.rfm.edubot.ai.tools.CrmToolPack
import com.rfm.edubot.ai.tools.ToolCallContext
import com.rfm.edubot.ai.tools.ToolLoop
import com.rfm.edubot.ai.tools.ToolPack
import com.rfm.edubot.bookings.BookingTools
import com.rfm.edubot.bookings.bookingDeps
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.CrmTools
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.persona.Checked
import com.rfm.edubot.persona.PersonaBehavior
import com.rfm.edubot.persona.PersonaBehaviorInput
import com.rfm.edubot.persona.PersonaChange
import com.rfm.edubot.persona.PersonaCompiler
import com.rfm.edubot.persona.PersonaDraftInput
import com.rfm.edubot.persona.PersonaFileExtractor
import com.rfm.edubot.persona.PersonaLanguages
import com.rfm.edubot.persona.PersonaLimits
import com.rfm.edubot.persona.PersonaProblem
import com.rfm.edubot.persona.PersonaPrompt
import com.rfm.edubot.persona.PersonaRepository
import com.rfm.edubot.persona.PersonaSourceSummary
import com.rfm.edubot.persona.PersonaStatus
import com.rfm.edubot.persona.PersonaValidation
import com.rfm.edubot.persona.PersonaVersion
import com.rfm.edubot.persona.PersonaVersionSummary
import com.rfm.edubot.persona.SourceKind
import com.rfm.edubot.persona.TenantPersona
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.TenantPipelineFactory
import com.rfm.edubot.tenant.model.Tenant
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.types.ObjectId

/**
 * `/app/api/persona`: how the company's bot talks to customers. Everyone with the module reads it and
 * chats with the test bot; only admins (and operators) change it, since it speaks for the whole company.
 */
internal fun Route.personaRoutes(
    mongo: MongoModule,
    pipelineFactory: TenantPipelineFactory,
    personaCompiler: PersonaCompiler,
    aiClient: AiClient,
) {
    val repo = PersonaRepository(mongo)

    suspend fun ApplicationCall.reply(ctx: DashboardContext, persona: TenantPersona?, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(status, personaDto(persona, repo.listSources(ctx.tenant.id), ctx.isAdmin()))

    route("/persona") {
        get {
            val ctx = call.personaContext() ?: return@get
            call.reply(ctx, repo.findByTenant(ctx.tenant.id))
        }
        put {
            val ctx = call.personaEditor() ?: return@put
            val request = call.body<PersonaUpdateRequest>() ?: return@put
            val instructions = when (val checked = PersonaValidation.instructions(request.compiledInstructions)) {
                is Checked.Invalid -> return@put call.problem(checked.problem)
                is Checked.Ok -> checked.value
            }
            val current = repo.findByTenant(ctx.tenant.id)
            if (current != null && current.compiledInstructions == instructions) return@put call.reply(ctx, current)
            val saved = repo.saveInstructions(ctx.tenant.id, instructions, PersonaChange.MANUAL, ctx.author())
            pipelineFactory.evict(ctx.tenant.id)
            call.reply(ctx, saved)
        }
        put("/behavior") {
            val ctx = call.personaEditor() ?: return@put
            val request = call.body<PersonaBehaviorInput>() ?: return@put
            val behavior = when (val checked = PersonaValidation.behavior(request)) {
                is Checked.Invalid -> return@put call.problem(checked.problem)
                is Checked.Ok -> checked.value
            }
            val current = repo.findByTenant(ctx.tenant.id)
            if (current != null && current.behavior == behavior) return@put call.reply(ctx, current)
            val saved = repo.saveBehavior(ctx.tenant.id, behavior, ctx.author())
            pipelineFactory.evict(ctx.tenant.id)
            call.reply(ctx, saved)
        }
        post("/sources") {
            val ctx = call.personaEditor() ?: return@post
            val request = call.body<PersonaSourceRequest>() ?: return@post
            val content = when (val checked = PersonaValidation.note(request.content)) {
                is Checked.Invalid -> return@post call.problem(checked.problem)
                is Checked.Ok -> checked.value
            }
            if (!call.roomFor(repo, ctx.tenant.id, content.length)) return@post
            repo.addSource(ctx.tenant.id, SourceKind.TEXT_NOTE, content, noteLabel(content), ctx.author())
            personaCompiler.queue(ctx.tenant)
            call.reply(ctx, repo.findByTenant(ctx.tenant.id), HttpStatusCode.Accepted)
        }
        post("/sources/file") {
            val ctx = call.personaEditor() ?: return@post
            val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > PersonaLimits.MAX_UPLOAD_BYTES + MULTIPART_OVERHEAD) {
                return@post call.problem(PersonaProblem("file_too_large", "file", PersonaLimits.MAX_UPLOAD_BYTES), HttpStatusCode.PayloadTooLarge)
            }
            var filename: String? = null
            var bytes: ByteArray? = null
            var tooLarge = false
            try {
                call.receiveMultipart(formFieldLimit = PersonaLimits.MAX_UPLOAD_BYTES + MULTIPART_OVERHEAD.toLong()).forEachPart { part ->
                    if (part is PartData.FileItem && filename == null) {
                        filename = part.originalFileName
                        val read = part.provider().readRemaining(PersonaLimits.MAX_UPLOAD_BYTES + 1L).readByteArray()
                        if (read.size > PersonaLimits.MAX_UPLOAD_BYTES) tooLarge = true else bytes = read
                    }
                    part.dispose()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The multipart reader refuses a part over its limit with an IOException; anything else is a malformed body.
                if (e.message?.contains("limit", ignoreCase = true) == true) tooLarge = true
                else return@post call.problem(PersonaProblem("invalid_request"))
            }
            if (tooLarge) return@post call.problem(PersonaProblem("file_too_large", "file", PersonaLimits.MAX_UPLOAD_BYTES), HttpStatusCode.PayloadTooLarge)
            val name = filename?.let(::cleanFileName)?.takeIf { it.isNotBlank() } ?: return@post call.problem(PersonaProblem("no_file", "file"))
            val data = bytes?.takeIf { it.isNotEmpty() } ?: return@post call.problem(PersonaProblem("no_text", "file"), HttpStatusCode.UnprocessableEntity)
            val extracted = try {
                PersonaFileExtractor.read(name, data)
            } catch (e: PersonaFileExtractor.UnsupportedFileException) {
                return@post call.problem(PersonaProblem("unsupported_file", "file"), HttpStatusCode.UnsupportedMediaType)
            } catch (e: PersonaFileExtractor.UnreadableFileException) {
                return@post call.problem(PersonaProblem("unreadable_file", "file"), HttpStatusCode.UnprocessableEntity)
            }
            if (extracted.text.isBlank()) return@post call.problem(PersonaProblem("no_text", "file"), HttpStatusCode.UnprocessableEntity)
            if (!call.roomFor(repo, ctx.tenant.id, extracted.text.length)) return@post
            repo.addSource(ctx.tenant.id, SourceKind.FILE, extracted.text, name, ctx.author(), truncated = extracted.truncated)
            personaCompiler.queue(ctx.tenant)
            call.reply(ctx, repo.findByTenant(ctx.tenant.id), HttpStatusCode.Accepted)
        }
        get("/sources/{id}") {
            val ctx = call.personaContext() ?: return@get
            val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@get call.problem(PersonaProblem("not_found"), HttpStatusCode.NotFound)
            val source = repo.findSource(ctx.tenant.id, id) ?: return@get call.problem(PersonaProblem("not_found"), HttpStatusCode.NotFound)
            call.respond(
                PersonaSourceDetailDto(
                    id = source.id.toHexString(),
                    kind = source.kind.name,
                    label = source.label,
                    content = source.content,
                    chars = source.content.length,
                    compiled = source.compiledIntoVersion != null,
                    compiledIntoVersion = source.compiledIntoVersion,
                    createdAt = source.createdAt.toString(),
                    addedBy = source.addedBy,
                    truncated = source.truncated,
                ),
            )
        }
        delete("/sources/{id}") {
            val ctx = call.personaEditor() ?: return@delete
            val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@delete call.problem(PersonaProblem("not_found"), HttpStatusCode.NotFound)
            val removed = repo.deleteSource(ctx.tenant.id, id) ?: return@delete call.problem(PersonaProblem("not_found"), HttpStatusCode.NotFound)
            if (removed.compiledIntoVersion != null) repo.markStale(ctx.tenant.id)
            val current = repo.findByTenant(ctx.tenant.id)
            // A failed synthesis is about the pending sources: once none is left, there is nothing to retry.
            if (current?.status == PersonaStatus.ERROR && repo.pendingSources(ctx.tenant.id).isEmpty()) repo.settleStatus(ctx.tenant.id)
            call.reply(ctx, repo.findByTenant(ctx.tenant.id))
        }
        post("/compile") {
            val ctx = call.personaEditor() ?: return@post
            if (repo.pendingSources(ctx.tenant.id).isEmpty()) return@post call.reply(ctx, repo.findByTenant(ctx.tenant.id))
            personaCompiler.start(ctx.tenant, fromScratch = false, author = ctx.author())
            call.reply(ctx, repo.findByTenant(ctx.tenant.id), HttpStatusCode.Accepted)
        }
        post("/rebuild") {
            val ctx = call.personaEditor() ?: return@post
            if (repo.countSources(ctx.tenant.id) == 0L) return@post call.problem(PersonaProblem("no_sources"))
            personaCompiler.start(ctx.tenant, fromScratch = true, author = ctx.author())
            call.reply(ctx, repo.findByTenant(ctx.tenant.id), HttpStatusCode.Accepted)
        }
        get("/versions") {
            val ctx = call.personaContext() ?: return@get
            val live = repo.findByTenant(ctx.tenant.id)?.version
            call.respond(repo.listVersions(ctx.tenant.id).map { it.dto(live) })
        }
        get("/versions/{version}") {
            val ctx = call.personaContext() ?: return@get
            val number = call.parameters["version"]?.toIntOrNull() ?: return@get call.problem(PersonaProblem("not_found"), HttpStatusCode.NotFound)
            val version = repo.findVersion(ctx.tenant.id, number) ?: return@get call.problem(PersonaProblem("not_found"), HttpStatusCode.NotFound)
            call.respond(version.detailDto(repo.findByTenant(ctx.tenant.id)?.version))
        }
        post("/versions/{version}/restore") {
            val ctx = call.personaEditor() ?: return@post
            val number = call.parameters["version"]?.toIntOrNull() ?: return@post call.problem(PersonaProblem("not_found"), HttpStatusCode.NotFound)
            val restored = repo.restore(ctx.tenant.id, number, ctx.author())
                ?: return@post call.problem(PersonaProblem("not_found"), HttpStatusCode.NotFound)
            pipelineFactory.evict(ctx.tenant.id)
            call.reply(ctx, restored)
        }
        post("/preview") {
            val ctx = call.personaContext() ?: return@post
            val request = call.body<PersonaPreviewRequest>() ?: return@post
            val persona = call.draftPersona(repo, ctx.tenant.id, request.draft) ?: return@post
            val block = PersonaPrompt.personaBlock(persona)
            call.respond(PersonaPreviewDto(block = block, chars = block?.length ?: 0, tokenEstimate = PersonaPrompt.estimateTokens(block)))
        }
        post("/test") {
            val ctx = call.personaContext() ?: return@post
            val request = call.body<PersonaTestRequest>() ?: return@post
            val history = request.messages
                .filter { it.content.isNotBlank() }
                .takeLast(PersonaLimits.MAX_TEST_MESSAGES)
                .map { it.copy(content = it.content.take(PersonaLimits.MAX_TEST_MESSAGE_CHARS)) }
            if (history.isEmpty()) return@post call.problem(PersonaProblem("messages_required", "messages"))
            val persona = call.draftPersona(repo, ctx.tenant.id, request.draft) ?: return@post
            val usage = TenantUsageRepository(mongo, ctx.tenant.id)
            if (usage.tokensUsedThisMonth() >= ctx.tenant.monthlyTokenBudget) {
                return@post call.problem(PersonaProblem("token_budget"), HttpStatusCode.Conflict)
            }
            val outcome = try {
                runPersonaTest(mongo, aiClient, ctx.tenant, persona, history)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@post call.problem(PersonaProblem("ai_unavailable"), HttpStatusCode.BadGateway)
            }
            usage.recordUsage(outcome.tokens, UsageSources.PERSONA)
            val reply = outcome.reply?.takeIf { it.isNotBlank() } ?: return@post call.problem(PersonaProblem("no_reply"), HttpStatusCode.BadGateway)
            call.respond(
                PersonaTestResponse(
                    reply = reply,
                    handoff = outcome.handoffReason != null,
                    handoffReason = outcome.handoffReason,
                    draft = request.draft != null,
                    version = persona.version,
                ),
            )
        }
    }
}

private const val MULTIPART_OVERHEAD = 64 * 1024

/** The caller's context when the company has the persona module; answers 403 otherwise. */
private suspend fun ApplicationCall.personaContext(): DashboardContext? {
    val ctx = dashboardContext() ?: return null
    if (!ctx.requireModule(DashboardModules.PERSONA)) {
        respond(HttpStatusCode.Forbidden)
        return null
    }
    return ctx
}

/** [personaContext] for a change: only admins and operators change how the bot speaks for the company. */
private suspend fun ApplicationCall.personaEditor(): DashboardContext? {
    val ctx = personaContext() ?: return null
    if (!ctx.isAdmin()) {
        problem(PersonaProblem("not_allowed"), HttpStatusCode.Forbidden)
        return null
    }
    return ctx
}

private fun DashboardContext.author(): String = user?.email ?: "operator"

private suspend inline fun <reified T : Any> ApplicationCall.body(): T? =
    runCatching { receive<T>() }.getOrNull() ?: run {
        problem(PersonaProblem("invalid_request"))
        null
    }

private suspend fun ApplicationCall.problem(problem: PersonaProblem, status: HttpStatusCode = HttpStatusCode.BadRequest) =
    respond(status, PersonaErrorDto(problem.error, problem.field, problem.limit))

/** Whether one more source of [chars] fits the company's limits; answers 409 when it doesn't. */
private suspend fun ApplicationCall.roomFor(repo: PersonaRepository, tenantId: ObjectId, chars: Int): Boolean {
    if (repo.countSources(tenantId) >= PersonaLimits.MAX_SOURCES) {
        problem(PersonaProblem("too_many_sources", limit = PersonaLimits.MAX_SOURCES), HttpStatusCode.Conflict)
        return false
    }
    if (repo.totalSourceChars(tenantId) + chars > PersonaLimits.MAX_TOTAL_SOURCE_CHARS) {
        problem(PersonaProblem("sources_full", limit = PersonaLimits.MAX_TOTAL_SOURCE_CHARS), HttpStatusCode.Conflict)
        return false
    }
    return true
}

/** The saved persona, or the saved one with an unsaved [draft] put in; answers 400 when the draft is invalid. */
private suspend fun ApplicationCall.draftPersona(repo: PersonaRepository, tenantId: ObjectId, draft: PersonaDraftInput?): TenantPersona? {
    val saved = repo.findByTenant(tenantId)
    if (draft == null) return saved ?: TenantPersona(tenantId = tenantId, updatedAt = SystemClock.now())
    return when (val checked = PersonaValidation.draft(saved, tenantId, draft)) {
        is Checked.Invalid -> {
            problem(checked.problem)
            null
        }
        is Checked.Ok -> checked.value
    }
}

/** A note's first line, short enough for a list. */
private fun noteLabel(content: String): String {
    val line = content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    return if (line.length > 60) line.take(59).trimEnd() + "…" else line
}

private fun cleanFileName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("""[\u0000-\u001f]"""), "").trim().take(120)

private class PlaygroundOutcome(val reply: String?, val handoffReason: String?, val tokens: Long)

/**
 * The persona test: [persona] (saved or a draft) against an in-memory chat, with the very system messages
 * a customer chat gets. Only read tools run; writes are refused and a handoff is only reported, so a test
 * chat never changes the company's records or conversations.
 */
private suspend fun runPersonaTest(
    mongo: MongoModule,
    aiClient: AiClient,
    tenant: Tenant,
    persona: TenantPersona,
    history: List<PersonaTestMessage>,
): PlaygroundOutcome {
    val modules = DashboardModules.effectiveFor(tenant).toSet()
    val bookings = DashboardModules.BOOKINGS in modules
    val context = PersonaPrompt.customerSystemMessages(
        persona = persona,
        modules = modules,
        timezoneId = tenant.timezone,
        bookingNote = if (bookings) SystemPrompts.BOOKING_TOOLS_NOTE + "\n" + SystemPrompts.bookingCustomerNote(null, null) else null,
    ).toMutableList()
    history.forEach { context += ChatMessage(role = if (it.role == "assistant") "assistant" else "user", content = it.content) }

    val handoff = PlaygroundHandoff()
    val crm = CrmToolPack(CrmTools(ClientRepository(mongo, tenant.id), QuoteRepository(mongo, tenant.id), InvoiceRepository(mongo, tenant.id), StandardItemRepository(mongo, tenant.id)))
    val booking = if (bookings) BookingToolPack(bookingDeps(mongo, tenant, BookingSource.WEB).tools()) else null
    val definitions = buildList<ToolDefinition> {
        addAll(crm.readOnlyDefinitionsFor(modules))
        booking?.let { pack -> addAll(pack.definitions.filter { it.name in BookingTools.READ_ONLY_TOOL_NAMES }) }
        if (PersonaPrompt.handsOff(persona)) add(PersonaPrompt.handoffTool)
    }
    val result = ToolLoop(aiClient).run(
        messages = context,
        tools = CompositeToolPack(crm, booking, handoff),
        definitions = definitions,
        maxIterations = 4,
        modelOverride = tenant.openrouterModel,
        deniedResult = {
            buildJsonObject {
                put("error", "tool_not_available_in_test")
                put("message", "This action is disabled in the persona test chat.")
            }
        },
    )
    val handoffMessage = persona.behavior.handoff.message?.trim()?.takeIf { it.isNotEmpty() }
    val reply = if (handoff.reason != null && handoffMessage != null) handoffMessage else result.text
    return PlaygroundOutcome(reply, handoff.reason, result.usage.total.toLong())
}

/** `handoff_to_human` in the test chat: noted for the reply, nothing paused. */
private class PlaygroundHandoff : ToolPack {
    var reason: String? = null
    override val definitions: List<ToolDefinition> = listOf(PersonaPrompt.handoffTool)
    override fun knows(name: String): Boolean = name == PersonaPrompt.HANDOFF_TOOL
    override fun isReadOnly(name: String): Boolean = true
    override fun moduleOf(name: String): String? = null
    override suspend fun execute(call: ToolCall, context: ToolCallContext): JsonObject {
        reason = call.arguments["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return buildJsonObject {
            put("handed_off", true)
            put("instruction", "The team now has this conversation. Tell the customer briefly that a person will continue here; don't promise a time.")
        }
    }
}

@Serializable private data class PersonaUpdateRequest(val compiledInstructions: String)
@Serializable private data class PersonaSourceRequest(val content: String)
@Serializable private data class PersonaTestMessage(val role: String, val content: String)
@Serializable private data class PersonaTestRequest(val messages: List<PersonaTestMessage> = emptyList(), val draft: PersonaDraftInput? = null)
@Serializable private data class PersonaTestResponse(val reply: String, val handoff: Boolean, val handoffReason: String?, val draft: Boolean, val version: Int)
@Serializable private data class PersonaPreviewRequest(val draft: PersonaDraftInput? = null)
@Serializable private data class PersonaPreviewDto(val block: String?, val chars: Int, val tokenEstimate: Int)
@Serializable private data class PersonaErrorDto(val error: String, val field: String? = null, val limit: Int? = null)

@Serializable
private data class PersonaSourceDto(
    val id: String,
    val kind: String,
    val label: String,
    val compiled: Boolean,
    val createdAt: String,
    val chars: Int,
    val addedBy: String?,
    val truncated: Boolean,
)

@Serializable
private data class PersonaSourceDetailDto(
    val id: String,
    val kind: String,
    val label: String,
    val content: String,
    val chars: Int,
    val compiled: Boolean,
    val compiledIntoVersion: Int?,
    val createdAt: String,
    val addedBy: String?,
    val truncated: Boolean,
)

@Serializable
private data class PersonaHandoffDto(val enabled: Boolean, val triggers: String?, val message: String?)

@Serializable
private data class PersonaBehaviorDto(
    val botName: String?,
    val language: String?,
    val languageStrict: Boolean,
    val tone: String?,
    val addressForm: String?,
    val replyLength: String?,
    val emoji: String?,
    val greeting: String?,
    val rules: List<String>,
    val handoff: PersonaHandoffDto,
)

@Serializable
private data class PersonaLimitsDto(
    val instructionsChars: Int,
    val noteChars: Int,
    val uploadBytes: Int,
    val sources: Int,
    val totalSourceChars: Int,
    val botNameChars: Int,
    val greetingChars: Int,
    val rules: Int,
    val ruleChars: Int,
    val handoffChars: Int,
    val testMessages: Int,
    val fileTypes: List<String>,
    val languages: List<String>,
)

@Serializable
private data class PersonaDto(
    val compiledInstructions: String,
    val version: Int,
    val tokenEstimate: Int,
    val status: String,
    val updatedAt: String?,
    val sources: List<PersonaSourceDto>,
    val behavior: PersonaBehaviorDto,
    /** Why the last synthesis failed; the previous persona is still in use. */
    val lastError: String?,
    /** A source already synthesized was removed and its facts are still in the instructions. */
    val stale: Boolean,
    val pendingSources: Int,
    val canEdit: Boolean,
    val limits: PersonaLimitsDto,
)

@Serializable
private data class PersonaVersionDto(
    val version: Int,
    val change: String,
    val author: String?,
    val restoredFrom: Int?,
    val sourceCount: Int,
    val trimmed: Boolean,
    val chars: Int,
    val createdAt: String,
    /** The version customers get now. */
    val live: Boolean,
)

@Serializable
private data class PersonaVersionDetailDto(
    val version: Int,
    val change: String,
    val author: String?,
    val restoredFrom: Int?,
    val sourceCount: Int,
    val trimmed: Boolean,
    val createdAt: String,
    val live: Boolean,
    val compiledInstructions: String,
    val behavior: PersonaBehaviorDto,
)

private val limitsDto = PersonaLimitsDto(
    instructionsChars = PersonaLimits.MAX_INSTRUCTIONS_CHARS,
    noteChars = PersonaLimits.MAX_NOTE_CHARS,
    uploadBytes = PersonaLimits.MAX_UPLOAD_BYTES,
    sources = PersonaLimits.MAX_SOURCES,
    totalSourceChars = PersonaLimits.MAX_TOTAL_SOURCE_CHARS,
    botNameChars = PersonaLimits.MAX_BOT_NAME_CHARS,
    greetingChars = PersonaLimits.MAX_GREETING_CHARS,
    rules = PersonaLimits.MAX_RULES,
    ruleChars = PersonaLimits.MAX_RULE_CHARS,
    handoffChars = PersonaLimits.MAX_HANDOFF_CHARS,
    testMessages = PersonaLimits.MAX_TEST_MESSAGES,
    fileTypes = PersonaFileExtractor.EXTENSIONS,
    languages = PersonaLanguages.names.keys.toList(),
)

private fun personaDto(persona: TenantPersona?, sources: List<PersonaSourceSummary>, canEdit: Boolean) = PersonaDto(
    compiledInstructions = persona?.compiledInstructions.orEmpty(),
    version = persona?.version ?: 0,
    tokenEstimate = persona?.tokenEstimate ?: 0,
    status = persona?.status?.name ?: PersonaStatus.EMPTY.name,
    updatedAt = persona?.updatedAt?.toString(),
    sources = sources.map {
        PersonaSourceDto(it.id.toHexString(), it.kind.name, it.label, it.compiledIntoVersion != null, it.createdAt.toString(), it.chars, it.addedBy, it.truncated)
    },
    behavior = (persona?.behavior ?: PersonaBehavior()).dto(),
    lastError = persona?.lastError,
    stale = persona?.stale ?: false,
    pendingSources = sources.count { it.compiledIntoVersion == null },
    canEdit = canEdit,
    limits = limitsDto,
)

private fun PersonaBehavior.dto() = PersonaBehaviorDto(
    botName = botName,
    language = language,
    languageStrict = languageStrict,
    tone = tone?.name,
    addressForm = addressForm?.name,
    replyLength = replyLength?.name,
    emoji = emoji?.name,
    greeting = greeting,
    rules = rules,
    handoff = PersonaHandoffDto(handoff.enabled, handoff.triggers, handoff.message),
)

private fun PersonaVersionSummary.dto(live: Int?) = PersonaVersionDto(
    version = version,
    change = change.name,
    author = author,
    restoredFrom = restoredFrom,
    sourceCount = sourceCount,
    trimmed = trimmed,
    chars = chars,
    createdAt = createdAt.toString(),
    live = version == live,
)

private fun PersonaVersion.detailDto(live: Int?) = PersonaVersionDetailDto(
    version = version,
    change = change.name,
    author = author,
    restoredFrom = restoredFrom,
    sourceCount = sourceCount,
    trimmed = trimmed,
    createdAt = createdAt.toString(),
    live = version == live,
    compiledInstructions = compiledInstructions,
    behavior = behavior.dto(),
)
