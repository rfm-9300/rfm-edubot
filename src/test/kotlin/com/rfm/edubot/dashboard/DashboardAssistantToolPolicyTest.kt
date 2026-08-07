package com.rfm.edubot.dashboard

import com.rfm.edubot.ai.ToolDefinition
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
}
