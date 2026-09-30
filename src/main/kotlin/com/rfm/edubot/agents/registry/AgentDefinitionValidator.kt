package com.rfm.edubot.agents.registry

import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.model.ConditionGroup
import com.rfm.edubot.agents.runtime.ConditionEvaluator
import com.rfm.edubot.agents.runtime.TemplateRenderer
import com.rfm.edubot.agents.store.AgentJson
import com.rfm.edubot.events.DomainEventTypes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A reason a definition can't run as written; [code] is translated by the dashboard (`app.agents.problems.<code>`). */
@Serializable
data class DefinitionProblem(val path: String, val code: String, val detail: String? = null)

/**
 * Checks an agent definition against the registry and what the company has. Drafts may be saved with
 * problems; an agent is only activated with none.
 */
class AgentDefinitionValidator(private val registry: AgentRegistry) {

    fun validate(definition: AgentDefinition, availability: Availability, params: JsonObject? = null): List<DefinitionProblem> {
        val problems = mutableListOf<DefinitionProblem>()
        if (definition.triggers.isEmpty()) problems += DefinitionProblem("triggers", "no_trigger")
        if (definition.triggers.size > MAX_TRIGGERS) problems += DefinitionProblem("triggers", "too_many_triggers")
        if (definition.steps.isEmpty()) problems += DefinitionProblem("steps", "no_steps")
        if (definition.steps.size > MAX_STEPS) problems += DefinitionProblem("steps", "too_many_steps")

        val subjectTypes = mutableSetOf<String?>()
        definition.triggers.forEachIndexed { index, trigger ->
            val path = "triggers[$index]"
            val type = registry.trigger(trigger.type)
            if (type == null) {
                problems += DefinitionProblem(path, "unknown_trigger", trigger.type)
                return@forEachIndexed
            }
            type.validate(trigger.config).forEach { problems += DefinitionProblem("$path.${it.path}", it.code, it.detail) }
            availability.missingModules(type.requiredModules(trigger.config)).forEach { problems += DefinitionProblem(path, "needs_module", it) }
            type.requiredIntegration(trigger.config)?.takeIf { !availability.has(it) }?.let { problems += DefinitionProblem(path, "needs_integration", it.name) }
            subjectTypes += type.subjectType(trigger.config)
        }
        if (definition.triggers.map { it.id }.distinct().size != definition.triggers.size) problems += DefinitionProblem("triggers", "duplicate_id")
        // Every trigger must be about the same kind of record (or none), so the steps' variables exist for all of them.
        if (subjectTypes.size > 1) problems += DefinitionProblem("triggers", "mixed_subjects")
        val subjectType = subjectTypes.singleOrNull()

        val known = AgentVariables.forSubject(subjectType).map { it.path }.toSet()
        val paramNames = params?.keys.orEmpty()
        definition.conditions?.let { checkConditions(it, "conditions", known, problems) }

        val stepIds = definition.steps.map { it.id }
        if (stepIds.distinct().size != stepIds.size) problems += DefinitionProblem("steps", "duplicate_id")
        definition.steps.forEachIndexed { index, step ->
            val path = "steps[$index]"
            val action = registry.action(step.action)
            if (action == null) {
                problems += DefinitionProblem(path, "unknown_action", step.action)
                return@forEachIndexed
            }
            (SchemaValidator.validate(action.inputSchema, step.input, "", lenientTemplates = true) + action.inputProblems(step.input, registry, availability, subjectType))
                .forEach { problems += DefinitionProblem("$path.input.${it.path}", it.code, it.detail) }
            availability.missingModules(action.requiredModules).forEach { problems += DefinitionProblem(path, "needs_module", it) }
            if (!availability.has(action.requiredIntegration)) problems += DefinitionProblem(path, "needs_integration", action.requiredIntegration?.name)
            if (action.subjectTypes.isNotEmpty() && subjectType !in action.subjectTypes) problems += DefinitionProblem(path, "wrong_subject", subjectType)

            val earlier = stepIds.take(index).toSet()
            TemplateRenderer.referencesIn(step.input).forEach { reference ->
                if (!referenceKnown(reference, known, earlier, paramNames)) problems += DefinitionProblem("$path.input", "unknown_variable", reference)
            }
            step.guard?.let { checkConditions(it, "$path.guard", known + earlierOutputs(earlier), problems) }
            if (step.action == "flow.branch") {
                step.input["conditions"]
                    ?.let { runCatching { AgentJson.json.decodeFromJsonElement(ConditionGroup.serializer(), it) }.getOrNull() }
                    ?.let { checkConditions(it, "$path.input.conditions", known, problems) }
            }

            val autonomy = step.autonomy ?: definition.policy.autonomy
            if (action.sideEffect == SideEffect.EXTERNAL_MESSAGE && autonomy == Autonomy.AUTO) {
                val fromAi = action.recipientFields.any { field -> TemplateRenderer.referencesIn(step.input[field]).any { it.startsWith("steps.") } }
                if (fromAi) problems += DefinitionProblem(path, "unsafe_ai_recipient")
            }
            branchTargets(step.input).forEach { target ->
                val targetIndex = stepIds.indexOf(target)
                if (targetIndex < 0) problems += DefinitionProblem(path, "unknown_step", target)
                else if (targetIndex <= index) problems += DefinitionProblem(path, "backward_jump", target)
            }
        }

        definition.exitRules.forEachIndexed { index, rule ->
            if (DomainEventTypes.spec(rule.event) == null) problems += DefinitionProblem("exitRules[$index]", "unknown_event", rule.event)
        }
        if (definition.policy.maxRunsPerDay !in 1..10_000) problems += DefinitionProblem("policy.maxRunsPerDay", "out_of_range")
        return problems
    }

    private fun checkConditions(group: ConditionGroup, path: String, known: Set<String>, problems: MutableList<DefinitionProblem>) {
        group.conditions.forEachIndexed { index, condition ->
            if (condition.op !in ConditionEvaluator.operators) problems += DefinitionProblem("$path[$index]", "unknown_operator", condition.op)
            if (!referenceKnown(condition.field, known, emptySet(), emptySet()) && !condition.field.startsWith("steps.")) {
                problems += DefinitionProblem("$path[$index]", "unknown_field", condition.field)
            }
        }
    }

    private fun referenceKnown(reference: String, known: Set<String>, earlierSteps: Set<String>, params: Set<String>): Boolean = when {
        reference in known -> true
        reference.startsWith("params.") -> reference.removePrefix("params.") in params
        reference.startsWith("steps.") -> reference.split('.').getOrNull(1) in earlierSteps
        else -> false
    }

    private fun earlierOutputs(steps: Set<String>): Set<String> = steps.map { "steps.$it" }.toSet()

    /** Step ids a branch step may jump to. */
    private fun branchTargets(input: JsonObject): List<String> =
        listOfNotNull(input.string("thenGoTo"), input.string("elseGoTo")).filter { it != "end" && it != "next" }

    companion object {
        const val MAX_TRIGGERS = 5
        const val MAX_STEPS = 25
    }
}
