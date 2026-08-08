package com.rfm.edubot.mobile.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.rfm.edubot.mobile.core.model.Tenant
import com.rfm.edubot.mobile.core.network.DashboardApi
import com.rfm.edubot.mobile.core.ui.BotColor
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.SectionLabel

@Composable
fun SettingsScreen(
    api: DashboardApi,
    token: String,
    tenant: Tenant,
    strings: MobileCopy,
    padding: PaddingValues,
    onLocaleUpdated: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val vm = viewModel<SettingsViewModel>(key = "settings:$token", factory = viewModelFactory { initializer { SettingsViewModel(api, token, tenant.locale, onLocaleUpdated) } })
    val settings by vm.state.collectAsState()
    LaunchedEffect(vm) { vm.load() }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { ScreenHeader(strings.settings.uppercase(), strings.settings) }
        item { InfoPanel(tenant.name, tenant.slug) }
        item { SectionLabel(strings.channels) }
        items(tenant.channels) { channel -> ListRow(channel.displayName ?: channel.platform, channel.platform, strings.active) {} }
        item { SectionLabel(strings.language) }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("en" to "EN", "pt-PT" to "PT", "es" to "ES").forEach { (locale, label) ->
                    val selected = settings.selectedLocale.equals(locale, ignoreCase = true)
                    if (selected) {
                        PrimaryButton(label, {}, enabled = !settings.updatingLocale)
                    } else {
                        SecondaryButton(label, { vm.updateLocale(locale) }, !settings.updatingLocale)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(18.dp)); SecondaryButton(strings.signOut, onSignOut, true, Modifier.padding(horizontal = 20.dp).fillMaxWidth(), BotColor.Danger) }
    }
}
