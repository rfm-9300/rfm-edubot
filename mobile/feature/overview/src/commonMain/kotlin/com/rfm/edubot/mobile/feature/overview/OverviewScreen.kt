package com.rfm.edubot.mobile.feature.overview

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.common.formatCents
import com.rfm.edubot.mobile.core.data.OverviewRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.Overview
import com.rfm.edubot.mobile.core.model.OverviewAgendaItem
import com.rfm.edubot.mobile.core.model.OverviewAttentionItem
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.MetricRow
import com.rfm.edubot.mobile.core.ui.RefreshBar
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SectionLabel
import com.rfm.edubot.mobile.core.ui.Tone
import kotlinx.coroutines.launch

/**
 * Home.
 *
 * The web calls this a cockpit and leads with what needs a person. The app used to show six
 * counters and a claim about an offline cache that did not exist; this shows the backend's own
 * attention queue, routes each row to its module, and only mentions the snapshot when it is one.
 */
@Composable
fun OverviewScreen(
    repository: OverviewRepository,
    tenantName: String,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpenModule: (String) -> Unit,
    onOpenProject: ((String) -> Unit)? = null,
    /** Opens the record a Needs-you row is about; false when there is none, and the row opens its module. */
    onOpenAttention: (OverviewAttentionItem) -> Boolean = { false },
) {
    val state by repository.overview.state.collectAsState()
    val hidden by repository.amountsHidden.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) {
        repository.restoreAmounts()
        repository.overview.load()
    }
    val overview = state.value
    val money = Money(hidden, strings)

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader(tenantName, strings.module("overview")) {
                Row {
                    TextButton(onClick = { scope.launch { repository.setAmountsHidden(!hidden) } }) {
                        Text(strings[if (hidden) Txt.ACTION_SHOW_AMOUNTS else Txt.ACTION_HIDE_AMOUNTS], color = BotColors.inkMuted)
                    }
                    TextButton(
                        onClick = { scope.launch { repository.overview.refresh() } },
                        enabled = !state.loading,
                    ) {
                        Text(strings[Txt.ACTION_REFRESH], color = BotColors.accentDeep)
                    }
                }
            }
            RefreshBar(state.loading && state.hasValue)
        }
        state.error?.let { error ->
            item { InfoPanel(strings.error(error), tone = Tone.Bad) }
        }
        if (state.fromCache) {
            item { InfoPanel(strings[Txt.OFFLINE_SNAPSHOT], strings[Txt.OFFLINE_SNAPSHOT_DETAIL], tone = Tone.Info) }
        }
        if (overview == null) {
            item { if (state.loading) LoadingScreen() else EmptyState(strings[Txt.EMPTY_TITLE]) }
            return@LazyColumn
        }

        item { SectionLabel(strings[Txt.OVERVIEW_NEEDS_YOU]) }
        if (overview.attention.isEmpty()) {
            item { InfoPanel(strings[Txt.OVERVIEW_NEEDS_YOU_EMPTY]) }
        } else {
            items(overview.attention.take(ATTENTION_LIMIT), key = { it.kind + it.id }) { item ->
                AttentionRow(item, strings, clock, money, onOpenModule, onOpenAttention)
            }
        }

        item { SectionLabel(strings[Txt.OVERVIEW_SNAPSHOT]) }
        items(overview.metricRows(strings, money), key = { it.key }) { row ->
            MetricRow(
                leftLabel = row.leftLabel,
                leftValue = row.leftValue,
                rightLabel = row.rightLabel,
                rightValue = row.rightValue,
                leftTone = row.leftTone,
                rightTone = row.rightTone,
            )
        }

        val shown = { card: String -> card !in overview.hiddenCards }
        overview.serviceStats?.takeIf { shown(CARD_SERVICES) }?.let { stats ->
            item { SectionLabel(strings.module("services")) }
            item {
                MetricRow(
                    leftLabel = strings[Txt.OVERVIEW_SERVICES_DONE_MONTH],
                    leftValue = stats.doneThisMonth.toString(),
                    rightLabel = strings[Txt.OVERVIEW_SERVICES_VALUE_MONTH],
                    rightValue = money(stats.doneThisMonthCents),
                )
            }
            item {
                MetricRow(
                    leftLabel = strings[Txt.OVERVIEW_SERVICES_WEEK],
                    leftValue = stats.doneThisWeek.toString(),
                    rightLabel = strings[Txt.OVERVIEW_SERVICES_TO_DO],
                    rightValue = stats.toDo.toString(),
                    rightTone = if (stats.toDoToday > 0) Tone.Warn else Tone.Neutral,
                )
            }
            if (stats.employees.isNotEmpty()) {
                item { SectionLabel(strings[Txt.OVERVIEW_SERVICES_BY_EMPLOYEE]) }
                items(stats.employees.take(TOP_LIMIT), key = { "emp-${it.id}" }) { employee ->
                    ListRow(
                        title = employee.name,
                        detail = employee.count.toString(),
                        leading = employee.name,
                        trailingLabel = money(employee.cents),
                        onClick = { onOpenModule("employees") },
                    )
                }
            }
        }

        overview.projects?.takeIf { shown(CARD_PROJECTS) }?.let { projects ->
            item { SectionLabel(strings.module("projects")) }
            item {
                MetricRow(
                    leftLabel = strings[Txt.OVERVIEW_PROJECTS_ACTIVE],
                    leftValue = projects.active.toString(),
                    rightLabel = strings[Txt.OVERVIEW_PROJECTS_BEHIND],
                    rightValue = (projects.atRisk + projects.delayed).toString(),
                    rightTone = if (projects.delayed > 0) Tone.Bad else if (projects.atRisk > 0) Tone.Warn else Tone.Neutral,
                )
            }
            item {
                MetricRow(
                    leftLabel = strings[Txt.OVERVIEW_ISSUES_OPEN],
                    leftValue = projects.openIssues.toString(),
                    rightLabel = strings[Txt.OVERVIEW_ISSUES_OVERDUE],
                    rightValue = projects.overdueIssues.toString(),
                    rightTone = if (projects.overdueIssues > 0) Tone.Bad else Tone.Neutral,
                )
            }
            item {
                MetricRow(
                    leftLabel = strings[Txt.OVERVIEW_DIARIES_TODAY],
                    leftValue = projects.diariesToday.toString(),
                    rightLabel = strings[Txt.OVERVIEW_WITHOUT_DIARY],
                    rightValue = projects.activeWithoutDiary.toString(),
                    rightTone = if (projects.activeWithoutDiary > 0) Tone.Warn else Tone.Neutral,
                )
            }
            if (projects.toInvoiceCount > 0) {
                item {
                    MetricRow(
                        leftLabel = strings.attention("measurement_to_invoice", "measurements"),
                        leftValue = projects.toInvoiceCount.toString(),
                        rightLabel = strings[Txt.BUILDING_TO_INVOICE],
                        rightValue = money(projects.toInvoiceCents),
                        rightTone = Tone.Warn,
                    )
                }
            }
            if (projects.behind.isNotEmpty()) {
                item { SectionLabel(strings[Txt.OVERVIEW_PROJECTS_FURTHEST]) }
                items(projects.behind, key = { "behind-${it.id}" }) { row ->
                    ListRow(
                        title = listOf(row.number, row.name).filter { it.isNotBlank() }.joinToString(" · "),
                        detail = listOfNotNull(
                            row.plannedPct?.let { planned -> strings.format(Txt.BUILDING_PROGRESS, "done" to row.progressPct, "planned" to planned) },
                            row.scheduleDays?.takeIf { it < 0 }?.let { strings.format(Txt.BUILDING_BEHIND, "days" to -it) },
                        ).joinToString(" · "),
                        leading = row.number.ifBlank { row.name },
                        status = row.health.takeIf { it.isNotBlank() },
                        statusLabel = row.health.takeIf { it.isNotBlank() }?.let { strings.label("building.health", it) },
                        onClick = { onOpenProject?.invoke(row.id) ?: onOpenModule("projects") },
                    )
                }
            }
        }

        if (overview.agenda.isNotEmpty()) {
            item { SectionLabel(strings[Txt.OVERVIEW_AGENDA]) }
            items(overview.agenda.take(AGENDA_LIMIT), key = { it.id }) { entry ->
                AgendaRow(entry, strings, clock) { onOpenModule("bookings") }
            }
        }

        overview.generatedAt.takeIf { it.isNotBlank() }?.let { stamp ->
            item {
                Text(
                    strings.format(Txt.OVERVIEW_GENERATED_AT, "time" to (clock.timeOfDay(stamp) ?: stamp)),
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = BotColors.inkFaint,
                )
            }
        }
    }
}

