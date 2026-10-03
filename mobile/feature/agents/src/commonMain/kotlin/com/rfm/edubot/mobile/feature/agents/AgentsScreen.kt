package com.rfm.edubot.mobile.feature.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.rfm.edubot.mobile.core.data.AgentsRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.AgentApproval
import com.rfm.edubot.mobile.core.model.AgentTask
import com.rfm.edubot.mobile.core.ui.Badge
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.Chip
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.MetricRow
import com.rfm.edubot.mobile.core.ui.Panel
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SectionLabel
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.Tone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Agents.
 *
 * The module the app was missing entirely, and the one that most needs a phone: an agent set to ask
 * before acting is blocked until somebody approves it, and that somebody is usually not at a desk.
 * Approving, rejecting and closing tasks all work here; building an agent stays on the web.
 */
@Composable
fun AgentsScreen(
    repository: AgentsRepository,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    canManage: Boolean,
) {
    val vm = viewModel<AgentsViewModel>(
        key = "agents",
        factory = viewModelFactory { initializer { AgentsViewModel(repository) } },
    )
    val tab by vm.tab.collectAsState()
    val summaryState by repository.summary.state.collectAsState()
    val approvalsState by repository.approvals.state.collectAsState()
    val tasksState by repository.tasks.state.collectAsState()
    val agentsState by repository.agents.state.collectAsState()
    val runs by vm.runs.collectAsState()
    val pending by vm.pending.collectAsState()
    val failure by vm.failure.collectAsState()
    LaunchedEffect(vm) { vm.load() }

    val summary = summaryState.value

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            ScreenHeader(strings[Txt.NAV_GROUP_AUTOMATION], strings[Txt.AGENTS_TITLE]) {
                TextButton(onClick = { vm.refresh() }) {
                    Text(strings[Txt.ACTION_REFRESH], color = BotColors.accentDeep)
                }
            }
        }
        item {
            LazyRow(
                Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.md),
                horizontalArrangement = Arrangement.spacedBy(BotSpace.sm),
            ) {
                items(AgentsTab.entries) { entry ->
                    Chip(
                        label = strings[entry.labelKey()],
                        selected = tab == entry,
                        onClick = { vm.setTab(entry) },
                        count = when (entry) {
                            AgentsTab.Inbox -> (approvalsState.value?.size ?: 0) + (tasksState.value?.size ?: 0)
                            else -> null
                        },
                    )
                }
            }
        }
        failure?.let { error ->
            item { ErrorPanel(strings.error(error), retryLabel = strings[Txt.ACTION_RETRY], onRetry = { vm.refresh() }) }
        }
        summary?.takeIf { it.paused }?.let {
            item {
                InfoPanel(
                    strings[if (it.pausedBy == "platform") Txt.AGENTS_PAUSED_BY_PLATFORM else Txt.AGENTS_PAUSED_COMPANY],
                    tone = Tone.Warn,
                )
            }
        }

        when (tab) {
            AgentsTab.Inbox -> {
                summary?.let {
                    item {
                        MetricRow(
                            leftLabel = strings[Txt.OVERVIEW_APPROVALS],
                            leftValue = it.pendingApprovals.toString(),
                            rightLabel = strings[Txt.OVERVIEW_TASKS],
                            rightValue = it.openTasks.toString(),
                            leftTone = if (it.pendingApprovals > 0) Tone.Warn else Tone.Neutral,
                        )
                    }
                }
                item { SectionLabel(strings[Txt.AGENTS_APPROVALS]) }
                val approvals = approvalsState.value.orEmpty()
                if (approvals.isEmpty()) {
                    item { InfoPanel(strings[Txt.AGENTS_APPROVALS_EMPTY]) }
                } else {
                    items(approvals, key = { it.id }) { approval ->
                        ApprovalCard(approval, strings, clock, pending, vm)
                    }
                }
                item { SectionLabel(strings[Txt.AGENTS_TASKS]) }
                val tasks = tasksState.value.orEmpty()
                if (tasks.isEmpty()) {
                    item { InfoPanel(strings[Txt.AGENTS_TASKS_EMPTY]) }
                } else {
                    items(tasks, key = { it.id }) { task ->
                        TaskRow(task, strings, clock, pending, vm)
                    }
                }
            }

            AgentsTab.Agents -> {
                val agents = agentsState.value.orEmpty()
                if (agents.isEmpty()) {
                    item { EmptyState(strings[Txt.EMPTY_TITLE], strings[Txt.AGENTS_EMPTY]) }
                } else {
                    items(agents, key = { it.id }) { agent ->
                        ListRow(
                            title = agent.name,
                            detail = listOfNotNull(
                                agent.description?.takeIf { it.isNotBlank() },
                                agent.stats.lastRunAt?.let { clock.listStamp(it) },
                            ).joinToString(" · "),
                            leading = agent.name,
                            status = agent.status,
                            statusLabel = strings.status(agent.status),
                            actionLabel = strings[if (agent.active) Txt.ACTION_PAUSE else Txt.ACTION_RESUME]
                                .takeIf { canManage },
                            onAction = if (!canManage || pending != null) {
                                null
                            } else {
                                ({ vm.setPaused(agent, !agent.active); Unit })
                            },
                        )
                    }
                }
                item { InfoPanel(strings[Txt.AGENTS_BUILDER_WEB_ONLY], tone = Tone.Info) }
            }

            AgentsTab.Activity -> {
                summary?.let {
                    item {
                        MetricRow(
                            leftLabel = strings[Txt.AGENTS_RUNS_TODAY],
                            leftValue = it.runsToday.toString(),
                            rightLabel = strings[Txt.AGENTS_FAILED_WEEK],
                            rightValue = it.failedThisWeek.toString(),
                            rightTone = if (it.failedThisWeek > 0) Tone.Bad else Tone.Neutral,
                        )
                    }
                }
                if (runs.isEmpty()) {
                    item { EmptyState(strings[Txt.EMPTY_TITLE], strings[Txt.AGENTS_ACTIVITY_EMPTY]) }
                } else {
                    items(runs, key = { it.id }) { run ->
                        ListRow(
                            title = run.agentName.ifBlank { strings[Txt.LABEL_UNTITLED] },
                            detail = listOfNotNull(
                                run.subjectLabel,
                                run.done.firstOrNull(),
                                run.error,
                            ).joinToString(" · "),
                            leading = run.agentName,
                            trailingLabel = clock.listStamp(run.createdAt),
                            status = run.status,
                            statusLabel = strings.status(run.status),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A decision an agent is blocked on. Approve and reject are both explicit buttons: either one has
 * real consequences — an email goes out, or it never does — so neither should be a stray tap.
 */
@Composable
private fun ApprovalCard(
    approval: AgentApproval,
    strings: Strings,
    clock: TenantClock,
    pending: String?,
    vm: AgentsViewModel,
) {
    val daysLeft = clock.daysSince(approval.expiresAt)?.let { -it }
    val busy = pending != null
    Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                approval.agentName.ifBlank { approval.action },
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = BotColors.ink,
            )
            when {
                daysLeft == null -> Unit
                daysLeft <= 0 -> Badge(strings[Txt.AGENTS_EXPIRES_TODAY], Tone.Bad)
                else -> Badge(strings.format(Txt.AGENTS_EXPIRES, "days" to daysLeft), Tone.Warn)
            }
        }
        val detail = listOfNotNull(approval.subjectLabel, approval.action.takeIf { it.isNotBlank() })
            .joinToString(" · ")
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(BotSpace.xs))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = BotColors.inkMuted)
        }
        approval.preview?.summarise()?.let { preview ->
            Spacer(Modifier.height(BotSpace.sm))
            Text(preview, style = MaterialTheme.typography.bodySmall, color = BotColors.inkSecondary)
        }
        Spacer(Modifier.height(BotSpace.md))
        if (!approval.canDecide) {
            Text(
                strings[Txt.AGENTS_CANNOT_DECIDE],
                style = MaterialTheme.typography.bodySmall,
                color = BotColors.inkFaint,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
                PrimaryButton(
                    text = strings[Txt.ACTION_APPROVE],
                    onClick = { vm.approve(approval) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    busy = pending == approval.id,
                )
                SecondaryButton(
                    text = strings[Txt.ACTION_REJECT],
                    onClick = { vm.reject(approval, null) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    tone = Tone.Bad,
                )
            }
        }
    }
}

/**
 * The gist of an `ActionPreview`. Its shape depends on the action, so this pulls out the few fields
 * worth a line on a phone instead of rendering the whole object.
 */
private fun JsonObject.summarise(): String? {
    fun text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val recipients = (this["recipients"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
    return listOfNotNull(recipients, text("subject"), text("body")?.take(PREVIEW_CHARS))
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" — ")
}

private const val PREVIEW_CHARS = 160

@Composable
private fun TaskRow(
    task: AgentTask,
    strings: Strings,
    clock: TenantClock,
    pending: String?,
    vm: AgentsViewModel,
) = ListRow(
    title = task.title,
    detail = listOfNotNull(
        task.detail?.takeIf { it.isNotBlank() },
        task.subjectLabel,
        task.dueAt?.let { strings.format(Txt.AGENTS_DUE, "date" to (clock.fullDate(it) ?: it)) },
    ).joinToString(" · "),
    leading = task.title,
    status = task.status,
    statusLabel = strings.status(task.status),
    actionLabel = strings[Txt.ACTION_DONE],
    actionTone = Tone.Ok,
    onAction = if (pending != null) null else ({ vm.completeTask(task); Unit }),
)

private fun AgentsTab.labelKey(): String = when (this) {
    AgentsTab.Inbox -> Txt.AGENTS_TAB_INBOX
    AgentsTab.Agents -> Txt.AGENTS_TAB_AGENTS
    AgentsTab.Activity -> Txt.AGENTS_TAB_ACTIVITY
}
