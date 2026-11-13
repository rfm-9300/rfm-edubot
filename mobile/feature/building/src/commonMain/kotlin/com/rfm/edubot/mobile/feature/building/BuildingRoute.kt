package com.rfm.edubot.mobile.feature.building

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.data.BuildingRepository
import com.rfm.edubot.mobile.core.data.MySitesRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.model.SiteIssue
import com.rfm.edubot.mobile.core.model.SiteLog

/**
 * The Building screens above a module's list. The team's go through [BuildingRepository]; the
 * `MySite…` ones are a site manager's own, through [MySitesRepository].
 */
sealed interface BuildingRoute {
    data class Project(val id: String) : BuildingRoute

    /** Writes a diary entry ([entry] null) or edits one; [projectId] null picks the project in the form. */
    data class Diary(val projectId: String?, val entry: SiteLog? = null) : BuildingRoute

    data class NewIssue(val projectId: String?) : BuildingRoute

    data class Issue(val id: String) : BuildingRoute

    data class Change(val id: String) : BuildingRoute

    data class Measurement(val id: String) : BuildingRoute

    data class MySite(val id: String) : BuildingRoute

    data class MySiteDiary(val siteId: String, val entry: SiteLog? = null) : BuildingRoute

    data class MySiteNewIssue(val siteId: String) : BuildingRoute

    data class MySiteIssue(val siteId: String, val issue: SiteIssue) : BuildingRoute
}

/**
 * Renders one [BuildingRoute]. The team's routes need [building], a site manager's [mySites]; the
 * shell only pushes a route its session has the repository for.
 */
@Composable
fun BuildingRouteScreen(
    route: BuildingRoute,
    building: BuildingRepository?,
    mySites: MySitesRepository?,
    modules: Set<String>,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpen: (BuildingRoute) -> Unit,
    onBack: () -> Unit,
) {
    when (route) {
        is BuildingRoute.Project -> building?.let { ProjectRecordScreen(it, route.id, modules, strings, clock, padding, onOpen, onBack) }
        is BuildingRoute.Diary -> building?.let { repository ->
            DiaryFormScreen(
                writer = repository,
                loadContext = { projectId -> teamContext(repository, projectId) },
                projectId = route.projectId,
                entry = route.entry,
                strings = strings,
                clock = clock,
                padding = padding,
                onDone = onBack,
            )
        }
        is BuildingRoute.NewIssue -> building?.let { repository ->
            IssueFormScreen(repository, { projectId -> teamContext(repository, projectId) }, route.projectId, strings, padding, onDone = onBack)
        }
        is BuildingRoute.Issue -> building?.let { repository ->
            IssueRecordScreen(
                key = "issue:${route.id}",
                load = { repository.issue(route.id) },
                writer = repository,
                strings = strings,
                clock = clock,
                padding = padding,
                onOpenProject = { onOpen(BuildingRoute.Project(it)) },
                onBack = onBack,
            )
        }
        is BuildingRoute.Change -> building?.let { ChangeRecordScreen(it, route.id, strings, clock, padding, { id -> onOpen(BuildingRoute.Project(id)) }, onBack) }
        is BuildingRoute.Measurement -> building?.let {
            MeasurementRecordScreen(it, route.id, modules, strings, clock, padding, { id -> onOpen(BuildingRoute.Project(id)) }, onBack)
        }
        is BuildingRoute.MySite -> mySites?.let { MySiteScreen(it, route.id, strings, clock, padding, onOpen, onBack) }
        is BuildingRoute.MySiteDiary -> mySites?.let { repository ->
            DiaryFormScreen(
                writer = repository,
                loadContext = { mySiteContext(repository, route.siteId) },
                projectId = route.siteId,
                entry = route.entry,
                strings = strings,
                clock = clock,
                padding = padding,
                onDone = onBack,
            )
        }
        is BuildingRoute.MySiteNewIssue -> mySites?.let { repository ->
            IssueFormScreen(repository, { mySiteContext(repository, route.siteId) }, route.siteId, strings, padding, onDone = onBack)
        }
        is BuildingRoute.MySiteIssue -> mySites?.let { repository ->
            IssueRecordScreen(
                key = "my-issue:${route.issue.id}",
                load = { com.rfm.edubot.mobile.core.common.Outcome.Success(route.issue) },
                writer = repository,
                strings = strings,
                clock = clock,
                padding = padding,
                onOpenProject = null,
                onBack = onBack,
            )
        }
    }
}

/** The team picks among the running projects; a project's phases come from its record. */
internal suspend fun teamContext(repository: BuildingRepository, projectId: String?): SiteFormContext {
    repository.projects.load()
    val projects = repository.projects.state.value.value.orEmpty().filter { it.running }.map { ProjectOption(it.id, it.label) }
    val phases = projectId?.let { id -> repository.project(id).valueOrNull?.phases }.orEmpty()
        .map { PhaseOption(it.id, it.name, it.progressPct) }
    return SiteFormContext(projects, phases)
}

/** A site manager writes for the site they opened. */
internal suspend fun mySiteContext(repository: MySitesRepository, siteId: String): SiteFormContext {
    val site = repository.site(siteId).valueOrNull ?: return SiteFormContext()
    return SiteFormContext(
        projects = listOf(ProjectOption(site.site.id, site.site.label)),
        phases = site.phases.map { PhaseOption(it.id, it.name, it.progressPct) },
    )
}
