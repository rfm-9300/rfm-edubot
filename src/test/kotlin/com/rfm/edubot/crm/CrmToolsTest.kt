package com.rfm.edubot.crm

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrmToolsTest {
    // MongoClient.create(...) doesn't open a connection until a command actually runs, so this is
    // safe to build for tests that only exercise the tool-definition filtering below.
    private val mongo = MongoModule(AppConfig.MongoConfig(uri = "mongodb://localhost:1/", database = "test"))
    private val tenantId = ObjectId()
    private val crmTools = CrmTools(
        ClientRepository(mongo, tenantId),
        QuoteRepository(mongo, tenantId),
        InvoiceRepository(mongo, tenantId),
        StandardItemRepository(mongo, tenantId),
    )

    @Test
    fun `no tools when no CRM module is enabled`() {
        assertEquals(emptyList(), crmTools.definitionsFor(emptySet()))
    }

    @Test
    fun `clients module exposes only client tools`() {
        val names = crmTools.definitionsFor(setOf(DashboardModules.CLIENTS)).map { it.name }.toSet()
        assertEquals(setOf("search_clients", "create_client"), names)
    }

    @Test
    fun `catalog module exposes service templates and standard items`() {
        val names = crmTools.definitionsFor(setOf(DashboardModules.CATALOG)).map { it.name }.toSet()
        assertEquals(setOf("list_service_templates", "list_standard_items"), names)
    }

    @Test
    fun `every tool is reachable via some module and all four modules together yield everything`() {
        val allModules = setOf(DashboardModules.CLIENTS, DashboardModules.CATALOG, DashboardModules.QUOTES, DashboardModules.INVOICES)
        assertEquals(crmTools.definitions.map { it.name }.toSet(), crmTools.definitionsFor(allModules).map { it.name }.toSet())
    }

    @Test
    fun `read-only filtering excludes write tools even when their module is enabled`() {
        val names = crmTools.readOnlyDefinitionsFor(setOf(DashboardModules.QUOTES, DashboardModules.INVOICES)).map { it.name }.toSet()
        assertEquals(setOf("list_quotes", "sum_quotes_by_client", "list_invoices", "sum_invoices_by_client"), names)
        assertTrue("create_quote" !in names && "create_invoice" !in names && "mark_invoice_paid" !in names)
    }
}
