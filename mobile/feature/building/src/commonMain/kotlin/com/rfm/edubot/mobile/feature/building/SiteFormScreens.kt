package com.rfm.edubot.mobile.feature.building

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.data.SiteWriter
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.IssueKind
import com.rfm.edubot.mobile.core.model.IssuePriority
import com.rfm.edubot.mobile.core.model.SiteLog
import com.rfm.edubot.mobile.core.model.Weather
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.BotSwitch
import com.rfm.edubot.mobile.core.ui.ChipRow
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.rememberFormKey

private fun optional(label: String, strings: Strings) = "$label (${strings[Txt.LABEL_OPTIONAL]})"

/** Writing or editing a day of the site diary, the phases it moved included. */
@Composable
fun DiaryFormScreen(
    writer: SiteWriter,
    loadContext: suspend (String?) -> SiteFormContext,
    projectId: String?,
    entry: SiteLog?,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onDone: () -> Unit,
) {
    val vm = viewModel<DiaryFormViewModel>(
        key = rememberFormKey("diary-form:${entry?.id ?: "new:$projectId"}"),
        factory = viewModelFactory {
            initializer { DiaryFormViewModel(writer, loadContext, clock.today().toString(), projectId, entry) }
        },
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(vm) { vm.load() }
    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
        ScreenHeader(
            entry?.let { "${it.projectNumber} · ${clock.day(it.day)}" } ?: strings.module("site-diary"),
            strings[if (entry == null) Txt.BUILDING_DIARY_NEW else Txt.BUILDING_DIARY_EDIT],
        )
        state.error?.let { ErrorPanel(strings.error(it)) }
        if (state.loading && state.context.projects.isEmpty()) {
            LoadingScreen()
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(BotSpace.xl),
            verticalArrangement = Arrangement.spacedBy(BotSpace.md),
        ) {
            if (projectId == null && entry == null) {
                Text(strings[Txt.BUILDING_PICK_PROJECT], style = MaterialTheme.typography.labelMedium, color = BotColors.inkMuted)
                ChipRow(state.context.projects.map { it.id to it.label }, state.projectId, vm::pickProject, horizontalPadding = 0.dp)
            }
            if (entry == null) {
                BotField(
                    value = state.day,
                    onValueChange = { value -> vm.edit { copy(day = value) } },
                    label = strings[Txt.BUILDING_DIARY_DAY],
                    supporting = strings[Txt.CRM_DATE_FORMAT_HINT],
                    modifier = Modifier.fillMaxWidth(),
                    error = !state.dayValid,
                )
            } else {
                Text(strings.format(Txt.BUILDING_WRITTEN_BY, "name" to entry.createdBy), style = MaterialTheme.typography.bodySmall, color = BotColors.inkMuted)
            }
            BotField(
                value = state.workDone,
                onValueChange = { value -> vm.edit { copy(workDone = value) } },
                label = strings[Txt.BUILDING_WORK_DONE],
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )
            Text(strings[Txt.BUILDING_WEATHER], style = MaterialTheme.typography.labelMedium, color = BotColors.inkMuted)
            ChipRow(
                options = listOf("" to strings[Txt.LABEL_NONE]) + Weather.all.map { it to strings.label(Vocab.WEATHER, it) },
                selected = state.weather,
                onSelect = { value -> vm.edit { copy(weather = value) } },
                horizontalPadding = 0.dp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
                BotField(
                    value = state.temperature,
                    onValueChange = { value -> vm.edit { copy(temperature = value) } },
                    label = strings[Txt.BUILDING_TEMPERATURE],
                    modifier = Modifier.weight(1f),
                    numeric = true,
                )
                BotField(
                    value = state.workers,
                    onValueChange = { value -> vm.edit { copy(workers = value) } },
                    label = strings[Txt.BUILDING_WORKERS],
                    modifier = Modifier.weight(1f),
                    numeric = true,
                )
            }
            if (state.context.phases.isNotEmpty()) {
                Text(strings[Txt.BUILDING_PHASE_PROGRESS], style = MaterialTheme.typography.labelMedium, color = BotColors.inkMuted)
                state.context.phases.forEach { phase ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(phase.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = BotColors.ink)
                        BotField(
                            value = state.progress[phase.id] ?: "",
                            onValueChange = { value -> vm.setProgress(phase.id, value) },
                            label = "${phase.progressPct}%",
                            modifier = Modifier.width(96.dp),
                            numeric = true,
                        )
                    }
                }
            }
            listOf(
                Txt.BUILDING_EQUIPMENT to DiaryText.Equipment,
                Txt.BUILDING_MATERIALS to DiaryText.Materials,
                Txt.BUILDING_VISITORS to DiaryText.Visitors,
                Txt.BUILDING_INCIDENTS to DiaryText.Incidents,
            ).forEach { (labelKey, field) ->
                BotField(
                    value = field.read(state),
                    onValueChange = { value -> vm.edit { field.write(this, value) } },
                    label = optional(strings[labelKey], strings),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                )
            }
            BotField(
                value = state.lostHours,
                onValueChange = { value -> vm.edit { copy(lostHours = value) } },
                label = optional(strings[Txt.BUILDING_LOST_HOURS], strings),
                modifier = Modifier.fillMaxWidth(),
                numeric = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(strings[Txt.BUILDING_SHARED], Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = BotColors.ink)
                BotSwitch(checked = state.shared, onCheckedChange = { value -> vm.edit { copy(shared = value) } })
            }
        }
        Row(Modifier.fillMaxWidth().padding(BotSpace.xl), horizontalArrangement = Arrangement.spacedBy(BotSpace.md)) {
            SecondaryButton(strings[Txt.ACTION_CANCEL], onDone, Modifier.weight(1f))
            PrimaryButton(strings[Txt.ACTION_SAVE], vm::save, Modifier.weight(1f), enabled = state.canSave, busy = state.saving)
        }
    }
}

