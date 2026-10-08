package com.rfm.edubot.mobile.feature.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.rfm.edubot.mobile.core.common.VoiceInput
import com.rfm.edubot.mobile.core.common.VoiceInputState
import com.rfm.edubot.mobile.core.data.AssistantRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.AssistantAction
import com.rfm.edubot.mobile.core.ui.Badge
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.Chip
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.MessageBubble
import com.rfm.edubot.mobile.core.ui.Panel
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.Tone
import com.rfm.edubot.mobile.core.ui.toneForStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The AI assistant.
 *
 * Adds a thread switcher: the screen only ever showed the newest thread, so earlier conversations
 * were unreachable from the phone even though the backend keeps them.
 */
@Composable
fun AssistantScreen(
    repository: AssistantRepository,
    voiceInput: VoiceInput,
    strings: Strings,
    locale: String,
    padding: PaddingValues,
) {
    val vm = viewModel<AssistantViewModel>(
        key = "assistant:$locale",
        factory = viewModelFactory { initializer { AssistantViewModel(repository, locale, voiceInput) } },
    )
    val assistant by vm.state.collectAsState()
    LaunchedEffect(vm) { vm.load() }

    val detail = assistant.detail
    val listState = rememberLazyListState()
    LaunchedEffect(detail?.messages?.size) {
        val count = detail?.messages?.size ?: 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
        ScreenHeader(strings[Txt.NAV_GROUP_AUTOMATION], strings[Txt.ASSISTANT_TITLE]) {
            PrimaryButton(
                text = strings[Txt.ACTION_NEW],
                onClick = { vm.createThread(strings[Txt.ASSISTANT_NEW_THREAD]) },
                enabled = !assistant.busy,
            )
        }
        if (assistant.threads.size > 1) {
            LazyRow(
                Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.sm),
                horizontalArrangement = Arrangement.spacedBy(BotSpace.sm),
            ) {
                items(assistant.threads, key = { it.id }) { thread ->
                    Chip(
                        label = thread.title.ifBlank { strings[Txt.LABEL_UNTITLED] },
                        selected = detail?.thread?.id == thread.id,
                        onClick = { vm.selectThread(thread) },
                    )
                }
            }
        }
        assistant.error?.let { ErrorPanel(strings.error(it)) }

        if (detail == null) {
            if (assistant.loading) {
                LoadingScreen()
            } else {
                EmptyState(strings[Txt.ASSISTANT_EMPTY], strings[Txt.ASSISTANT_START_THREAD])
            }
            return@Column
        }

        LazyColumn(
            Modifier.weight(1f).padding(horizontal = BotSpace.xl),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(BotSpace.sm),
            contentPadding = PaddingValues(vertical = BotSpace.lg),
        ) {
            items(detail.messages, key = { it.id }) { message ->
                if (message.content.isNotBlank()) {
                    MessageBubble(
                        text = message.content,
                        stamp = message.createdAt,
                        fromCustomer = message.role == "user",
                    )
                }
                message.action?.let { action ->
                    Spacer(Modifier.height(BotSpace.sm))
                    ActionCard(action, strings, assistant.busy, vm::decide)
                }
            }
        }
        assistant.voiceError?.let { ErrorPanel(strings.voiceError(it)) }
        Composer(
            draft = assistant.draft,
            onDraft = vm::updateDraft,
            strings = strings,
            sending = assistant.busy,
            voiceState = assistant.voiceState,
            onVoice = vm::toggleVoice,
            onSend = vm::send,
        )
    }
}

/** A tool call the assistant proposed. Nothing happens until somebody confirms it. */
@Composable
private fun ActionCard(
    action: AssistantAction,
    strings: Strings,
    busy: Boolean,
    onDecide: (String, Boolean) -> Unit,
) = Panel(tone = if (action.pending) Tone.Warn else Tone.Neutral) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            action.toolName,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = BotColors.ink,
        )
        Badge(strings.status(action.status), toneForStatus(action.status))
    }
    action.preview?.summarise()?.let {
        Spacer(Modifier.height(BotSpace.xs))
        Text(it, style = MaterialTheme.typography.bodySmall, color = BotColors.inkSecondary)
    }
    if (action.pending) {
        Spacer(Modifier.height(BotSpace.md))
        Text(
            strings[Txt.ASSISTANT_ACTION_PENDING],
            style = MaterialTheme.typography.bodySmall,
            color = BotColors.warnInk,
        )
        Spacer(Modifier.height(BotSpace.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
            PrimaryButton(
                text = strings[Txt.ACTION_CONFIRM],
                onClick = { onDecide(action.id, true) },
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
            SecondaryButton(
                text = strings[Txt.ACTION_CANCEL],
                onClick = { onDecide(action.id, false) },
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    strings: Strings,
    sending: Boolean,
    voiceState: VoiceInputState,
    onVoice: () -> Unit,
    onSend: () -> Unit,
) {
    val listening = voiceState is VoiceInputState.Listening
    val requestingPermission = voiceState is VoiceInputState.RequestingPermission
    Surface(color = BotColors.surface, border = BorderStroke(1.dp, BotColors.line)) {
        Column(
            Modifier.fillMaxWidth().padding(BotSpace.md),
            verticalArrangement = Arrangement.spacedBy(BotSpace.sm),
        ) {
            BotField(
                value = draft,
                onValueChange = onDraft,
                label = strings[Txt.ASSISTANT_PROMPT_PLACEHOLDER],
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (listening) {
                    Text(
                        strings[Txt.VOICE_LISTENING],
                        style = MaterialTheme.typography.labelMedium,
                        color = BotColors.badInk,
                    )
                    Spacer(Modifier.width(BotSpace.sm))
                }
                IconButton(onClick = onVoice, enabled = !sending && !requestingPermission) {
                    Icon(
                        imageVector = if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = strings[if (listening) Txt.VOICE_STOP else Txt.VOICE_START],
                        tint = if (listening) BotColors.bad else BotColors.accentDeep,
                    )
                }
                Spacer(Modifier.width(BotSpace.sm))
                PrimaryButton(
                    text = strings[Txt.ACTION_SEND],
                    onClick = onSend,
                    enabled = draft.isNotBlank() && !listening,
                    busy = sending,
                )
            }
        }
    }
}

/** The preview's names, numbers and texts one per line, as the dashboard lists them; null when there are none. */
private fun JsonObject.summarise(): String? =
    values.mapNotNull { value -> (value as? JsonPrimitive)?.takeIf { it.booleanOrNull == null }?.contentOrNull?.takeIf(String::isNotBlank) }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n")
