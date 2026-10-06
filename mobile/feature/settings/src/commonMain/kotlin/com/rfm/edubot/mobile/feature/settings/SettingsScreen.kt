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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.data.SettingsRepository
import com.rfm.edubot.mobile.core.localization.AppLocale
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.DashboardIdentity
import com.rfm.edubot.mobile.core.model.SupportedLocales
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.Chip
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SectionLabel
import com.rfm.edubot.mobile.core.ui.ThemeChoice
import com.rfm.edubot.mobile.core.ui.Tone
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings.
 *
 * Adds the light/dark choice the app had no way to express (it was dark-only while the web
 * dashboard had both) and company switching, which an account with more than one company needs to
 * reach the others at all.
 */
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    identity: DashboardIdentity,
    strings: Strings,
    theme: StateFlow<ThemeChoice>,
    padding: PaddingValues,
    switching: Boolean,
    onTheme: (ThemeChoice) -> Unit,
    onLocale: (String) -> Unit,
    onSwitchCompany: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val vm = viewModel<SettingsViewModel>(
        key = "settings:${identity.tenant.id}",
        factory = viewModelFactory {
            initializer { SettingsViewModel(settings, identity.tenant.locale, onLocale) }
        },
    )
    val state by vm.state.collectAsState()
    val themeChoice by theme.collectAsState()
    LaunchedEffect(identity.tenant.id) { vm.load() }

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { ScreenHeader(strings[Txt.NAV_GROUP_SETUP], strings[Txt.SETTINGS_TITLE]) }
        item { InfoPanel(identity.tenant.name, identity.tenant.slug) }
        state.error?.let { item { ErrorPanel(strings.error(it)) } }
        if (identity.isOperator) {
            item { InfoPanel(strings[Txt.SETTINGS_OPERATOR_SESSION], tone = Tone.Warn) }
        }

        item { SectionLabel(strings[Txt.SETTINGS_APPEARANCE]) }
        item {
            ChoiceRow(
                options = ThemeChoice.entries.map { it to strings[it.labelKey()] },
                selected = themeChoice,
                onSelect = onTheme,
            )
        }

        item { SectionLabel(strings[Txt.SETTINGS_LANGUAGE]) }
        item {
            ChoiceRow(
                options = SupportedLocales.all.map { it to it.localeLabel() },
                selected = state.selectedLocale,
                enabled = !state.updatingLocale,
                onSelect = vm::updateLocale,
            )
        }

        if (identity.companies.size > 1) {
            item { SectionLabel(strings[Txt.SETTINGS_COMPANIES]) }
            items(identity.companies, key = { it.id }) { company ->
                val current = company.id == identity.tenant.id || company.slug == identity.tenant.slug
                ListRow(
                    title = company.name,
                    detail = company.slug,
                    leading = company.name,
                    status = if (current) "ACTIVE" else null,
                    statusLabel = if (current) strings.status("ACTIVE") else null,
                    actionLabel = strings[Txt.SETTINGS_COMPANY_SWITCH].takeIf { !current },
                    onAction = if (current || switching) null else ({ onSwitchCompany(company.id) }),
                )
            }
        }

        item { SectionLabel(strings[Txt.SETTINGS_CHANNELS]) }
        if (identity.tenant.channels.isEmpty()) {
            item { InfoPanel(strings[Txt.SETTINGS_CHANNELS_EMPTY]) }
        } else {
            items(identity.tenant.channels, key = { it.platform + it.externalId }) { channel ->
                ListRow(
                    title = channel.displayName ?: channel.platform,
                    detail = channel.platform,
                    leading = channel.platform,
                    status = "ACTIVE",
                    statusLabel = strings.status("ACTIVE"),
                )
            }
        }
        state.widget?.publicKey?.let { key ->
            item { SectionLabel(strings[Txt.SETTINGS_WIDGET]) }
            item { InfoPanel(strings[Txt.SETTINGS_WIDGET_KEY], key) }
        }

        state.account?.let { account ->
            item { SectionLabel(strings[Txt.SETTINGS_ACCOUNT]) }
            item {
                InfoPanel(
                    account.email,
                    listOfNotNull(strings.status(account.role), account.googleEmail).joinToString(" · "),
                )
            }
        }

        item { InfoPanel(strings[Txt.SETTINGS_WEB_FOR_MORE], tone = Tone.Info) }
        item {
            Spacer(Modifier.height(BotSpace.lg))
            SecondaryButton(
                text = strings[Txt.ACTION_SIGN_OUT],
                onClick = onSignOut,
                modifier = Modifier.padding(horizontal = BotSpace.xl).fillMaxWidth(),
                tone = Tone.Bad,
            )
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) = Row(
    Modifier.fillMaxWidth().padding(horizontal = BotSpace.xl),
    horizontalArrangement = Arrangement.spacedBy(BotSpace.sm),
) {
    options.forEach { (value, label) ->
        Chip(
            label = label,
            selected = value == selected,
            onClick = { if (enabled) onSelect(value) },
        )
    }
}

private fun ThemeChoice.labelKey(): String = when (this) {
    ThemeChoice.System -> Txt.SETTINGS_THEME_SYSTEM
    ThemeChoice.Light -> Txt.SETTINGS_THEME_LIGHT
    ThemeChoice.Dark -> Txt.SETTINGS_THEME_DARK
}

/** The language's own name, which is what a language picker should show. */
private fun String.localeLabel(): String = when (AppLocale.of(this)) {
    AppLocale.English -> "English"
    AppLocale.Portuguese -> "Português"
    AppLocale.Spanish -> "Español"
}
