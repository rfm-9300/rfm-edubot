package com.rfm.edubot.agents.actions

import com.rfm.edubot.agents.ai.ActionToolPack
import com.rfm.edubot.agents.ai.UntrustedContent
import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.model.AgentVoice
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.registry.ActionCategory
import com.rfm.edubot.agents.registry.ActionResult
import com.rfm.edubot.agents.registry.AgentAction
import com.rfm.edubot.agents.registry.AgentAvailability
import com.rfm.edubot.agents.registry.AgentRegistry
import com.rfm.edubot.agents.registry.Availability
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SchemaProblem
import com.rfm.edubot.agents.registry.SideEffect
import com.rfm.edubot.agents.registry.bool
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.agents.registry.strings
import com.rfm.edubot.agents.runtime.Guardrails
import com.rfm.edubot.agents.runtime.RunContext
import com.rfm.edubot.agents.runtime.TemplateRenderer
import com.rfm.edubot.agents.runtime.ValueFormatter
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.SystemPrompts
import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.ai.UsageSources
import com.rfm.edubot.ai.tools.BookingToolPack
import com.rfm.edubot.ai.tools.CompositeToolPack
import com.rfm.edubot.ai.tools.CrmToolPack
import com.rfm.edubot.ai.tools.TokenCount
import com.rfm.edubot.ai.tools.ToolLoop
import com.rfm.edubot.ai.tools.WriteDecision
import com.rfm.edubot.bookings.bookingDeps
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.CrmTools
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persona.PersonaPrompt
import com.rfm.edubot.persona.PersonaRepository
import com.rfm.edubot.tenant.model.TenantLocales
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs

/** One field an `ai.task` step returns. */
internal data class AiOutput(val name: String, val type: String, val options: List<String>, val description: String?) {
    fun schema(): JsonObject = when (type) {
        "number" -> Schema.number(description = description)
        "boolean" -> Schema.boolean(description = description)
        "date" -> Schema.string(description = listOfNotNull(description, "a date as YYYY-MM-DD").joinToString("; "))
        "choice" -> Schema.string(enum = options, description = description)
        else -> Schema.string(maxLength = MAX_TEXT, description = description)
    }

    /** The model's value in the declared type, or null when it isn't one. */
    fun read(value: JsonElement?): JsonPrimitive? {
        val primitive = value as? JsonPrimitive ?: return null
        val text = primitive.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) return null
        return when (type) {
            "number" -> (primitive.takeIf { !it.isString }?.doubleOrNull ?: text.replace(',', '.').toDoubleOrNull())
                ?.let { if (it % 1.0 == 0.0 && abs(it) < 1e15) JsonPrimitive(it.toLong()) else JsonPrimitive(it) }
            "boolean" -> (primitive.takeIf { !it.isString }?.booleanOrNull ?: text.lowercase().toBooleanStrictOrNull())?.let { JsonPrimitive(it) }
            "date" -> text.takeIf { DATE.matches(it) && runCatching { LocalDate.parse(it) }.isSuccess }?.let { JsonPrimitive(it) }
            "choice" -> options.firstOrNull { it.equals(text, ignoreCase = true) }?.let { JsonPrimitive(it) }
            else -> JsonPrimitive(text.take(MAX_TEXT))
        }
    }

    companion object {
        const val MAX_TEXT = 2_000
        private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    }
}

/** What both AI actions share: budgets, metering, the record as context and the step's text rendered safely. */
internal object AiSteps {
    const val UNAVAILABLE = "ai_unavailable"
    const val TOKEN_BUDGET = "token_budget"
    const val RUN_TOKEN_LIMIT = "run_token_limit"
    const val NO_RESULT = "no_result"

    /** The company's monthly AI budget or the run's limit is spent: the step fails instead of calling the model. */
    suspend fun overBudget(ctx: RunContext): ActionResult.Failed? {
        if (ctx.services.usage(ctx.tenant).tokensUsedThisMonth() >= ctx.tenant.monthlyTokenBudget) return ActionResult.Failed(TOKEN_BUDGET)
        val limit = ctx.run.definition.policy.maxTokensPerRun
        if (limit > 0 && ctx.run.promptTokens + ctx.run.completionTokens >= limit) return ActionResult.Failed(RUN_TOKEN_LIMIT)
        return null
    }

