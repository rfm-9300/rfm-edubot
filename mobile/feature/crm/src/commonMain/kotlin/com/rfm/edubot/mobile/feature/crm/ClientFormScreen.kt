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
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SecondaryButton

/**
 * Create or edit a client.
 *
 * The app could not create a client at all: it sent only a name, a phone and an address, and the
 * backend has required a NIF since `requiredError()` landed, so every attempt came back
 * `tax_id_required`. This collects both required fields and shows the backend's own error on the
 * field it belongs to.
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
        key = "client-form:${clientId ?: "new"}",
        factory = viewModelFactory { initializer { ClientFormViewModel(repository, clientId) } },
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(clientId) { vm.load() }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
        ScreenHeader(
            eyebrow = strings[Txt.CRM_CLIENTS_TITLE],
            title = strings[if (clientId == null) Txt.CRM_CLIENT_CREATE else Txt.ACTION_SAVE],
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
            BotField(
                value = state.name,
                onValueChange = vm::setName,
                label = strings[Txt.CRM_CLIENT_NAME],
                modifier = Modifier.fillMaxWidth(),
                error = state.invalidFields.contains(ClientField.Name),
            )
            BotField(
                value = state.phone,
                onValueChange = vm::setPhone,
                label = strings[Txt.CRM_CLIENT_PHONE],
                modifier = Modifier.fillMaxWidth(),
                error = state.invalidFields.contains(ClientField.Phone),
            )
            BotField(
                value = state.taxId,
                onValueChange = vm::setTaxId,
                label = strings[Txt.CRM_CLIENT_TAX_ID],
                modifier = Modifier.fillMaxWidth(),
                supporting = strings[Txt.CRM_CLIENT_TAX_ID_HINT],
                error = state.invalidFields.contains(ClientField.TaxId),
            )
            BotField(
                value = state.address,
                onValueChange = vm::setAddress,
                label = strings[Txt.CRM_CLIENT_ADDRESS],
                modifier = Modifier.fillMaxWidth(),
                error = state.invalidFields.contains(ClientField.Address),
            )
            BotField(
                value = state.postalCode,
                onValueChange = vm::setPostalCode,
                label = "${strings[Txt.CRM_CLIENT_POSTAL_CODE]} (${strings[Txt.LABEL_OPTIONAL]})",
                modifier = Modifier.fillMaxWidth(),
            )
            BotField(
                value = state.city,
                onValueChange = vm::setCity,
                label = "${strings[Txt.CRM_CLIENT_CITY]} (${strings[Txt.LABEL_OPTIONAL]})",
                modifier = Modifier.fillMaxWidth(),
            )
            BotField(
                value = state.email,
                onValueChange = vm::setEmail,
                label = "${strings[Txt.CRM_CLIENT_EMAIL]} (${strings[Txt.LABEL_OPTIONAL]})",
                modifier = Modifier.fillMaxWidth(),
                error = state.invalidFields.contains(ClientField.Email),
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
