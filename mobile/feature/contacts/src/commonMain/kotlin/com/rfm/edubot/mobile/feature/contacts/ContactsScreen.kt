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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.localization.MobileCopy
import com.rfm.edubot.mobile.core.network.DashboardApi
import com.rfm.edubot.mobile.core.ui.BotColor
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.ScreenHeader

@Composable
fun ContactsScreen(api: DashboardApi, token: String, strings: MobileCopy, padding: PaddingValues) {
    val vm = viewModel<ContactsViewModel>(key = "contacts:$token", factory = viewModelFactory { initializer { ContactsViewModel(api, token) } })
    val contacts by vm.state.collectAsState()
    LaunchedEffect(vm) { vm.load() }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { ScreenHeader(strings.contacts.uppercase(), strings.contacts) { TextButton(onClick = vm::load) { Text(strings.refresh, color = BotColor.Accent) } } }
        contacts.error?.let { item { ErrorPanel(strings.error(it)) } }
        if (contacts.loading) item { LoadingScreen() }
        items(contacts.contacts, key = { it.id }) { contact ->
            ListRow(contact.displayName ?: contact.waId, "${contact.channel} · ${contact.lastSeenAt}", contact.status) {
                vm.toggleStatus(contact)
            }
        }
    }
}
