package com.rfm.edubot.mobile.feature.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import com.rfm.edubot.mobile.core.data.InboxRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.Conversation
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
 * The conversation list.
 *
 * Adds what the web inbox has and the app did not: the needs-reply / unread / AI-paused filters,
 * the unread badge, a preview of the last message, and a list that refreshes itself.
 */
@Composable
fun InboxScreen(
    repository: InboxRepository,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpen: (Conversation) -> Unit,
) {
    val vm = viewModel<InboxViewModel>(
        key = "inbox",
        factory = viewModelFactory { initializer { InboxViewModel(repository) } },
    )
    val state by vm.conversations.collectAsState()
    val filter by vm.filter.collectAsState()
    LaunchedEffect(vm) { vm.start() }

    val all = state.value.orEmpty()
    val counts = vm.counts(all)
    val rows = vm.visible(all, filter)

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            ScreenHeader(strings[Txt.NAV_GROUP_INBOX], strings[Txt.INBOX_TITLE]) {
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
                items(InboxFilter.entries) { entry ->
                    Chip(
                        label = strings[entry.labelKey()],
                        selected = filter == entry,
                        onClick = { vm.setFilter(entry) },
                        count = counts[entry],
                    )
                }
            }
        }
        state.error?.let { error ->
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
            rows.isEmpty() && state.loading -> item { LoadingScreen() }
            rows.isEmpty() -> item { EmptyState(strings[Txt.EMPTY_TITLE], strings[Txt.INBOX_EMPTY]) }
            else -> items(rows, key = { it.id }) { conversation ->
                ConversationRow(conversation, strings, clock) { onOpen(conversation) }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    strings: Strings,
    clock: TenantClock,
    onClick: () -> Unit,
) {
    val detail = buildList {
        conversation.lastPreview?.takeIf { it.isNotBlank() }?.let(::add)
        if (isEmpty()) add(strings.plural(Txt.INBOX_MESSAGES_COUNT, conversation.messageCount))
        if (!conversation.autoReplyEnabled) add(strings[Txt.INBOX_FILTER_PAUSED])
    }.joinToString(" · ")
    Row(Modifier) {
        ListRow(
            title = conversation.title,
            detail = detail,
            leading = conversation.title,
            trailingLabel = clock.listStamp(conversation.lastMessageAt),
            status = conversation.badgeStatus(),
            statusLabel = conversation.badgeLabel(strings),
            emphasised = conversation.unreadCount > 0,
            onClick = onClick,
        )
    }
}

/** The one badge worth a row: waiting beats unread, and unread beats the channel. */
private fun Conversation.badgeStatus(): String? = when {
    waiting -> "PENDING"
    unreadCount > 0 -> "OPEN"
    else -> null
}

private fun Conversation.badgeLabel(strings: Strings): String? = when {
    waiting -> strings[Txt.INBOX_FILTER_NEEDS_REPLY]
    unreadCount > 0 -> unreadCount.toString()
    else -> null
}

private fun InboxFilter.labelKey(): String = when (this) {
    InboxFilter.All -> Txt.LABEL_ALL
    InboxFilter.NeedsReply -> Txt.INBOX_FILTER_NEEDS_REPLY
    InboxFilter.Unread -> Txt.INBOX_FILTER_UNREAD
    InboxFilter.Paused -> Txt.INBOX_FILTER_PAUSED
}
