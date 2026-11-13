package com.rfm.edubot.mobile.feature.building

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.data.BuildingRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.ChangeOrder
import com.rfm.edubot.mobile.core.model.DashboardModules
import com.rfm.edubot.mobile.core.model.Measurement
import com.rfm.edubot.mobile.core.model.Phase
import com.rfm.edubot.mobile.core.model.Project
import com.rfm.edubot.mobile.core.model.ProjectStatus
import com.rfm.edubot.mobile.core.model.SiteIssue
import com.rfm.edubot.mobile.core.model.SiteLog
import com.rfm.edubot.mobile.core.ui.ActionRow
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.ChoiceDialog
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.FieldRow
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.MetricRow
import com.rfm.edubot.mobile.core.ui.Panel
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ProgressBar
import com.rfm.edubot.mobile.core.ui.PromptDialog
import com.rfm.edubot.mobile.core.ui.RecordCard
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.SectionLabel
import com.rfm.edubot.mobile.core.ui.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectRecordState(
    val project: Project? = null,
    val diary: List<SiteLog> = emptyList(),
    val issues: List<SiteIssue> = emptyList(),
    val changes: List<ChangeOrder> = emptyList(),
    val measurements: List<Measurement> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: AppError? = null,
)

/**
 * A project's record: where it stands against the plan, its phases (whose progress can be updated
 * from site), its money, and what the other Building pages hold for it. Each list is read only when
 * the company has that page: asking for one it lacks answers 403.
 */
class ProjectRecordViewModel(
    private val repository: BuildingRepository,
    private val projectId: String,
    private val modules: Set<String>,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(ProjectRecordState())
    val state: StateFlow<ProjectRecordState> = mutable.asStateFlow()

    fun load() = scope.launch {
        mutable.update { it.copy(loading = true, error = null) }
        val diary = async { if (DashboardModules.SITE_DIARY in modules) repository.diaryFor(projectId).valueOrNull.orEmpty() else emptyList() }
        val issues = async { if (DashboardModules.SITE_ISSUES in modules) repository.issuesFor(projectId).valueOrNull.orEmpty() else emptyList() }
        val changes = async { if (DashboardModules.CHANGE_ORDERS in modules) repository.changesFor(projectId).valueOrNull.orEmpty() else emptyList() }
        val measurements = async { if (DashboardModules.MEASUREMENTS in modules) repository.measurementsFor(projectId).valueOrNull.orEmpty() else emptyList() }
        when (val project = repository.project(projectId)) {
            is Outcome.Success -> mutable.update {
                it.copy(
                    project = project.value,
                    diary = diary.await().sortedByDescending { entry -> entry.day }.take(RECENT),
                    issues = issues.await().filter { issue -> issue.open },
                    changes = changes.await(),
                    measurements = measurements.await().sortedByDescending { measurement -> measurement.number },
                    loading = false,
                )
            }
            is Outcome.Failure -> mutable.update { it.copy(loading = false, error = project.error) }
        }
    }

    fun setStatus(status: String) = change { repository.setProjectStatus(it, status) }

    fun setPhase(phase: Phase, progressPct: Int) = change { repository.setPhaseProgress(it, phase.id, progressPct) }

    private fun change(action: suspend (Project) -> Outcome<Project>) = scope.launch {
        val project = mutable.value.project ?: return@launch
        if (mutable.value.busy) return@launch
        mutable.update { it.copy(busy = true, error = null) }
        when (val done = action(project)) {
            is Outcome.Success -> mutable.update { it.copy(project = done.value, busy = false) }
            is Outcome.Failure -> mutable.update { it.copy(busy = false, error = done.error) }
        }
    }

    private companion object {
        const val RECENT = 5
    }
}

