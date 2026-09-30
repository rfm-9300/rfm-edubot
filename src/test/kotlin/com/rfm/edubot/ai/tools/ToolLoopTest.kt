package com.rfm.edubot.ai.tools

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.AiResponse
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.ai.UsageInfo
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolLoopTest {
    private class FakePack : ToolPack {
        val executed = mutableListOf<String>()
        override val definitions = listOf("read_it", "write_it").map { ToolDefinition(it, it, buildJsonObject {}) }
        override fun knows(name: String) = name in setOf("read_it", "write_it")
        override fun isReadOnly(name: String) = name == "read_it"
        override fun moduleOf(name: String): String? = null
        override suspend fun execute(call: ToolCall, context: ToolCallContext): JsonObject {
            executed += call.name
            return buildJsonObject { put("done", call.name) }
        }
    }

    private fun toolUse(vararg names: String, tokens: Int = 10) = AiResponse.ToolUse(
        calls = names.mapIndexed { index, name -> ToolCall("call-$index-$name", name, buildJsonObject {}) },
        usage = UsageInfo(prompt_tokens = tokens, completion_tokens = 1),
        responseId = "r",
        message = ChatMessage(role = "assistant"),
    )

    private fun text(content: String) = AiResponse.Text(content, UsageInfo(prompt_tokens = 5, completion_tokens = 2), "r")

    @Test
    fun `reads run immediately and the answer comes back with the tokens spent`() = runBlocking {
        val ai = mockk<AiClient>()
        coEvery { ai.complete(any(), any(), any(), any()) } returnsMany listOf(toolUse("read_it"), text("Here you go"))
        val pack = FakePack()

        val result = ToolLoop(ai).run(listOf(ChatMessage("user", "hi")), pack)

        assertEquals("Here you go", result.text)
        assertEquals(listOf("read_it"), pack.executed)
        assertEquals(TokenCount(15, 3), result.usage)
    }

    @Test
    fun `a proposed write stops the loop without running it`() = runBlocking {
        val ai = mockk<AiClient>()
        coEvery { ai.complete(any(), any(), any(), any()) } returns toolUse("read_it", "write_it")
        val pack = FakePack()

        val result = ToolLoop(ai).run(listOf(ChatMessage("user", "do it")), pack, decide = { WriteDecision.PROPOSE })

        assertNull(result.text)
        assertEquals(listOf("write_it"), result.proposals.map { it.name })
        assertEquals(listOf("read_it"), pack.executed)
    }

    @Test
    fun `denied and unlisted tools get an error result the model sees`() = runBlocking {
        val ai = mockk<AiClient>()
        val seen = slot<List<ChatMessage>>()
        coEvery { ai.complete(capture(seen), any(), any(), any()) } returnsMany listOf(toolUse("write_it"), text("Sorry"))
        val pack = FakePack()

        val result = ToolLoop(ai).run(
            listOf(ChatMessage("user", "do it")),
            pack,
            definitions = pack.definitions.filter { it.name == "read_it" },
        )

        assertEquals("Sorry", result.text)
        assertTrue(pack.executed.isEmpty())
        assertTrue(seen.captured.last().content!!.contains("tool_not_allowed"))
    }

    @Test
    fun `the finishing tool returns the structured result`() = runBlocking {
        val ai = mockk<AiClient>()
        val finish = ToolDefinition("submit_result", "Submit", buildJsonObject {})
        coEvery { ai.complete(any(), any(), any(), any()) } returns AiResponse.ToolUse(
            calls = listOf(ToolCall("c1", "submit_result", buildJsonObject { put("intent", "quote_request") })),
            usage = null,
            responseId = "r",
            message = ChatMessage(role = "assistant"),
        )

        val result = ToolLoop(ai).run(listOf(ChatMessage("user", "classify")), FakePack(), finishTool = finish)

        assertEquals("quote_request", result.submitted?.get("intent")?.jsonPrimitive?.content)
    }

    @Test
    fun `when the iterations run out the model is asked to answer without tools`() = runBlocking {
        val ai = mockk<AiClient>()
        coEvery { ai.complete(any(), any(), any(), any()) } returnsMany listOf(toolUse("read_it"), toolUse("read_it"), text("Final"))

        val result = ToolLoop(ai).run(listOf(ChatMessage("user", "loop")), FakePack(), maxIterations = 2)

        assertEquals("Final", result.text)
    }
}
