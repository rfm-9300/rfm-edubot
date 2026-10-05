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

object ModuleRegistry {
    /** Order and grouping follow the web sidebar so the two products read the same. */
    val catalog: List<MobileModule> = listOf(
        MobileModule(DashboardModules.OVERVIEW, ModuleGroup.Home, "⌂"),
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
        MobileModule(DashboardModules.CATALOG, ModuleGroup.Business, "⊞"),
        MobileModule(DashboardModules.BOOKINGS, ModuleGroup.Business, "▦"),
        MobileModule(DashboardModules.PERSONA, ModuleGroup.Automation, "☺"),
        MobileModule(DashboardModules.AI_ASSISTANT, ModuleGroup.Automation, "✦"),
        MobileModule(DashboardModules.AGENTS, ModuleGroup.Automation, "⚙"),
        MobileModule(DashboardModules.SETTINGS, ModuleGroup.Setup, "⚒"),
    )

    private val byId: Map<String, MobileModule> = catalog.associateBy { it.id }

    /**
     * Settings is always reachable: signing out, changing language and switching company live
     * there, and a tenant whose plan omits the module would otherwise be stuck.
     */
    private val alwaysReachable = setOf(DashboardModules.OVERVIEW, DashboardModules.SETTINGS)

    fun find(id: String): MobileModule? = byId[id]

    /** The modules the app can open for this tenant, in nav order. */
    fun available(enabled: Collection<String>): List<MobileModule> {
        val granted = enabled.toSet() + alwaysReachable
        return catalog.filter { it.supported && it.id in granted }
    }

    /** Grouped for the "More" list, skipping whatever is already on the bottom bar. */
    fun sections(enabled: Collection<String>, excluding: Collection<String> = emptySet()): List<NavSection> {
        val hidden = excluding.toSet()
        return available(enabled)
            .filterNot { it.id in hidden }
            .groupBy { it.group }
            .map { (group, modules) -> NavSection(group, modules) }
            .sortedBy { it.group.ordinal }
    }

    /**
     * The tenant pays for these and the app has no screen for them yet. Surfaced honestly rather
     * than as a tap that re-renders the same list.
     */
    fun webOnly(enabled: Collection<String>): List<MobileModule> {
        val granted = enabled.toSet()
        return catalog.filter { !it.supported && it.id in granted }
    }

    /**
     * The bottom bar. Home, the inbox and whichever automation surface this tenant has, because
     * those are what someone opens a phone for; everything else is one tap away under More.
     */
    fun bottomBar(enabled: Collection<String>): List<MobileModule> {
        val available = available(enabled).map { it.id }.toSet()
        return listOf(
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
    fun startModule(enabled: Collection<String>): String =
        bottomBar(enabled).firstOrNull()?.id ?: available(enabled).firstOrNull()?.id ?: DashboardModules.SETTINGS

    /** `More` takes the last slot, so the bar never holds more than this many modules. */
    const val BOTTOM_BAR_SLOTS = 3
}
