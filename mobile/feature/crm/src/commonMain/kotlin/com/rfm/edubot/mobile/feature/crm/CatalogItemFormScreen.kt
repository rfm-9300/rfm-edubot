package com.rfm.edubot.mobile.feature.crm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.ChipRow
import com.rfm.edubot.mobile.core.ui.ConfirmDialog
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.Chip
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.rememberFormKey

/**
 * Adding a catalog item, or editing one ([itemId]).
 *
 * The first version asked for an "Item ID", which the backend would reject as `id_taken` against
 * any existing code; it also predated titles, so every item's name ended up in its description.
 * This sends a title and lets the backend number the code.
 */
@Composable
fun CatalogItemFormScreen(
    repository: CrmRepository,
    strings: Strings,
    padding: PaddingValues,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    itemId: String? = null,
) {
    val vm = viewModel<CatalogItemFormViewModel>(
        key = rememberFormKey("catalog-form:${itemId ?: "new"}"),
        factory = viewModelFactory { initializer { CatalogItemFormViewModel(repository, itemId) } },
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(itemId) { vm.load() }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
        ScreenHeader(strings[Txt.CRM_CATALOG_TITLE], strings[if (itemId == null) Txt.ACTION_NEW else Txt.CRM_CATALOG_EDIT]) {
            if (state.existing != null) {
                TextButton(onClick = { confirmDelete = true }, enabled = !state.saving) {
                    Text(strings[Txt.ACTION_DELETE], color = BotColors.badInk)
                }
            }
        }
        state.error?.let { ErrorPanel(strings.error(it)) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(BotSpace.xl),
            verticalArrangement = Arrangement.spacedBy(BotSpace.md),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
                CatalogItemType.entries.forEach { type ->
                    Chip(
                        label = strings[type.labelKey()],
                        selected = state.type == type,
                        onClick = { vm.setType(type) },
                    )
                }
            }
            BotField(
                value = state.title,
                onValueChange = vm::setTitle,
                label = strings[Txt.CRM_CATALOG_ITEM_TITLE],
                modifier = Modifier.fillMaxWidth(),
            )
            BotField(
                value = state.description,
                onValueChange = vm::setDescription,
                label = "${strings[Txt.CRM_CATALOG_DESCRIPTION]} (${strings[Txt.LABEL_OPTIONAL]})",
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )
            BotField(
                value = state.category,
                onValueChange = vm::setCategory,
                label = "${strings[Txt.CRM_CATALOG_CATEGORY]} (${strings[Txt.LABEL_OPTIONAL]})",
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.categories.isNotEmpty()) {
                ChipRow(
                    options = state.categories.map { it to it },
                    selected = state.category.trim(),
                    onSelect = vm::setCategory,
                    horizontalPadding = 0.dp,
                )
            }
            BotField(
                value = state.unit,
                onValueChange = vm::setUnit,
                label = strings[Txt.CRM_CATALOG_UNIT],
                modifier = Modifier.fillMaxWidth(),
            )
            BotField(
                value = state.price,
                onValueChange = vm::setPrice,
                label = strings[Txt.CRM_CATALOG_PRICE],
                modifier = Modifier.fillMaxWidth(),
                numeric = true,
            )
            BotField(
                value = state.code,
                onValueChange = vm::setCode,
                label = "${strings[Txt.CRM_CATALOG_ITEM_CODE]} (${strings[Txt.LABEL_OPTIONAL]})",
                modifier = Modifier.fillMaxWidth(),
                error = state.codeRejected,
            )
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
    if (confirmDelete) {
        ConfirmDialog(
            title = strings[Txt.ACTION_DELETE],
            message = strings.format(Txt.CRM_CATALOG_DELETE_CONFIRM, "name" to state.title),
            confirmLabel = strings[Txt.ACTION_DELETE],
            cancelLabel = strings[Txt.ACTION_CANCEL],
            destructive = true,
            onConfirm = { confirmDelete = false; vm.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun CatalogItemType.labelKey(): String = when (this) {
    CatalogItemType.Service -> Txt.CRM_CATALOG_TYPE_SERVICE
    CatalogItemType.Product -> Txt.CRM_CATALOG_TYPE_PRODUCT
}
