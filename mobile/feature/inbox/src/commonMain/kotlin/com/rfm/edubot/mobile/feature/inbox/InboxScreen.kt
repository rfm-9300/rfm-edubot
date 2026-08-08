package com.rfm.edubot.mobile.feature.inbox

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.localization.MobileCopy
import com.rfm.edubot.mobile.core.model.ChannelAsset
import com.rfm.edubot.mobile.core.network.DashboardApi
import com.rfm.edubot.mobile.core.ui.BotColor
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.MessageBubble
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader

@Composable
fun InboxScreen(api: DashboardApi, token: String, channels: List<ChannelAsset>, strings: MobileCopy, padding: PaddingValues) {
    val vm = viewModel<InboxViewModel>(key = "inbox:$token", factory = viewModelFactory { initializer { InboxViewModel(api, token, channels) } })
    val inbox by vm.state.collectAsState()
    var draft by remember(inbox.selectedConversation?.id) { mutableStateOf("") }
    LaunchedEffect(vm) { vm.load() }
    if (inbox.selectedConversation == null) {
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
            item { ScreenHeader(strings.inbox.uppercase(), strings.conversations) { TextButton(onClick = vm::load) { Text(strings.refresh, color = BotColor.Accent) } } }
            inbox.error?.let { item { ErrorPanel(strings.error(it)) } }
            if (inbox.loading) item { LoadingScreen() }
            items(inbox.conversations, key = { it.id }) { conversation ->
                ListRow(
                    title = conversation.displayName ?: conversation.waId,
                    detail = "${conversation.channel} · ${conversation.messageCount} ${strings.messages.lowercase()}",
                    status = conversation.state,
                    onClick = { vm.select(conversation) },
                )
            }
        }
    } else {
        val conversation = requireNotNull(inbox.selectedConversation)
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(strings.inbox.uppercase(), conversation.displayName ?: conversation.waId) {
                TextButton(onClick = vm::clearSelection) { Text(strings.backToInbox, color = BotColor.Accent) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                items(inbox.messages, key = { it.id }) { message -> MessageBubble(message.role == "USER", message.text, message.createdAt) }
            }
            if (conversation.channel == "WEB") InfoPanel(strings.webReplyUnavailable, "") else ReplyComposer(draft, { draft = it }, strings, inbox.sending) { vm.send(draft); draft = "" }
        }
    }
}

@Composable
private fun ReplyComposer(draft: String, onDraft: (String) -> Unit, strings: MobileCopy, sending: Boolean, onSend: () -> Unit) {
    Surface(color = BotColor.Surface, border = androidx.compose.foundation.BorderStroke(1.dp, BotColor.Border)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BotField(draft, onDraft, strings.message, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            PrimaryButton(strings.send, onSend, enabled = draft.isNotBlank() && !sending)
        }
    }
}
