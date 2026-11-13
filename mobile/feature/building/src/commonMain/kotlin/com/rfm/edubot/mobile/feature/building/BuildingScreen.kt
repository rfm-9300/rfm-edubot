package com.rfm.edubot.mobile.feature.building

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.data.BuildingRepository
import com.rfm.edubot.mobile.core.data.CachedResource
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.ChangeOrder
import com.rfm.edubot.mobile.core.model.ChangeOrderStatus
import com.rfm.edubot.mobile.core.model.DashboardModules
import com.rfm.edubot.mobile.core.model.IssueStatus
import com.rfm.edubot.mobile.core.model.Measurement
import com.rfm.edubot.mobile.core.model.MeasurementStatus
import com.rfm.edubot.mobile.core.model.Project
import com.rfm.edubot.mobile.core.model.ProjectStatus
import com.rfm.edubot.mobile.core.model.SiteIssue
import com.rfm.edubot.mobile.core.model.SiteLog
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.ChipRow
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.RefreshBar
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.Tone
import kotlinx.coroutines.launch

/** The five Building pages' lists; a row opens its record. */
@Composable
fun BuildingScreen(
    repository: BuildingRepository,
    section: String,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpen: (BuildingRoute) -> Unit,
) {
    when (section) {
        DashboardModules.SITE_DIARY -> BuildingList(
            resource = repository.diary,
            section = section,
            strings = strings,
            padding = padding,
            emptyKey = Txt.BUILDING_DIARY_EMPTY,
            onNew = { onOpen(BuildingRoute.Diary(projectId = null)) },
            chips = null,
            key = { it.id },
        ) { entry -> DiaryRow(entry, strings, clock) { onOpen(BuildingRoute.Diary(entry.projectId, entry)) } }

        DashboardModules.SITE_ISSUES -> BuildingList(
            resource = repository.issues,
            section = section,
            strings = strings,
            padding = padding,
            emptyKey = Txt.BUILDING_ISSUES_EMPTY,
            onNew = { onOpen(BuildingRoute.NewIssue(projectId = null)) },
            chips = listOf(OPEN_ISSUES, IssueStatus.RESOLVED, IssueStatus.CLOSED, ALL).map { key ->
                key to when (key) {
                    OPEN_ISSUES -> strings.label(Vocab.ISSUE_STATUS, IssueStatus.OPEN)
                    ALL -> strings[Txt.LABEL_ALL]
                    else -> strings.label(Vocab.ISSUE_STATUS, key)
                }
            } to { issue: SiteIssue, chip: String ->
                when (chip) {
                    OPEN_ISSUES -> issue.open
                    ALL -> true
                    else -> issue.status == chip
                }
            },
            key = { it.id },
        ) { issue -> IssueRow(issue, strings, clock) { onOpen(BuildingRoute.Issue(issue.id)) } }

        DashboardModules.CHANGE_ORDERS -> BuildingList(
            resource = repository.changes,
            section = section,
            strings = strings,
            padding = padding,
            emptyKey = Txt.BUILDING_CHANGES_EMPTY,
            onNew = null,
            chips = (listOf(ALL to strings[Txt.LABEL_ALL]) + ChangeOrderStatus.all.map { it to strings.label(Vocab.CHANGE_STATUS, it) }) to
                { change: ChangeOrder, chip: String -> chip == ALL || change.status == chip },
            key = { it.id },
        ) { change -> ChangeRow(change, strings) { onOpen(BuildingRoute.Change(change.id)) } }

        DashboardModules.MEASUREMENTS -> BuildingList(
            resource = repository.measurements,
            section = section,
            strings = strings,
            padding = padding,
            emptyKey = Txt.BUILDING_MEASUREMENTS_EMPTY,
            onNew = null,
            chips = (listOf(ALL to strings[Txt.LABEL_ALL]) + MeasurementStatus.all.map { it to strings.label(Vocab.MEASUREMENT_STATUS, it) }) to
                { measurement: Measurement, chip: String -> chip == ALL || measurement.status == chip },
            key = { it.id },
        ) { measurement -> MeasurementRow(measurement, strings, clock) { onOpen(BuildingRoute.Measurement(measurement.id)) } }

        else -> BuildingList(
            resource = repository.projects,
            section = DashboardModules.PROJECTS,
            strings = strings,
            padding = padding,
            emptyKey = Txt.BUILDING_PROJECTS_EMPTY,
            onNew = null,
            chips = listOf(RUNNING, ProjectStatus.COMPLETED, ALL).map { key ->
                key to when (key) {
                    RUNNING -> strings.label(Vocab.STATUS, ProjectStatus.ACTIVE)
                    ALL -> strings[Txt.LABEL_ALL]
                    else -> strings.label(Vocab.STATUS, key)
                }
            } to { project: Project, chip: String ->
                when (chip) {
                    RUNNING -> project.running
                    ALL -> true
                    else -> project.status == chip
                }
            },
            key = { it.id },
        ) { project -> ProjectRow(project, strings) { onOpen(BuildingRoute.Project(project.id)) } }
    }
}

