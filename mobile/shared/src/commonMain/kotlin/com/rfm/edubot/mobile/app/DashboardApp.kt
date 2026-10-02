package com.rfm.edubot.mobile.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.DashboardModules
import com.rfm.edubot.mobile.core.ui.Badge
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.BotTheme
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SectionLabel
import com.rfm.edubot.mobile.core.ui.ThemeChoice
import com.rfm.edubot.mobile.core.ui.Tone
import com.rfm.edubot.mobile.feature.agents.AgentsScreen
import com.rfm.edubot.mobile.feature.assistant.AssistantScreen
import com.rfm.edubot.mobile.feature.auth.LoginExperienceScreen
import com.rfm.edubot.mobile.feature.bookings.BookingsScreen
import com.rfm.edubot.mobile.feature.contacts.ContactsScreen
import com.rfm.edubot.mobile.feature.crm.CatalogItemFormScreen
import com.rfm.edubot.mobile.feature.crm.ClientFormScreen
import com.rfm.edubot.mobile.feature.crm.CrmScreen
import com.rfm.edubot.mobile.feature.crm.DocumentFormScreen
import com.rfm.edubot.mobile.feature.crm.DocumentKind
import com.rfm.edubot.mobile.feature.inbox.ConversationScreen
import com.rfm.edubot.mobile.feature.inbox.InboxScreen
import com.rfm.edubot.mobile.feature.notifications.NotificationsScreen
import com.rfm.edubot.mobile.feature.overview.OverviewScreen
import com.rfm.edubot.mobile.feature.persona.PersonaScreen
import com.rfm.edubot.mobile.feature.settings.SettingsScreen

/**
 * The app.
 *
 * [onBackHandlerChanged] hands the platform a way to pop the stack: Android wires it to the
 * activity's back dispatcher, so back inside a conversation returns to the inbox instead of closing
 * the app.
 */
@Composable
fun DashboardApp(
    graph: MobileGraph,
    deviceLocale: String? = null,
    initialEmail: String = "",
    initialPassword: String = "",
    onBackHandlerChanged: (((() -> Boolean)?) -> Unit)? = null,
) {
    // iOS has no ambient ViewModelStoreOwner outside navigation; Android gets the activity's.
    val fallbackOwner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    CompositionLocalProvider(
        LocalViewModelStoreOwner provides (LocalViewModelStoreOwner.current ?: fallbackOwner),
    ) {
        val sessionVm = viewModel<DashboardSessionViewModel>(
            key = "session",
            factory = viewModelFactory {
                initializer { DashboardSessionViewModel(graph.session, deviceLocale) }
            },
        )
        val state by sessionVm.state.collectAsState()
        val themeChoice by sessionVm.theme.collectAsState()
        val dark = when (themeChoice) {
            ThemeChoice.Light -> false
            ThemeChoice.Dark -> true
            ThemeChoice.System -> isSystemInDarkTheme()
        }

        BotTheme(dark = dark) {
            LaunchedEffect(sessionVm) { sessionVm.restore() }
            when (val current = state) {
                SessionState.Restoring -> Surface(color = BotColors.background) { LoadingScreen() }
                is SessionState.SignedOut -> LoginExperienceScreen(
                    strings = sessionVm.strings,
                    errorMessage = current.error?.let(sessionVm.strings::error),
                    busy = current.busy,
                    initialEmail = initialEmail,
                    initialPassword = initialPassword,
                    onSignIn = sessionVm::signIn,
                )
                is SessionState.SignedIn -> DashboardShell(
                    graph = graph,
                    sessionVm = sessionVm,
                    signedIn = current,
                    onBackHandlerChanged = onBackHandlerChanged,
                )
            }
        }
    }
}

