package com.rfm.edubot.mobile.feature.building

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.data.MySitesRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.DashboardModules
import com.rfm.edubot.mobile.core.model.MySiteDetail
import com.rfm.edubot.mobile.core.ui.ActionRow
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.Panel
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ProgressBar
import com.rfm.edubot.mobile.core.ui.RecordCard
import com.rfm.edubot.mobile.core.ui.RecordViewModel
import com.rfm.edubot.mobile.core.ui.RefreshBar
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.SectionLabel
import com.rfm.edubot.mobile.core.ui.Tone
import kotlinx.coroutines.launch

/**
 * A site manager's My sites: the running projects they run, each saying whether today's diary is
 * written. No money anywhere, as on the web.
 */
@Composable
fun MySitesScreen(
    repository: MySitesRepository,
    strings: Strings,
    padding: PaddingValues,
    onOpen: (BuildingRoute) -> Unit,
) {
    val state by repository.sites.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) { repository.sites.load() }
    val sites = state.value.orEmpty()

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader(strings.module(DashboardModules.MY_SITES), strings.module(DashboardModules.MY_SITES)) {
                TextButton(onClick = { scope.launch { repository.sites.refresh() } }) { Text(strings[Txt.ACTION_REFRESH], color = BotColors.inkMuted) }
            }
            RefreshBar(state.loading && state.hasValue)
        }
        state.error?.let { error ->
            item { ErrorPanel(strings.error(error), retryLabel = strings[Txt.ACTION_RETRY], onRetry = { scope.launch { repository.sites.refresh() } }) }
        }
        when {
            sites.isEmpty() && state.loading -> item { LoadingScreen() }
            sites.isEmpty() -> item { EmptyState(strings[Txt.EMPTY_TITLE], strings[Txt.MY_SITES_EMPTY]) }
            else -> items(sites, key = { it.id }) { site ->
                BuildingRow(
                    title = site.label,
                    detail = listOfNotNull(
                        site.address,
                        strings[if (site.todayEntryId != null) Txt.MY_SITES_TODAY_WRITTEN else Txt.MY_SITES_TODAY_MISSING],
                        site.openIssues.takeIf { it > 0 }?.let { strings.format(Txt.MY_SITES_OPEN_ISSUES, "count" to it) },
                    ).joinToString(" · "),
                    badge = { LabelBadge(strings, Vocab.STATUS, site.status, if (site.todayEntryId == null) Tone.Warn else Tone.Ok) },
                    onClick = { onOpen(BuildingRoute.MySite(site.id)) },
                    below = { ProgressBar(site.progressPct, description = "${site.progressPct}%") },
                )
            }
        }
    }
}

/** One of a site manager's sites: phases, today's diary, the last two weeks and the issues. */
@Composable
fun MySiteScreen(
    repository: MySitesRepository,
    siteId: String,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpen: (BuildingRoute) -> Unit,
    onBack: () -> Unit,
) {
    val vm = viewModel<RecordViewModel<MySiteDetail>>(
        key = "my-site:$siteId:${repository.employeeId}",
        factory = viewModelFactory { initializer { RecordViewModel({ repository.site(siteId) }) } },
    )
    val state by vm.state.collectAsState()
    // Coming back from the diary or an issue shows what was written there.
    LaunchedEffect(siteId) { vm.load() }

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackHeader(strings.module(DashboardModules.MY_SITES), state.value?.site?.label ?: strings.module(DashboardModules.MY_SITES), strings, onBack) }
        state.error?.let { error -> item { ErrorPanel(strings.error(error), retryLabel = strings[Txt.ACTION_RETRY], onRetry = { vm.load() }) } }
        val detail = state.value
        if (detail == null) {
            item { if (state.loading) LoadingScreen() else EmptyState(strings[Txt.EMPTY_TITLE]) }
            return@LazyColumn
        }
        val site = detail.site
        val today = detail.diary.firstOrNull { it.day == detail.today }
        item {
            RecordCard(site.name, subtitle = listOfNotNull(site.number, site.address).joinToString(" · ")) {
                ProgressBar(site.progressPct, description = "${site.progressPct}%")
                Spacer(Modifier.height(BotSpace.xs))
                Text("${site.progressPct}%", style = MaterialTheme.typography.bodySmall, color = BotColors.inkMuted)
            }
        }
        item {
            ActionRow {
                if (detail.canDiary) {
                    PrimaryButton(
                        strings[if (today == null) Txt.BUILDING_DIARY_NEW else Txt.BUILDING_DIARY_EDIT],
                        { onOpen(BuildingRoute.MySiteDiary(site.id, today)) },
                    )
                }
                if (detail.canIssues) {
                    SecondaryButton(strings[Txt.BUILDING_ISSUE_NEW], { onOpen(BuildingRoute.MySiteNewIssue(site.id)) })
                }
            }
        }
        if (detail.canDiary) {
            item {
                InfoPanel(
                    strings[if (today == null) Txt.MY_SITES_TODAY_MISSING else Txt.MY_SITES_TODAY_WRITTEN],
                    tone = if (today == null) Tone.Warn else Tone.Ok,
                )
            }
        }
        if (detail.phases.isNotEmpty()) {
            item { SectionLabel(strings[Txt.BUILDING_PHASES]) }
            items(detail.phases, key = { "phase-${it.id}" }) { phase ->
                Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
                    Row {
                        Text(phase.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = BotColors.ink)
                        if (phase.state.isNotBlank()) LabelBadge(strings, Vocab.PHASE_STATE, phase.state, phaseTone(phase.state))
                    }
                    Spacer(Modifier.height(BotSpace.sm))
                    ProgressBar(phase.progressPct, tone = phaseTone(phase.state).takeIf { it != Tone.Neutral } ?: Tone.Accent, description = "${phase.progressPct}%")
                }
            }
        }
        if (detail.issues.isNotEmpty()) {
            item { SectionLabel(strings.module(DashboardModules.SITE_ISSUES)) }
            items(detail.issues, key = { "issue-${it.id}" }) { issue ->
                IssueRow(issue, strings, clock) { onOpen(BuildingRoute.MySiteIssue(site.id, issue)) }
            }
        }
        if (detail.diary.isNotEmpty()) {
            item { SectionLabel(strings[Txt.MY_SITES_RECENT_DIARY]) }
            items(detail.diary, key = { "diary-${it.id}" }) { entry ->
                DiaryRow(entry, strings, clock, onClick = if (detail.canDiary) ({ onOpen(BuildingRoute.MySiteDiary(site.id, entry)) }) else null)
            }
        }
        if (detail.photos.isNotEmpty()) {
            item { InfoPanel(strings.format(Txt.BUILDING_PHOTOS, "count" to detail.photos.size), strings[Txt.BUILDING_PHOTOS_WEB]) }
        }
    }
}
