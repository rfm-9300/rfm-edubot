package com.rfm.edubot.mobile.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rfm.edubot.mobile.data.DashboardApi
import com.rfm.edubot.mobile.data.TokenStore

@Composable
fun DashboardApp(
    api: DashboardApi,
    tokenStore: TokenStore,
    voiceInput: VoiceInput,
    initialEmail: String = "",
    initialPassword: String = "",
) {
    val controller = remember(api, tokenStore) { DashboardSessionController(api, tokenStore) }
    val state by controller.state.collectAsState()

    BotTheme {
        LaunchedEffect(controller) { controller.restore() }
        when (val current = state) {
            DashboardSessionState.Restoring -> LoadingScreen()
            is DashboardSessionState.SignedOut -> LoginScreen(current.error, initialEmail, initialPassword, controller::login)
            is DashboardSessionState.SignedIn -> DashboardShell(api, voiceInput, current, controller::refreshOverview, controller::applyLocale, controller::signOut)
        }
    }
}

@Composable
private fun LoadingScreen() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = BotColor.Accent, modifier = Modifier.size(36.dp))
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
                border = androidx.compose.foundation.BorderStroke(1.dp, BotColor.Border),
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
            "overview" -> OverviewScreen(state, strings, onRefreshOverview, padding)
            "conversations" -> InboxScreen(api, state, strings, padding)
            "contacts" -> ContactsScreen(api, state, strings, padding)
            "ai-assistant" -> AssistantScreen(api, voiceInput, state, strings, padding)
            "clients", "quotes", "invoices", "catalog" -> CrmScreen(api, state, selectedModule, strings, padding)
            "persona" -> PersonaScreen(api, state, strings, padding)
            "settings" -> SettingsScreen(api, state, strings, padding, onLocaleUpdated, onSignOut)
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
private fun ScreenHeader(eyebrow: String, title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = BotColor.Muted)
            Spacer(Modifier.height(3.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        action?.invoke()
    }
    HorizontalDivider(color = BotColor.Border)
}

@Composable
private fun OverviewScreen(state: DashboardSessionState.SignedIn, strings: MobileCopy, onRefresh: () -> Unit, padding: PaddingValues) {
    val overview = state.overview
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader(state.identity.tenant.name.uppercase(), strings.overview) {
                TextButton(onClick = onRefresh, enabled = !state.loadingOverview) { Text(strings.refresh, color = BotColor.Accent) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(20.dp, 18.dp, 20.dp, 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(strings.operationalSnapshot, style = MaterialTheme.typography.labelLarge)
                StatusLabel(strings.synced, BotColor.Success)
            }
        }
        if (overview == null) item { LoadingScreen() } else {
            item { MetricRow(strings.messagesToday, overview.messagesToday, strings.messages, overview.messages) }
            item { MetricRow(strings.contacts, overview.users, strings.conversations, overview.conversations) }
            item { MetricRow(strings.quotes, overview.quotes, strings.invoices, overview.invoices) }
            item { InfoPanel(strings.cacheReady, strings.cacheDescription) }
        }
    }
}