@Composable
private fun DashboardShell(
    graph: MobileGraph,
    sessionVm: DashboardSessionViewModel,
    signedIn: SessionState.SignedIn,
    onBackHandlerChanged: (((() -> Boolean)?) -> Unit)?,
) {
    val identity = signedIn.identity
    val strings = sessionVm.strings
    val clock = sessionVm.clock
    val modules = identity.modules
    val navigator = remember(identity.tenant.id) { Navigator(ModuleRegistry.startModule(modules)) }
    val nav by navigator.state.collectAsState()
    val bottomBar = remember(modules) { ModuleRegistry.bottomBar(modules) }

    val notificationsVm = viewModel<NotificationsBadgeViewModel>(
        key = "notifications:${identity.tenant.id}",
        factory = viewModelFactory { initializer { NotificationsBadgeViewModel(graph.notifications) } },
    )
    val unread by notificationsVm.unread.collectAsState()
    LaunchedEffect(identity.tenant.id) { notificationsVm.watch() }

    // Only claim back while there is something to pop, so the gesture still closes the app at a root.
    LaunchedEffect(nav.canGoBack, onBackHandlerChanged) {
        onBackHandlerChanged?.invoke(if (nav.canGoBack) ({ navigator.back() }) else null)
    }

    Scaffold(
        containerColor = BotColors.background,
        topBar = {
            TenantBar(
                tenantName = identity.tenant.name,
                operator = identity.isOperator,
                unread = unread,
                strings = strings,
                onNotifications = { navigator.open(Destination.Notifications) },
            )
        },
        bottomBar = {
            BottomBar(
                modules = bottomBar,
                activeModule = nav.activeModule,
                moreSelected = nav.current is Destination.MoreMenu,
                strings = strings,
                onModule = navigator::selectModule,
                onMore = { navigator.open(Destination.MoreMenu) },
            )
        },
    ) { padding ->
        when (val destination = nav.current) {
            is Destination.MoreMenu -> MoreScreen(
                modules = modules,
                excluding = bottomBar.map { it.id },
                strings = strings,
                padding = padding,
                onSelect = navigator::selectModule,
            )
            is Destination.Notifications -> NotificationsScreen(
                repository = graph.notifications,
                strings = strings,
                clock = clock,
                padding = padding,
                onOpenModule = navigator::selectModule,
            )
            is Destination.Conversation -> ConversationScreen(
                repository = graph.inbox,
                conversationId = destination.conversationId,
                channels = identity.tenant.channels,
                strings = strings,
                clock = clock,
                padding = padding,
                onBack = { navigator.back() },
            )
            is Destination.NewClient -> ClientFormScreen(
                repository = graph.crm,
                strings = strings,
                padding = padding,
                onSaved = { navigator.back() },
                onCancel = { navigator.back() },
            )
            is Destination.NewQuote, is Destination.NewInvoice -> DocumentFormScreen(
                repository = graph.crm,
                kind = if (destination is Destination.NewQuote) DocumentKind.Quote else DocumentKind.Invoice,
                strings = strings,
                padding = padding,
                onSaved = { navigator.back() },
                onCancel = { navigator.back() },
            )
            is Destination.NewCatalogItem -> CatalogItemFormScreen(
                repository = graph.crm,
                strings = strings,
                padding = padding,
                onSaved = { navigator.back() },
                onCancel = { navigator.back() },
            )
            is Destination.Client -> ClientFormScreen(
                repository = graph.crm,
                strings = strings,
                padding = padding,
                clientId = destination.clientId,
                onSaved = { navigator.back() },
                onCancel = { navigator.back() },
            )
            is Destination.Module -> ModuleScreen(
                graph = graph,
                sessionVm = sessionVm,
                signedIn = signedIn,
                moduleId = destination.id,
                navigator = navigator,
                padding = padding,
            )
        }
    }
}

@Composable
private fun ModuleScreen(
    graph: MobileGraph,
    sessionVm: DashboardSessionViewModel,
    signedIn: SessionState.SignedIn,
    moduleId: String,
    navigator: Navigator,
    padding: PaddingValues,
) {
    val identity = signedIn.identity
    val strings = sessionVm.strings
    val clock = sessionVm.clock
    when (moduleId) {
        DashboardModules.OVERVIEW -> OverviewScreen(
            repository = graph.overview,
            tenantName = identity.tenant.name,
            strings = strings,
            clock = clock,
            padding = padding,
            onOpenModule = navigator::selectModule,
        )
        DashboardModules.CONVERSATIONS -> InboxScreen(
            repository = graph.inbox,
            strings = strings,
            clock = clock,
            padding = padding,
            onOpen = { navigator.open(Destination.Conversation(it.id)) },
        )
        DashboardModules.CONTACTS -> ContactsScreen(
            repository = graph.inbox,
            strings = strings,
            clock = clock,
            padding = padding,
        )
        DashboardModules.AI_ASSISTANT -> AssistantScreen(
            repository = graph.assistant,
            voiceInput = graph.voiceInput,
            strings = strings,
            locale = identity.tenant.locale,
            padding = padding,
        )
        DashboardModules.AGENTS -> AgentsScreen(
            repository = graph.agents,
            strings = strings,
            clock = clock,
            padding = padding,
            canManage = identity.isAdmin,
        )
        DashboardModules.BOOKINGS -> BookingsScreen(
            repository = graph.bookings,
            strings = strings,
            clock = clock,
            padding = padding,
        )
        DashboardModules.PERSONA -> PersonaScreen(
            repository = graph.persona,
            strings = strings,
            padding = padding,
        )
        DashboardModules.SETTINGS -> SettingsScreen(
            settings = graph.settings,
            identity = identity,
            strings = strings,
            theme = sessionVm.theme,
            padding = padding,
            switching = signedIn.switchingCompany,
            onTheme = sessionVm::applyTheme,
            onLocale = sessionVm::applyLocale,
            onSwitchCompany = sessionVm::switchCompany,
            onSignOut = sessionVm::signOut,
        )
        else -> CrmScreen(
            repository = graph.crm,
            section = moduleId,
            strings = strings,
            clock = clock,
            padding = padding,
            onNew = newRecordFor(moduleId)?.let { destination -> ({ navigator.open(destination) }) },
            onOpenClient = { navigator.open(Destination.Client(it)) },
        )
    }
}