/** A list with its header, refresh, failure, cache notice, optional status chips (the first is the default). */
@Composable
private fun <T : Any> BuildingList(
    resource: CachedResource<List<T>>,
    section: String,
    strings: Strings,
    padding: PaddingValues,
    emptyKey: String,
    onNew: (() -> Unit)?,
    chips: Pair<List<Pair<String, String>>, (T, String) -> Boolean>?,
    key: (T) -> Any,
    row: @Composable (T) -> Unit,
) {
    val state by resource.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(resource) { resource.load() }
    var chip by rememberSaveable(section) { mutableStateOf(chips?.first?.firstOrNull()?.first.orEmpty()) }
    val all = state.value.orEmpty()
    val rows = chips?.let { (_, matches) -> all.filter { matches(it, chip) } } ?: all

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader(strings[Txt.NAV_GROUP_BUILDING], strings.module(section)) {
                Row {
                    TextButton(onClick = { scope.launch { resource.refresh() } }) { Text(strings[Txt.ACTION_REFRESH], color = BotColors.inkMuted) }
                    onNew?.let { TextButton(onClick = it) { Text(strings[Txt.ACTION_NEW], color = BotColors.accentDeep) } }
                }
            }
            RefreshBar(state.loading && state.hasValue)
        }
        state.error?.let { error ->
            item { ErrorPanel(strings.error(error), retryLabel = strings[Txt.ACTION_RETRY], onRetry = { scope.launch { resource.refresh() } }) }
        }
        if (state.fromCache) item { InfoPanel(strings[Txt.OFFLINE_SNAPSHOT], tone = Tone.Info) }
        chips?.let { (options, _) -> item { ChipRow(options, chip, { chip = it }) } }
        when {
            all.isEmpty() && state.loading -> item { LoadingScreen() }
            rows.isEmpty() -> item { EmptyState(strings[Txt.EMPTY_TITLE], strings[emptyKey]) }
            else -> items(rows, key = key) { row(it) }
        }
    }
}

/** A row with a title, a detail line, a badge and optionally the progress bar under them. */
@Composable
internal fun BuildingRow(
    title: String,
    detail: String,
    badge: (@Composable () -> Unit)?,
    onClick: (() -> Unit)?,
    trailing: String? = null,
    below: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = BotSpace.xl, vertical = BotSpace.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = BotColors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = BotColors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = BotColors.inkMuted) }
                badge?.invoke()
            }
        }
        below?.let {
            Spacer(Modifier.height(BotSpace.sm))
            it()
        }
    }
    HorizontalDivider(color = BotColors.lineSoft)
}

