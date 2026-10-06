package com.rfm.edubot.mobile.feature.persona

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.data.PersonaRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.ui.Badge
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.Panel
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.SectionLabel
import com.rfm.edubot.mobile.core.ui.Tone
import com.rfm.edubot.mobile.core.ui.toneForStatus

/**
 * Persona.
 *
 * The app only had the compiled-instructions box. This adds what the web page is actually for: the
 * sources that feed the prompt, a rebuild, and the test chat that shows how the bot would answer.
 */
@Composable
fun PersonaScreen(
    repository: PersonaRepository,
    strings: Strings,
    padding: PaddingValues,
) {
    val vm = viewModel<PersonaViewModel>(
        key = "persona",
        factory = viewModelFactory { initializer { PersonaViewModel(repository) } },
    )
    val state by vm.persona.collectAsState()
    val editor by vm.editor.collectAsState()
    LaunchedEffect(vm) { vm.load() }

    val persona = state.value
    var instructions by remember(persona?.version) { mutableStateOf(persona?.compiledInstructions.orEmpty()) }

    LazyColumn(
        Modifier.fillMaxSize().padding(padding).imePadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            ScreenHeader(strings[Txt.NAV_GROUP_AUTOMATION], strings[Txt.PERSONA_TITLE]) {
                TextButton(onClick = { vm.refresh() }) {
                    Text(strings[Txt.ACTION_REFRESH], color = BotColors.accentDeep)
                }
            }
        }
        editor.error?.let { item { ErrorPanel(strings.error(it)) } }
        if (persona == null) {
            item { if (state.loading) LoadingScreen() else ErrorPanel(strings[Txt.ERROR_OFFLINE]) }
            return@LazyColumn
        }

        item {
            Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.sm)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
                    Text(
                        strings.format(Txt.PERSONA_VERSION, "version" to persona.version),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = BotColors.inkMuted,
                    )
                    Badge(strings.status(persona.status), toneForStatus(persona.status))
                }
                Spacer(Modifier.height(BotSpace.xs))
                Text(
                    strings.format(Txt.PERSONA_TOKENS, "count" to persona.tokenEstimate),
                    style = MaterialTheme.typography.bodySmall,
                    color = BotColors.inkFaint,
                )
                Spacer(Modifier.height(BotSpace.md))
                Row(horizontalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
                    SecondaryButton(
                        text = strings[Txt.PERSONA_REBUILD],
                        onClick = vm::rebuild,
                        enabled = !editor.busy && !persona.compiling,
                    )
                }
            }
        }
        if (persona.compiling) {
            item { InfoPanel(strings[Txt.PERSONA_COMPILING], tone = Tone.Warn) }
        }

        item { SectionLabel(strings[Txt.PERSONA_SOURCES]) }
        if (persona.sources.isEmpty()) {
            item { InfoPanel(strings[Txt.PERSONA_SOURCES_EMPTY]) }
        } else {
            items(persona.sources, key = { it.id }) { source ->
                ListRow(
                    title = source.label.ifBlank { source.kind },
                    detail = if (source.compiled) source.kind else strings[Txt.PERSONA_SOURCE_PENDING],
                    leading = source.kind,
                    actionLabel = strings[Txt.ACTION_CLOSE],
                    actionTone = Tone.Bad,
                    onAction = if (editor.busy) null else ({ vm.deleteSource(source.id); Unit }),
                )
            }
        }
        item {
            Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.sm)) {
                BotField(
                    value = editor.noteDraft,
                    onValueChange = vm::setNoteDraft,
                    label = strings[Txt.PERSONA_NOTE_PLACEHOLDER],
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                )
                Spacer(Modifier.height(BotSpace.sm))
                PrimaryButton(
                    text = strings[Txt.PERSONA_ADD_NOTE],
                    onClick = vm::addNote,
                    enabled = editor.noteDraft.isNotBlank(),
                    busy = editor.busy,
                )
            }
            InfoPanel(strings[Txt.PERSONA_FILE_WEB_ONLY], tone = Tone.Info)
        }

        item { SectionLabel(strings[Txt.PERSONA_TEST]) }
        item {
            Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.sm)) {
                BotField(
                    value = editor.testDraft,
                    onValueChange = vm::setTestDraft,
                    label = strings[Txt.PERSONA_TEST_PLACEHOLDER],
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                )
                Spacer(Modifier.height(BotSpace.sm))
                PrimaryButton(
                    text = strings[Txt.PERSONA_TEST],
                    onClick = vm::test,
                    enabled = editor.testDraft.isNotBlank(),
                    busy = editor.testing,
                )
                editor.testReply?.let {
                    Spacer(Modifier.height(BotSpace.md))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = BotColors.ink)
                }
            }
        }

        item { SectionLabel(strings[Txt.PERSONA_INSTRUCTIONS]) }
        item {
            Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.sm)) {
                BotField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = strings[Txt.PERSONA_INSTRUCTIONS],
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    singleLine = false,
                )
                Spacer(Modifier.height(BotSpace.sm))
                PrimaryButton(
                    text = strings[Txt.ACTION_SAVE],
                    onClick = { vm.save(instructions) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = instructions != persona.compiledInstructions,
                    busy = editor.busy,
                )
            }
        }
    }
}