    /** Counts the tokens on the run and on the company's month, dry runs included: they were spent. */
    suspend fun meter(ctx: RunContext, usage: TokenCount) {
        ctx.addTokens(usage)
        ctx.services.usage(ctx.tenant).recordUsage(usage.total.toLong(), UsageSources.AGENTS)
    }

    fun usable(action: AgentAction, ctx: RunContext, availability: Availability): Boolean =
        availability.missingModules(action.requiredModules).isEmpty() && availability.has(action.requiredIntegration) &&
            (action.subjectTypes.isEmpty() || ctx.run.subject?.type in action.subjectTypes)

    fun language(locale: String): String = when (TenantLocales.normalize(locale)) {
        "en" -> "English"
        "es" -> "Spanish (Spain)"
        else -> "European Portuguese (Portugal)"
    }

    /** The date, and the record and company as `path: value` lines. */
    fun context(ctx: RunContext): String = buildString {
        appendLine(SystemPrompts.currentDateTimeContext(ctx.tenant.timezone))
        val subject = ctx.run.subject?.type
        appendLine(if (subject != null) "The run is about a $subject record. Its details, and the company's:" else "The company's details:")
        append(UntrustedContent.describe(ctx.variables))
    }

    /** A text field of the step as written, rendered with untrusted values wrapped where they land. */
    fun rendered(ctx: RunContext, input: JsonObject, field: String): String {
        val raw = (ctx.step.input[field] as? JsonPrimitive)?.contentOrNull
        val text = raw?.let { UntrustedContent.render(it, ctx.variables, ValueFormatter(ctx.locale, ctx.zone)) }
        return text?.takeIf { it.isNotBlank() } ?: input.string(field).orEmpty()
    }
}

/**
 * Reasons over the record, with read tools when [readData] is on, and returns the fields the step
 * declares through a forced `submit_result` call; later steps read them as `steps.<id>.output.<name>`.
 * The actions it may take follow the step's autonomy: on Auto they run (a message only to contacts on
 * file, else a person approves it), on Ask first they become approvals, on Draft only they are noted.
 */
object AiTaskAction : AgentAction {
    override val key = "ai.task"
    override val category = ActionCategory.AI
    override val sideEffect = SideEffect.NONE
    override val aiCallable = false
    override val toolDescription = "Reason over the record the agent works on and return structured fields."
    val outputTypes = listOf("text", "number", "boolean", "date", "choice")
    const val SUBMIT = "submit_result"
    private const val MAX_OUTPUTS = 10
    private const val MAX_ITERATIONS = 6
    private val NAME = Regex("^[A-Za-z][A-Za-z0-9_]{0,39}$")
    private val RESERVED = setOf("actions", "proposals")

