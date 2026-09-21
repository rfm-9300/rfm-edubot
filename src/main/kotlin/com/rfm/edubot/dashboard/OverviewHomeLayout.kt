package com.rfm.edubot.dashboard

import com.rfm.edubot.tenant.model.Tenant
import kotlinx.serialization.Serializable

/**
 * Tenant-chosen Home layout. Cards default to visible; [Tenant.overviewHiddenCards] lists
 * the ones the manager turned off. Unknown ids are dropped. Snapshot payloads stay on
 * the overview DTO so sidebar counts keep working; the dashboard hides cards in the UI.
 */
object OverviewHomeLayout {
    const val HIGHLIGHTS = "highlights"
    const val PULSE = "pulse"
    const val ATTENTION = "attention"
    const val SETUP = "setup"
    const val CASH = "cash"
    const val PIPELINE = "pipeline"
    const val CUSTOMERS = "customers"
    const val INBOX = "inbox"
    const val CALENDAR = "calendar"
    const val SOCIAL = "social"
    const val CATALOG = "catalog"
    const val ASSISTANT = "assistant"

    const val GROUP_SECTIONS = "sections"
    const val GROUP_SNAPSHOTS = "snapshots"

    val sections = listOf(HIGHLIGHTS, PULSE, ATTENTION, SETUP)
    val snapshots = listOf(CASH, PIPELINE, CUSTOMERS, INBOX, CALENDAR, SOCIAL, CATALOG, ASSISTANT)
    val all = (sections + snapshots).toSet()

    fun sanitize(hidden: List<String>?): List<String> =
        hidden.orEmpty().filter { it in all }.distinct()

    fun available(modules: Set<String>): List<OverviewLayoutOptionDto> {
        val options = sections.map { OverviewLayoutOptionDto(it, GROUP_SECTIONS) }.toMutableList()
        fun addSnapshot(id: String, enabled: Boolean) {
            if (enabled) options += OverviewLayoutOptionDto(id, GROUP_SNAPSHOTS)
        }
        addSnapshot(CASH, DashboardModules.INVOICES in modules)
        addSnapshot(PIPELINE, DashboardModules.QUOTES in modules)
        addSnapshot(CUSTOMERS, DashboardModules.CLIENTS in modules)
        addSnapshot(INBOX, DashboardModules.CONVERSATIONS in modules || DashboardModules.CONTACTS in modules)
        addSnapshot(CALENDAR, DashboardModules.BOOKINGS in modules)
        addSnapshot(SOCIAL, DashboardModules.INSTAGRAM in modules)
        addSnapshot(CATALOG, DashboardModules.CATALOG in modules)
        addSnapshot(ASSISTANT, DashboardModules.AI_ASSISTANT in modules)
        return options
    }

    fun dto(tenant: Tenant): OverviewLayoutDto {
        val modules = DashboardModules.effectiveFor(tenant).toSet()
        return OverviewLayoutDto(
            hidden = sanitize(tenant.overviewHiddenCards),
            available = available(modules),
        )
    }

    fun apply(dto: OverviewDto, hidden: List<String>?): OverviewDto {
        val hide = sanitize(hidden).toSet()
        val highlights = if (HIGHLIGHTS in hide) {
            emptyList()
        } else {
            dto.highlights.filter { snapshotForHighlight(it.key) !in hide }
        }
        return dto.copy(
            hiddenCards = hide.toList(),
            highlights = highlights,
        )
    }

    fun snapshotForHighlight(key: String): String? = when (key) {
        OverviewMath.HIGHLIGHT_COLLECTED,
        OverviewMath.HIGHLIGHT_OUTSTANDING,
        OverviewMath.HIGHLIGHT_OVERDUE,
        -> CASH
        OverviewMath.HIGHLIGHT_PIPELINE,
        OverviewMath.HIGHLIGHT_WIN_RATE,
        -> PIPELINE
        OverviewMath.HIGHLIGHT_WAITING,
        OverviewMath.HIGHLIGHT_MESSAGES,
        OverviewMath.HIGHLIGHT_CONTACTS,
        -> INBOX
        OverviewMath.HIGHLIGHT_BOOKINGS_TODAY -> CALENDAR
        OverviewMath.HIGHLIGHT_CLIENTS -> CUSTOMERS
        OverviewMath.HIGHLIGHT_UNREPLIED -> SOCIAL
        else -> null
    }
}

@Serializable
data class OverviewLayoutDto(
    val hidden: List<String> = emptyList(),
    val available: List<OverviewLayoutOptionDto> = emptyList(),
)

@Serializable
data class OverviewLayoutOptionDto(
    val id: String,
    val group: String,
)
