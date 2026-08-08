package com.rfm.edubot.mobile.feature.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.rfm.edubot.mobile.core.localization.MobileCopy
import com.rfm.edubot.mobile.core.network.DashboardApi
import com.rfm.edubot.mobile.core.ui.BotColor
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.MessageBubble
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.StatusLabel

@Composable
fun AssistantScreen(api: DashboardApi, voiceInput: VoiceInput, token: String, locale: String, strings: MobileCopy, padding: PaddingValues) {
    val vm = viewModel<AssistantViewModel>(key = "assistant:$token:$locale", factory = viewModelFactory { initializer { AssistantViewModel(api, token, locale, voiceInput) } })
    val assistant by vm.state.collectAsState()
    LaunchedEffect(vm) { vm.load() }
    Column(Modifier.fillMaxSize().padding(padding)) {
        ScreenHeader(strings.assistant.uppercase(), strings.assistant) { PrimaryButton("+ ${strings.newConversation}", vm::createThread, enabled = !assistant.busy) }
        val detail = assistant.detail
        if (detail == null) {
            if (assistant.loading) LoadingScreen() else InfoPanel(strings.noAssistantThreads, strings.startAssistantThread)
        } else {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                items(detail.messages, key = { it.id }) { message ->
                    MessageBubble(message.role == "user", message.content, message.createdAt)
                    message.action?.let { action ->
                        Surface(color = BotColor.Panel, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, BotColor.Border)) {
                            Column(Modifier.padding(14.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(action.toolName, style = MaterialTheme.typography.titleMedium)
                                    StatusLabel(action.status, if (action.status == "PENDING") BotColor.Warning else BotColor.Success)
                                }
                                if (action.status == "PENDING") Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SecondaryButton(strings.cancel, { vm.decide(action.id, "cancel") }, !assistant.busy)
                                    PrimaryButton(strings.confirm, { vm.decide(action.id, "confirm") }, enabled = !assistant.busy)
                                }
                            }
                        }
                    }
                }
            }
            assistant.voiceError?.let { ErrorPanel(strings.voiceError(it)) }
            AssistantComposer(
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
}

@Composable
private fun AssistantComposer(
    draft: String,
    onDraft: (String) -> Unit,
    strings: MobileCopy,
    sending: Boolean,
    voiceState: VoiceInputState,
    onVoice: () -> Unit,
    onSend: () -> Unit,
) {
    val listening = voiceState is VoiceInputState.Listening
    val requestingPermission = voiceState is VoiceInputState.RequestingPermission
    Surface(color = BotColor.Surface, border = BorderStroke(1.dp, BotColor.Border)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BotField(draft, onDraft, strings.message, Modifier.fillMaxWidth(), singleLine = false)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onVoice, enabled = !sending && !requestingPermission) {
                    Icon(
                        imageVector = if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (listening) strings.stopListening else strings.voice,
                        tint = if (listening) BotColor.Danger else BotColor.Accent,
                    )
                }
                Spacer(Modifier.width(8.dp))
                PrimaryButton(strings.send, onSend, enabled = draft.isNotBlank() && !sending && !listening)
            }
        }
    }
}