@Composable
private fun AttentionRow(
    item: OverviewAttentionItem,
    strings: Strings,
    clock: TenantClock,
    money: Money,
    onOpenModule: (String) -> Unit,
    onOpenAttention: (OverviewAttentionItem) -> Boolean,
) = ListRow(
    // `detail` is already a readable label from the backend (a client name, an invoice number);
    // the reason it is in the queue comes from the kind.
    title = item.detail.ifBlank { strings.attention(item.kind, item.tab) },
    detail = listOfNotNull(
        strings.attention(item.kind, item.tab),
        item.amountCents?.let(money::invoke),
        // A due date comes as a plain day, an event as an instant; each reads as the company writes dates.
        item.at?.let { at -> if (clock.parse(at) != null) clock.listStamp(at) else clock.day(at) },
    ).joinToString(" · "),
    leading = strings.module(item.tab),
    emphasised = true,
    onClick = { if (!onOpenAttention(item)) onOpenModule(item.tab) },
)

/**
 * Every amount Home shows goes through here, so hiding them can't miss one: counts, percentages and
 * dates stay readable, money reads as dots until the person shows it.
 */
private class Money(private val hidden: Boolean, private val strings: Strings) {
    operator fun invoke(cents: Long): String = "€ ${if (hidden) strings[Txt.LABEL_HIDDEN_AMOUNT] else formatCents(cents)}"
}

