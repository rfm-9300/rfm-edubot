package com.rfm.edubot.testing

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.AiResponse
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.OpenRouterFunctionCall
import com.rfm.edubot.ai.OpenRouterToolCall
import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.ai.UsageInfo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A model that answers from a script, one step per call to [AiClient.complete]. A step can read the request
 * (what the model was told and which tools it was offered), and every request is kept for the test to check.
 */
class ScriptedModel {
    data class Request(val messages: List<ChatMessage>, val tools: List<ToolDefinition>) {
        val system: String get() = messages.filter { it.role == "system" }.joinToString("\n") { it.content.orEmpty() }
        val toolNames: List<String> get() = tools.map { it.name }

        /** The result the model got for its latest call of [tool], parsed. */
        fun resultOf(tool: String): JsonObject? {
            val callId = messages.lastOrNull { m -> m.toolCalls?.any { it.function.name == tool } == true }
                ?.toolCalls?.last { it.function.name == tool }?.id ?: return null
            return messages.lastOrNull { it.role == "tool" && it.toolCallId == callId }?.content?.let { Json.parseToJsonElement(it).jsonObject }
        }
    }

    val requests = mutableListOf<Request>()
    private val steps = ArrayDeque<(Request) -> AiResponse>()
    private var ids = 0

    /** Calls that throw before answering, as when the provider is down. */
    var failures = 0

    val client: AiClient = mockk<AiClient>().also { ai ->
        coEvery { ai.complete(any(), any(), any(), any()) } coAnswers {
            // Callers keep adding to the list they pass, so keep it as it was when the model got it.
            val request = Request(firstArg<List<ChatMessage>>().toList(), secondArg<List<ToolDefinition>>().toList())
            requests += request
            if (failures > 0) {
                failures -= 1
                throw RuntimeException("model unavailable")
            }
            val step = steps.removeFirstOrNull() ?: error("No scripted answer left; last message: ${request.messages.last()}")
            step(request)
        }
    }

    val last: Request get() = requests.last()

    fun text(content: String) = step { AiResponse.Text(content, USAGE, "r") }

    fun call(name: String, args: JsonObject = buildJsonObject {}, note: String? = null) = step { toolUse(listOf(name to args), note) }

    /** One step that calls several tools at once. */
    fun calls(vararg calls: Pair<String, JsonObject>) = step { toolUse(calls.toList(), null) }

    fun step(answer: (Request) -> AiResponse) = apply { steps += answer }

    fun pending(): Int = steps.size

    fun toolUse(calls: List<Pair<String, JsonObject>>, note: String?): AiResponse.ToolUse {
        val made = calls.map { (name, args) -> ToolCall("call_${++ids}", name, args) }
        return AiResponse.ToolUse(
            calls = made,
            usage = USAGE,
            responseId = "r",
            message = ChatMessage(
                role = "assistant",
                content = note,
                toolCalls = made.map { OpenRouterToolCall(it.id, function = OpenRouterFunctionCall(it.name, it.arguments.toString())) },
            ),
        )
    }

    companion object {
        val USAGE = UsageInfo(prompt_tokens = 120, completion_tokens = 30, total_tokens = 150)
    }
}
