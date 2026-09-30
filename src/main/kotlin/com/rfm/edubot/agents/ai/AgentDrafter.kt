package com.rfm.edubot.agents.ai

import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.actions.AiSteps
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentKind
import com.rfm.edubot.agents.model.AgentPolicy
import com.rfm.edubot.agents.model.AgentStatus
import com.rfm.edubot.agents.model.AgentVoice
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.model.CompanyAgentSettings
import com.rfm.edubot.agents.registry.AgentDefinitionValidator
import com.rfm.edubot.agents.registry.AgentVariables
import com.rfm.edubot.agents.registry.Availability
import com.rfm.edubot.agents.registry.DefinitionProblem
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SideEffect
import com.rfm.edubot.agents.registry.VariableSpec
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.agents.runtime.ConditionEvaluator
import com.rfm.edubot.agents.store.AgentJson
import com.rfm.edubot.agents.templates.AgentTemplates
import com.rfm.edubot.agents.templates.TemplateBuild
import com.rfm.edubot.ai.AiResponse
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.UsageSources
import com.rfm.edubot.ai.tools.TokenCount
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantLocales
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Drafts an agent from a request in plain words. The model gets the catalog (triggers, events,
 * actions, variables) and answers with a definition, which is cleaned up and validated; when it has
 * problems the model gets them back once to fix. The result is saved as a DRAFT for a person to
 * review in the builder: nothing is activated here, and no step gets more freedom than the
 * company's default autonomy.
 */
class AgentDrafter(private val module: AgentsModule) {

    sealed class Outcome {
        data class Drafted(val agent: Agent, val problems: List<DefinitionProblem>, val note: String?) : Outcome()

        /** [reason]: ai_unavailable, token_budget or no_result. */
        data class Failed(val reason: String) : Outcome()
    }

    private class Candidate(
        val name: String,
        val description: String?,
        val icon: String,
        val kind: AgentKind,
        val definition: AgentDefinition,
        val note: String?,
        val problems: List<DefinitionProblem>,
    )

    private val log = LoggerFactory.getLogger("AgentDrafter")

