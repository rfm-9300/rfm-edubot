package com.rfm.edubot.mobile.feature.bookings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.common.formatEuros
import com.rfm.edubot.mobile.core.data.BookingsRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.Chip
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.RefreshBar
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.Tone

/**
 * Bookings.
 *
 * Another module the app had no screen for. Confirming a booking and marking it done are the two
 * things that happen away from a desk, so those are the ones this does; the week grid, the opening
 * hours editor and the slot picker stay on the web.
 */
@Composable
fun BookingsScreen(
    repository: BookingsRepository,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
) {
    val vm = viewModel<BookingsViewModel>(
        key = "bookings",
        factory = viewModelFactory { initializer { BookingsViewModel(repository) } },
    )
    val state by repository.upcoming.state.collectAsState()
    val filter by vm.filter.collectAsState()
    val pending by vm.pending.collectAsState()
    val failure by vm.failure.collectAsState()
    LaunchedEffect(vm) { vm.load() }

    val all = state.value.orEmpty()
    val counts = vm.counts(all, clock)
    val rows = vm.visible(all, filter, clock)

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            ScreenHeader(strings[Txt.NAV_GROUP_BUSINESS], strings[Txt.BOOKINGS_TITLE]) {
                TextButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                    Text(strings[Txt.ACTION_REFRESH], color = BotColors.accentDeep)
                }
            }
            RefreshBar(state.loading && state.hasValue)
        }
        item {
            LazyRow(
                Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.md),
                horizontalArrangement = Arrangement.spacedBy(BotSpace.sm),
            ) {
                items(BookingFilter.entries) { entry ->
                    Chip(
                        label = strings[entry.labelKey()],
                        selected = filter == entry,
                        onClick = { vm.setFilter(entry) },
                        count = counts[entry],
                    )
                }
            }
        }
        (failure ?: state.error)?.let { error ->
            item { ErrorPanel(strings.error(error), retryLabel = strings[Txt.ACTION_RETRY], onRetry = { vm.refresh() }) }
        }
        if (state.fromCache) {
            item { InfoPanel(strings[Txt.OFFLINE_SNAPSHOT], tone = Tone.Info) }
        }
        when {
            rows.isEmpty() && state.loading -> item { LoadingScreen() }
            rows.isEmpty() -> item { EmptyState(strings[Txt.EMPTY_TITLE], strings[Txt.BOOKINGS_EMPTY]) }
            else -> items(rows, key = { it.id }) { booking ->
                val next = vm.primaryTransition(booking)
                ListRow(
                    title = booking.contactName.ifBlank { booking.contactPhone },
                    detail = listOfNotNull(
                        booking.serviceName.takeIf { it.isNotBlank() },
                        booking.priceEur?.let { "€ ${formatEuros(it)}" },
                        strings[Txt.BOOKINGS_BILLABLE].takeIf { booking.billable },
                    ).joinToString(" · "),
                    leading = booking.contactName.ifBlank { booking.contactPhone },
                    trailingLabel = clock.listStamp(booking.startAt),
                    status = booking.status,
                    statusLabel = strings.status(booking.status),
                    emphasised = booking.pending,
                    actionLabel = next?.let(strings::status),
                    actionTone = Tone.Ok,
                    onAction = if (next == null || pending != null) null else ({ vm.setStatus(booking, next); Unit }),
                )
            }
        }
    }
}

private fun BookingFilter.labelKey(): String = when (this) {
    BookingFilter.Today -> Txt.LABEL_TODAY
    BookingFilter.Upcoming -> Txt.BOOKINGS_UPCOMING
    BookingFilter.NeedsConfirm -> Txt.BOOKINGS_NEEDS_CONFIRM
}
