package com.rfm.edubot.mobile.feature.notifications

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.data.NotificationsRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.AppNotification
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.RefreshBar
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import kotlinx.coroutines.launch

/**
 * The notification centre, which the app did not have at all.
 *
 * The backend sends a `kind` and its params rather than a sentence, so the wording comes from the
 * catalogs and arrives in the reader's own language. Tapping a row marks it read and opens the
 * module its web link points at.
 */
@Composable
fun NotificationsScreen(
    repository: NotificationsRepository,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpenModule: (String) -> Unit,
) {
    val state by repository.notifications.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) { repository.notifications.load() }

    val notifications = state.value
    val items = notifications?.items.orEmpty()

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            ScreenHeader(
                eyebrow = notifications?.unread
                    ?.takeIf { it > 0 }
                    ?.let { strings.format(Txt.NOTIFICATIONS_UNREAD, "count" to it) }
                    .orEmpty(),
                title = strings[Txt.NOTIFICATIONS_TITLE],
            ) {
                if ((notifications?.unread ?: 0) > 0) {
                    TextButton(onClick = { scope.launch { repository.markAllRead() } }) {
                        Text(strings[Txt.ACTION_MARK_ALL_READ], color = BotColors.accentDeep)
                    }
                }
            }
            RefreshBar(state.loading && state.hasValue)
        }
        state.error?.let { error ->
            item {
                ErrorPanel(
                    message = strings.error(error),
                    retryLabel = strings[Txt.ACTION_RETRY],
                    onRetry = { scope.launch { repository.notifications.refresh() } },
                )
            }
        }
        when {
            items.isEmpty() && state.loading -> item { LoadingScreen() }
            items.isEmpty() -> item { EmptyState(strings[Txt.EMPTY_TITLE], strings[Txt.NOTIFICATIONS_EMPTY]) }
            else -> items(items, key = { it.id }) { notification ->
                ListRow(
                    title = strings.notification(notification.kind, notification.params),
                    detail = listOfNotNull(
                        notification.body?.takeIf { it.isNotBlank() },
                        clock.listStamp(notification.createdAt),
                    ).joinToString(" · "),
                    leading = notification.kind,
                    emphasised = !notification.read,
                    onClick = {
                        scope.launch { repository.markRead(notification) }
                        notification.moduleId()?.let(onOpenModule)
                    },
                )
            }
        }
    }
}

/**
 * The module a notification points at. The backend sends the web dashboard's hash (`#invoices`),
 * which happens to be the module id, so the phone can route the same link.
 */
private fun AppNotification.moduleId(): String? =
    link?.removePrefix("#")?.substringBefore('/')?.takeIf { it.isNotBlank() }