@Composable
fun ProjectRecordScreen(
    repository: BuildingRepository,
    projectId: String,
    modules: Set<String>,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpen: (BuildingRoute) -> Unit,
    onBack: () -> Unit,
) {
    val vm = viewModel<ProjectRecordViewModel>(
        key = "project:$projectId",
        factory = viewModelFactory { initializer { ProjectRecordViewModel(repository, projectId, modules) } },
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(projectId) { vm.load() }
    var pickStatus by remember { mutableStateOf(false) }
    var phaseToSet by remember { mutableStateOf<Phase?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackHeader(strings.module(DashboardModules.PROJECTS), state.project?.label ?: strings.module(DashboardModules.PROJECTS), strings, onBack) }
        state.error?.let { error -> item { ErrorPanel(strings.error(error), retryLabel = strings[Txt.ACTION_RETRY], onRetry = { vm.load() }) } }
        val project = state.project
        if (project == null) {
            item { if (state.loading) LoadingScreen() else EmptyState(strings[Txt.EMPTY_TITLE]) }
            return@LazyColumn
        }
        item {
            RecordCard(project.name, subtitle = listOf(project.number, strings.label(Vocab.STATUS, project.status)).joinToString(" · ")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    val health = project.health.ifBlank { project.status }
                    LabelBadge(strings, Vocab.HEALTH, health, healthTone(health))
                }
                Spacer(Modifier.height(BotSpace.sm))
                ProgressBlock(project.progressPct, project.plannedPct, project.scheduleDays, project.health, strings)
                Spacer(Modifier.height(BotSpace.sm))
                FieldRow(strings[Txt.BUILDING_CLIENT], project.clientName)
                FieldRow(strings[Txt.BUILDING_MANAGER], project.managerName)
                FieldRow(strings[Txt.BUILDING_ADDRESS], project.address)
                FieldRow(
                    strings[Txt.BUILDING_PLANNED],
                    listOfNotNull(project.plannedStart?.let(clock::day), project.plannedEnd?.let(clock::day)).joinToString(" → "),
                )
                FieldRow(strings[Txt.BUILDING_REVISED_END], project.revisedEnd?.takeIf { it != project.plannedEnd }?.let(clock::day))
                FieldRow(strings[Txt.BUILDING_STARTED], project.actualStart?.let(clock::day))
                FieldRow(strings[Txt.BUILDING_LAST_DIARY], project.lastDiaryDay?.let(clock::day))
                FieldRow(strings.module(DashboardModules.SITE_ISSUES), project.openIssues.takeIf { it > 0 }?.let { strings.format(Txt.MY_SITES_OPEN_ISSUES, "count" to it) })
                project.photoCount.takeIf { it > 0 }?.let { count ->
                    FieldRow(strings.format(Txt.BUILDING_PHOTOS, "count" to count), strings[Txt.BUILDING_PHOTOS_WEB])
                }
            }
        }
        item {
            ActionRow {
                if (DashboardModules.SITE_DIARY in modules && project.running) {
                    PrimaryButton(strings[Txt.BUILDING_DIARY_NEW], { onOpen(BuildingRoute.Diary(project.id)) })
                }
                if (DashboardModules.SITE_ISSUES in modules && project.running) {
                    SecondaryButton(strings[Txt.BUILDING_ISSUE_NEW], { onOpen(BuildingRoute.NewIssue(project.id)) })
                }
                SecondaryButton(strings[Txt.BUILDING_SET_STATUS], { pickStatus = true }, enabled = !state.busy)
            }
        }
        item {
            MetricRow(
                leftLabel = strings[Txt.BUILDING_CONTRACT],
                leftValue = money(project.currentContractEur),
                rightLabel = strings[Txt.BUILDING_MEASURED],
                rightValue = money(project.measuredEur),
            )
        }
        if (project.toInvoiceEur > 0 || project.retentionHeldEur > 0) {
            item {
                MetricRow(
                    leftLabel = strings[Txt.BUILDING_TO_INVOICE],
                    leftValue = money(project.toInvoiceEur),
                    rightLabel = strings[Txt.BUILDING_RETENTION_HELD],
                    rightValue = money(project.retentionHeldEur),
                    leftTone = if (project.toInvoiceEur > 0) Tone.Warn else Tone.Neutral,
                )
            }
        }
        if (project.phases.isNotEmpty()) {
            item { SectionLabel(strings[Txt.BUILDING_PHASES]) }
            items(project.phases, key = { "phase-${it.id}" }) { phase ->
                PhaseRow(phase, strings, clock, enabled = !state.busy && project.running) { phaseToSet = phase }
            }
        }
        if (state.diary.isNotEmpty()) {
            item { SectionLabel(strings.module(DashboardModules.SITE_DIARY)) }
            items(state.diary, key = { "diary-${it.id}" }) { entry -> DiaryRow(entry, strings, clock) { onOpen(BuildingRoute.Diary(entry.projectId, entry)) } }
        }
        if (state.issues.isNotEmpty()) {
            item { SectionLabel(strings.module(DashboardModules.SITE_ISSUES)) }
            items(state.issues, key = { "issue-${it.id}" }) { issue -> IssueRow(issue, strings, clock) { onOpen(BuildingRoute.Issue(issue.id)) } }
        }
        if (state.changes.isNotEmpty()) {
            item { SectionLabel(strings.module(DashboardModules.CHANGE_ORDERS)) }
            items(state.changes, key = { "change-${it.id}" }) { change -> ChangeRow(change, strings) { onOpen(BuildingRoute.Change(change.id)) } }
        }
        if (state.measurements.isNotEmpty()) {
            item { SectionLabel(strings.module(DashboardModules.MEASUREMENTS)) }
            items(state.measurements, key = { "measurement-${it.id}" }) { measurement ->
                MeasurementRow(measurement, strings, clock) { onOpen(BuildingRoute.Measurement(measurement.id)) }
            }
        }
        if (project.phases.isEmpty() && state.diary.isEmpty() && state.issues.isEmpty()) {
            item { InfoPanel(strings[Txt.EMPTY_TITLE]) }
        }
    }

    if (pickStatus) {
        val current = state.project?.status
        ChoiceDialog(
            title = strings[Txt.BUILDING_SET_STATUS],
            options = ProjectStatus.all.filterNot { it == current }.map { it to strings.label(Vocab.STATUS, it) },
            selected = current,
            cancelLabel = strings[Txt.ACTION_CANCEL],
            onPick = { pickStatus = false; vm.setStatus(it) },
            onDismiss = { pickStatus = false },
        )
    }
    phaseToSet?.let { phase ->
        PromptDialog(
            title = "${strings[Txt.BUILDING_PHASE_SET]}: ${phase.name}",
            label = strings[Txt.BUILDING_PHASE_PROMPT],
            initial = phase.progressPct.toString(),
            confirmLabel = strings[Txt.ACTION_SAVE],
            cancelLabel = strings[Txt.ACTION_CANCEL],
            canConfirm = { it.trim().toIntOrNull() in 0..100 },
            onConfirm = { text -> phaseToSet = null; vm.setPhase(phase, text.trim().toInt()) },
            onDismiss = { phaseToSet = null },
        )
    }
}

