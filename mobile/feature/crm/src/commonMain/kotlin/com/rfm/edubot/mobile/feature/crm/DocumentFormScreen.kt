package com.rfm.edubot.mobile.feature.crm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import com.rfm.edubot.mobile.core.common.formatEuros
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.Chip
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.Panel
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.SectionLabel
import com.rfm.edubot.mobile.core.ui.Tone

/**
 * Creating a quote or an invoice from the phone, with a client picked from the directory and as
 * many lines as the job has. The previous version took a hand-typed client id and a single line.
 */
@Composable
fun DocumentFormScreen(
    repository: CrmRepository,
    kind: DocumentKind,
    strings: Strings,
    padding: PaddingValues,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val vm = viewModel<DocumentFormViewModel>(
        key = "document-form:$kind",
        factory = viewModelFactory { initializer { DocumentFormViewModel(repository, kind) } },
    )
    val state by vm.state.collectAsState()
    val clientsState by vm.clients.collectAsState()
    val catalogState by vm.catalog.collectAsState()
    LaunchedEffect(kind) { vm.load() }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    val suggestions = vm.matchingClients(clientsState.value.orEmpty(), state.clientQuery)
    val catalog = catalogState.value.orEmpty()

    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
        ScreenHeader(
            eyebrow = strings[Txt.NAV_GROUP_BUSINESS],
            title = strings[if (kind == DocumentKind.Quote) Txt.CRM_QUOTES_TITLE else Txt.CRM_INVOICES_TITLE],
        )
        state.error?.let { ErrorPanel(strings.error(it)) }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = BotSpace.lg)) {
            item { SectionLabel(strings[Txt.CRM_PICK_CLIENT]) }
            item {
                Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
                    BotField(
                        value = state.clientQuery,
                        onValueChange = vm::setClientQuery,
                        label = strings[Txt.CRM_CLIENT_NAME],
                        modifier = Modifier.fillMaxWidth(),
                        error = state.clientId == null && state.clientQuery.isNotBlank(),
                    )
                    if (state.clientId == null && suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(BotSpace.sm))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
                            items(suggestions, key = { it.id }) { client ->
                                Chip(
                                    label = client.name,
                                    selected = false,
                                    onClick = { vm.setClient(client) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionLabel(
                    strings[if (kind == DocumentKind.Quote) Txt.CRM_QUOTE_VALID_UNTIL else Txt.CRM_INVOICE_DUE_DATE],
                )
            }
            item {
                Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
                    BotField(
                        value = state.date,
                        onValueChange = vm::setDate,
                        label = strings[Txt.CRM_DATE_FORMAT_HINT],
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item { SectionLabel(strings[Txt.CRM_LINE_DESCRIPTION]) }
            items(state.lines.size) { index ->
                LineEditor(
                    line = state.lines[index],
                    strings = strings,
                    catalogLabels = catalog.map { it.label },
                    canRemove = state.lines.size > 1,
                    onEdit = { transform -> vm.editLine(index, transform) },
                    onPickCatalog = { label ->
                        catalog.firstOrNull { it.label == label }?.let { vm.applyCatalogItem(index, it) }
                    },
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
                    Text(
                        "${strings[Txt.LABEL_TOTAL]} € ${formatEuros(state.totalEur)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = BotColors.ink,
                    )
                }
            }

            if (kind == DocumentKind.Quote) {
                item {
                    Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
                        BotField(
                            value = state.notes,
                            onValueChange = vm::setNotes,
                            label = "${strings[Txt.CRM_LINE_DESCRIPTION]} (${strings[Txt.LABEL_OPTIONAL]})",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(BotSpace.xl),
            horizontalArrangement = Arrangement.spacedBy(BotSpace.md),
        ) {
            SecondaryButton(strings[Txt.ACTION_CANCEL], onCancel, Modifier.weight(1f))
            PrimaryButton(
                text = strings[Txt.ACTION_SAVE],
                onClick = vm::save,
                modifier = Modifier.weight(1f),
                enabled = state.canSave,
                busy = state.saving,
            )
        }
    }
}

@Composable
private fun LineEditor(
    line: LineDraft,
    strings: Strings,
    catalogLabels: List<String>,
    canRemove: Boolean,
    onEdit: (LineDraft.() -> LineDraft) -> Unit,
    onPickCatalog: (String) -> Unit,
    onRemove: () -> Unit,
) = Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
    BotField(
        value = line.description,
        onValueChange = { value -> onEdit { copy(description = value) } },
        label = strings[Txt.CRM_LINE_DESCRIPTION],
        modifier = Modifier.fillMaxWidth(),
    )
    if (line.description.isBlank() && catalogLabels.isNotEmpty()) {
        Spacer(Modifier.height(BotSpace.sm))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
            items(catalogLabels.take(CATALOG_SUGGESTIONS)) { label ->
                Chip(label = label, selected = false, onClick = { onPickCatalog(label) })
            }
        }
    }
    Spacer(Modifier.height(BotSpace.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
        BotField(
            value = line.quantity,
            onValueChange = { value -> onEdit { copy(quantity = value) } },
            label = strings[Txt.CRM_LINE_QUANTITY],
            modifier = Modifier.weight(1f),
            numeric = true,
        )
        BotField(
            value = line.unitPrice,
            onValueChange = { value -> onEdit { copy(unitPrice = value) } },
            label = strings[Txt.CRM_LINE_UNIT_PRICE],
            modifier = Modifier.weight(1f),
            numeric = true,
        )
    }
    Spacer(Modifier.height(BotSpace.sm))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "€ ${formatEuros(line.totalEur)}",
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = BotColors.inkSecondary,
        )
        if (canRemove) {
            SecondaryButton(strings[Txt.ACTION_CLOSE], onRemove, tone = Tone.Bad)
        }
    }
}

private const val CATALOG_SUGGESTIONS = 5