    suspend fun draft(tenant: Tenant, request: String, createdBy: String?): Outcome {
        val ai = module.services.aiClient ?: return Outcome.Failed(AiSteps.UNAVAILABLE)
        val usage = module.services.usage(tenant)
        if (usage.tokensUsedThisMonth() >= tenant.monthlyTokenBudget) return Outcome.Failed(AiSteps.TOKEN_BUDGET)
        val availability = module.availability(tenant)
        val company = module.settings.get(tenant.id).company
        val conversation = mutableListOf(
            ChatMessage(role = "system", content = prompt(tenant, availability, company)),
            ChatMessage(role = "user", content = request),
        )
        var tokens = TokenCount()
        var best: Candidate? = null
        var failure = AiSteps.NO_RESULT
        try {
            for (attempt in 1..MAX_ATTEMPTS) {
                val response = ai.complete(conversation, modelOverride = tenant.openrouterModel)
                tokens += when (response) {
                    is AiResponse.Text -> response.usage
                    is AiResponse.ToolUse -> response.usage
                }
                val text = (response as? AiResponse.Text)?.content
                val candidate = text?.let(::parseAnswer)?.let { candidate(it, request, company, availability) }
                if (candidate != null && (best == null || candidate.problems.size < best.problems.size)) best = candidate
                if (candidate?.problems?.isEmpty() == true || attempt == MAX_ATTEMPTS) break
                if (!text.isNullOrBlank()) conversation += ChatMessage(role = "assistant", content = text)
                conversation += ChatMessage(role = "user", content = feedback(candidate?.problems))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Agent draft failed for tenant={}: {}", tenant.slug, e.message)
            failure = AiSteps.UNAVAILABLE
        }
        if (tokens.total > 0) usage.recordUsage(tokens.total.toLong(), UsageSources.AGENTS)

        val chosen = best ?: return Outcome.Failed(failure)
        val now = module.services.clock()
        val saved = module.agents.insert(
            Agent(
                tenantId = tenant.id,
                name = chosen.name,
                description = chosen.description,
                icon = chosen.icon,
                kind = chosen.kind,
                status = AgentStatus.DRAFT,
                definition = chosen.definition,
                createdBy = createdBy,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return Outcome.Drafted(saved, chosen.problems, chosen.note)
    }

    private fun candidate(answer: JsonObject, request: String, company: CompanyAgentSettings, availability: Availability): Candidate? {
        val raw = answer["definition"] as? JsonObject ?: return null
        val decoded = runCatching { AgentJson.json.decodeFromJsonElement(AgentDefinition.serializer(), withIds(raw)) }.getOrNull() ?: return null
        val definition = sanitize(decoded, company.defaultAutonomy)
        return Candidate(
            name = answer.string("name")?.trim()?.take(MAX_NAME) ?: request.trim().lineSequence().first().take(MAX_NAME),
            description = answer.string("description")?.trim()?.take(MAX_DESCRIPTION),
            icon = answer.string("icon")?.takeIf { it in ICONS } ?: "bot",
            kind = answer.string("kind")?.let { kind -> AgentKind.entries.firstOrNull { it.name == kind } } ?: AgentKind.WORKFLOW,
            definition = definition,
            note = answer.string("note")?.trim()?.take(MAX_NOTE),
            problems = module.validator.validate(definition, availability),
        )
    }

    private fun feedback(problems: List<DefinitionProblem>?): String =
        if (problems == null) {
            "That wasn't an agent in the format described. Answer again with only the JSON object."
        } else {
            buildString {
                appendLine("The agent has these problems (path: problem, detail):")
                problems.take(MAX_FEEDBACK).forEach { appendLine("- ${it.path}: ${it.code}${it.detail?.let { detail -> " ($detail)" }.orEmpty()}") }
                append("Fix them and answer again with the whole JSON object.")
            }
        }

    private fun prompt(tenant: Tenant, availability: Availability, company: CompanyAgentSettings): String = buildString {
        val registry = module.registry
        appendLine("You design automations (\"agents\") for a small business's CRM. Turn the person's request into one agent.")
        appendLine("Answer with only a JSON object with these keys:")
        appendLine("- name: short, in the language of the request. description: one sentence on what it does, in the same language.")
        appendLine("- icon: one of ${ICONS.joinToString(", ")}.")
        appendLine("- kind: WORKFLOW (a sequence of steps), AI_WORKER (AI steps do the work), DIGEST (a summary for the team) or MONITOR (an alert).")
        appendLine("- note: for the person, in the language of the request: what you left out or what the company must set up first. Leave it out when there is nothing to say.")
        appendLine("- definition: {\"triggers\", \"conditions\", \"steps\", \"exitRules\", \"voice\"}, described below.")
        appendLine()
        appendLine("TRIGGERS say when the agent runs: usually one, and all about the same kind of record. Each is {\"id\": \"t1\", \"type\", \"config\"}.")
        registry.triggers.forEach { trigger ->
            val integration = trigger.requiredIntegration(JsonObject(emptyMap()))?.takeIf { !availability.has(it) }
            append("- ${trigger.key}: ${TRIGGER_NOTES[trigger.key].orEmpty()} config ${compact(trigger.configSchema)}")
            appendLine(integration?.let { " (not available yet: needs $it)" }.orEmpty())
        }
        appendLine()
        appendLine("EVENTS for the event trigger (event → the record it is about):")
        appendLine(
            DomainEventTypes.specs.joinToString("; ") { spec ->
                val missing = spec.module?.takeIf { it !in availability.modules }
                "${spec.type} → ${spec.subjectType}${missing?.let { " (needs the $it module)" }.orEmpty()}"
            },
        )
        appendLine()
        appendLine("CONDITIONS: {\"match\": \"ALL\"|\"ANY\", \"conditions\": [{\"field\", \"op\", \"value\"}]}. The definition's conditions are checked before a run starts; a step's guard (same shape) before that step.")
        appendLine("Operators: ${ConditionEvaluator.operators.joinToString(", ")}. in/not_in take a list; exists, not_exists, is_empty and not_empty take no value; days_since N = at least N days ago; days_until N = today or within N days.")
        appendLine()
        appendLine("STEPS run in order. Each is {\"id\": \"s1\", \"action\", \"input\", \"guard\"?, \"autonomy\"?, \"onError\"?}.")
        appendLine("Text inputs may use {{variables}} and the outputs of earlier steps as {{steps.<id>.output.<field>}}.")
        appendLine("Actions (key [reach; record types it needs]: what it does; input schema; outputs):")
        registry.actions.forEach { action ->
            val missing = availability.missingModules(action.requiredModules).firstOrNull()?.let { "needs the $it module" }
                ?: action.requiredIntegration?.takeIf { !availability.has(it) }?.let { "not connected yet: $it" }
            val reach = when (action.sideEffect) {
                SideEffect.NONE -> "changes nothing"
                SideEffect.INTERNAL_WRITE -> "changes the company's records"
                SideEffect.EXTERNAL_MESSAGE -> "reaches someone outside"
            }
            val needs = action.subjectTypes.takeIf { it.isNotEmpty() }?.joinToString("/")?.let { "; $it" }.orEmpty()
            val outputs = (action.outputSchema?.get("properties") as? JsonObject)?.keys?.takeIf { it.isNotEmpty() }?.let { "; outputs ${it.joinToString(", ")}" }.orEmpty()
            appendLine("- ${action.key} [$reach$needs]: ${action.toolDescription} input ${compact(action.inputSchema)}$outputs${missing?.let { " ($it)" }.orEmpty()}")
        }
        appendLine("ai.task returns the fields its outputs declare. flow.wait pauses the run. For a later step that should only happen if something is still true, give it a guard.")
        appendLine()
        appendLine("EXIT RULES: [{\"event\", \"conditions\"?}], a later event on the same record that ends the run early (invoice.paid stops payment reminders).")
        appendLine("VOICE, for AI-written messages: {\"tone\": \"friendly\"|\"formal\"|\"brief\", \"emoji\", \"signature\", \"instructions\", \"usePersona\"}.")
        appendLine()
        appendLine("VARIABLES. Every run has: ${paths(AgentVariables.common + AgentVariables.company)}; runs started by an event also have ${paths(AgentVariables.event)}.")
        appendLine("By the record the run is about:")
        RECORDS.forEach { type ->
            val base = (AgentVariables.common + AgentVariables.company + AgentVariables.event).map { it.path }.toSet()
            appendLine("- $type: ${paths(AgentVariables.forSubject(type).filter { it.path !in base })}")
        }
        appendLine()
        appendLine("RULES")
        appendLine("- Use only the triggers, events, actions and variables above, with their exact keys, and fill every required input.")
        appendLine("- Prefer what is available. When the request needs something that isn't, use it anyway and say so in the note.")
        appendLine("- Write messages to customers in ${AiSteps.language(tenant.locale)}, ready to send, with variables for names, numbers, amounts and dates. Never invent prices, links or facts.")
        appendLine("- Leave autonomy out unless the request asks for a person to check a step first (APPROVE) or only for drafts (DRAFT): the company's default (${company.defaultAutonomy}) applies otherwise.")
        appendLine("- Keep it as small as the request allows.")
        example(tenant, company)?.let {
            appendLine()
            appendLine("EXAMPLE definition (an overdue invoice sequence): $it")
        }
    }

    /** A built-in template's definition, as a sample of the format in the company's language. */
    private fun example(tenant: Tenant, company: CompanyAgentSettings): String? {
        val built = AgentTemplates.build(EXAMPLE_TEMPLATE, JsonObject(emptyMap()), tenant.locale, company) as? TemplateBuild.Built ?: return null
        val definition = built.agent.definition.let { it.copy(steps = it.steps.map { step -> step.copy(autonomy = null) }, policy = AgentPolicy()) }
        return compactJson.encodeToString(AgentDefinition.serializer(), definition)
    }

    companion object {
        private const val MAX_ATTEMPTS = 2
        private const val MAX_FEEDBACK = 20
        private const val MAX_NAME = 80
        private const val MAX_DESCRIPTION = 300
        private const val MAX_NOTE = 600
        private const val MAX_LABEL = 80
        private const val MAX_COOLDOWN_HOURS = 24 * 365
        private const val EXAMPLE_TEMPLATE = "overdue_sequence"

        /** Icons the dashboard draws for agents. */
        val ICONS = listOf(
            "bot", "invoice", "receipt", "alert", "heart", "wallet", "chart", "quote", "clock", "check", "user",
            "calendar", "bell", "pending", "star", "list", "chat", "comment", "wave", "tasks", "mail", "sparkle",
        )
        private val TONES = setOf("friendly", "formal", "brief")
        private val STRICTNESS = listOf(Autonomy.AUTO, Autonomy.APPROVE, Autonomy.DRAFT)
        private val RECORDS = listOf(
            SubjectTypes.CLIENT, SubjectTypes.QUOTE, SubjectTypes.INVOICE, SubjectTypes.PAYMENT, SubjectTypes.BOOKING,
            SubjectTypes.SERVICE, SubjectTypes.CONVERSATION, SubjectTypes.CONTACT, SubjectTypes.INSTAGRAM_COMMENT, SubjectTypes.EMAIL,
        )
        private val TRIGGER_NOTES = mapOf(
            "event" to "a record changed or something arrived; the event (see EVENTS) also sets the record.",
            "schedule" to "a time of day, week or month in the company's timezone (weekdays: 1 = Monday). Without forEach it runs once with no record (digests, briefings); with forEach once per matching record, where being conditions on that record.",
            "date_offset" to "days before (negative) or after a record's date: an invoice's or payment's due date, a quote's validity, a booking's start (use offsetHours for bookings); statuses narrows the records.",
            "inactivity" to "nothing happened for a while: a quote still unanswered, a chat waiting for an answer, a client with no new activity.",
            "manual" to "a person runs it from a record's page or from the assistant.",
            "email.received" to "a new email in the company's connected inbox.",
        )
        private val compactJson = Json { encodeDefaults = false; explicitNulls = false }

        private fun compact(schema: JsonObject): String = compactJson.encodeToString(JsonElement.serializer(), Schema.forLlm(schema))

        private fun paths(specs: List<VariableSpec>): String = specs.joinToString(", ") { it.path }

        /** The JSON object in the model's answer, allowing code fences or a sentence around it. */
        internal fun parseAnswer(text: String): JsonObject? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { Json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull()
        }

        /** Gives triggers and steps that lack an id the next free one ("t1", "s2"…), keeping the ids the model chose. */
        internal fun withIds(definition: JsonObject): JsonObject {
            fun numbered(key: String, prefix: String): Pair<String, JsonElement>? {
                val items = definition[key] as? JsonArray ?: return null
                val taken = items.mapNotNull { (it as? JsonObject)?.string("id") }.toMutableSet()
                var next = 1
                return key to JsonArray(
                    items.map { item ->
                        val obj = item as? JsonObject
                        if (obj == null || obj.string("id") != null) return@map item
                        while ("$prefix$next" in taken) next++
                        val id = "$prefix$next".also { taken += it }
                        JsonObject(obj + ("id" to JsonPrimitive(id)))
                    },
                )
            }
            return JsonObject(definition + listOfNotNull(numbered("triggers", "t"), numbered("steps", "s")))
        }

        /**
         * Keeps a drafted definition within what a person would have to approve: the company's autonomy
         * (a step may only be stricter), bounded sizes, a known tone and language, default limits.
         */
        internal fun sanitize(definition: AgentDefinition, companyAutonomy: Autonomy): AgentDefinition {
            val voice = definition.voice
            return definition.copy(
                triggers = definition.triggers.take(AgentDefinitionValidator.MAX_TRIGGERS),
                steps = definition.steps.take(AgentDefinitionValidator.MAX_STEPS).map { step ->
                    step.copy(
                        autonomy = step.autonomy?.takeIf { STRICTNESS.indexOf(it) > STRICTNESS.indexOf(companyAutonomy) },
                        label = step.label?.trim()?.take(MAX_LABEL)?.takeIf { it.isNotEmpty() },
                    )
                },
                policy = AgentPolicy(
                    autonomy = companyAutonomy,
                    businessDaysOnly = definition.policy.businessDaysOnly,
                    cooldownHours = definition.policy.cooldownHours.coerceIn(0, MAX_COOLDOWN_HOURS),
                ),
                voice = AgentVoice(
                    tone = voice.tone.takeIf { it in TONES } ?: AgentVoice().tone,
                    language = voice.language?.takeIf { it in TenantLocales.SUPPORTED },
                    signature = voice.signature?.trim()?.take(200)?.takeIf { it.isNotEmpty() },
                    usePersona = voice.usePersona,
                    instructions = voice.instructions?.trim()?.take(1_000)?.takeIf { it.isNotEmpty() },
                    emoji = voice.emoji,
                ),
            )
        }
    }
}