    override val inputSchema = Schema.obj(
        "instructions" to Schema.string(widget = "template", maxLength = 4000, description = "what to do"),
        "outputs" to Schema.array(
            Schema.obj(
                "name" to Schema.string(maxLength = 40),
                "type" to Schema.string(enum = outputTypes, default = "text"),
                "options" to Schema.array(Schema.string(maxLength = 60), maxItems = 12),
                "description" to Schema.string(maxLength = 200),
                required = listOf("name"),
            ),
            widget = "ai-outputs",
            maxItems = MAX_OUTPUTS,
            default = JsonArray(listOf(buildJsonObject { put("name", "summary"); put("type", "text") })),
        ),
        "readData" to Schema.boolean(default = true),
        "actions" to Schema.array(Schema.string(maxLength = 60), widget = "ai-actions", maxItems = 10, default = JsonArray(emptyList())),
        required = listOf("instructions"),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(kind = "generic", body = input.string("instructions"))

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val ai = ctx.services.aiClient ?: return ActionResult.Failed(AiSteps.UNAVAILABLE)
        val registry = ctx.registry ?: return ActionResult.Failed(AiSteps.UNAVAILABLE)
        AiSteps.overBudget(ctx)?.let { return it }
        val availability = ctx.availability?.invoke() ?: AgentAvailability.of(ctx.tenant)
        val writes = input.strings("actions").distinct()
            .mapNotNull { registry.action(it) }
            .filter { isWrite(it) && AiSteps.usable(it, ctx, availability) }
        if (!ctx.dryRun && ctx.autonomy == Autonomy.AUTO && writes.any { it.sideEffect == SideEffect.EXTERNAL_MESSAGE }) {
            val definition = ctx.run.definition
            val company = ctx.settings.company
            val later = Guardrails.nextAllowed(ctx.now, ctx.zone, Guardrails.quietHours(definition, company), Guardrails.businessDaysOnly(definition, company))
            if (later != null) return ActionResult.Wait(later, note = "quiet_hours", retrySameStep = true)
        }

        val readData = input.bool("readData") ?: true
        val reads = if (readData) registry.actions.filter { isRead(it) && AiSteps.usable(it, ctx, availability) } else emptyList()
        val actions = ActionToolPack(reads, writes, ctx)
        val mongo = ctx.services.mongo
        val tenantId = ctx.tenant.id
        val crm = CrmToolPack(CrmTools(ClientRepository(mongo, tenantId), QuoteRepository(mongo, tenantId), InvoiceRepository(mongo, tenantId), StandardItemRepository(mongo, tenantId)))
        val bookings = if (readData && DashboardModules.BOOKINGS in availability.modules) BookingToolPack(bookingDeps(mongo, ctx.tenant, BookingSource.ASSISTANT).tools()) else null
        val definitions = actions.definitions +
            if (readData) crm.readOnlyDefinitionsFor(availability.modules) + bookings?.readOnlyDefinitionsFor(availability.modules).orEmpty() else emptyList()
        val outputs = outputsOf(input)
        val drafting = ctx.dryRun || ctx.autonomy == Autonomy.DRAFT

        val result = ToolLoop(ai).run(
            messages = listOf(
                ChatMessage(role = "system", content = prompt(ctx, writes.isNotEmpty())),
                ChatMessage(role = "system", content = AiSteps.context(ctx)),
                ChatMessage(role = "user", content = AiSteps.rendered(ctx, input, "instructions")),
            ),
            tools = CompositeToolPack(actions, crm, bookings),
            definitions = definitions,
            maxIterations = MAX_ITERATIONS,
            modelOverride = ctx.tenant.openrouterModel,
            finishTool = submitTool(outputs),
            fallbackInstruction = null,
            deniedResult = { call ->
                actions.input(call)?.second?.takeIf { it.isNotEmpty() }?.let { ActionToolPack.invalid(it) }
                    ?: buildJsonObject { put("error", "tool_not_allowed") }
            },
            decide = { call -> decide(call, actions, ctx) },
            proposedResult = {
                buildJsonObject {
                    put("status", if (drafting) "drafted" else "waiting_for_approval")
                    put("note", "A person reviews this before it happens. Don't call it again; finish the task.")
                }
            },
        )
        AiSteps.meter(ctx, result.usage)

        val submitted = result.submitted ?: return ActionResult.Failed(AiSteps.NO_RESULT, retryable = actions.executed.isEmpty())
        val output = buildJsonObject {
            outputs.forEach { spec -> spec.read(submitted[spec.name])?.let { put(spec.name, it) } }
            if (actions.executed.isNotEmpty()) put("actions", JsonArray(actions.executed))
        }
        val proposals = result.proposals.mapNotNull { actions.proposal(it) }
        return if (proposals.isEmpty()) ActionResult.Done(output) else ActionResult.Propose(proposals, output)
    }

    /** On Auto a write runs unless it reaches someone not on file; anything else waits for a person or is only noted. */
    private suspend fun decide(call: ToolCall, actions: ActionToolPack, ctx: RunContext): WriteDecision {
        val action = actions.action(call.name) ?: return WriteDecision.DENY
        val (input, problems) = actions.input(call) ?: return WriteDecision.DENY
        return when {
            problems.isNotEmpty() -> WriteDecision.DENY
            ctx.dryRun || ctx.autonomy != Autonomy.AUTO -> WriteDecision.PROPOSE
            !action.reachesOnlyKnownContacts(input, ctx) -> WriteDecision.PROPOSE
            else -> WriteDecision.EXECUTE
        }
    }

    private fun prompt(ctx: RunContext, canAct: Boolean): String = buildString {
        appendLine("You are one step of an automation (an \"agent\") that works for a small business. Do the task the user message gives, for the record described below.")
        appendLine("Use the record's details and, when they help, the read tools. Never invent facts, identifiers, amounts or dates: leave a field out when you don't know it.")
        if (canAct) appendLine("You may take the actions offered as tools when the task calls for it. Some are held for a person to approve: that is expected, don't repeat them.")
        appendLine("Write text fields in ${AiSteps.language(ctx.locale)}.")
        appendLine("When you are done, call $SUBMIT once with the result.")
        append(UntrustedContent.RULE)
    }

