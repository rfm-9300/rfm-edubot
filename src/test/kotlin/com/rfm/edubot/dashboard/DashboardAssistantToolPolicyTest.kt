package com.rfm.edubot.dashboard

import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.bookings.BookingTools
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.CrmTools
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.persistence.MongoModule
import kotlinx.serialization.json.buildJsonObject
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardAssistantToolPolicyTest {
    private val mongo = MongoModule(AppConfig.MongoConfig(uri = "mongodb://localhost:1/", database = "test"))
    private val tenantId = ObjectId()
    private val crmTools = CrmTools(
        ClientRepository(mongo, tenantId),
        QuoteRepository(mongo, tenantId),
        InvoiceRepository(mongo, tenantId),
        StandardItemRepository(mongo, tenantId),
    )
    private val allToolNames = crmTools.definitions.map { it.name } + BookingTools.TOOL_NAMES

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
    fun `booking tools are gated by the bookings module`() {
        val definitions = listOf("list_bookings", "create_booking", "search_clients")
            .map { ToolDefinition(it, it, buildJsonObject {}) }
        val filtered = DashboardAssistantToolPolicy.filterDefinitions(definitions, listOf(DashboardModules.BOOKINGS))
        assertEquals(listOf("list_bookings", "create_booking"), filtered.map { it.name })
        assertTrue(DashboardAssistantToolPolicy.canExecuteWrite("create_booking", listOf(DashboardModules.BOOKINGS)))
        assertFalse(DashboardAssistantToolPolicy.canExecuteWrite("list_bookings", listOf(DashboardModules.BOOKINGS)))
        assertTrue(DashboardAssistantToolPolicy.isReadOnly("list_available_slots"))
    }

    @Test
    fun `assistant policy stays aligned with CRM and booking module ownership`() {
        assertEquals(CrmTools.MODULE_OF_TOOL + BookingTools.MODULE_OF_TOOL, DashboardAssistantToolPolicy.moduleByTool)
        assertEquals(allToolNames.toSet(), DashboardAssistantToolPolicy.moduleByTool.keys)
        assertEquals(
            crmTools.definitionsFor(setOf(DashboardModules.CATALOG)).map { it.name }.toSet(),
            setOf("list_service_templates", "list_standard_items"),
        )
    }

    @Test
    fun `service templates are catalog tools not quote tools`() {
        val definitions = allToolNames.map { ToolDefinition(it, it, buildJsonObject {}) }
        val catalog = DashboardAssistantToolPolicy.filterDefinitions(definitions, listOf(DashboardModules.CATALOG)).map { it.name }.toSet()
        val quotes = DashboardAssistantToolPolicy.filterDefinitions(definitions, listOf(DashboardModules.QUOTES)).map { it.name }.toSet()
        assertTrue("list_service_templates" in catalog)
        assertTrue("list_standard_items" in catalog)
        assertFalse("list_service_templates" in quotes)
        assertEquals(setOf("create_quote", "update_quote", "list_quotes", "sum_quotes_by_client"), quotes)
    }

    @Test
    fun `each CRM and bookings module exposes its assistant tools and nothing else`() {
        val definitions = allToolNames.map { ToolDefinition(it, it, buildJsonObject {}) }
        val expected = mapOf(
            DashboardModules.CLIENTS to setOf("search_clients", "create_client"),
            DashboardModules.CATALOG to setOf("list_service_templates", "list_standard_items"),
            DashboardModules.QUOTES to setOf("create_quote", "update_quote", "list_quotes", "sum_quotes_by_client"),
            DashboardModules.INVOICES to setOf("create_invoice", "list_invoices", "mark_invoice_paid", "sum_invoices_by_client"),
            DashboardModules.BOOKINGS to BookingTools.TOOL_NAMES,
        )
        expected.forEach { (module, tools) ->
            assertEquals(
                tools,
                DashboardAssistantToolPolicy.filterDefinitions(definitions, listOf(module)).map { it.name }.toSet(),
                "assistant tools for $module",
            )
        }
    }

    @Test
    fun `dashboard modules without assistant tools stay classified so coverage cannot silently shrink`() {
        val operable = setOf(
            DashboardModules.CLIENTS,
            DashboardModules.CATALOG,
            DashboardModules.QUOTES,
            DashboardModules.INVOICES,
            DashboardModules.BOOKINGS,
        )
        val host = setOf(DashboardModules.AI_ASSISTANT)
        val noTools = setOf(
            DashboardModules.OVERVIEW,
            DashboardModules.CONVERSATIONS,
            DashboardModules.CONTACTS,
            DashboardModules.SETTINGS,
            DashboardModules.PERSONA,
            DashboardModules.SERVICES,
            DashboardModules.SUPPLIERS,
            DashboardModules.PAYMENTS,
            DashboardModules.INSTAGRAM,
        )
        assertEquals(DashboardModules.catalog.toSet(), operable + host + noTools)
        assertEquals(operable, DashboardAssistantToolPolicy.moduleByTool.values.toSet())

        val definitions = allToolNames.map { ToolDefinition(it, it, buildJsonObject {}) }
        (noTools + host).forEach { module ->
            assertEquals(
                emptyList(),
                DashboardAssistantToolPolicy.filterDefinitions(definitions, listOf(module)).map { it.name },
                "assistant must not receive tools for $module",
            )
        }
    }
}
