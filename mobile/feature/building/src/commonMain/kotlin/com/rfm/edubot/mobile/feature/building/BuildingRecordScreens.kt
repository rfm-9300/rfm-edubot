package com.rfm.edubot.mobile.feature.building

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.common.parseDay
import com.rfm.edubot.mobile.core.data.BuildingRepository
import com.rfm.edubot.mobile.core.data.SiteWriter
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.ChangeOrder
import com.rfm.edubot.mobile.core.model.ChangeOrderStatus
import com.rfm.edubot.mobile.core.model.ChangeStatus
import com.rfm.edubot.mobile.core.model.DashboardModules
import com.rfm.edubot.mobile.core.model.IssueStatus
import com.rfm.edubot.mobile.core.model.Measurement
import com.rfm.edubot.mobile.core.model.MeasurementStatus
import com.rfm.edubot.mobile.core.model.SaveIssue
import com.rfm.edubot.mobile.core.model.SiteIssue
import com.rfm.edubot.mobile.core.ui.ActionRow
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.FieldRow
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.LineRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.MetricRow
import com.rfm.edubot.mobile.core.ui.Panel
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.PromptDialog
import com.rfm.edubot.mobile.core.ui.RecordCard
import com.rfm.edubot.mobile.core.ui.RecordState
import com.rfm.edubot.mobile.core.ui.RecordViewModel
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.SectionLabel
import com.rfm.edubot.mobile.core.ui.Tone

@Composable
private fun <T : Any> Frame(
    state: RecordState<T>,
    eyebrow: String,
    title: String,
    strings: Strings,
    padding: PaddingValues,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    content: LazyListScope.(T) -> Unit,
) = LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
    item { BackHeader(eyebrow, title, strings, onBack) }
    state.error?.let { error -> item { ErrorPanel(strings.error(error), retryLabel = strings[Txt.ACTION_RETRY], onRetry = onRetry) } }
    val value = state.value
    if (value == null) item { if (state.loading) LoadingScreen() else EmptyState(strings[Txt.EMPTY_TITLE]) } else content(value)
}

/** The statuses an issue moves to from [status], in the order work goes. */
internal fun nextIssueStatuses(status: String): List<String> = when (status) {
    IssueStatus.OPEN -> listOf(IssueStatus.IN_PROGRESS, IssueStatus.RESOLVED)
    IssueStatus.IN_PROGRESS -> listOf(IssueStatus.RESOLVED, IssueStatus.OPEN)
    IssueStatus.RESOLVED -> listOf(IssueStatus.CLOSED, IssueStatus.OPEN)
    else -> listOf(IssueStatus.OPEN)
}

/**
 * An issue and its history, moved along with an optional note. [load] and [writer] are the team's or
 * a site manager's; a site manager's issue comes from their site's page, which already holds it.
 */
