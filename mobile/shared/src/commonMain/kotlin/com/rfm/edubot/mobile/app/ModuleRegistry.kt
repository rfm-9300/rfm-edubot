package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.core.model.DashboardModules

enum class ModuleGroup { Home, Inbox, Business, Automation, Setup }

/**
 * A dashboard module as the app knows it.
 *
 * [supported] is the field that matters. `GET /app/api/me` lists every module the tenant pays for,
 * and the app renders only some of them. Listing the rest as if they were screens produced taps
 * that went nowhere, so an unsupported module is shown under its own heading instead of pretending.
 */
data class MobileModule(
    val id: String,
    val group: ModuleGroup,
    val glyph: String,
    val supported: Boolean = true,
)

data class NavSection(val group: ModuleGroup, val modules: List<MobileModule>)

/**
 * The functions take `employee` because an employee's token is refused everywhere but their own pages:
 * offering them a company screen would sign them out on its first call.
 */
object ModuleRegistry {
    /** Order and grouping follow the web sidebar so the two products read the same. */
    val catalog: List<MobileModule> = listOf(
        MobileModule(DashboardModules.OVERVIEW, ModuleGroup.Home, "⌂"),
        MobileModule(DashboardModules.MY_HOURS, ModuleGroup.Home, "◷"),
        MobileModule(DashboardModules.MY_SERVICES, ModuleGroup.Home, "✓", supported = false),
        MobileModule(DashboardModules.CONVERSATIONS, ModuleGroup.Inbox, "◌"),
        MobileModule(DashboardModules.CONTACTS, ModuleGroup.Inbox, "◎"),
        MobileModule(DashboardModules.INSTAGRAM, ModuleGroup.Inbox, "◍", supported = false),
        MobileModule(DashboardModules.CLIENTS, ModuleGroup.Business, "☰"),
        MobileModule(DashboardModules.SERVICES, ModuleGroup.Business, "✓"),
        MobileModule(DashboardModules.QUOTES, ModuleGroup.Business, "✎"),
        MobileModule(DashboardModules.INVOICES, ModuleGroup.Business, "€"),
        MobileModule(DashboardModules.PAYMENTS, ModuleGroup.Business, "↗"),
        MobileModule(DashboardModules.SUPPLIERS, ModuleGroup.Business, "⌸"),
        MobileModule(DashboardModules.EMPLOYEES, ModuleGroup.Business, "☷"),
        MobileModule(DashboardModules.TIMESHEETS, ModuleGroup.Business, "◷", supported = false),
        MobileModule(DashboardModules.CATALOG, ModuleGroup.Business, "⊞"),
        MobileModule(DashboardModules.BOOKINGS, ModuleGroup.Business, "▦"),
        MobileModule(DashboardModules.PERSONA, ModuleGroup.Automation, "☺"),
        MobileModule(DashboardModules.AI_ASSISTANT, ModuleGroup.Automation, "✦"),
        MobileModule(DashboardModules.AGENTS, ModuleGroup.Automation, "⚙"),
        MobileModule(DashboardModules.SETTINGS, ModuleGroup.Setup, "⚒"),
    )

    private val byId: Map<String, MobileModule> = catalog.associateBy { it.id }

    /** Only an employee's sign-in has these, and it has nothing else but Settings. */
    private val employeePages = setOf(DashboardModules.MY_HOURS, DashboardModules.MY_SERVICES)

    /**
     * Settings is always reachable: signing out, changing language and switching company live
     * there, and a tenant whose plan omits the module would otherwise be stuck. Home is too, except
     * for an employee, whose session can't read the company's overview.
     */
    private fun alwaysReachable(employee: Boolean): Set<String> =
        if (employee) setOf(DashboardModules.SETTINGS) else setOf(DashboardModules.OVERVIEW, DashboardModules.SETTINGS)

    private fun MobileModule.grantedTo(granted: Set<String>, employee: Boolean): Boolean =
        id in granted && (id == DashboardModules.SETTINGS || (id in employeePages) == employee)

    fun find(id: String): MobileModule? = byId[id]

    /** The modules the app can open for this tenant, in nav order. */
    fun available(enabled: Collection<String>, employee: Boolean = false): List<MobileModule> {
        val granted = enabled.toSet() + alwaysReachable(employee)
        return catalog.filter { it.supported && it.grantedTo(granted, employee) }
    }

    /** Grouped for the "More" list, skipping whatever is already on the bottom bar. */
    fun sections(enabled: Collection<String>, excluding: Collection<String> = emptySet(), employee: Boolean = false): List<NavSection> {
        val hidden = excluding.toSet()
        return available(enabled, employee)
            .filterNot { it.id in hidden }
            .groupBy { it.group }
            .map { (group, modules) -> NavSection(group, modules) }
            .sortedBy { it.group.ordinal }
    }

    /**
     * The tenant pays for these and the app has no screen for them yet. Surfaced honestly rather
     * than as a tap that re-renders the same list.
     */
    fun webOnly(enabled: Collection<String>, employee: Boolean = false): List<MobileModule> {
        val granted = enabled.toSet()
        return catalog.filter { !it.supported && it.grantedTo(granted, employee) }
    }

    /**
     * The bottom bar. Home, the inbox and whichever automation surface this tenant has, because
     * those are what someone opens a phone for; everything else is one tap away under More. An
     * employee's is their clock.
     */
    fun bottomBar(enabled: Collection<String>, employee: Boolean = false): List<MobileModule> {
        val available = available(enabled, employee).map { it.id }.toSet()
        return listOf(
            DashboardModules.MY_HOURS,
            DashboardModules.OVERVIEW,
            DashboardModules.CONVERSATIONS,
            DashboardModules.AGENTS,
            DashboardModules.AI_ASSISTANT,
            DashboardModules.BOOKINGS,
        )
            .filter { it in available }
            .take(BOTTOM_BAR_SLOTS)
            .mapNotNull(::find)
    }

    /** Where to land after signing in, and the fallback when a module disappears from the plan. */
    fun startModule(enabled: Collection<String>, employee: Boolean = false): String =
        bottomBar(enabled, employee).firstOrNull()?.id
            ?: available(enabled, employee).firstOrNull()?.id
            ?: DashboardModules.SETTINGS

    /** `More` takes the last slot, so the bar never holds more than this many modules. */
    const val BOTTOM_BAR_SLOTS = 3
}