@Composable
private fun MetricRow(leftLabel: String, leftValue: Long, rightLabel: String, rightValue: Long) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard(leftLabel, leftValue, Modifier.weight(1f))
        MetricCard(rightLabel, rightValue, Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(label: String, value: Long, modifier: Modifier) = Surface(
    modifier = modifier, color = BotColor.Surface, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, BotColor.Border),
) {
    Column(Modifier.padding(16.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = BotColor.Muted)
        Spacer(Modifier.height(8.dp))
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun InboxScreen(api: DashboardApi, state: DashboardSessionState.SignedIn, strings: MobileCopy, padding: PaddingValues) {
    val controller = remember(api, state.token) { InboxController(api, state.token, state.identity.tenant.channels) }
    val inbox by controller.state.collectAsState()
    var draft by remember(inbox.selectedConversation?.id) { mutableStateOf("") }
    LaunchedEffect(controller) { controller.load() }
    if (inbox.selectedConversation == null) {
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
            item { ScreenHeader(strings.inbox.uppercase(), strings.conversations) { TextButton(onClick = controller::load) { Text(strings.refresh, color = BotColor.Accent) } } }
            inbox.error?.let { item { ErrorPanel(strings.error(it)) } }
            if (inbox.loading) item { LoadingScreen() }
            items(inbox.conversations, key = { it.id }) { conversation ->
                ListRow(
                    title = conversation.displayName ?: conversation.waId,
                    detail = "${conversation.channel} · ${conversation.messageCount} ${strings.messages.lowercase()}",
                    status = conversation.state,
                    onClick = { controller.select(conversation) },
                )
            }
        }
    } else {
        val conversation = requireNotNull(inbox.selectedConversation)
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(strings.inbox.uppercase(), conversation.displayName ?: conversation.waId) {
                TextButton(onClick = controller::clearSelection) { Text(strings.backToInbox, color = BotColor.Accent) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                items(inbox.messages, key = { it.id }) { message -> MessageBubble(message.role == "USER", message.text, message.createdAt) }
            }
            if (conversation.channel == "WEB") InfoPanel(strings.webReplyUnavailable, "") else ReplyComposer(draft, { draft = it }, strings, inbox.sending) { controller.send(draft); draft = "" }
        }
    }
}

@Composable
private fun MessageBubble(customer: Boolean, text: String, createdAt: String) {
    val color = if (customer) BotColor.Surface else Color(0x26FFD60A)
    Surface(
        modifier = Modifier.fillMaxWidth(), color = color, shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, BotColor.Border),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(createdAt, style = MaterialTheme.typography.labelMedium, color = BotColor.Muted)
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

@Composable
private fun ContactsScreen(api: DashboardApi, state: DashboardSessionState.SignedIn, strings: MobileCopy, padding: PaddingValues) {
    val controller = remember(api, state.token) { ContactsController(api, state.token) }
    val contacts by controller.state.collectAsState()
    LaunchedEffect(controller) { controller.load() }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { ScreenHeader(strings.contacts.uppercase(), strings.contacts) { TextButton(onClick = controller::load) { Text(strings.refresh, color = BotColor.Accent) } } }
        contacts.error?.let { item { ErrorPanel(strings.error(it)) } }
        if (contacts.loading) item { LoadingScreen() }
        items(contacts.contacts, key = { it.id }) { contact ->
            ListRow(contact.displayName ?: contact.waId, "${contact.channel} · ${contact.lastSeenAt}", contact.status) {
                controller.toggleStatus(contact)
            }
        }
    }
}

@Composable
private fun AssistantScreen(api: DashboardApi, voiceInput: VoiceInput, state: DashboardSessionState.SignedIn, strings: MobileCopy, padding: PaddingValues) {
    val controller = remember(api, state.token, state.identity.tenant.locale, voiceInput) {
        AssistantController(api, state.token, state.identity.tenant.locale, voiceInput)
    }
    val assistant by controller.state.collectAsState()
    LaunchedEffect(controller) { controller.load() }
    DisposableEffect(controller) { onDispose(controller::close) }
    Column(Modifier.fillMaxSize().padding(padding)) {
        ScreenHeader(strings.assistant.uppercase(), strings.assistant) { PrimaryButton("+ ${strings.newConversation}", controller::createThread, enabled = !assistant.busy) }
        val detail = assistant.detail
        if (detail == null) {
            if (assistant.loading) LoadingScreen() else InfoPanel(strings.noAssistantThreads, strings.startAssistantThread)
        } else {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                items(detail.messages, key = { it.id }) { message ->
                    MessageBubble(message.role == "user", message.content, message.createdAt)
                    message.action?.let { action ->
                        Surface(color = BotColor.Panel, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, BotColor.Border)) {
                            Column(Modifier.padding(14.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(action.toolName, style = MaterialTheme.typography.titleMedium)
                                    StatusLabel(action.status, if (action.status == "PENDING") BotColor.Warning else BotColor.Success)
                                }
                                if (action.status == "PENDING") Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SecondaryButton(strings.cancel, { controller.decide(action.id, "cancel") }, !assistant.busy)
                                    PrimaryButton(strings.confirm, { controller.decide(action.id, "confirm") }, enabled = !assistant.busy)
                                }
                            }
                        }
                    }
                }
            }
            assistant.voiceError?.let { ErrorPanel(strings.voiceError(it)) }
            AssistantComposer(
                draft = assistant.draft,
                onDraft = controller::updateDraft,
                strings = strings,
                sending = assistant.busy,
                voiceState = assistant.voiceState,
                onVoice = controller::toggleVoice,
                onSend = controller::send,
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
    Surface(color = BotColor.Surface, border = androidx.compose.foundation.BorderStroke(1.dp, BotColor.Border)) {
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

@Composable
private fun MoreScreen(modules: List<String>, strings: MobileCopy, padding: PaddingValues, onSelect: (String) -> Unit) {
    val hidden = setOf("overview", "conversations", "ai-assistant")
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { ScreenHeader(strings.more.uppercase(), strings.more) }
        items(modules.filterNot { it in hidden }) { module -> ListRow(strings.module(module), strings.moduleDescription(module), null) { onSelect(module) } }
    }
}

@Composable
private fun PersonaScreen(api: DashboardApi, state: DashboardSessionState.SignedIn, strings: MobileCopy, padding: PaddingValues) {
    val controller = remember(api, state.token) { PersonaController(api, state.token) }
    val personaState by controller.state.collectAsState()
    var instructions by remember(personaState.persona?.version) { mutableStateOf(personaState.persona?.compiledInstructions.orEmpty()) }
    LaunchedEffect(controller) { controller.load() }
    Column(Modifier.fillMaxSize().padding(padding)) {
        ScreenHeader(strings.persona.uppercase(), strings.persona)
        val persona = personaState.persona
        if (persona == null) LoadingScreen() else Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${strings.version} ${persona.version}", style = MaterialTheme.typography.labelMedium, color = BotColor.Muted)
                StatusLabel(persona.status, BotColor.Success)
            }
            Spacer(Modifier.height(16.dp))
            BotField(instructions, { instructions = it }, strings.instructions, Modifier.fillMaxWidth().weight(1f), singleLine = false)
            Spacer(Modifier.height(12.dp))
            PrimaryButton(strings.save, { controller.save(instructions) }, Modifier.fillMaxWidth(), !personaState.saving)
        }
    }
}

