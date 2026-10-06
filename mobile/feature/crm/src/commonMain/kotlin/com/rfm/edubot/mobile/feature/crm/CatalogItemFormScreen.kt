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
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.Chip
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SecondaryButton

/**
 * Adding a catalog item.
 *
 * The previous form asked for an "Item ID", which the backend would reject as `id_taken` against
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
) {
    val vm = viewModel<CatalogItemFormViewModel>(
        key = "catalog-form",
        factory = viewModelFactory { initializer { CatalogItemFormViewModel(repository) } },
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
        ScreenHeader(strings[Txt.CRM_CATALOG_TITLE], strings[Txt.ACTION_NEW])
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
                value = state.category,
                onValueChange = vm::setCategory,
                label = strings[Txt.CRM_CATALOG_CATEGORY],
                modifier = Modifier.fillMaxWidth(),
            )
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
}

private fun CatalogItemType.labelKey(): String = when (this) {
    CatalogItemType.Service -> Txt.CRM_CATALOG_TYPE_SERVICE
    CatalogItemType.Product -> Txt.CRM_CATALOG_TYPE_PRODUCT
}
