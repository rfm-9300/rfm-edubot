package com.rfm.edubot.dashboard

import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.ai.tools.ToolCallContext
import com.rfm.edubot.ai.tools.ToolPack
import com.rfm.edubot.crm.CrmTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardAssistantToolPolicyTest {
    @Test
    fun `assistant only receives tools for enabled dashboard modules`() {
        val definitions = listOf("search_clients", "create_quote", "list_invoices", "list_standard_items")
            .map { ToolDefinition(it, it, buildJsonObject {}) }

        val filtered = DashboardAssistantToolPolicy.filterDefinitions(
            definitions,
            listOf(DashboardModules.CLIENTS, DashboardModules.INVOICES),
        )

        assertEquals(listOf("search_clients", "list_invoices"), filtered.map { it.name })
    }

    @Test
    fun `writes require their owning module and read tools cannot execute as confirmed actions`() {
        assertTrue(DashboardAssistantToolPolicy.canExecuteWrite("create_invoice", listOf(DashboardModules.INVOICES)))
        assertFalse(DashboardAssistantToolPolicy.canExecuteWrite("create_invoice", listOf(DashboardModules.CLIENTS)))
        assertFalse(DashboardAssistantToolPolicy.canExecuteWrite("list_invoices", listOf(DashboardModules.INVOICES)))
        assertFalse(DashboardAssistantToolPolicy.canExecuteWrite("unknown", DashboardModules.catalog))
    }

    @Test
    fun `assistant gates every CRM tool by the same module as the WhatsApp bot`() {
        val definitions = CrmTools.MODULE_OF_TOOL.keys.map { ToolDefinition(it, it, buildJsonObject {}) }
        for (module in DashboardModules.catalog) {
            val expected = CrmTools.MODULE_OF_TOOL.filterValues { it == module }.keys
            val actual = DashboardAssistantToolPolicy.filterDefinitions(definitions, listOf(module)).map { it.name }.toSet()
            assertEquals(expected, actual, "tools exposed for module $module")
        }
    }

    @Test
    fun `service templates follow the catalog module, where the CRM prompt describes them`() {
        val definitions = listOf(ToolDefinition("list_service_templates", "list_service_templates", buildJsonObject {}))
        assertEquals(1, DashboardAssistantToolPolicy.filterDefinitions(definitions, listOf(DashboardModules.CATALOG)).size)
        assertEquals(0, DashboardAssistantToolPolicy.filterDefinitions(definitions, listOf(DashboardModules.QUOTES)).size)
    }

    @Test
    fun `booking tools are gated by the bookings module`() {
        val definitions = listOf("list_bookings", "create_booking", "search_clients")
            .map { ToolDefinition(it, it, buildJsonObject {}) }
        val filtered = DashboardAssistantToolPolicy.filterDefinitions(definitions, listOf(DashboardModules.BOOKINGS))
        assertEquals(listOf("list_bookings", "create_booking"), filtered.map { it.name })
        assertTrue(DashboardAssistantToolPolicy.canExecuteWrite("create_booking", listOf(DashboardModules.BOOKINGS)))
        assertFalse(DashboardAssistantToolPolicy.canExecuteWrite("list_bookings", listOf(DashboardModules.BOOKINGS)))
        assertTrue(DashboardAssistantToolPolicy.isReadOnly("list_available_slots"))
        assertTrue(DashboardAssistantToolPolicy.canExecuteWrite("reschedule_booking", listOf(DashboardModules.BOOKINGS)))
        assertFalse(DashboardAssistantToolPolicy.canExecuteWrite("reschedule_booking", listOf(DashboardModules.CLIENTS)))
    }

    /** An extension's pack: tool name → whether it only reads. */
    private class ExtraPack(private val tools: Map<String, Boolean>, private val module: String = DashboardModules.AGENTS) : ToolPack {
        override val definitions = tools.keys.map { ToolDefinition(it, it, buildJsonObject {}) }
        override fun knows(name: String) = name in tools
        override fun isReadOnly(name: String) = tools[name] == true
        override fun moduleOf(name: String) = module.takeIf { name in tools }
        override suspend fun execute(call: ToolCall, context: ToolCallContext): JsonObject = buildJsonObject {}
    }

    @Test
    fun `an extension's tools follow the module and read-only flag its pack declares`() {
        val agents = ExtraPack(mapOf("list_agents" to true, "pause_agent" to false))
        val definitions = listOf("search_clients", "list_agents", "pause_agent").map { ToolDefinition(it, it, buildJsonObject {}) }
        val both = listOf(DashboardModules.CLIENTS, DashboardModules.AGENTS)

        assertEquals(listOf("search_clients", "list_agents", "pause_agent"), DashboardAssistantToolPolicy.filterDefinitions(definitions, both, agents).map { it.name })
        assertEquals(listOf("search_clients"), DashboardAssistantToolPolicy.filterDefinitions(definitions, listOf(DashboardModules.CLIENTS), agents).map { it.name })
        assertEquals(listOf("search_clients"), DashboardAssistantToolPolicy.filterDefinitions(definitions, both).map { it.name }, "without the pack its tools are unknown")

        assertTrue(DashboardAssistantToolPolicy.canExecuteWrite("pause_agent", both, agents))
        assertFalse(DashboardAssistantToolPolicy.canExecuteWrite("pause_agent", listOf(DashboardModules.CLIENTS), agents))
        assertFalse(DashboardAssistantToolPolicy.canExecuteWrite("pause_agent", both))
        assertFalse(DashboardAssistantToolPolicy.canExecuteWrite("list_agents", both, agents), "reads never run as confirmed actions")
        assertTrue(DashboardAssistantToolPolicy.isReadOnly("list_agents", agents))
        assertFalse(DashboardAssistantToolPolicy.isReadOnly("pause_agent", agents))
        assertFalse(DashboardAssistantToolPolicy.isReadOnly("list_agents"))
    }

    @Test
    fun `an extension can't take over a CRM or booking tool`() {
        val rogue = ExtraPack(mapOf("create_invoice" to true, "list_bookings" to false))

        assertFalse(DashboardAssistantToolPolicy.isReadOnly("create_invoice", rogue), "a CRM write stays a write")
        assertTrue(DashboardAssistantToolPolicy.isReadOnly("list_bookings", rogue))
        assertTrue(DashboardAssistantToolPolicy.canExecuteWrite("create_invoice", listOf(DashboardModules.INVOICES), rogue))
        assertFalse(DashboardAssistantToolPolicy.canExecuteWrite("create_invoice", listOf(DashboardModules.AGENTS), rogue), "and keeps its own module")
        val definitions = listOf(ToolDefinition("create_invoice", "create_invoice", buildJsonObject {}))
        assertEquals(0, DashboardAssistantToolPolicy.filterDefinitions(definitions, listOf(DashboardModules.AGENTS), rogue).size)
    }
}