@Composable
private fun SettingsScreen(
    api: DashboardApi,
    state: DashboardSessionState.SignedIn,
    strings: MobileCopy,
    padding: PaddingValues,
    onLocaleUpdated: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val controller = remember(api, state.token) {
        SettingsController(api, state.token, state.identity.tenant.locale, onLocaleUpdated)
    }
    val settings by controller.state.collectAsState()
    LaunchedEffect(controller) { controller.load() }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { ScreenHeader(strings.settings.uppercase(), strings.settings) }
        item { InfoPanel(state.identity.tenant.name, state.identity.tenant.slug) }
        item { SectionLabel(strings.channels) }
        items(state.identity.tenant.channels) { channel -> ListRow(channel.displayName ?: channel.platform, channel.platform, strings.active) {} }
        item { SectionLabel(strings.language) }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("en" to "EN", "pt-PT" to "PT", "es" to "ES").forEach { (locale, label) ->
                    val selected = settings.selectedLocale.equals(locale, ignoreCase = true)
                    if (selected) {
                        PrimaryButton(label, {}, enabled = !settings.updatingLocale)
                    } else {
                        SecondaryButton(label, { controller.updateLocale(locale) }, !settings.updatingLocale)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(18.dp)); SecondaryButton(strings.signOut, onSignOut, true, Modifier.padding(horizontal = 20.dp).fillMaxWidth(), BotColor.Danger) }
    }
}

