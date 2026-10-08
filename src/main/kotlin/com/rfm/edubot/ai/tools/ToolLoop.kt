package com.rfm.edubot.ai.tools

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.AiResponse
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.ai.UsageInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What happens to a write tool call: run it now, hold it for a person to approve, or refuse it. */
enum class WriteDecision { EXECUTE, PROPOSE, DENY }

data class ToolTraceEntry(val call: ToolCall, val result: JsonObject?, val decision: WriteDecision)

data class TokenCount(val prompt: Int = 0, val completion: Int = 0) {
    val total: Int get() = prompt + completion
    operator fun plus(usage: UsageInfo?): TokenCount =
        if (usage == null) this else TokenCount(prompt + usage.prompt_tokens, completion + usage.completion_tokens)
    operator fun plus(other: TokenCount): TokenCount = TokenCount(prompt + other.prompt, completion + other.completion)
}

data class ToolLoopResult(
    /** The model's final answer; null when it proposed writes or submitted a result instead. */
    val text: String?,
    /**
     * Write calls held for approval. Without a `proposedResult` the loop stops at the first response
     * that proposes any; with one it keeps going and collects them all.
     */
    val proposals: List<ToolCall> = emptyList(),
    /** Arguments of the finishing tool, when the caller asked for structured output. */
    val submitted: JsonObject? = null,
    val trace: List<ToolTraceEntry> = emptyList(),
    val usage: TokenCount = TokenCount(),
    /** What the model wrote alongside the writes it proposed, if anything. */
    val note: String? = null,
)

/**
 * What the model may read of a tool's failure. Bad arguments say what was wrong, so it can correct them;
 * anything else (the database, the network) says nothing about the system behind it.
 */
fun toolFailureMessage(e: Throwable): String = when (e) {
    is IllegalArgumentException, is IllegalStateException, is NoSuchElementException -> e.message?.take(300) ?: "The tool failed."
    else -> "The tool failed for a technical reason."
}

/**
 * The tool-calling loop shared by the dashboard assistant, the persona playground and agent AI steps:
 * read tools run immediately, writes follow the caller's [WriteDecision], unknown or disallowed calls
 * get an error result the model can recover from. The customer-facing pipeline keeps its own loop for
 * its chat-specific confirmation heuristics.
 */
class ToolLoop(private val aiClient: AiClient) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun run(
        messages: List<ChatMessage>,
        tools: ToolPack,
        definitions: List<ToolDefinition> = tools.definitions,
        context: ToolCallContext = ToolCallContext(),
        maxIterations: Int = 4,
        modelOverride: String? = null,
        /** When set, the model must end by calling this tool; its arguments are the result. */
        finishTool: ToolDefinition? = null,
        fallbackInstruction: String? = "Answer the user now without calling tools.",
        deniedResult: (ToolCall) -> JsonObject = { buildJsonObject { put("error", "tool_not_allowed") } },
        decide: suspend (ToolCall) -> WriteDecision = { WriteDecision.DENY },
        /** When set, a proposed write gets this tool result and the loop carries on instead of stopping. */
        proposedResult: ((ToolCall) -> JsonObject)? = null,
    ): ToolLoopResult {
        val conversation = messages.toMutableList()
        val allowed = definitions.map { it.name }.toSet()
        val offered = definitions + listOfNotNull(finishTool)
        val trace = mutableListOf<ToolTraceEntry>()
        val proposals = mutableListOf<ToolCall>()
        var usage = TokenCount()

        repeat(maxIterations) { iteration ->
            val last = iteration == maxIterations - 1
            if (finishTool != null && last && iteration > 0) {
                conversation.add(ChatMessage(role = "system", content = "This is your last turn: call ${finishTool.name} now with the result."))
            }
            val response = aiClient.complete(
                conversation,
                offered,
                forceToolUse = finishTool != null && last,
                modelOverride = modelOverride,
            )
            when (response) {
                is AiResponse.Text -> {
                    usage += response.usage
                    if (finishTool == null) return ToolLoopResult(response.content, proposals.toList(), trace = trace, usage = usage)
                    conversation.add(ChatMessage(role = "assistant", content = response.content))
                    conversation.add(ChatMessage(role = "system", content = "Call ${finishTool.name} now with the result."))
                }
                is AiResponse.ToolUse -> {
                    usage += response.usage
                    conversation.add(response.message)
                    var proposed = false
                    var submitted: JsonObject? = null
                    for (call in response.calls) {
                        when {
                            finishTool != null && call.name == finishTool.name -> {
                                submitted = call.arguments
                                conversation.add(toolResult(call, buildJsonObject { put("ok", true) }))
                            }
                            call.name !in allowed -> {
                                val result = deniedResult(call)
                                trace += ToolTraceEntry(call, result, WriteDecision.DENY)
                                conversation.add(toolResult(call, result))
                            }
                            tools.isReadOnly(call.name) -> {
                                val result = execute(tools, call, context)
                                trace += ToolTraceEntry(call, result, WriteDecision.EXECUTE)
                                conversation.add(toolResult(call, result))
                            }
                            else -> when (decide(call)) {
                                WriteDecision.EXECUTE -> {
                                    val result = execute(tools, call, context)
                                    trace += ToolTraceEntry(call, result, WriteDecision.EXECUTE)
                                    conversation.add(toolResult(call, result))
                                }
                                WriteDecision.PROPOSE -> {
                                    proposals += call
                                    proposed = true
                                    val result = proposedResult?.invoke(call)
                                    trace += ToolTraceEntry(call, result, WriteDecision.PROPOSE)
                                    if (result != null) conversation.add(toolResult(call, result))
                                }
                                WriteDecision.DENY -> {
                                    val result = deniedResult(call)
                                    trace += ToolTraceEntry(call, result, WriteDecision.DENY)
                                    conversation.add(toolResult(call, result))
                                }
                            }
                        }
                    }
                    if (submitted != null || (proposed && proposedResult == null)) {
                        val note = response.message.content?.trim()?.takeIf { proposed && it.isNotEmpty() }
                        return ToolLoopResult(null, proposals.toList(), submitted, trace, usage, note)
                    }
                }
            }
        }

        if (fallbackInstruction == null) return ToolLoopResult(null, proposals.toList(), trace = trace, usage = usage)
        val fallback = aiClient.complete(
            conversation + ChatMessage(role = "system", content = fallbackInstruction),
            emptyList(),
            modelOverride = modelOverride,
        )
        val text = (fallback as? AiResponse.Text)?.let { usage += it.usage; it.content }
        return ToolLoopResult(text, proposals.toList(), trace = trace, usage = usage)
    }

    private suspend fun execute(tools: ToolPack, call: ToolCall, context: ToolCallContext): JsonObject =
        runCatching { tools.execute(call, context) }
            .getOrElse { buildJsonObject { put("error", "tool_failed"); put("message", toolFailureMessage(it)) } }

    private fun toolResult(call: ToolCall, result: JsonObject) =
        ChatMessage(role = "tool", content = json.encodeToString(result), toolCallId = call.id)
}
