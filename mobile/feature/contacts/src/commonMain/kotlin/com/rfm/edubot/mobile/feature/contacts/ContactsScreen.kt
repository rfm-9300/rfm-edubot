package com.rfm.edubot.mobile.feature.contacts

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.rfm.edubot.mobile.core.data.InboxRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.RefreshBar
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.Tone

@Composable
fun ContactsScreen(
    repository: InboxRepository,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
) {
    val vm = viewModel<ContactsViewModel>(
        key = "contacts",
        factory = viewModelFactory { initializer { ContactsViewModel(repository) } },
    )
    val state by vm.contacts.collectAsState()
    val pending by vm.pending.collectAsState()
    val failure by vm.failure.collectAsState()
    LaunchedEffect(vm) { vm.load() }

    val contacts = state.value.orEmpty()
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            ScreenHeader(strings[Txt.NAV_GROUP_INBOX], strings[Txt.CONTACTS_TITLE]) {
                TextButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                    Text(strings[Txt.ACTION_REFRESH], color = BotColors.accentDeep)
                }
            }
            RefreshBar(state.loading && state.hasValue)
        }
        (failure ?: state.error)?.let { error ->
            item {
                ErrorPanel(
                    message = strings.error(error),
                    retryLabel = strings[Txt.ACTION_RETRY],
                    onRetry = { vm.refresh() },
                )
            }
        }
        if (state.fromCache) {
            item { InfoPanel(strings[Txt.OFFLINE_SNAPSHOT], tone = Tone.Info) }
        }
        when {
            contacts.isEmpty() && state.loading -> item { LoadingScreen() }
            contacts.isEmpty() -> item { EmptyState(strings[Txt.EMPTY_TITLE], strings[Txt.CONTACTS_EMPTY]) }
            else -> items(contacts, key = { it.id }) { contact ->
                ListRow(
                    title = contact.displayName ?: contact.waId,
                    detail = listOf(
                        contact.channel,
                        strings.format(Txt.CONTACTS_LAST_SEEN, "time" to clock.listStamp(contact.lastSeenAt)),
                    ).joinToString(" · "),
                    leading = contact.displayName ?: contact.waId,
                    status = contact.status,
                    statusLabel = strings.status(contact.status),
                    trailingLabel = strings[
                        if (contact.blocked) Txt.CONTACTS_UNBLOCK else Txt.CONTACTS_BLOCK,
                    ].takeIf { pending != contact.id },
                    onClick = { vm.toggleBlocked(contact) },
                )
            }
        }
    }
}