@Composable
private fun CrmScreen(api: DashboardApi, state: DashboardSessionState.SignedIn, module: String, strings: MobileCopy, padding: PaddingValues) {
    val controller = remember(api, state.token) { CrmController(api, state.token) }
    val crm by controller.state.collectAsState()
    var clientName by remember(module) { mutableStateOf("") }
    var clientPhone by remember(module) { mutableStateOf("") }
    var clientAddress by remember(module) { mutableStateOf("") }
    var itemId by remember(module) { mutableStateOf("") }
    var itemDescription by remember(module) { mutableStateOf("") }
    var itemCategory by remember(module) { mutableStateOf("") }
    var itemUnit by remember(module) { mutableStateOf("") }
    var itemPrice by remember(module) { mutableStateOf("") }
    var showCreateForm by remember(module) { mutableStateOf(false) }
    var documentClientId by remember(module) { mutableStateOf("") }
    var documentItemDescription by remember(module) { mutableStateOf("") }
    var documentQuantity by remember(module) { mutableStateOf("1") }
    var documentUnit by remember(module) { mutableStateOf("") }
    var documentUnitPrice by remember(module) { mutableStateOf("") }
    var quoteNotes by remember(module) { mutableStateOf("") }
    var quoteValidUntil by remember(module) { mutableStateOf("") }
    var invoiceDueDate by remember(module) { mutableStateOf("") }
    LaunchedEffect(controller, module) { controller.load(module) }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            ScreenHeader(strings.module(module).uppercase(), strings.module(module)) {
                if (module == "quotes" || module == "invoices") {
                    TextButton(onClick = { showCreateForm = !showCreateForm }) { Text(strings.newDocument, color = BotColor.Accent) }
                }
            }
        }
        if (module == "clients") item {
            Surface(Modifier.fillMaxWidth().padding(20.dp, 12.dp), color = BotColor.Panel, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, BotColor.Border)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotField(clientName, { clientName = it }, strings.clientName, Modifier.fillMaxWidth())
                    BotField(clientPhone, { clientPhone = it }, strings.clientPhone, Modifier.fillMaxWidth())
                    BotField(clientAddress, { clientAddress = it }, strings.clientAddress, Modifier.fillMaxWidth())
                    PrimaryButton(strings.createClient, {
                        controller.createClient(clientName, clientPhone, clientAddress)
                        clientName = ""; clientPhone = ""; clientAddress = ""
                    }, Modifier.fillMaxWidth(), clientName.isNotBlank() && clientPhone.isNotBlank() && !crm.loading)
                }
            }
        }
        if (module == "catalog") item {
            Surface(Modifier.fillMaxWidth().padding(20.dp, 12.dp), color = BotColor.Panel, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, BotColor.Border)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotField(itemId, { itemId = it }, strings.itemId, Modifier.fillMaxWidth())
                    BotField(itemDescription, { itemDescription = it }, strings.itemDescription, Modifier.fillMaxWidth())
                    BotField(itemCategory, { itemCategory = it }, strings.itemCategory, Modifier.fillMaxWidth())
                    BotField(itemUnit, { itemUnit = it }, strings.itemUnit, Modifier.fillMaxWidth())
                    BotField(itemPrice, { itemPrice = it }, strings.itemPrice, Modifier.fillMaxWidth())
                    PrimaryButton(strings.createCatalogItem, {
                        controller.createCatalogItem(com.rfm.edubot.mobile.data.CatalogItem(itemId, "service", itemCategory, itemDescription, itemUnit, itemPrice.toDoubleOrNull() ?: 0.0))
                        itemId = ""; itemDescription = ""; itemCategory = ""; itemUnit = ""; itemPrice = ""
                    }, Modifier.fillMaxWidth(), itemId.isNotBlank() && itemDescription.isNotBlank() && itemCategory.isNotBlank() && itemUnit.isNotBlank() && !crm.loading)
                }
            }
        }
        if ((module == "quotes" || module == "invoices") && showCreateForm) item {
            val isQuote = module == "quotes"
            val quantity = documentQuantity.toDoubleOrNull()
            val unitPrice = documentUnitPrice.toDoubleOrNull()
            val canCreate = documentClientId.isNotBlank() && documentItemDescription.isNotBlank() && documentUnit.isNotBlank() && quantity != null && quantity > 0 && unitPrice != null && unitPrice >= 0 && (isQuote || invoiceDueDate.isNotBlank()) && !crm.loading
            Surface(Modifier.fillMaxWidth().padding(20.dp, 12.dp), color = BotColor.Panel, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, BotColor.Border)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotField(documentClientId, { documentClientId = it }, strings.clientId, Modifier.fillMaxWidth())
                    BotField(documentItemDescription, { documentItemDescription = it }, strings.itemDescription, Modifier.fillMaxWidth())
                    BotField(documentQuantity, { documentQuantity = it }, strings.quantity, Modifier.fillMaxWidth())
                    BotField(documentUnit, { documentUnit = it }, strings.itemUnit, Modifier.fillMaxWidth())
                    BotField(documentUnitPrice, { documentUnitPrice = it }, strings.itemPrice, Modifier.fillMaxWidth())
                    if (isQuote) {
                        BotField(quoteValidUntil, { quoteValidUntil = it }, strings.validUntil, Modifier.fillMaxWidth())
                        BotField(quoteNotes, { quoteNotes = it }, strings.notes, Modifier.fillMaxWidth())
                    } else {
                        BotField(invoiceDueDate, { invoiceDueDate = it }, strings.dueDate, Modifier.fillMaxWidth())
                    }
                    PrimaryButton(if (isQuote) strings.createQuote else strings.createInvoice, {
                        val lineItem = com.rfm.edubot.mobile.data.LineItem(documentItemDescription, quantity ?: 0.0, documentUnit, unitPrice ?: 0.0)
                        if (isQuote) {
                            controller.createQuote(com.rfm.edubot.mobile.data.CreateQuote(documentClientId, listOf(lineItem), quoteNotes.ifBlank { null }, quoteValidUntil.ifBlank { null }))
                        } else {
                            controller.createInvoice(com.rfm.edubot.mobile.data.CreateInvoice(documentClientId, items = listOf(lineItem), dueDate = invoiceDueDate))
                        }
                        showCreateForm = false
                    }, Modifier.fillMaxWidth(), canCreate)
                }
            }
        }
        if (crm.loading) item { LoadingScreen() }
        when (module) {
            "clients" -> items(crm.clients, key = { it.id }) { ListRow(it.name, "${it.phone} · ${it.number}", null) {} }
            "quotes" -> items(crm.quotes, key = { it.id }) { ListRow(it.number, "${it.clientName ?: ""} · ${it.totalEur}", it.status) {} }
            "invoices" -> items(crm.invoices, key = { it.id }) { ListRow(it.number, "${it.clientName ?: ""} · ${it.totalEur}", it.status) {} }
            "catalog" -> items(crm.catalog, key = { it.id }) { ListRow(it.description, "${it.category} · ${it.defaultUnitPriceEur}/${it.unit}", null) {} }
        }
    }
}