    private fun submitTool(outputs: List<AiOutput>) = ToolDefinition(
        name = SUBMIT,
        description = "Submit the result of the task. Call it once, at the end.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(outputs.associate { it.name to it.schema() }))
            put("required", JsonArray(outputs.map { JsonPrimitive(it.name) }))
        },
    )

    /** Looks something up: offered whenever the step reads data. */
    fun isRead(action: AgentAction): Boolean = action.aiCallable && action.category == ActionCategory.DATA && action.sideEffect == SideEffect.NONE

    /** Changes something or reaches someone: offered only when the step lists it, and governed by its autonomy. */
    fun isWrite(action: AgentAction): Boolean = action.aiCallable && !isRead(action)

    internal fun outputsOf(input: JsonObject): List<AiOutput> =
        (input["outputs"] as? JsonArray).orEmpty()
            .mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val name = obj.string("name")?.trim()?.takeIf { NAME.matches(it) && it !in RESERVED } ?: return@mapNotNull null
                AiOutput(name, obj.string("type")?.takeIf { it in outputTypes } ?: "text", obj.strings("options"), obj.string("description"))
            }
            .distinctBy { it.name }
            .take(MAX_OUTPUTS)

    override fun inputProblems(input: JsonObject, registry: AgentRegistry, availability: Availability, subjectType: String?): List<SchemaProblem> = buildList {
        val names = mutableSetOf<String>()
        (input["outputs"] as? JsonArray)?.forEachIndexed { index, element ->
            val output = element as? JsonObject ?: return@forEachIndexed
            val path = "outputs[$index]"
            val name = output.string("name")?.trim()
            if (name != null) {
                if (!NAME.matches(name) || name in RESERVED) add(SchemaProblem("$path.name", "invalid_name", name))
                else if (!names.add(name)) add(SchemaProblem("$path.name", "duplicate_name", name))
            }
            if (output.string("type") == "choice" && output.strings("options").isEmpty()) add(SchemaProblem("$path.options", "no_options", name))
        }
        input.strings("actions").forEachIndexed { index, key ->
            val path = "actions[$index]"
            val action = registry.action(key)
            when {
                action == null -> add(SchemaProblem(path, "unknown_action", key))
                !isWrite(action) -> add(SchemaProblem(path, "not_ai_callable", key))
                else -> {
                    availability.missingModules(action.requiredModules).forEach { add(SchemaProblem(path, "needs_module", it)) }
                    if (!availability.has(action.requiredIntegration)) add(SchemaProblem(path, "needs_integration", action.requiredIntegration?.name))
                    if (action.subjectTypes.isNotEmpty() && subjectType !in action.subjectTypes) add(SchemaProblem(path, "wrong_subject", subjectType))
                }
            }
        }
    }
}

/**
 * Writes one message from a brief, the record and the agent's Voice (and its compiled Persona when
 * the agent follows it). The text is the step's output, so a later send step uses
 * `{{steps.<id>.output.text}}` and its approval shows the final words. The signature is appended
 * as written, never by the model.
 */
object AiComposeAction : AgentAction {
    override val key = "ai.compose"
    override val category = ActionCategory.AI
    override val sideEffect = SideEffect.NONE
    override val aiCallable = false
    override val toolDescription = "Write a message from the record and the agent's voice."
    val channels = listOf("whatsapp", "email", "instagram", "internal")
    const val SUBMIT = "submit_message"

    override val inputSchema = Schema.obj(
        "brief" to Schema.string(widget = "template", maxLength = 4000, description = "what the message should say"),
        "channel" to Schema.string(enum = channels, default = "whatsapp"),
        "length" to Schema.string(enum = listOf("short", "medium", "long"), default = "short"),
        required = listOf("brief"),
    )
    override val outputSchema = Schema.obj("text" to Schema.string(), "subject" to Schema.string())

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(kind = "generic", body = input.string("brief"))

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val ai = ctx.services.aiClient ?: return ActionResult.Failed(AiSteps.UNAVAILABLE)
        AiSteps.overBudget(ctx)?.let { return it }
        val brief = AiSteps.rendered(ctx, input, "brief").takeIf { it.isNotBlank() } ?: return ActionResult.Skipped("empty_brief")
        val channel = input.string("channel")?.takeIf { it in channels } ?: "whatsapp"
        val voice = ctx.run.definition.voice
        val signature = signature(ctx, voice, channel)
        // The step's own Voice sets tone, emoji, length and language; the Persona adds how customers are addressed, its rules and its knowledge.
        val persona = if (voice.usePersona && channel != "internal") {
            PersonaPrompt.personaBlock(PersonaRepository(ctx.services.mongo).findByTenant(ctx.tenant.id), PersonaPrompt.Purpose.COMPOSE)
        } else {
            null
        }