@Composable
private fun AgendaRow(
    entry: OverviewAgendaItem,
    strings: Strings,
    clock: TenantClock,
    onClick: () -> Unit,
) = ListRow(
    title = entry.contactName.ifBlank { strings[Txt.LABEL_UNTITLED] },
    detail = listOfNotNull(entry.service.takeIf { it.isNotBlank() }, clock.timeOfDay(entry.startAt)).joinToString(" · "),
    leading = clock.timeOfDay(entry.startAt) ?: entry.contactName,
    status = entry.status.takeIf { it.isNotBlank() },
    statusLabel = entry.status.takeIf { it.isNotBlank() }?.let(strings::status),
    onClick = onClick,
)

private data class MetricRowModel(
    val key: String,
    val leftLabel: String,
    val leftValue: String,
    val rightLabel: String,
    val rightValue: String,
    val leftTone: Tone = Tone.Neutral,
    val rightTone: Tone = Tone.Neutral,
)

/**
 * Only the cards for modules the tenant has. The backend sends a null section for a module that is
 * off, which is why none of this is unconditional.
 */
private fun Overview.metricRows(strings: Strings, money: Money): List<MetricRowModel> = buildList {
    inbox?.let {
        add(
            MetricRowModel(
                key = "inbox",
                leftLabel = strings[Txt.OVERVIEW_MESSAGES_TODAY],
                leftValue = it.messagesToday.toString(),
                rightLabel = strings[Txt.OVERVIEW_WAITING],
                rightValue = it.waiting.toString(),
                rightTone = if (it.waiting > 0) Tone.Warn else Tone.Neutral,
            ),
        )
    }
    cash?.let {
        add(
            MetricRowModel(
                key = "cash",
                leftLabel = strings[Txt.OVERVIEW_COLLECTED_MONTH],
                leftValue = money(it.collectedThisMonthCents),
                rightLabel = strings[Txt.OVERVIEW_OUTSTANDING],
                rightValue = money(it.outstandingCents),
                leftTone = Tone.Ok,
            ),
        )
        if (it.overdueCount > 0) {
            add(
                MetricRowModel(
                    key = "overdue",
                    leftLabel = strings[Txt.OVERVIEW_OVERDUE],
                    leftValue = money(it.overdueCents),
                    rightLabel = strings[Txt.CRM_INVOICES_TITLE],
                    rightValue = it.overdueCount.toString(),
                    leftTone = Tone.Bad,
                    rightTone = Tone.Bad,
                ),
            )
        }
    }
    pipeline?.let {
        add(
            MetricRowModel(
                key = "pipeline",
                leftLabel = strings[Txt.OVERVIEW_PIPELINE_OPEN],
                leftValue = money(it.openCents),
                rightLabel = strings[Txt.CRM_QUOTES_TITLE],
                rightValue = it.quoteCount.toString(),
            ),
        )
    }
    calendar?.let {
        add(
            MetricRowModel(
                key = "calendar",
                leftLabel = strings[Txt.OVERVIEW_BOOKINGS_TODAY],
                leftValue = it.today.toString(),
                rightLabel = strings[Txt.OVERVIEW_BOOKINGS_PENDING],
                rightValue = it.pending.toString(),
                rightTone = if (it.pending > 0) Tone.Warn else Tone.Neutral,
            ),
        )
    }
    agents?.let {
        add(
            MetricRowModel(
                key = "agents",
                leftLabel = strings[Txt.OVERVIEW_APPROVALS],
                leftValue = it.pendingApprovals.toString(),
                rightLabel = strings[Txt.OVERVIEW_TASKS],
                rightValue = it.openTasks.toString(),
                leftTone = if (it.pendingApprovals > 0) Tone.Warn else Tone.Neutral,
            ),
        )
    }
    if (isEmpty()) {
        add(
            MetricRowModel(
                key = "fallback",
                leftLabel = strings[Txt.OVERVIEW_CONTACTS],
                leftValue = users.toString(),
                rightLabel = strings[Txt.OVERVIEW_CONVERSATIONS],
                rightValue = conversations.toString(),
            ),
        )
    }
}

private const val ATTENTION_LIMIT = 6
private const val TOP_LIMIT = 3

/** Home's card ids, as `hiddenCards` names the ones a company hid in Settings → Home. */
private const val CARD_SERVICES = "services"
private const val CARD_PROJECTS = "projects"
private const val AGENDA_LIMIT = 5
