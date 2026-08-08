package com.rfm.edubot.mobile.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.common.SessionError
import com.rfm.edubot.mobile.core.common.TokenStore
import com.rfm.edubot.mobile.core.common.VoiceInput
import com.rfm.edubot.mobile.core.localization.MobileCopy
import com.rfm.edubot.mobile.core.localization.MobileStrings
import com.rfm.edubot.mobile.core.network.DashboardApi
import com.rfm.edubot.mobile.core.ui.BotColor
import com.rfm.edubot.mobile.core.ui.BotTheme
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.feature.assistant.AssistantScreen
import com.rfm.edubot.mobile.feature.auth.LoginExperienceScreen
import com.rfm.edubot.mobile.feature.contacts.ContactsScreen
import com.rfm.edubot.mobile.feature.crm.CrmScreen
import com.rfm.edubot.mobile.feature.inbox.InboxScreen
import com.rfm.edubot.mobile.feature.overview.OverviewScreen
import com.rfm.edubot.mobile.feature.persona.PersonaScreen
import com.rfm.edubot.mobile.feature.settings.SettingsScreen

@Composable
fun DashboardApp(
    api: DashboardApi,
    tokenStore: TokenStore,
    voiceInput: VoiceInput,
    initialEmail: String = "",
    initialPassword: String = "",
) {
    // iOS has no ambient ViewModelStoreOwner outside navigation; Android gets the activity-scoped one.
    val fallbackOwner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides (LocalViewModelStoreOwner.current ?: fallbackOwner)) {
        val vm = viewModel<DashboardSessionViewModel>(key = "session", factory = viewModelFactory { initializer { DashboardSessionViewModel(api, tokenStore) } })
        val state by vm.state.collectAsState()

        BotTheme {
            LaunchedEffect(vm) { vm.restore() }
            when (val current = state) {
                DashboardSessionState.Restoring -> LoadingScreen()
                is DashboardSessionState.SignedOut -> LoginScreen(current.error, initialEmail, initialPassword, vm::login)
                is DashboardSessionState.SignedIn -> DashboardShell(api, voiceInput, current, vm::refreshOverview, vm::applyLocale, vm::signOut)
            }
        }
    }
}

@Composable
private fun LoginScreen(
    error: SessionError?,
    initialEmail: String,
    initialPassword: String,
    onLogin: (String, String) -> Unit,
) {
    LoginExperienceScreen(
        initialEmail = initialEmail,
        initialPassword = initialPassword,
        errorMessage = error?.let(MobileStrings.english::error),
        onLogin = onLogin,
    )
}

@Composable
private fun DashboardShell(
    api: DashboardApi,
    voiceInput: VoiceInput,
    state: DashboardSessionState.SignedIn,
    onRefreshOverview: () -> Unit,
    onLocaleUpdated: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val strings = MobileStrings.forLocale(state.identity.tenant.locale)
    val modules = state.identity.modules
    val primaryModules = listOf("overview", "conversations", "ai-assistant").filter { it in modules }
    var selectedModule by remember(modules) { mutableStateOf(primaryModules.firstOrNull() ?: "more") }
    val showingMore = selectedModule == "more"

    Scaffold(
        containerColor = BotColor.Background,
        bottomBar = {
            Surface(
                modifier = Modifier.navigationBarsPadding(),
                color = BotColor.Surface,
                border = BorderStroke(1.dp, BotColor.Border),
            ) {
                Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    primaryModules.forEach { module ->
                        BottomDestination(strings.module(module), moduleLabel(module), selectedModule == module) { selectedModule = module }
                    }
                    BottomDestination(strings.more, "•••", showingMore) { selectedModule = "more" }
                }
            }
        },
    ) { padding ->
        when (selectedModule) {
            "overview" -> OverviewScreen(state.identity.tenant.name, state.overview, state.loadingOverview, strings, onRefreshOverview, padding)
            "conversations" -> InboxScreen(api, state.token, state.identity.tenant.channels, strings, padding)
            "contacts" -> ContactsScreen(api, state.token, strings, padding)
            "ai-assistant" -> AssistantScreen(api, voiceInput, state.token, state.identity.tenant.locale, strings, padding)
            "clients", "quotes", "invoices", "catalog" -> CrmScreen(api, state.token, selectedModule, strings, padding)
            "persona" -> PersonaScreen(api, state.token, strings, padding)
            "settings" -> SettingsScreen(api, state.token, state.identity.tenant, strings, padding, onLocaleUpdated, onSignOut)
            else -> MoreScreen(modules, strings, padding, onSelect = { selectedModule = it })
        }
    }
}

@Composable
private fun BottomDestination(label: String, mark: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) BotColor.Accent else BotColor.Muted
    Column(
        Modifier.width(80.dp).clickable(onClick = onClick).padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(mark, color = color, style = MaterialTheme.typography.titleMedium)
        Text(label, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MoreScreen(modules: List<String>, strings: MobileCopy, padding: PaddingValues, onSelect: (String) -> Unit) {
    val hidden = setOf("overview", "conversations", "ai-assistant")
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { ScreenHeader(strings.more.uppercase(), strings.more) }
        items(modules.filterNot { it in hidden }) { module -> ListRow(strings.module(module), strings.moduleDescription(module), null) { onSelect(module) } }
    }
}

private fun moduleLabel(module: String): String = when (module) { "overview" -> "⌂"; "conversations" -> "◌"; "ai-assistant" -> "✦"; else -> "•" }
