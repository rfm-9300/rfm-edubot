package com.rfm.edubot.ai

import com.rfm.edubot.dashboard.DashboardModules
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemPromptsTest {
    @Test
    fun `no CRM prompt when no CRM module is enabled`() {
        assertNull(SystemPrompts.crmPromptFor(emptySet()))
        assertNull(SystemPrompts.crmPromptFor(setOf(DashboardModules.BOOKINGS, DashboardModules.INSTAGRAM)))
    }

    @Test
    fun `only the catalog tool bullet is listed when only catalog is enabled`() {
        // The "Ferramentas disponiveis" bullet list is the actual per-module gate — it's what tells
        // the model which tools it may call. The shared Regras prose may still cross-reference other
        // tool names generically (e.g. "chame create_quote..."), which is fine since those tools are
        // never actually offered to the model when their module is disabled (see CrmToolsTest).
        val prompt = SystemPrompts.crmPromptFor(setOf(DashboardModules.CATALOG))!!
        val toolBulletSection = prompt.substringAfter("Ferramentas disponiveis:").substringBefore("Regras:")
        assertTrue(toolBulletSection.contains("list_standard_items"))
        assertFalse(toolBulletSection.contains("search_clients"))
        assertFalse(toolBulletSection.contains("create_quote"))
        assertFalse(toolBulletSection.contains("create_invoice"))
    }

    @Test
    fun `full CRM module set includes every tool block and the shared rules`() {
        val prompt = SystemPrompts.crmPromptFor(
            setOf(DashboardModules.CLIENTS, DashboardModules.CATALOG, DashboardModules.QUOTES, DashboardModules.INVOICES),
        )!!
        for (tool in listOf(
            "search_clients", "create_client",
            "list_service_templates", "list_standard_items",
            "create_quote", "update_quote", "list_quotes", "sum_quotes_by_client",
            "create_invoice", "list_invoices", "mark_invoice_paid", "sum_invoices_by_client",
        )) {
            assertTrue(prompt.contains(tool), "expected prompt to mention $tool")
        }
        assertTrue(prompt.contains("Regras:"))
    }

    @Test
    fun `CRM prompt no longer forces Brazilian Portuguese`() {
        val prompt = SystemPrompts.crmPromptFor(setOf(DashboardModules.CLIENTS))!!
        assertFalse(prompt.contains("portugues brasileiro"))
    }
}
