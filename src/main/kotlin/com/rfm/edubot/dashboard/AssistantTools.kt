package com.rfm.edubot.dashboard

import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.ai.tools.ToolCallContext
import com.rfm.edubot.ai.tools.ToolPack
import com.rfm.edubot.dashboard.assistant.AssistantToolGroup
import com.rfm.edubot.dashboard.assistant.AssistantToolSession
import com.rfm.edubot.dashboard.assistant.BillingTools
import com.rfm.edubot.dashboard.assistant.ClientTools
import com.rfm.edubot.dashboard.assistant.ConversationTools
import com.rfm.edubot.dashboard.assistant.OverviewTools
import com.rfm.edubot.dashboard.assistant.PayablesTools
import com.rfm.edubot.dashboard.assistant.ToolGroupSpec
import com.rfm.edubot.dashboard.assistant.ToolProblem
import com.rfm.edubot.dashboard.assistant.toolError
import com.rfm.edubot.persistence.MongoModule
import kotlinx.serialization.json.JsonObject

/**
 * Tools only the dashboard assistant gets, because it works for the company's team: the business
 * overview, a client's whole record and changes to it, money in and out with real filters, and the inbox.
 * Its create_client, list_quotes and list_invoices take the place of the customer bot's versions, so the
 * assistant checks what the company requires of a client and counts overdue invoices the way Home does.
 * [usable] are the modules the assistant may use; [inbox] sends replies, and without it none are offered.
 * Each area's tools are a group in `dashboard/assistant`; this routes a call to its group.
 */
internal class AssistantTools(
    mongo: MongoModule,
    ctx: DashboardContext,
    private val usable: Collection<String>,
    private val inbox: InboxService? = null,
) : ToolPack {
    private val session = AssistantToolSession(mongo, ctx, usable, inbox)
    private val groups: List<AssistantToolGroup> = listOf(
        OverviewTools(session),
        ClientTools(session),
        BillingTools(session),
        PayablesTools(session),
        ConversationTools(session),
    )

    override val definitions: List<ToolDefinition> = DEFINITIONS.filter { offered(it.name) }
    override fun knows(name: String): Boolean = name in MODULE_OF
    override fun isReadOnly(name: String): Boolean = name in READ_ONLY
    override fun moduleOf(name: String): String? = MODULE_OF[name]

    private fun offered(name: String): Boolean = when (name) {
        BillingTools.CONVERT_QUOTE -> DashboardModules.QUOTES in usable && DashboardModules.INVOICES in usable
        ConversationTools.REPLY -> inbox != null
        else -> true
    }

    private fun groupOf(name: String): AssistantToolGroup? = groups.firstOrNull { name in it.spec.moduleOf }

    override suspend fun execute(call: ToolCall, context: ToolCallContext): JsonObject = try {
        if (!offered(call.name)) throw ToolProblem("not_available", "This isn't available here.")
        groupOf(call.name)?.execute(call.name, call.arguments) ?: toolError("unknown_tool", "Unknown tool.")
    } catch (e: ToolProblem) {
        toolError(e.code, e.message)
    }

    override suspend fun describe(call: ToolCall): JsonObject? = groupOf(call.name)?.describe(call.name, call.arguments)

    override suspend fun check(call: ToolCall): JsonObject? = try {
        groupOf(call.name)?.check(call.name, call.arguments)
        null
    } catch (e: ToolProblem) {
        toolError(e.code, e.message)
    }

    companion object {
        /** The groups' tools, in the order the model is offered them. */
        private val SPECS: List<ToolGroupSpec> = listOf(OverviewTools, ClientTools, BillingTools, PayablesTools, ConversationTools)

        val MODULE_OF: Map<String, String> = SPECS.fold(emptyMap()) { all, spec -> all + spec.moduleOf }
        val READ_ONLY: Set<String> = SPECS.flatMap { it.readOnly }.toSet()
        private val DEFINITIONS: List<ToolDefinition> = SPECS.flatMap { it.definitions }
    }
}