@Composable
fun IssueRecordScreen(
    key: String,
    load: suspend () -> Outcome<SiteIssue>,
    writer: SiteWriter,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpenProject: ((String) -> Unit)?,
    onBack: () -> Unit,
) {
    val vm = viewModel<RecordViewModel<SiteIssue>>(key = key, factory = viewModelFactory { initializer { RecordViewModel(load) } })
    val state by vm.state.collectAsState()
    LaunchedEffect(key) { vm.load() }
    var moving by remember { mutableStateOf<String?>(null) }

    Frame(state, strings.module(DashboardModules.SITE_ISSUES), state.value?.number ?: strings.module(DashboardModules.SITE_ISSUES), strings, padding, onBack, { vm.load() }) { issue ->
        item {
            RecordCard(issue.title, subtitle = "${issue.projectNumber} ${issue.projectName}".trim()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (issue.overdue) com.rfm.edubot.mobile.core.ui.Badge(strings[Txt.BUILDING_ISSUE_OVERDUE], Tone.Bad)
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    LabelBadge(strings, Vocab.ISSUE_STATUS, issue.status, issueTone(issue.status, false))
                }
                FieldRow(strings[Txt.BUILDING_ISSUE_DESCRIPTION], issue.description)
                FieldRow(strings[Txt.BUILDING_ISSUE_LOCATION], issue.location)
                FieldRow(strings[Txt.BUILDING_ISSUE_KIND], issue.kind.takeIf { it.isNotBlank() }?.let { strings.label(Vocab.ISSUE_KIND, it) })
                FieldRow(strings[Txt.BUILDING_ISSUE_PRIORITY], issue.priority.takeIf { it.isNotBlank() }?.let { strings.label(Vocab.PRIORITY, it) })
                FieldRow(strings[Txt.BUILDING_ISSUE_PHASE], issue.phaseName)
                FieldRow(strings[Txt.BUILDING_ISSUE_ASSIGNEE], issue.assigneeName)
                FieldRow(strings[Txt.BUILDING_ISSUE_DUE], issue.dueDate?.let(clock::day), tone = if (issue.overdue) Tone.Bad else Tone.Neutral)
            }
        }
        item {
            ActionRow {
                nextIssueStatuses(issue.status).forEachIndexed { index, next ->
                    val label = strings.label(Vocab.ISSUE_STATUS, next)
                    if (index == 0) {
                        PrimaryButton(label, { moving = next }, busy = state.busy)
                    } else {
                        SecondaryButton(label, { moving = next }, enabled = !state.busy)
                    }
                }
                onOpenProject?.let { open -> SecondaryButton(strings.module(DashboardModules.PROJECTS), { open(issue.projectId) }) }
            }
        }
        if (issue.photos.isNotEmpty()) item { InfoPanel(strings.format(Txt.BUILDING_PHOTOS, "count" to issue.photos.size), strings[Txt.BUILDING_PHOTOS_WEB]) }
        if (issue.history.isNotEmpty()) {
            item { SectionLabel(strings[Txt.BUILDING_ISSUE_HISTORY]) }
            items(issue.history.reversed()) { event ->
                Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
                    Text(
                        listOfNotNull(event.from?.let { strings.label(Vocab.ISSUE_STATUS, it) }, strings.label(Vocab.ISSUE_STATUS, event.to)).joinToString(" → "),
                        style = MaterialTheme.typography.titleSmall,
                        color = BotColors.ink,
                    )
                    Text("${event.by} · ${clock.listStamp(event.at)}", style = MaterialTheme.typography.bodySmall, color = BotColors.inkMuted)
                    event.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BotColors.inkSecondary) }
                }
            }
        }
    }

    moving?.let { target ->
        PromptDialog(
            title = "${strings[Txt.BUILDING_ISSUE_MOVE]}: ${strings.label(Vocab.ISSUE_STATUS, target)}",
            label = strings[Txt.BUILDING_ISSUE_NOTE],
            confirmLabel = strings[Txt.ACTION_SAVE],
            cancelLabel = strings[Txt.ACTION_CANCEL],
            singleLine = false,
            onConfirm = { note ->
                moving = null
                vm.act { issue -> writer.editIssue(issue.projectId, issue.id, SaveIssue(status = target, note = note.trim().ifBlank { null })) }
            },
            onDismiss = { moving = null },
        )
    }
}

/**
 * A change order: its lines, value and days, and the team's steps (send it to the client, decide it
 * for them, take it back to draft). Writing its lines stays on the web.
 */
