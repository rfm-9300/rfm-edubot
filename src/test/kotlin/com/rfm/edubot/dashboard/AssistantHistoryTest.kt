package com.rfm.edubot.dashboard

import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantHistoryTest {
    private val now = Clock.System.now()
    private fun message(role: String, content: String, action: AssistantAction? = null, error: String? = null) =
        AssistantMessage(role = role, content = content, createdAt = now, action = action, error = error)

    private fun action(status: String, result: JsonObject? = null) = AssistantAction(
        id = "act_1",
        toolName = "create_client",
        arguments = buildJsonObject { put("name", "Rui"); put("phone", "+351 912 000 000") },
        status = status,
        result = result,
    )

    private fun outcomeOf(status: String, result: JsonObject? = null): JsonObject =
        Json.parseToJsonElement(AssistantHistory.toChat(listOf(message("assistant", "", action(status, result))))[1].content!!).jsonObject

    @Test
    fun `a proposal reads as the call it was, followed by how it ended`() {
        val chat = AssistantHistory.toChat(
            listOf(
                message("user", "Cria o Rui"),
                message("assistant", "Aqui está.", action(AssistantActionStatus.PENDING)),
                message("user", "Obrigado"),
            ),
        )

        assertEquals(listOf("user", "assistant", "tool", "user"), chat.map { it.role })
        val call = chat[1].toolCalls!!.single()
        assertEquals("act_1", call.id)
        assertEquals("create_client", call.function.name)
        assertEquals("Rui", Json.parseToJsonElement(call.function.arguments).jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Aqui está.", chat[1].content, "what the model wrote with it stays")
        assertEquals("act_1", chat[2].toolCallId)
    }

    @Test
    fun `every outcome tells the model what happened`() {
        assertEquals("waiting_for_confirmation", outcomeOf(AssistantActionStatus.PENDING)["status"]!!.jsonPrimitive.content)
        assertEquals("cancelled", outcomeOf(AssistantActionStatus.CANCELLED)["status"]!!.jsonPrimitive.content)
        assertEquals("expired", outcomeOf(AssistantActionStatus.EXPIRED)["status"]!!.jsonPrimitive.content)
        assertEquals("running", outcomeOf(AssistantActionStatus.EXECUTING)["status"]!!.jsonPrimitive.content)
        val created = buildJsonObject { put("created", true); put("id", "abc"); put("number", "CLT-009") }
        assertEquals(created, outcomeOf(AssistantActionStatus.CONFIRMED, created), "a confirmed change reads as its result, ids included")
        val failed = buildJsonObject { put("error", "phone_taken") }
        assertEquals(failed, outcomeOf(AssistantActionStatus.FAILED, failed))
    }

    @Test
    fun `turns that failed are left out, and long results are cut`() {
        val chat = AssistantHistory.toChat(listOf(message("user", "Olá"), message("assistant", "", error = AssistantErrors.MODEL_UNAVAILABLE), message("user", "Olá?")))
        assertEquals(listOf("user", "user"), chat.map { it.role })

        val huge = buildJsonObject { put("notes", "x".repeat(10_000)) }
        val cut = AssistantHistory.toChat(listOf(message("assistant", "", action(AssistantActionStatus.CONFIRMED, huge))))[1].content!!
        assertTrue(cut.length <= 2_000)
        assertNull(AssistantHistory.toChat(listOf(message("assistant", "", action(AssistantActionStatus.PENDING))))[0].content, "an empty note isn't sent")
    }
}
