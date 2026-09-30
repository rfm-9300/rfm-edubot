package com.rfm.edubot.agents.registry

import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.runtime.RunContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject

enum class ActionCategory { MESSAGE, TEAM, CRM, DOCUMENT, DATA, FLOW, AI }

/** How far an action reaches: nothing, the company's own records, or someone outside the company. */
enum class SideEffect { NONE, INTERNAL_WRITE, EXTERNAL_MESSAGE }

enum class IntegrationKind { WHATSAPP, INSTAGRAM, GMAIL, GMAIL_INBOX }

/**
 * One thing an agent step can do. Its [inputSchema] drives validation on save, the builder form and,
 * for [aiCallable] actions, the tool definition AI steps get. Actions are stateless: everything they
 * need comes through [RunContext].
 */
interface AgentAction {
    val key: String
    val category: ActionCategory
    val sideEffect: SideEffect
    val inputSchema: JsonObject
    val outputSchema: JsonObject? get() = null
    val requiredModules: Set<String> get() = emptySet()
    val requiredIntegration: IntegrationKind? get() = null
    /** Input fields that choose who receives a message, for the unsafe-recipient check. */
    val recipientFields: Set<String> get() = emptySet()
    /** Record types the action needs (doc.pdf needs a quote or invoice); empty means any or none. */
    val subjectTypes: Set<String> get() = emptySet()
    /** For the model, in English. */
    val toolDescription: String
    val aiCallable: Boolean get() = sideEffect != SideEffect.NONE || category == ActionCategory.DATA

    /** What would happen, without doing it: approvals, dry runs and DRAFT autonomy show this. */
    suspend fun preview(input: JsonObject, ctx: RunContext): ActionPreview

    suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult
}

sealed class ActionResult {
    data class Done(val output: JsonObject = JsonObject(emptyMap()), val note: String? = null) : ActionResult()

    /** Pause the run until [until] (a wait step, or a message held back by quiet hours). */
    data class Wait(val until: Instant, val note: String? = null, val retrySameStep: Boolean = false) : ActionResult()

    /** Continue at [stepId] instead of the next step (branches only jump forward). */
    data class Jump(val stepId: String, val note: String? = null) : ActionResult()

    /** End the run successfully now. */
    data class Stop(val outcome: String) : ActionResult()

    data class Skipped(val reason: String) : ActionResult()

    data class Failed(val error: String, val retryable: Boolean = false) : ActionResult()

    /** AI step writes held for a person: each becomes an approval and the run waits for them. */
    data class Propose(val proposals: List<ProposedAction>, val output: JsonObject = JsonObject(emptyMap())) : ActionResult()
}

data class ProposedAction(val action: String, val input: JsonObject, val preview: ActionPreview)

/**
 * When an agent wakes up and what record it is about. Matching events and sweeping records is the
 * runtime's job; the type describes the configuration and what it requires.
 */
interface AgentTriggerType {
    val key: String
    val configSchema: JsonObject
    fun subjectType(config: JsonObject): String?
    fun requiredModules(config: JsonObject): Set<String>
    fun requiredIntegration(config: JsonObject): IntegrationKind? = null
    fun validate(config: JsonObject): List<SchemaProblem> = SchemaValidator.validate(configSchema, config, "", lenientTemplates = false)
}

class AgentRegistry(actions: List<AgentAction>, triggers: List<AgentTriggerType>) {
    private val actionsByKey = actions.associateBy { it.key }
    private val triggersByKey = triggers.associateBy { it.key }

    val actions: List<AgentAction> get() = actionsByKey.values.toList()
    val triggers: List<AgentTriggerType> get() = triggersByKey.values.toList()

    fun action(key: String): AgentAction? = actionsByKey[key]
    fun trigger(key: String): AgentTriggerType? = triggersByKey[key]
}

/** What a company can use right now: its modules and connected integrations. */
data class Availability(val modules: Set<String>, val integrations: Set<IntegrationKind>) {
    fun missingModules(required: Set<String>): Set<String> = required - modules
    fun has(integration: IntegrationKind?): Boolean = integration == null || integration in integrations
}
