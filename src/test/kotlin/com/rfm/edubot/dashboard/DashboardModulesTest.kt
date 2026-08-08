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
    fun `AI assistant is an optional tenant module`() {
        assertEquals(true, DashboardModules.AI_ASSISTANT in DashboardModules.optional)
        assertEquals(
            DashboardModules.alwaysOn + DashboardModules.AI_ASSISTANT,
            DashboardModules.effectiveFor(tenant(listOf(DashboardModules.AI_ASSISTANT))),
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

    private fun tenant(enabledModules: List<String>?) = Tenant(
        slug = "test",
        name = "Test",
        channels = emptyList(),
        enabledModules = enabledModules,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
}
