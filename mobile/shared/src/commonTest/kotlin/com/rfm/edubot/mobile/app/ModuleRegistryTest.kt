package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.core.localization.AppLocale
import com.rfm.edubot.mobile.core.localization.Localization
import com.rfm.edubot.mobile.core.model.DashboardModules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The "More" list used to offer every module id `/app/api/me` returned, while the shell could only
 * render nine of them — so tapping Bookings or Agents re-rendered the same list, labelled with the
 * raw module id. These tests are about never shipping that again.
 */
class ModuleRegistryTest {
    private val fullPlan = ModuleRegistry.catalog.map { it.id }

    @Test
    fun `only modules the app can open are offered`() {
        val offered = ModuleRegistry.available(fullPlan)
        assertTrue(offered.all { it.supported }, "an unsupported module must never be a tappable row")
    }

    @Test
    fun `every offered module routes somewhere the shell renders`() {
        // The shell's `when` ends in CrmScreen, which only knows the CRM sections.
        val crmSections = setOf(
            DashboardModules.CLIENTS, DashboardModules.QUOTES, DashboardModules.INVOICES,
            DashboardModules.CATALOG, DashboardModules.SERVICES, DashboardModules.PAYMENTS,
            DashboardModules.SUPPLIERS, DashboardModules.EMPLOYEES,
        )
        val handledDirectly = setOf(
            DashboardModules.OVERVIEW, DashboardModules.CONVERSATIONS, DashboardModules.CONTACTS,
            DashboardModules.AI_ASSISTANT, DashboardModules.AGENTS, DashboardModules.BOOKINGS,
            DashboardModules.PERSONA, DashboardModules.SETTINGS, DashboardModules.MY_HOURS,
        )
        val routable = crmSections + handledDirectly
        (ModuleRegistry.available(fullPlan) + ModuleRegistry.available(fullPlan, employee = true)).forEach { module ->
            assertTrue(module.id in routable, "${module.id} is offered but the shell has no screen for it")
        }
    }

    @Test
    fun `a module the tenant pays for but the app cannot render is named rather than faked`() {
        val webOnly = ModuleRegistry.webOnly(fullPlan).map { it.id }
        assertEquals(listOf(DashboardModules.INSTAGRAM, DashboardModules.TIMESHEETS), webOnly)
        assertFalse(
            ModuleRegistry.available(fullPlan).any { it.id in webOnly },
            "it must not also appear as something tappable",
        )
    }

    @Test
    fun `an employee gets their clock and settings and nothing of the company's`() {
        val plan = listOf(DashboardModules.MY_HOURS, DashboardModules.MY_SERVICES)
        assertEquals(
            listOf(DashboardModules.MY_HOURS, DashboardModules.SETTINGS),
            ModuleRegistry.available(plan, employee = true).map { it.id },
            "their token is refused by every company endpoint, so a company screen would sign them out",
        )
        assertEquals(listOf(DashboardModules.MY_HOURS), ModuleRegistry.bottomBar(plan, employee = true).map { it.id })
        assertEquals(DashboardModules.MY_HOURS, ModuleRegistry.startModule(plan, employee = true))
        assertEquals(listOf(DashboardModules.MY_SERVICES), ModuleRegistry.webOnly(plan, employee = true).map { it.id })
    }

    @Test
    fun `an employee without the time clock lands on settings rather than the company's home`() {
        val plan = listOf(DashboardModules.MY_SERVICES)
        assertEquals(DashboardModules.SETTINGS, ModuleRegistry.startModule(plan, employee = true))
        assertFalse(ModuleRegistry.available(plan, employee = true).any { it.id == DashboardModules.OVERVIEW })
    }

    @Test
    fun `a company session never offers an employee's own pages`() {
        val listed = ModuleRegistry.available(fullPlan).map { it.id } + ModuleRegistry.webOnly(fullPlan).map { it.id }
        assertFalse(DashboardModules.MY_HOURS in listed)
        assertFalse(DashboardModules.MY_SERVICES in listed)
    }

