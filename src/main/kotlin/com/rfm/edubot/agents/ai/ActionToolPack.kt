package com.rfm.edubot.agents.ai

import com.rfm.edubot.agents.registry.ActionResult
import com.rfm.edubot.agents.registry.AgentAction
import com.rfm.edubot.agents.registry.ProposedAction
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SchemaProblem
import com.rfm.edubot.agents.registry.SchemaValidator
import com.rfm.edubot.agents.runtime.RunContext
import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.ai.tools.ToolCallContext
import com.rfm.edubot.ai.tools.ToolPack
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Registry actions as tools for an AI step: named after the key with underscores, described by
 * [AgentAction.toolDescription], with the input schema minus the dashboard hints as parameters.
 * [reads] run as soon as the model calls them; [writes] go through the loop's write policy. Each
 * write runs under a step id of its own (`s1.ai1`, `s1.ai2`…), so a send made inside a retried or
 * resumed step still goes out once per run.
 */
class ActionToolPack(reads: List<AgentAction>, writes: List<AgentAction>, private val ctx: RunContext) : ToolPack {
    private val log = LoggerFactory.getLogger("ActionToolPack")
    private val byTool = (reads + writes).associateBy { toolName(it.key) }
    private val readTools = reads.map { toolName(it.key) }.toSet()
    private val done = mutableListOf<JsonObject>()
    private var written = 0

    /** What the step's writes did, for its output. */
    val executed: List<JsonObject> get() = done.toList()

    override val definitions: List<ToolDefinition> =
        (reads + writes).map { ToolDefinition(toolName(it.key), it.toolDescription, Schema.forLlm(it.inputSchema)) }

    override fun knows(name: String): Boolean = name in byTool
    override fun isReadOnly(name: String): Boolean = name in readTools
    override fun moduleOf(name: String): String? = null

    fun action(name: String): AgentAction? = byTool[name]

    /** The call's arguments with the action's defaults filled in, and what's wrong with them. */
    fun input(call: ToolCall): Pair<JsonObject, List<SchemaProblem>>? {
        val action = byTool[call.name] ?: return null
        val input = SchemaValidator.coerce(action.inputSchema, Schema.withDefaults(action.inputSchema, call.arguments))
        return input to SchemaValidator.validate(action.inputSchema, input, "", lenientTemplates = false)
    }

    /** A held call as an approval: its action, final input and what a person will see. */
    suspend fun proposal(call: ToolCall): ProposedAction? {
        val action = byTool[call.name] ?: return null
        val (input, problems) = input(call) ?: return null
        if (problems.isNotEmpty()) return null
        return ProposedAction(action.key, input, action.preview(input, ctx))
    }

    override suspend fun execute(call: ToolCall, context: ToolCallContext): JsonObject {
        val action = byTool[call.name] ?: return buildJsonObject { put("error", "unknown_tool") }
        val (input, problems) = input(call) ?: return buildJsonObject { put("error", "unknown_tool") }
        if (problems.isNotEmpty()) return invalid(problems)
        val write = call.name !in readTools
        val callCtx = if (write) ctx.forCall("${ctx.step.id}.ai${++written}") else ctx
        val result = try {
            action.execute(input, callCtx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Action {} called by an AI step failed in run {}: {}", action.key, ctx.run.id, e.message)
            ActionResult.Failed(e.message ?: "action_failed")
        }
        val (status, reply) = when (result) {
            is ActionResult.Done -> "done" to buildJsonObject {
                put("ok", true)
                put("output", result.output)
                result.note?.let { put("note", it) }
            }
            is ActionResult.Skipped -> "skipped" to buildJsonObject { put("skipped", result.reason) }
            is ActionResult.Failed -> "failed" to buildJsonObject { put("error", result.error) }
            is ActionResult.Wait -> "deferred" to buildJsonObject { put("not_done", result.note ?: "later") }
            else -> "failed" to buildJsonObject { put("error", "not_supported") }
        }
        if (write) {
            done += buildJsonObject {
                put("action", action.key)
                put("status", status)
                when (result) {
                    is ActionResult.Done -> result.note?.let { put("note", it) }
                    is ActionResult.Skipped -> put("note", result.reason)
                    is ActionResult.Failed -> put("note", result.error)
                    is ActionResult.Wait -> result.note?.let { put("note", it) }
                    else -> Unit
                }
            }
        }
        return reply
    }

    companion object {
        fun toolName(key: String): String = key.replace('.', '_')

        fun invalid(problems: List<SchemaProblem>): JsonObject = buildJsonObject {
            put("error", "invalid_input")
            put("problems", JsonArray(problems.map { JsonPrimitive(listOfNotNull(it.path.ifEmpty { null }, it.code, it.detail).joinToString(": ")) }))
        }
    }
}