@Composable
private fun PhaseRow(phase: Phase, strings: Strings, clock: TenantClock, enabled: Boolean, onSet: () -> Unit) = Panel(
    Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs),
    onClick = if (enabled) onSet else null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
        Column(Modifier.weight(1f)) {
            Text(phase.name, style = MaterialTheme.typography.titleSmall, color = BotColors.ink)
            val dates = listOfNotNull(phase.plannedStart?.let(clock::day), phase.plannedEnd?.let(clock::day)).joinToString(" → ")
            if (dates.isNotBlank()) Text(dates, style = MaterialTheme.typography.bodySmall, color = BotColors.inkMuted)
        }
        if (phase.state.isNotBlank()) LabelBadge(strings, Vocab.PHASE_STATE, phase.state, phaseTone(phase.state))
    }
    Spacer(Modifier.height(BotSpace.sm))
    ProgressBar(phase.progressPct, plannedPct = phase.plannedPct, tone = phaseTone(phase.state).takeIf { it != Tone.Neutral } ?: Tone.Accent)
    Spacer(Modifier.height(BotSpace.xs))
    Text(
        phase.plannedPct?.let { strings.format(Txt.BUILDING_PROGRESS, "done" to phase.progressPct, "planned" to it) } ?: "${phase.progressPct}%",
        style = MaterialTheme.typography.bodySmall,
        color = BotColors.inkMuted,
    )
}