    @Test
    fun `a module the tenant does not have is not listed at all`() {
        val plan = listOf(DashboardModules.OVERVIEW, DashboardModules.CONVERSATIONS)
        val listed = ModuleRegistry.available(plan).map { it.id } + ModuleRegistry.webOnly(plan).map { it.id }
        assertFalse(DashboardModules.AGENTS in listed)
        assertFalse(DashboardModules.INSTAGRAM in listed)
    }

    @Test
    fun `settings stays reachable even when the plan omits it`() {
        val plan = listOf(DashboardModules.CONVERSATIONS)
        val offered = ModuleRegistry.available(plan).map { it.id }
        assertTrue(DashboardModules.SETTINGS in offered, "otherwise there is no way to sign out")
        assertTrue(DashboardModules.OVERVIEW in offered)
    }

    @Test
    fun `the bottom bar leaves room for the More tab`() {
        val bar = ModuleRegistry.bottomBar(fullPlan)
        assertTrue(bar.size <= ModuleRegistry.BOTTOM_BAR_SLOTS)
    }

    @Test
    fun `the bottom bar only holds modules the tenant has`() {
        val plan = listOf(DashboardModules.OVERVIEW, DashboardModules.BOOKINGS)
        val bar = ModuleRegistry.bottomBar(plan).map { it.id }
        assertEquals(listOf(DashboardModules.OVERVIEW, DashboardModules.BOOKINGS), bar)
    }

    @Test
    fun `the start module is one the app can open`() {
        listOf(
            fullPlan,
            listOf(DashboardModules.OVERVIEW),
            listOf(DashboardModules.CLIENTS),
            emptyList(),
        ).forEach { plan ->
            val start = ModuleRegistry.startModule(plan)
            assertTrue(
                ModuleRegistry.available(plan).any { it.id == start },
                "$start is not openable for a tenant with $plan",
            )
        }
    }

    @Test
    fun `the More list excludes whatever is already on the bottom bar`() {
        val bar = ModuleRegistry.bottomBar(fullPlan).map { it.id }
        val more = ModuleRegistry.sections(fullPlan, bar).flatMap { it.modules }.map { it.id }
        assertTrue(bar.none { it in more }, "a module should not be in two places at once")
    }

    @Test
    fun `the More list covers everything not on the bar`() {
        val bar = ModuleRegistry.bottomBar(fullPlan).map { it.id }
        val more = ModuleRegistry.sections(fullPlan, bar).flatMap { it.modules }.map { it.id }
        assertEquals(
            ModuleRegistry.available(fullPlan).map { it.id }.toSet(),
            (bar + more).toSet(),
            "every openable module must be reachable from somewhere",
        )
    }

    @Test
    fun `sections come back in the web sidebar's order`() {
        val groups = ModuleRegistry.sections(fullPlan).map { it.group }
        assertEquals(groups.sortedBy { it.ordinal }, groups)
    }

    @Test
    fun `every module in the catalog has copy in every language`() {
        AppLocale.entries.forEach { locale ->
            val strings = Localization.of(locale)
            ModuleRegistry.catalog.forEach { module ->
                assertTrue(
                    strings.module(module.id) != "module.${module.id}",
                    "${locale.tag} has no name for ${module.id}; the old shell rendered the raw id",
                )
            }
        }
    }

    @Test
    fun `module ids are unique`() {
        assertEquals(ModuleRegistry.catalog.size, ModuleRegistry.catalog.map { it.id }.toSet().size)
    }

    @Test
    fun `bottom bar labels fit the tab without eliding`() {
        AppLocale.entries.forEach { locale ->
            val strings = Localization.of(locale)
            (ModuleRegistry.bottomBar(fullPlan) + ModuleRegistry.bottomBar(fullPlan, employee = true)).forEach { module ->
                val label = strings.moduleShort(module.id)
                assertTrue(
                    label.length <= BOTTOM_BAR_LABEL_CHARS,
                    "${locale.tag}'s \"$label\" for ${module.id} is too long for a 76dp tab",
                )
            }
        }
    }

    private companion object {
        /** What fits a bottom-bar tab at the label style before Compose elides it. */
        const val BOTTOM_BAR_LABEL_CHARS = 10
    }
}