/** The diary's free-text fields, so the form lays them out in one loop. */
private enum class DiaryText(val read: (DiaryFormState) -> String, val write: DiaryFormState.(String) -> DiaryFormState) {
    Equipment({ it.equipment }, { copy(equipment = it) }),
    Materials({ it.materials }, { copy(materials = it) }),
    Visitors({ it.visitors }, { copy(visitors = it) }),
    Incidents({ it.incidents }, { copy(incidents = it) }),
}

/** Logging an issue, on the team's pages or a site manager's. */
@Composable
fun IssueFormScreen(
    writer: SiteWriter,
    loadContext: suspend (String?) -> SiteFormContext,
    projectId: String?,
    strings: Strings,
    padding: PaddingValues,
    onDone: () -> Unit,
) {
    val vm = viewModel<IssueFormViewModel>(
        key = rememberFormKey("issue-form:$projectId"),
        factory = viewModelFactory { initializer { IssueFormViewModel(writer, loadContext, projectId) } },
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(vm) { vm.load() }
    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
        ScreenHeader(strings.module("site-issues"), strings[Txt.BUILDING_ISSUE_NEW])
        state.error?.let { ErrorPanel(strings.error(it)) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(BotSpace.xl),
            verticalArrangement = Arrangement.spacedBy(BotSpace.md),
        ) {
            if (projectId == null) {
                Text(strings[Txt.BUILDING_PICK_PROJECT], style = MaterialTheme.typography.labelMedium, color = BotColors.inkMuted)
                ChipRow(state.context.projects.map { it.id to it.label }, state.projectId, vm::pickProject, horizontalPadding = 0.dp)
            }
            BotField(
                value = state.title,
                onValueChange = { value -> vm.edit { copy(title = value) } },
                label = strings[Txt.BUILDING_ISSUE_TITLE],
                modifier = Modifier.fillMaxWidth(),
            )
            BotField(
                value = state.description,
                onValueChange = { value -> vm.edit { copy(description = value) } },
                label = optional(strings[Txt.BUILDING_ISSUE_DESCRIPTION], strings),
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )
            BotField(
                value = state.location,
                onValueChange = { value -> vm.edit { copy(location = value) } },
                label = optional(strings[Txt.BUILDING_ISSUE_LOCATION], strings),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(strings[Txt.BUILDING_ISSUE_KIND], style = MaterialTheme.typography.labelMedium, color = BotColors.inkMuted)
            ChipRow(IssueKind.all.map { it to strings.label(Vocab.ISSUE_KIND, it) }, state.kind, { value -> vm.edit { copy(kind = value) } }, horizontalPadding = 0.dp)
            Text(strings[Txt.BUILDING_ISSUE_PRIORITY], style = MaterialTheme.typography.labelMedium, color = BotColors.inkMuted)
            ChipRow(IssuePriority.all.map { it to strings.label(Vocab.PRIORITY, it) }, state.priority, { value -> vm.edit { copy(priority = value) } }, horizontalPadding = 0.dp)
            if (state.context.phases.isNotEmpty()) {
                Text(optional(strings[Txt.BUILDING_ISSUE_PHASE], strings), style = MaterialTheme.typography.labelMedium, color = BotColors.inkMuted)
                ChipRow(
                    listOf("" to strings[Txt.LABEL_NONE]) + state.context.phases.map { it.id to it.name },
                    state.phaseId,
                    { value -> vm.edit { copy(phaseId = value) } },
                    horizontalPadding = 0.dp,
                )
            }
            BotField(
                value = state.dueDate,
                onValueChange = { value -> vm.edit { copy(dueDate = value) } },
                label = optional(strings[Txt.BUILDING_ISSUE_DUE], strings),
                supporting = strings[Txt.CRM_DATE_FORMAT_HINT],
                modifier = Modifier.fillMaxWidth(),
                error = !state.dueValid,
            )
        }
        Row(Modifier.fillMaxWidth().padding(BotSpace.xl), horizontalArrangement = Arrangement.spacedBy(BotSpace.md)) {
            SecondaryButton(strings[Txt.ACTION_CANCEL], onDone, Modifier.weight(1f))
            PrimaryButton(strings[Txt.ACTION_SAVE], vm::save, Modifier.weight(1f), enabled = state.canSave, busy = state.saving)
        }
    }
}