@Composable
private fun ListRow(title: String, detail: String, status: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(Modifier.size(38.dp), shape = RoundedCornerShape(10.dp), color = BotColor.Panel) { Box(contentAlignment = Alignment.Center) { Text(title.take(2).uppercase(), style = MaterialTheme.typography.labelMedium, color = BotColor.Accent) } }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = BotColor.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        status?.let { StatusLabel(it, statusColor(it)) }
    }
    HorizontalDivider(Modifier.padding(start = 70.dp), color = BotColor.Border)
}

@Composable
private fun StatusLabel(text: String, color: Color) = Text("• ${text.uppercase()}", style = MaterialTheme.typography.labelMedium, color = color)

@Composable
private fun SectionLabel(text: String) = Text(text.uppercase(), Modifier.padding(20.dp, 18.dp, 20.dp, 6.dp), style = MaterialTheme.typography.labelMedium, color = BotColor.Muted)

@Composable
private fun InfoPanel(title: String, detail: String) = Surface(Modifier.fillMaxWidth().padding(20.dp, 12.dp), color = BotColor.Panel, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, BotColor.Border)) {
    Column(Modifier.padding(14.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); if (detail.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(detail, style = MaterialTheme.typography.bodyMedium, color = BotColor.Subtle) } }
}

@Composable
private fun ErrorPanel(text: String) = Surface(Modifier.fillMaxWidth().padding(20.dp, 12.dp), color = Color(0x1FF06B70), shape = RoundedCornerShape(10.dp)) { Text(text, Modifier.padding(14.dp), color = BotColor.Danger) }

