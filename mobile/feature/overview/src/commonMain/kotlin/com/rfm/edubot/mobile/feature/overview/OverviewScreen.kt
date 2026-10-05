package com.rfm.edubot.mobile.feature.overview

import androidx.compose.foundation.layout.PaddingValues
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
) {
    val state by repository.overview.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) { repository.overview.load() }
    val overview = state.value

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader(tenantName, strings.module("overview")) {
                TextButton(
                    onClick = { scope.launch { repository.overview.refresh() } },
                    enabled = !state.loading,
                ) {
                    Text(strings[Txt.ACTION_REFRESH], color = BotColors.accentDeep)
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
                AttentionRow(item, strings, clock, onOpenModule)
            }
        }

        item { SectionLabel(strings[Txt.OVERVIEW_SNAPSHOT]) }
        items(overview.metricRows(strings), key = { it.key }) { row ->
            MetricRow(
                leftLabel = row.leftLabel,
                leftValue = row.leftValue,
                rightLabel = row.rightLabel,
                rightValue = row.rightValue,
                leftTone = row.leftTone,
                rightTone = row.rightTone,
            )
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
    onOpenModule: (String) -> Unit,
) = ListRow(
    // `detail` is already a readable label from the backend (a client name, an invoice number);
    // the reason it is in the queue comes from the kind.
    title = item.detail.ifBlank { strings.attention(item.kind, item.tab) },
    detail = listOfNotNull(
        strings.attention(item.kind, item.tab),
        item.amountCents?.let { "€ ${formatCents(it)}" },
        item.at?.let(clock::listStamp),
    ).joinToString(" · "),
    leading = strings.module(item.tab),
    emphasised = true,
    onClick = { onOpenModule(item.tab) },
)

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
private fun Overview.metricRows(strings: Strings): List<MetricRowModel> = buildList {
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
                leftValue = "€ ${formatCents(it.collectedThisMonthCents)}",
                rightLabel = strings[Txt.OVERVIEW_OUTSTANDING],
                rightValue = "€ ${formatCents(it.outstandingCents)}",
                leftTone = Tone.Ok,
            ),
        )
        if (it.overdueCount > 0) {
            add(
                MetricRowModel(
                    key = "overdue",
                    leftLabel = strings[Txt.OVERVIEW_OVERDUE],
                    leftValue = "€ ${formatCents(it.overdueCents)}",
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
                leftValue = "€ ${formatCents(it.openCents)}",
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
private const val AGENDA_LIMIT = 5
