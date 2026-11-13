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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.CustomField
import com.rfm.edubot.mobile.core.model.DirectoryFields
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.BotSwitch
import com.rfm.edubot.mobile.core.ui.ChipRow
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.rememberFormKey

/**
 * Create or edit a client with the fields the company asks for: the standard ones in its order,
 * each marked optional unless the company requires it, then its own fields by type.
 */
@Composable
fun ClientFormScreen(
    repository: CrmRepository,
    strings: Strings,
    padding: PaddingValues,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    clientId: String? = null,
) {
    val vm = viewModel<ClientFormViewModel>(
        key = rememberFormKey("client-form:${clientId ?: "new"}"),
        factory = viewModelFactory { initializer { ClientFormViewModel(repository, clientId) } },
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(clientId) { vm.load() }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
        ScreenHeader(
            eyebrow = strings[Txt.CRM_CLIENTS_TITLE],
            title = strings[if (clientId == null) Txt.CRM_CLIENT_CREATE else Txt.CRM_CLIENT_EDIT],
        )
        if (state.loading) {
            LoadingScreen()
            return@Column
        }
        state.error?.let { ErrorPanel(strings.error(it)) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(BotSpace.xl),
            verticalArrangement = Arrangement.spacedBy(BotSpace.md),
        ) {
            listOf(DirectoryFields.NAME, DirectoryFields.PHONE).forEach { key ->
                StandardInput(key, state, strings, required = true, onChange = { vm.set(key, it) })
            }
            state.detailFields.forEach { key ->
                StandardInput(key, state, strings, required = state.fields.isRequired(key), onChange = { vm.set(key, it) })
            }
            state.fields.custom.forEach { field ->
                CustomInput(field, state, strings, onChange = { vm.setCustom(field.key, it) })
            }
            BotField(
                value = state.value(NOTES_KEY),
                onValueChange = { vm.set(NOTES_KEY, it) },
                label = strings[Txt.CRM_CLIENT_NOTES],
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                error = NOTES_KEY in state.invalid,
            )
            Spacer(Modifier.height(BotSpace.sm))
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
private fun StandardInput(
    key: String,
    state: ClientFormState,
    strings: Strings,
    required: Boolean,
    onChange: (String) -> Unit,
) = BotField(
    value = state.value(key),
    onValueChange = onChange,
    label = fieldLabel(strings[standardLabel(key)], required, strings),
    modifier = Modifier.fillMaxWidth(),
    supporting = if (key == DirectoryFields.TAX_ID) strings[Txt.CRM_CLIENT_TAX_ID_HINT] else null,
    error = key in state.invalid,
)

@Composable
private fun CustomInput(field: CustomField, state: ClientFormState, strings: Strings, onChange: (String) -> Unit) {
    val value = state.customValue(field.key)
    val invalid = field.key in state.invalid
    when (field.type) {
        CustomField.CHECKBOX -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = BotColors.ink)
            BotSwitch(checked = value == "true", onCheckedChange = { onChange(if (it) "true" else "") })
        }
        CustomField.SELECT -> Column(Modifier.fillMaxWidth()) {
            Text(
                fieldLabel(field.label, field.required, strings),
                style = MaterialTheme.typography.labelMedium,
                color = if (invalid) BotColors.badInk else BotColors.inkMuted,
            )
            // Unpicking is choosing nothing, which a field that isn't required allows.
            val none = if (field.required) emptyList() else listOf("" to strings[Txt.LABEL_NONE])
            ChipRow(
                options = none + field.options.map { it to it },
                selected = value,
                onSelect = onChange,
                horizontalPadding = 0.dp,
            )
        }
        else -> BotField(
            value = value,
            onValueChange = onChange,
            label = fieldLabel(field.label, field.required, strings),
            modifier = Modifier.fillMaxWidth(),
            numeric = field.type == CustomField.NUMBER,
            supporting = if (field.type == CustomField.DATE) strings[Txt.CRM_DATE_FORMAT_HINT] else null,
            error = invalid,
        )
    }
}

internal fun fieldLabel(label: String, required: Boolean, strings: Strings): String =
    if (required) label else "$label (${strings[Txt.LABEL_OPTIONAL]})"

private fun standardLabel(key: String): String = when (key) {
    DirectoryFields.NAME -> Txt.CRM_CLIENT_NAME
    DirectoryFields.PHONE -> Txt.CRM_CLIENT_PHONE
    DirectoryFields.TAX_ID -> Txt.CRM_CLIENT_TAX_ID
    DirectoryFields.EMAIL -> Txt.CRM_CLIENT_EMAIL
    DirectoryFields.CONTACT_PERSON -> Txt.CRM_CLIENT_CONTACT_PERSON
    DirectoryFields.ADDRESS -> Txt.CRM_CLIENT_ADDRESS
    DirectoryFields.POSTAL_CODE -> Txt.CRM_CLIENT_POSTAL_CODE
    DirectoryFields.CITY -> Txt.CRM_CLIENT_CITY
    else -> key
}