@Composable
private fun BotField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, password: Boolean = false, singleLine: Boolean = true) = OutlinedTextField(
    value = value, onValueChange = onValueChange, modifier = modifier, label = { Text(label) }, singleLine = singleLine,
    visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = BotColor.Accent, unfocusedBorderColor = BotColor.Border, focusedLabelColor = BotColor.Accent),
)

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) = Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = BotColor.Accent, contentColor = BotColor.Background)) { Text(text) }

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier, color: Color = BotColor.Subtle) = Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = color, disabledContainerColor = Color.Transparent)) { Text(text) }

private fun statusColor(status: String): Color = when (status.uppercase()) {
    "ACTIVE", "OPEN", "CONFIRMED", "PAID", "RESOLVED" -> BotColor.Success
    "PENDING", "DRAFT" -> BotColor.Warning
    "BLOCKED", "FAILED", "CANCELLED" -> BotColor.Danger
    else -> BotColor.Info
}

private fun moduleLabel(module: String): String = when (module) { "overview" -> "⌂"; "conversations" -> "◌"; "ai-assistant" -> "✦"; else -> "•" }

private data class MobileCopy(
    val loginTitle: String, val loginDescription: String, val email: String, val password: String, val signIn: String, val signOut: String,
    val overview: String, val refresh: String, val messagesToday: String, val messages: String, val contacts: String, val conversations: String,
    val inbox: String, val backToInbox: String, val message: String, val send: String, val webReplyUnavailable: String, val assistant: String,
    val newConversation: String, val confirm: String, val cancel: String, val persona: String, val instructions: String, val save: String,
    val version: String, val settings: String, val channels: String, val language: String, val quotes: String, val invoices: String,
    val clientName: String, val clientPhone: String, val clientAddress: String, val createClient: String,
    val itemId: String, val itemDescription: String, val itemCategory: String, val itemUnit: String, val itemPrice: String, val createCatalogItem: String,
    val more: String, val synced: String, val operationalSnapshot: String, val cacheReady: String, val cacheDescription: String,
    val noAssistantThreads: String, val startAssistantThread: String, val active: String, val modules: Map<String, String>, val errors: Map<SessionError, String>,
    val voice: String = "Voice", val stopListening: String = "Stop", val voicePermissionDenied: String = "Microphone and speech recognition permission is required.", val voiceUnavailable: String = "Speech recognition is unavailable on this device.", val voiceRecognitionFailed: String = "Speech could not be recognized. Please try again.",
    val newDocument: String = "New", val clientId: String = "Client ID", val quantity: String = "Quantity", val validUntil: String = "Valid until (YYYY-MM-DD)", val dueDate: String = "Due date (YYYY-MM-DD)", val notes: String = "Notes", val createQuote: String = "Create quote", val createInvoice: String = "Create invoice",
) {
    fun module(id: String): String = modules[id] ?: id
    fun moduleDescription(id: String): String = when (id) { "contacts" -> contacts; "clients" -> "CRM"; "quotes", "invoices", "catalog" -> "CRM"; "persona" -> persona; "settings" -> settings; else -> "" }
    fun error(error: SessionError): String = errors.getValue(error)
    fun error(error: String): String = errors[if (error == "send" || error == "update") SessionError.INVALID_CREDENTIALS else SessionError.CONNECTION_FAILED].orEmpty()
    fun voiceError(error: VoiceInputError): String = when (error) {
        VoiceInputError.PERMISSION_DENIED -> voicePermissionDenied
        VoiceInputError.UNAVAILABLE -> voiceUnavailable
        VoiceInputError.RECOGNITION_FAILED -> voiceRecognitionFailed
    }
}