@Composable
private fun TenantBar(
    tenantName: String,
    operator: Boolean,
    unread: Long,
    strings: Strings,
    onNotifications: () -> Unit,
) = Surface(color = BotColors.surface, border = BorderStroke(1.dp, BotColors.line)) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = BotSpace.xl, vertical = BotSpace.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                tenantName,
                style = MaterialTheme.typography.titleMedium,
                color = BotColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (operator) {
                Text(
                    strings[Txt.SETTINGS_OPERATOR_SESSION],
                    style = MaterialTheme.typography.labelSmall,
                    color = BotColors.warnInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            Modifier.clickable(onClick = onNotifications).padding(BotSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BotSpace.xs),
        ) {
            Text("◔", style = MaterialTheme.typography.titleMedium, color = BotColors.inkSecondary)
            if (unread > 0) Badge(unread.toString(), Tone.Bad)
        }
    }
}

@Composable
private fun BottomBar(
    modules: List<MobileModule>,
    activeModule: String?,
    moreSelected: Boolean,
    strings: Strings,
    onModule: (String) -> Unit,
    onMore: () -> Unit,
) = Surface(
    modifier = Modifier.navigationBarsPadding(),
    color = BotColors.surface,
    border = BorderStroke(1.dp, BotColors.line),
) {
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        modules.forEach { module ->
            BottomDestination(
                label = strings.module(module.id),
                glyph = module.glyph,
                selected = !moreSelected && activeModule == module.id,
            ) { onModule(module.id) }
        }
        BottomDestination(strings[Txt.NAV_MORE], "•••", moreSelected, onClick = onMore)
    }
}

@Composable
private fun BottomDestination(label: String, glyph: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) BotColors.accentDeep else BotColors.inkMuted
    Column(
        Modifier.width(76.dp).clickable(onClick = onClick).padding(vertical = BotSpace.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier.size(26.dp).then(
                if (selected) Modifier.background(BotColors.accentSoft, CircleShape) else Modifier,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, style = MaterialTheme.typography.titleSmall, color = color)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The rest of the modules, grouped as the web sidebar groups them.
 *
 * Modules the tenant pays for but the app cannot open are listed separately and are not tappable —
 * the previous version offered every module id as a row, and tapping one re-rendered this list.
 */
@Composable
private fun MoreScreen(
    modules: List<String>,
    excluding: List<String>,
    strings: Strings,
    padding: PaddingValues,
    onSelect: (String) -> Unit,
) {
    val sections = remember(modules, excluding) { ModuleRegistry.sections(modules, excluding) }
    val webOnly = remember(modules) { ModuleRegistry.webOnly(modules) }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(bottom = BotSpace.xl),
    ) {
        item { ScreenHeader(strings[Txt.NAV_MORE], strings[Txt.NAV_MORE]) }
        sections.forEach { section ->
            item(key = "group-${section.group}") { SectionLabel(strings[section.group.labelKey()]) }
            items(section.modules, key = { it.id }) { module ->
                ListRow(
                    title = strings.module(module.id),
                    detail = strings.moduleSubtitle(module.id),
                    leading = module.glyph,
                    onClick = { onSelect(module.id) },
                )
            }
        }
        if (webOnly.isNotEmpty()) {
            item { SectionLabel(strings[Txt.NAV_WEB_ONLY]) }
            item { InfoPanel(strings[Txt.NAV_WEB_ONLY_DETAIL]) }
            items(webOnly, key = { "web-${it.id}" }) { module ->
                ListRow(
                    title = strings.module(module.id),
                    detail = strings.moduleSubtitle(module.id),
                    leading = module.glyph,
                )
            }
        }
        if (sections.isEmpty() && webOnly.isEmpty()) {
            item { EmptyState(strings[Txt.EMPTY_TITLE]) }
        }
    }
}

/** What the list's "New" button creates, or null for the lists the app cannot add to yet. */
private fun newRecordFor(moduleId: String): Destination? = when (moduleId) {
    DashboardModules.CLIENTS -> Destination.NewClient
    DashboardModules.QUOTES -> Destination.NewQuote
    DashboardModules.INVOICES -> Destination.NewInvoice
    DashboardModules.CATALOG -> Destination.NewCatalogItem
    else -> null
}

private fun ModuleGroup.labelKey(): String = when (this) {
    ModuleGroup.Home -> Txt.NAV_GROUP_HOME
    ModuleGroup.Inbox -> Txt.NAV_GROUP_INBOX
    ModuleGroup.Business -> Txt.NAV_GROUP_BUSINESS
    ModuleGroup.Automation -> Txt.NAV_GROUP_AUTOMATION
    ModuleGroup.Setup -> Txt.NAV_GROUP_SETUP
}
