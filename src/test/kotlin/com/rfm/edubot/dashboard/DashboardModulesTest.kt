package com.rfm.edubot.dashboard

import com.rfm.edubot.tenant.model.Tenant
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DashboardModulesTest {
    @Test
    fun `null enabled modules preserves access to the full catalog`() {
        assertEquals(DashboardModules.catalog, DashboardModules.effectiveFor(tenant(enabledModules = null)))
    }

    @Test
    fun `effective modules keep core modules and discard unknown values`() {
        assertEquals(
            DashboardModules.alwaysOn + DashboardModules.PERSONA,
            DashboardModules.effectiveFor(tenant(listOf(DashboardModules.PERSONA, "unknown"))),
        )
    }

    @Test
    fun `sanitization keeps always-on modules and selected optional modules`() {
        assertEquals(
            DashboardModules.alwaysOn + listOf(DashboardModules.CLIENTS, DashboardModules.CATALOG),
            DashboardModules.sanitize(listOf(DashboardModules.CLIENTS, "unknown", DashboardModules.CATALOG)),
        )
    }

    @Test
    fun `null selection remains the migration default`() {
        assertNull(DashboardModules.sanitize(null))
    }

    @Test
    fun `messaging modules are optional so a tenant can run CRM-only`() {
        listOf(DashboardModules.CONVERSATIONS, DashboardModules.CONTACTS, DashboardModules.SETTINGS)
            .forEach { assertEquals(true, it in DashboardModules.optional, "$it should be optional") }
        assertEquals(
            listOf(DashboardModules.OVERVIEW, DashboardModules.CLIENTS, DashboardModules.SERVICES),
            DashboardModules.effectiveFor(tenant(listOf(DashboardModules.CLIENTS))),
        )
    }

    @Test
    fun `overview is the only module that cannot be switched off`() {
        assertEquals(listOf(DashboardModules.OVERVIEW), DashboardModules.alwaysOn)
        assertEquals(listOf(DashboardModules.OVERVIEW), DashboardModules.sanitize(emptyList()))
    }

    @Test
    fun `AI assistant is an optional tenant module`() {
        assertEquals(true, DashboardModules.AI_ASSISTANT in DashboardModules.optional)
        assertEquals(
            DashboardModules.alwaysOn + DashboardModules.AI_ASSISTANT,
            DashboardModules.effectiveFor(tenant(listOf(DashboardModules.AI_ASSISTANT))),
        )
    }

    @Test
    fun `services is an optional tenant module enabled with clients`() {
        assertEquals(true, DashboardModules.SERVICES in DashboardModules.optional)
        assertEquals(
            DashboardModules.alwaysOn + DashboardModules.SERVICES,
            DashboardModules.effectiveFor(tenant(listOf(DashboardModules.SERVICES))),
        )
    }

    @Test
    fun `bookings is an optional tenant module`() {
        assertEquals(true, DashboardModules.BOOKINGS in DashboardModules.optional)
        assertEquals(
            DashboardModules.alwaysOn + DashboardModules.BOOKINGS,
            DashboardModules.effectiveFor(tenant(listOf(DashboardModules.BOOKINGS))),
        )
    }

    @Test
    fun `instagram is an optional tenant module`() {
        assertEquals(true, DashboardModules.INSTAGRAM in DashboardModules.optional)
        assertEquals(
            DashboardModules.alwaysOn + DashboardModules.INSTAGRAM,
            DashboardModules.effectiveFor(tenant(listOf(DashboardModules.INSTAGRAM))),
        )
    }

    @Test
    fun `employees is an optional tenant module`() {
        assertEquals(true, DashboardModules.EMPLOYEES in DashboardModules.optional)
        assertEquals(
            DashboardModules.alwaysOn + DashboardModules.EMPLOYEES,
            DashboardModules.effectiveFor(tenant(listOf(DashboardModules.EMPLOYEES))),
        )
    }

    @Test
    fun `payments is an optional tenant module that also enables suppliers`() {
        assertEquals(true, DashboardModules.PAYMENTS in DashboardModules.optional)
        assertEquals(true, DashboardModules.SUPPLIERS in DashboardModules.optional)
        assertEquals(
            listOf(DashboardModules.OVERVIEW, DashboardModules.PAYMENTS, DashboardModules.SUPPLIERS),
            DashboardModules.effectiveFor(tenant(listOf(DashboardModules.PAYMENTS))),
        )
        assertEquals(
            DashboardModules.alwaysOn + DashboardModules.SUPPLIERS,
            DashboardModules.effectiveFor(tenant(listOf(DashboardModules.SUPPLIERS))),
        )
    }

    private fun tenant(enabledModules: List<String>?) = Tenant(
        slug = "test",
        name = "Test",
        channels = emptyList(),
        enabledModules = enabledModules,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
}