private object MobileStrings {
    val english = MobileCopy(
        "Sign in", "Manage your assistant from anywhere.", "Email", "Password", "Sign in", "Sign out", "Overview", "Refresh", "Messages today", "Messages", "Contacts", "Conversations", "Inbox", "Back", "Message", "Send", "Replies are unavailable for website conversations.", "Assistant", "New", "Confirm", "Cancel", "Persona", "Instructions", "Save", "Version", "Settings", "Channels", "Language", "Quotes", "Invoices", "Client name", "Phone", "Address", "Create client", "Item ID", "Description", "Category", "Unit", "Price", "Create catalog item", "More", "Synced", "Operational snapshot", "Cache ready", "Showing the last successful snapshot when offline.", "No assistant threads", "Start a new thread to work with your assistant.", "Active",
        mapOf("overview" to "Overview", "conversations" to "Inbox", "contacts" to "Contacts", "ai-assistant" to "Assistant", "clients" to "Clients", "quotes" to "Quotes", "invoices" to "Invoices", "catalog" to "Catalog", "persona" to "Persona", "settings" to "Settings"),
        mapOf(SessionError.MISSING_CREDENTIALS to "Enter your email and password.", SessionError.INVALID_CREDENTIALS to "Unable to sign in. Check your credentials and try again.", SessionError.SESSION_EXPIRED to "Your session has expired. Please sign in again.", SessionError.CONNECTION_FAILED to "Unable to connect to the dashboard."),
    )
    private val portuguese = english.copy(loginTitle = "Entrar", loginDescription = "Gira o seu assistente em qualquer lugar.", signIn = "Entrar", signOut = "Sair", overview = "Visão geral", refresh = "Atualizar", messagesToday = "Mensagens hoje", contacts = "Contactos", conversations = "Conversas", inbox = "Caixa de entrada", send = "Enviar", assistant = "Assistente", newConversation = "Novo", confirm = "Confirmar", cancel = "Cancelar", instructions = "Instruções", save = "Guardar", settings = "Definições", channels = "Canais", language = "Idioma", more = "Mais", voice = "Voz", stopListening = "Parar", voicePermissionDenied = "É necessária autorização para o microfone e reconhecimento de voz.", voiceUnavailable = "O reconhecimento de voz não está disponível neste dispositivo.", voiceRecognitionFailed = "Não foi possível reconhecer a fala. Tente novamente.", newDocument = "Novo", clientId = "ID do cliente", quantity = "Quantidade", validUntil = "Válido até (AAAA-MM-DD)", dueDate = "Data de vencimento (AAAA-MM-DD)", notes = "Notas", createQuote = "Criar orçamento", createInvoice = "Criar fatura")
    private val spanish = english.copy(loginTitle = "Iniciar sesión", loginDescription = "Gestiona tu asistente desde cualquier lugar.", signIn = "Iniciar sesión", signOut = "Cerrar sesión", overview = "Resumen", refresh = "Actualizar", messagesToday = "Mensajes de hoy", conversations = "Conversaciones", inbox = "Bandeja de entrada", send = "Enviar", assistant = "Asistente", newConversation = "Nuevo", confirm = "Confirmar", cancel = "Cancelar", persona = "Personalidad", instructions = "Instrucciones", save = "Guardar", settings = "Configuración", channels = "Canales", language = "Idioma", more = "Más", voice = "Voz", stopListening = "Parar", voicePermissionDenied = "Se requiere permiso para el micrófono y el reconocimiento de voz.", voiceUnavailable = "El reconocimiento de voz no está disponible en este dispositivo.", voiceRecognitionFailed = "No se pudo reconocer la voz. Inténtalo de nuevo.", newDocument = "Nuevo", clientId = "ID del cliente", quantity = "Cantidad", validUntil = "Válido hasta (AAAA-MM-DD)", dueDate = "Fecha de vencimiento (AAAA-MM-DD)", notes = "Notas", createQuote = "Crear presupuesto", createInvoice = "Crear factura")
    fun forLocale(locale: String): MobileCopy = when (locale.lowercase().substringBefore('-')) { "pt" -> portuguese; "es" -> spanish; else -> english }
}