@Composable
fun ChangeRecordScreen(
    repository: BuildingRepository,
    changeId: String,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpenProject: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm = viewModel<RecordViewModel<ChangeOrder>>(
        key = "change:$changeId",
        factory = viewModelFactory { initializer { RecordViewModel({ repository.change(changeId) }) } },
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(changeId) { vm.load() }
    var deciding by remember { mutableStateOf<String?>(null) }

    Frame(state, strings.module(DashboardModules.CHANGE_ORDERS), state.value?.number ?: strings.module(DashboardModules.CHANGE_ORDERS), strings, padding, onBack, { vm.load() }) { change ->
        item {
            RecordCard(change.title, subtitle = "${change.projectNumber} ${change.projectName}".trim()) {
                Row(Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    LabelBadge(strings, Vocab.CHANGE_STATUS, change.status, changeTone(change.status))
                }
                FieldRow(strings[Txt.BUILDING_ISSUE_KIND], change.kind.takeIf { it.isNotBlank() }?.let { strings.label(Vocab.CHANGE_KIND, it) })
                FieldRow(strings[Txt.LABEL_TOTAL], money(change.signedEur))
                FieldRow(strings[Txt.BUILDING_CHANGE_DAYS_LABEL], change.daysImpact.takeIf { it != 0 }?.let { strings.format(Txt.BUILDING_CHANGE_DAYS, "days" to it) })
                FieldRow(strings[Txt.BUILDING_CLIENT], change.clientName)
                FieldRow(strings[Txt.BUILDING_ISSUE_DESCRIPTION], change.description)
                change.decidedBy?.let { name ->
                    FieldRow(
                        strings.format(Txt.BUILDING_CHANGE_DECIDED, "name" to name),
                        listOfNotNull(
                            change.decidedAt?.let(clock::day),
                            strings[Txt.BUILDING_CHANGE_VIA_LINK].takeIf { change.decidedVia == "CLIENT_LINK" },
                            change.decisionNote,
                        ).joinToString(" · "),
                    )
                }
            }
        }
        item {
            ActionRow {
                val open = change.status == ChangeOrderStatus.DRAFT || change.status == ChangeOrderStatus.SENT
                if (change.status == ChangeOrderStatus.DRAFT) {
                    PrimaryButton(strings[Txt.BUILDING_CHANGE_SEND], { vm.act { repository.setChangeStatus(it, ChangeStatus(ChangeOrderStatus.SENT)) } }, busy = state.busy)
                }
                if (open) {
                    SecondaryButton(strings[Txt.BUILDING_CHANGE_APPROVE], { deciding = ChangeOrderStatus.APPROVED }, enabled = !state.busy)
                    SecondaryButton(strings[Txt.ACTION_REJECT], { deciding = ChangeOrderStatus.REJECTED }, enabled = !state.busy, tone = Tone.Bad)
                }
                if (change.status != ChangeOrderStatus.DRAFT && !change.measured) {
                    SecondaryButton(strings[Txt.BUILDING_CHANGE_TO_DRAFT], {
                        vm.act { repository.setChangeStatus(it, ChangeStatus(ChangeOrderStatus.DRAFT)) }
                    }, enabled = !state.busy)
                }
                SecondaryButton(strings.module(DashboardModules.PROJECTS), { onOpenProject(change.projectId) })
            }
        }
        if (change.status == ChangeOrderStatus.DRAFT) item { InfoPanel(strings[Txt.BUILDING_CHANGE_CREATE_WEB], tone = Tone.Info) }
        if (change.items.isNotEmpty()) {
            item { SectionLabel(strings[Txt.CRM_LINES]) }
            item {
                Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
                    change.items.forEach { line ->
                        LineRow(line.description, "${formatQuantity(line.quantity)} ${line.unit} × ${money(line.unitPriceEur)}".trim(), money(line.totalEur))
                    }
                }
            }
        }
    }

    deciding?.let { target ->
        PromptDialog(
            title = strings[if (target == ChangeOrderStatus.APPROVED) Txt.BUILDING_CHANGE_APPROVE else Txt.ACTION_REJECT],
            label = strings[Txt.BUILDING_CHANGE_DECIDED_BY],
            confirmLabel = strings[if (target == ChangeOrderStatus.APPROVED) Txt.ACTION_APPROVE else Txt.ACTION_REJECT],
            cancelLabel = strings[Txt.ACTION_CANCEL],
            onConfirm = { name ->
                deciding = null
                vm.act { repository.setChangeStatus(it, ChangeStatus(target, decidedBy = name.trim().ifBlank { null })) }
            },
            onDismiss = { deciding = null },
        )
    }
}

/**
 * A measurement (auto de medição): the period's value, the retention and what is left to invoice,
 * line by line; approved, reopened and invoiced here, measured on the web.
 */