@Composable
internal fun ProjectRow(project: Project, strings: Strings, onClick: () -> Unit) = BuildingRow(
    title = project.label,
    detail = listOfNotNull(project.clientName, project.managerName, project.address).filter { it.isNotBlank() }.joinToString(" · "),
    badge = {
        val health = project.health.ifBlank { project.status }
        LabelBadge(strings, Vocab.HEALTH, health, healthTone(health))
    },
    onClick = onClick,
    below = { ProgressBlock(project.progressPct, project.plannedPct, project.scheduleDays, project.health, strings) },
)

@Composable
internal fun DiaryRow(entry: SiteLog, strings: Strings, clock: TenantClock, onClick: (() -> Unit)?) = BuildingRow(
    title = listOf(clock.day(entry.day), "${entry.projectNumber} ${entry.projectName}".trim()).filter { it.isNotBlank() }.joinToString(" · "),
    detail = listOfNotNull(
        entry.weather?.let { strings.label(Vocab.WEATHER, it) },
        entry.workers?.let { "${strings[Txt.BUILDING_WORKERS]}: $it" },
        entry.workDone,
    ).joinToString(" · "),
    badge = if (entry.shared) ({ com.rfm.edubot.mobile.core.ui.Badge(strings[Txt.BUILDING_SHARED_BADGE], Tone.Info) }) else null,
    onClick = onClick,
)

@Composable
internal fun IssueRow(issue: SiteIssue, strings: Strings, clock: TenantClock, onClick: (() -> Unit)?) = BuildingRow(
    title = listOf(issue.number, issue.title).filter { it.isNotBlank() }.joinToString(" · "),
    detail = listOfNotNull(
        "${issue.projectNumber} ${issue.projectName}".trim().takeIf { it.isNotBlank() },
        issue.location,
        strings.label(Vocab.PRIORITY, issue.priority).takeIf { issue.priority.isNotBlank() },
        issue.dueDate?.let { "${strings[Txt.BUILDING_ISSUE_DUE]} ${clock.day(it)}" },
    ).joinToString(" · "),
    badge = {
        if (issue.overdue) {
            com.rfm.edubot.mobile.core.ui.Badge(strings[Txt.BUILDING_ISSUE_OVERDUE], Tone.Bad)
        } else {
            LabelBadge(strings, Vocab.ISSUE_STATUS, issue.status, issueTone(issue.status, false))
        }
    },
    onClick = onClick,
)

@Composable
internal fun ChangeRow(change: ChangeOrder, strings: Strings, onClick: () -> Unit) = BuildingRow(
    title = listOf(change.number, change.title).filter { it.isNotBlank() }.joinToString(" · "),
    detail = listOfNotNull(
        "${change.projectNumber} ${change.projectName}".trim().takeIf { it.isNotBlank() },
        strings.label(Vocab.CHANGE_KIND, change.kind).takeIf { change.kind.isNotBlank() },
        change.daysImpact.takeIf { it != 0 }?.let { strings.format(Txt.BUILDING_CHANGE_DAYS, "days" to it) },
    ).joinToString(" · "),
    trailing = money(change.signedEur),
    badge = { LabelBadge(strings, Vocab.CHANGE_STATUS, change.status, changeTone(change.status)) },
    onClick = onClick,
)

@Composable
internal fun MeasurementRow(measurement: Measurement, strings: Strings, clock: TenantClock, onClick: () -> Unit) = BuildingRow(
    title = strings.format(Txt.BUILDING_MEASUREMENT_TITLE, "number" to measurement.number),
    detail = listOfNotNull(
        "${measurement.projectNumber} ${measurement.projectName}".trim().takeIf { it.isNotBlank() },
        measurement.periodEnd.takeIf { it.isNotBlank() }?.let { "${strings[Txt.BUILDING_PERIOD_END]} ${clock.day(it)}" },
    ).joinToString(" · "),
    trailing = money(measurement.netEur),
    badge = { LabelBadge(strings, Vocab.MEASUREMENT_STATUS, measurement.status, measurementTone(measurement.status)) },
    onClick = onClick,
)

private const val ALL = "ALL"
private const val RUNNING = "RUNNING"
private const val OPEN_ISSUES = "OPEN_ISSUES"
