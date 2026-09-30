package com.rfm.edubot.ai.tools

import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.bookings.BookingCallContext
import com.rfm.edubot.bookings.BookingTools
import com.rfm.edubot.crm.CrmTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where a tool call comes from, for tools that behave differently per caller (bookings default the chat's customer). */
data class ToolCallContext(val booking: BookingCallContext? = null)

/**
 * A set of LLM tools with what the callers need to gate them: the dashboard module that owns each
 * tool and whether it only reads. CRM, bookings and agent actions each implement it; callers combine
 * them with [CompositeToolPack].
 */
interface ToolPack {
    val definitions: List<ToolDefinition>
    fun knows(name: String): Boolean
    fun isReadOnly(name: String): Boolean
    /** The module a tenant needs for this tool, or null when the tool isn't tied to one. */
    fun moduleOf(name: String): String?
    suspend fun execute(call: ToolCall, context: ToolCallContext = ToolCallContext()): JsonObject

    fun definitionsFor(modules: Collection<String>): List<ToolDefinition> =
        definitions.filter { definition -> moduleOf(definition.name)?.let { it in modules } ?: true }

    fun readOnlyDefinitionsFor(modules: Collection<String>): List<ToolDefinition> =
        definitionsFor(modules).filter { isReadOnly(it.name) }
}

class CrmToolPack(private val tools: CrmTools) : ToolPack {
    override val definitions: List<ToolDefinition> get() = tools.definitions
    override fun knows(name: String): Boolean = name in CrmTools.MODULE_OF_TOOL
    override fun isReadOnly(name: String): Boolean = name in CrmTools.READ_ONLY_TOOL_NAMES
    override fun moduleOf(name: String): String? = CrmTools.MODULE_OF_TOOL[name]
    override suspend fun execute(call: ToolCall, context: ToolCallContext): JsonObject = tools.execute(call)
}

class BookingToolPack(private val tools: BookingTools) : ToolPack {
    override val definitions: List<ToolDefinition> get() = tools.definitions
    override fun knows(name: String): Boolean = tools.knows(name)
    override fun isReadOnly(name: String): Boolean = name in BookingTools.READ_ONLY_TOOL_NAMES
    override fun moduleOf(name: String): String? = BookingTools.MODULE_OF_TOOL[name]
    override suspend fun execute(call: ToolCall, context: ToolCallContext): JsonObject = tools.execute(call, context.booking)
}

/** Routes each call to the first pack that knows it. */
class CompositeToolPack(private val packs: List<ToolPack>) : ToolPack {
    constructor(vararg packs: ToolPack?) : this(packs.filterNotNull())

    override val definitions: List<ToolDefinition> get() = packs.flatMap { it.definitions }
    private fun packFor(name: String): ToolPack? = packs.firstOrNull { it.knows(name) }
    override fun knows(name: String): Boolean = packFor(name) != null
    override fun isReadOnly(name: String): Boolean = packFor(name)?.isReadOnly(name) ?: false
    override fun moduleOf(name: String): String? = packFor(name)?.moduleOf(name)
    override suspend fun execute(call: ToolCall, context: ToolCallContext): JsonObject =
        packFor(call.name)?.execute(call, context) ?: buildJsonObject { put("error", "Unknown tool: ${call.name}") }
}
