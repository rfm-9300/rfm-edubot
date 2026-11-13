package com.rfm.edubot.mobile.feature.crm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.ChoiceDialog
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.Panel
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.SectionLabel

/** The service form's body; the team's and the employee's screens give it its view model. */
@Composable
internal fun ServiceForm(
    vm: ServiceFormViewModel,
    eyebrow: String,
    title: String,
    strings: Strings,
    padding: PaddingValues,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    headerAction: (@Composable () -> Unit)? = null,
    extraError: String? = null,
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(vm) { vm.load() }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    var pickEmployee by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
        ScreenHeader(eyebrow, title, action = headerAction)
        extraError?.let { ErrorPanel(it) }
        if (state.loading && state.options.clients.isEmpty()) {
            LoadingScreen()
            return@Column
        }
        state.error?.let { ErrorPanel(strings.error(it)) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = BotSpace.xxl)) {
            item { SectionLabel(strings[Txt.CRM_PICK_CLIENT]) }
            item {
                ClientPicker(
                    query = state.clientQuery,
                    selectedId = state.clientId,
                    options = state.options.clients,
                    strings = strings,
                    onQuery = vm::setClientQuery,
                    onPick = vm::pickClient,
                )
            }
            item { SectionLabel(strings[Txt.CRM_SERVICE_DAY]) }
            item {
                Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
                    BotField(
                        value = state.day,
                        onValueChange = vm::setDay,
                        label = strings[Txt.CRM_DATE_FORMAT_HINT],
                        modifier = Modifier.fillMaxWidth(),
                        error = !state.dayValid,
                    )
                }
            }
            state.options.employees?.let { employees ->
                item { SectionLabel(strings[Txt.CRM_SERVICE_DONE_BY]) }
                item {
                    Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs), onClick = { pickEmployee = true }) {
                        Text(
                            employees.firstOrNull { it.first == state.employeeId }?.second ?: strings[Txt.CRM_SERVICE_NOBODY],
                            style = MaterialTheme.typography.bodyLarge,
                            color = BotColors.ink,
                        )
                    }
                }
            }
            item { SectionLabel(strings[Txt.CRM_LINES]) }
            items(state.lines.size) { index ->
                LineEditor(
                    line = state.lines[index],
                    strings = strings,
                    catalogLabels = state.options.catalog.map { it.label },
                    canRemove = state.lines.size > 1,
                    onEdit = { transform -> vm.editLine(index, transform) },
                    onPickCatalog = { label -> state.options.catalog.firstOrNull { it.label == label }?.let { vm.applyCatalogItem(index, it) } },
                    onRemove = { vm.removeLine(index) },
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = BotSpace.xl, vertical = BotSpace.sm),
                    horizontalArrangement = Arrangement.spacedBy(BotSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SecondaryButton(strings[Txt.CRM_LINE_ADD], vm::addLine)
                    Spacer(Modifier.weight(1f))
                    Text("${strings[Txt.LABEL_TOTAL]} ${money(state.totalEur)}", style = MaterialTheme.typography.titleMedium, color = BotColors.ink)
                }
            }
            item {
                Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
                    BotField(
                        value = state.name,
                        onValueChange = vm::setName,
                        label = strings[Txt.CRM_SERVICE_NAME],
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.padding(top = BotSpace.sm))
                    BotField(
                        value = state.notes,
                        onValueChange = vm::setNotes,
                        label = "${strings[Txt.CRM_NOTES]} (${strings[Txt.LABEL_OPTIONAL]})",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(BotSpace.xl), horizontalArrangement = Arrangement.spacedBy(BotSpace.md)) {
            SecondaryButton(strings[Txt.ACTION_CANCEL], onCancel, Modifier.weight(1f))
            PrimaryButton(strings[Txt.ACTION_SAVE], vm::save, Modifier.weight(1f), enabled = state.canSave, busy = state.saving)
        }
    }

    val employees = state.options.employees
    if (pickEmployee && employees != null) {
        ChoiceDialog(
            title = strings[Txt.CRM_SERVICE_DONE_BY],
            options = listOf("" to strings[Txt.CRM_SERVICE_NOBODY]) + employees,
            selected = state.employeeId,
            cancelLabel = strings[Txt.ACTION_CANCEL],
            onPick = { pickEmployee = false; vm.setEmployee(it) },
            onDismiss = { pickEmployee = false },
        )
    }
}