        val result = ToolLoop(ai).run(
            messages = listOfNotNull(
                ChatMessage(role = "system", content = prompt(ctx, channel, input.string("length") ?: "short", voice, signature != null)),
                persona?.let { ChatMessage(role = "system", content = it) },
                ChatMessage(role = "system", content = AiSteps.context(ctx)),
                ChatMessage(role = "user", content = brief),
            ),
            tools = CompositeToolPack(emptyList()),
            definitions = emptyList(),
            maxIterations = 1,
            modelOverride = ctx.tenant.openrouterModel,
            finishTool = submitTool(channel),
            fallbackInstruction = null,
        )
        AiSteps.meter(ctx, result.usage)

        val submitted = result.submitted ?: return ActionResult.Failed(AiSteps.NO_RESULT, retryable = true)
        val body = submitted.string("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return ActionResult.Failed(AiSteps.NO_RESULT, retryable = true)
        val limit = when (channel) {
            "instagram" -> 1_000
            "email" -> 20_000
            else -> 4_000
        }
        val text = if (signature == null || body.endsWith(signature)) {
            body.take(limit)
        } else {
            "${body.take((limit - signature.length - 2).coerceAtLeast(0)).trimEnd()}\n\n$signature"
        }
        return ActionResult.Done(
            buildJsonObject {
                put("text", text)
                if (channel == "email") submitted.string("subject")?.trim()?.takeIf { it.isNotEmpty() }?.let { put("subject", it.take(300)) }
            },
        )
    }

    private fun signature(ctx: RunContext, voice: AgentVoice, channel: String): String? {
        if (channel == "internal") return null
        val raw = voice.signature?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return TemplateRenderer.render(raw, ctx.variables, ValueFormatter(ctx.locale, ctx.zone)).trim().takeIf { it.isNotEmpty() }
    }

    private fun prompt(ctx: RunContext, channel: String, length: String, voice: AgentVoice, signed: Boolean): String = buildString {
        val company = AgentMessaging.variable(ctx, "company.name") ?: ctx.tenant.name
        appendLine(
            if (channel == "internal") "You write one note for the team of $company, a small business."
            else "You write one message to a customer on behalf of $company, a small business.",
        )
        appendLine(
            when (channel) {
                "email" -> "It is an email: a subject line and a plain-text body with a greeting. No markdown."
                "instagram" -> "It is an Instagram reply: plain text, under 1000 characters."
                "internal" -> "It is an internal note: plain text, straight to the point."
                else -> "It is a WhatsApp message: plain text, short paragraphs, no headings."
            },
        )
        appendLine(
            when (length) {
                "long" -> "Length: a few short paragraphs at most."
                "medium" -> "Length: one or two short paragraphs."
                else -> "Length: one to three sentences."
            },
        )
        appendLine(
            when (voice.tone) {
                "formal" -> "Tone: formal and polite."
                "brief" -> "Tone: brief and direct."
                else -> "Tone: warm and friendly."
            },
        )
        appendLine("Write in ${AiSteps.language(ctx.locale)}.")
        appendLine(if (voice.emoji) "An emoji or two is fine." else "Don't use emoji.")
        if (signed) appendLine("Don't sign off with the company's name: its signature is added after your text.")
        voice.instructions?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine("The company's instructions for these messages: $it") }
        appendLine("Say only what the brief asks and the record supports: never invent prices, dates, links or promises.")
        appendLine("Call $SUBMIT with the finished message.")
        append(UntrustedContent.RULE)
    }

    private fun submitTool(channel: String): ToolDefinition {
        val email = channel == "email"
        val properties = buildMap {
            put("text", Schema.string(description = "the message, ready to send"))
            if (email) put("subject", Schema.string(maxLength = 200, description = "the email's subject line"))
        }
        return ToolDefinition(
            name = SUBMIT,
            description = "Submit the finished message.",
            parameters = Schema.forLlm(Schema.obj(*properties.toList().toTypedArray(), required = listOfNotNull("text", "subject".takeIf { email }))),
        )
    }
}