@Composable
fun MeasurementRecordScreen(
    repository: BuildingRepository,
    measurementId: String,
    modules: Set<String>,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpenProject: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm = viewModel<RecordViewModel<Measurement>>(
        key = "measurement:$measurementId",
        factory = viewModelFactory { initializer { RecordViewModel({ repository.measurement(measurementId) }) } },
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(measurementId) { vm.load() }
    var invoicing by remember { mutableStateOf(false) }

    val title = state.value?.let { strings.format(Txt.BUILDING_MEASUREMENT_TITLE, "number" to it.number) } ?: strings.module(DashboardModules.MEASUREMENTS)
    Frame(state, strings.module(DashboardModules.MEASUREMENTS), title, strings, padding, onBack, { vm.load() }) { measurement ->
        item {
            RecordCard(title, subtitle = "${measurement.projectNumber} ${measurement.projectName}".trim()) {
                Row(Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    LabelBadge(strings, Vocab.MEASUREMENT_STATUS, measurement.status, measurementTone(measurement.status))
                }
                FieldRow(strings[Txt.BUILDING_PERIOD_END], measurement.periodEnd.takeIf { it.isNotBlank() }?.let(clock::day))
                FieldRow(strings[Txt.BUILDING_CLIENT], measurement.clientName)
                FieldRow(strings[Txt.BUILDING_TO_DATE], "${money(measurement.toDateEur)} · ${formatPct(measurement.pctToDate)}")
                FieldRow(strings[Txt.CRM_NOTES], measurement.notes)
                measurement.invoiceNumber?.let { FieldRow(strings[Txt.CRM_SERVICE_INVOICE], strings.format(Txt.BUILDING_INVOICED_AS, "number" to it)) }
            }
        }
        item {
            MetricRow(
                leftLabel = strings[Txt.BUILDING_PERIOD],
                leftValue = money(measurement.grossEur),
                rightLabel = strings.format(Txt.BUILDING_RETENTION, "pct" to formatPct(measurement.retentionPct).removeSuffix("%")),
                rightValue = money(-measurement.retentionEur),
            )
        }
        item {
            MetricRow(
                leftLabel = strings[Txt.BUILDING_NET],
                leftValue = money(measurement.netEur),
                rightLabel = strings[Txt.BUILDING_CONTRACT],
                rightValue = money(measurement.contractEur),
                leftTone = if (measurement.status == MeasurementStatus.APPROVED) Tone.Warn else Tone.Neutral,
            )
        }
        item {
            ActionRow {
                when (measurement.status) {
                    MeasurementStatus.DRAFT -> PrimaryButton(strings[Txt.ACTION_APPROVE], { vm.act { repository.approveMeasurement(it) } }, busy = state.busy)
                    MeasurementStatus.APPROVED -> {
                        if (DashboardModules.INVOICES in modules) {
                            PrimaryButton(strings[Txt.BUILDING_MEASUREMENT_INVOICE], { invoicing = true }, busy = state.busy)
                        }
                        if (measurement.latest) {
                            SecondaryButton(strings[Txt.ACTION_REOPEN], { vm.act { repository.reopenMeasurement(it) } }, enabled = !state.busy)
                        }
                    }
                }
                SecondaryButton(strings.module(DashboardModules.PROJECTS), { onOpenProject(measurement.projectId) })
            }
        }
        if (measurement.status == MeasurementStatus.DRAFT) item { InfoPanel(strings[Txt.BUILDING_MEASUREMENT_EDIT_WEB], tone = Tone.Info) }
        if (measurement.lines.isNotEmpty()) {
            item { SectionLabel(strings[Txt.CRM_LINES]) }
            item {
                Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
                    measurement.lines.forEach { line ->
                        Column(Modifier.fillMaxWidth().padding(vertical = BotSpace.xs)) {
                            LineRow(
                                description = line.description,
                                detail = "${strings[Txt.BUILDING_TO_DATE]} ${formatQuantity(line.toDateQty)} / ${formatQuantity(line.quantity)} ${line.unit} · ${formatPct(line.pctToDate)}",
                                amount = money(line.periodEur),
                            )
                        }
                    }
                }
            }
        }
    }

    if (invoicing) {
        PromptDialog(
            title = strings[Txt.BUILDING_MEASUREMENT_INVOICE],
            label = strings[Txt.CRM_INVOICE_DUE_DATE],
            supporting = strings[Txt.CRM_DATE_FORMAT_HINT],
            initial = clock.today().toString(),
            confirmLabel = strings[Txt.BUILDING_MEASUREMENT_INVOICE],
            cancelLabel = strings[Txt.ACTION_CANCEL],
            canConfirm = { parseDay(it) != null },
            onConfirm = { due ->
                invoicing = false
                vm.act { measurement ->
                    when (val done = repository.invoiceMeasurement(measurement, due.trim())) {
                        is Outcome.Success -> Outcome.Success(done.value.measurement)
                        is Outcome.Failure -> done
                    }
                }
            },
            onDismiss = { invoicing = false },
        )
    }
}

/** `42%`, or `42,5%` when it isn't whole. */
internal fun formatPct(pct: Double): String {
    val rounded = kotlin.math.round(pct * 10) / 10
    val text = if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString().replace('.', ',')
    return "$text%"
}
