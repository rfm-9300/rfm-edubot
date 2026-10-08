package com.rfm.edubot.testing

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.AiResponse
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.ai.UsageInfo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections

/**
 * A model for tests: it keeps every request and answers through [answer], so a test can read exactly
 * what the bot was told and which tools it was offered.
 */
class FakeModel(var answer: suspend Request.() -> AiResponse = { text("ok") }) {
    data class Request(val messages: List<ChatMessage>, val tools: List<ToolDefinition>, val forceToolUse: Boolean, val model: String?) {
        val system: List<String> get() = messages.filter { it.role == "system" }.mapNotNull { it.content }
        val lastUser: String? get() = messages.lastOrNull { it.role == "user" }?.content
        val user: List<String> get() = messages.filter { it.role == "user" }.mapNotNull { it.content }
        fun offers(name: String) = tools.any { it.name == name }
        /** Whether a tool result is already in the conversation, i.e. this is the turn after a tool call. */
        val afterTool: Boolean get() = messages.lastOrNull()?.role == "tool"
    }

    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    val client: AiClient = mockk<AiClient>().also { ai ->
        coEvery { ai.complete(any(), any(), any(), any()) } coAnswers {
            val request = Request(firstArg<List<ChatMessage>>().toList(), secondArg<List<ToolDefinition>>().toList(), thirdArg(), arg(3))
            requests += request
            answer(request)
        }
    }

    fun last(): Request = requests.last()

    companion object {
        fun text(content: String, prompt: Int = 100, completion: Int = 20) =
            AiResponse.Text(content, UsageInfo(prompt_tokens = prompt, completion_tokens = completion, total_tokens = prompt + completion), "resp-text")

        fun call(name: String, arguments: JsonObject = buildJsonObject { }) = AiResponse.ToolUse(
            calls = listOf(ToolCall("call-$name", name, arguments)),
            usage = UsageInfo(prompt_tokens = 50, completion_tokens = 5, total_tokens = 55),
            responseId = "resp-tool",
            message = ChatMessage(role = "assistant"),
        )

        fun handoff(reason: String) = call("handoff_to_human", buildJsonObject { put("reason", reason) })
    }
}
