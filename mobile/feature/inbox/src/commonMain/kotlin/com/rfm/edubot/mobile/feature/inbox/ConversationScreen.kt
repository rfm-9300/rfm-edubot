package com.rfm.edubot.mobile.feature.inbox

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.rfm.edubot.mobile.core.data.InboxRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.ChannelAsset
import com.rfm.edubot.mobile.core.model.ThreadMessage
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.BotSwitch
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.MessageBubble
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.Tone

/**
 * One conversation: the thread, who wrote each message, the assistant's on/off switch, and a
 * composer that respects WhatsApp's 24-hour window.
 */
@Composable
fun ConversationScreen(
    repository: InboxRepository,
    conversationId: String,
    channels: List<ChannelAsset>,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    val vm = viewModel<ConversationViewModel>(
        key = "conversation:$conversationId",
        factory = viewModelFactory {
            initializer { ConversationViewModel(repository, conversationId, channels) }
        },
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(conversationId) { vm.open() }

    val snapshot = state.snapshot
    val conversation = snapshot?.conversation
    val listState = rememberLazyListState()
    LaunchedEffect(snapshot?.messages?.size) {
        val count = snapshot?.messages?.size ?: 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
        ScreenHeader(
            eyebrow = conversation?.channel.orEmpty(),
            title = conversation?.title ?: strings[Txt.INBOX_TITLE],
        ) {
            TextButton(onClick = onBack) {
                Text(strings[Txt.ACTION_BACK], color = BotColors.accentDeep)
            }
        }
        if (conversation == null) {
            if (state.loading) LoadingScreen() else {
                state.error?.let { ErrorPanel(strings.error(it), retryLabel = strings[Txt.ACTION_RETRY], onRetry = vm::open) }
            }
            return@Column
        }

        AutoReplyBar(
            enabled = conversation.autoReplyEnabled,
            strings = strings,
            onToggle = vm::setAutoReply,
        )
        state.error?.let {
            ErrorPanel(strings.error(it), retryLabel = strings[Txt.ACTION_CLOSE], onRetry = vm::dismissError)
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = BotSpace.xl),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(BotSpace.sm),
            contentPadding = PaddingValues(vertical = BotSpace.lg),
        ) {
            items(snapshot.messages, key = { it.id }) { message ->
                MessageBubble(
                    text = message.displayText(strings),
                    stamp = clock.listStamp(message.createdAt),
                    fromCustomer = message.fromCustomer,
                    attribution = message.attribution(strings),
                    footnote = message.footnote(strings),
                    failed = message.failed,
                    onRetry = if (message.failed) ({ vm.retry(message.id); Unit }) else null,
                    retryLabel = strings[Txt.ACTION_RETRY].takeIf { message.failed },
                )
            }
        }

        val closed = conversation.windowClosed(clock.instant().toString())
        when {
            conversation.channel == ChannelAsset.WEB ->
                InfoPanel(strings[Txt.INBOX_WEB_REPLY_UNAVAILABLE], tone = Tone.Info)
            closed -> InfoPanel(strings[Txt.INBOX_WINDOW_CLOSED], tone = Tone.Warn)
            else -> Composer(
                draft = state.draft,
                sending = state.sending,
                strings = strings,
                onDraft = vm::updateDraft,
                onSend = vm::send,
            )
        }
    }
}

@Composable
private fun AutoReplyBar(
    enabled: Boolean,
    strings: Strings,
    onToggle: (Boolean) -> Unit,
) = Surface(color = if (enabled) BotColors.surface else BotColors.warnSoft) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = BotSpace.xl, vertical = BotSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            strings[if (enabled) Txt.INBOX_AUTO_REPLY_ON else Txt.INBOX_AUTO_REPLY_OFF],
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) BotColors.inkMuted else BotColors.warnInk,
        )
        BotSwitch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun Composer(
    draft: String,
    sending: Boolean,
    strings: Strings,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
) = Surface(color = BotColors.surface, border = BorderStroke(1.dp, BotColors.line)) {
    Row(
        Modifier.fillMaxWidth().padding(BotSpace.md),
        verticalAlignment = Alignment.Bottom,
    ) {
        BotField(
            value = draft,
            onValueChange = onDraft,
            label = strings[Txt.INBOX_MESSAGE_PLACEHOLDER],
            modifier = Modifier.weight(1f),
            singleLine = false,
        )
        Spacer(Modifier.width(BotSpace.sm))
        PrimaryButton(
            text = strings[Txt.ACTION_SEND],
            onClick = onSend,
            enabled = draft.isNotBlank(),
            busy = sending,
        )
    }
}

private fun ThreadMessage.displayText(strings: Strings): String = when {
    text.isNotBlank() -> text
    hasAttachment -> fileName ?: strings[Txt.INBOX_ATTACHMENT]
    else -> strings[Txt.LABEL_NONE]
}

/** Who sent it. A reply from a person and a reply from the assistant used to look identical. */
private fun ThreadMessage.attribution(strings: Strings): String? = when {
    fromCustomer -> null
    agentName != null -> strings.format(Txt.INBOX_WRITTEN_BY_AGENT, "name" to agentName)
    fromStaff -> strings.format(Txt.INBOX_WRITTEN_BY_AGENT, "name" to strings[Txt.SETTINGS_ACCOUNT])
    else -> null
}

private fun ThreadMessage.footnote(strings: Strings): String? = when {
    failed -> errorText?.takeIf { it.isNotBlank() }
        ?: errorKey?.let { strings.error(com.rfm.edubot.mobile.core.common.AppError.Rejected(it)) }
        ?: strings[Txt.INBOX_SEND_FAILED]
    templateName != null -> templateName
    status.isNotBlank() && !fromCustomer -> strings.status(status)
    else -> null
}
