package com.rfm.edubot.mobile.feature.crm

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.ClientService
import com.rfm.edubot.mobile.core.model.CrmClient
import com.rfm.edubot.mobile.core.model.CustomField
import com.rfm.edubot.mobile.core.model.DashboardModules
import com.rfm.edubot.mobile.core.model.DirectoryFields
import com.rfm.edubot.mobile.core.model.Invoice
import com.rfm.edubot.mobile.core.model.Quote
import com.rfm.edubot.mobile.core.ui.ActionRow
import com.rfm.edubot.mobile.core.ui.ConfirmDialog
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.FieldRow
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.RecordCard
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.SectionLabel
import com.rfm.edubot.mobile.core.ui.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ClientRecord(
    val client: CrmClient,
    val fields: DirectoryFields,
    val quotes: List<Quote> = emptyList(),
    val invoices: List<Invoice> = emptyList(),
    val services: List<ClientService> = emptyList(),
) {
    /** Invoiced and not cancelled. */
    val billedEur: Double get() = invoices.filterNot { it.cancelled }.sumOf { it.totalEur }

    val toReceiveEur: Double get() = invoices.filterNot { it.closed }.sumOf { it.outstandingEur }
}

data class ClientRecordState(
    val record: ClientRecord? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: AppError? = null,
    val archived: Boolean = false,
)

/**
 * A client's record, put together like the web's from the per-client lists, each only when the
 * company has that module: asking for a module it lacks answers 403, which is not an error here.
 */
class ClientRecordViewModel(
    private val repository: CrmRepository,
    private val clientId: String,
    private val modules: Set<String>,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(ClientRecordState())
    val state: StateFlow<ClientRecordState> = mutable.asStateFlow()

    fun load() = scope.launch {
        mutable.update { it.copy(loading = true, error = null) }
        val quotes = async { if (DashboardModules.QUOTES in modules) repository.quotesFor(clientId).valueOrNull.orEmpty() else emptyList() }
        val invoices = async { if (DashboardModules.INVOICES in modules) repository.invoicesFor(clientId).valueOrNull.orEmpty() else emptyList() }
        val services = async { if (DashboardModules.SERVICES in modules) repository.servicesFor(clientId = clientId).valueOrNull.orEmpty() else emptyList() }
        repository.clientFields.load()
        when (val client = repository.client(clientId)) {
            is Outcome.Success -> mutable.update {
                it.copy(
                    loading = false,
                    record = ClientRecord(
                        client = client.value,
                        fields = repository.clientFields.state.value.value ?: DirectoryFields.DEFAULT,
                        quotes = quotes.await(),
                        invoices = invoices.await(),
                        services = services.await().sortedByDescending { service -> service.day },
                    ),
                )
            }
            is Outcome.Failure -> mutable.update { it.copy(loading = false, error = client.error) }
        }
    }

    /** Called as the screen steps back after [archive]; a flag left set would send the next visit back. */
    fun consumeArchived() = mutable.update { it.copy(archived = false) }

    fun archive() = scope.launch {
        val client = mutable.value.record?.client ?: return@launch
        mutable.update { it.copy(busy = true, error = null) }
        when (val done = repository.archiveClient(client, archived = !client.archived)) {
            is Outcome.Success -> mutable.update { it.copy(busy = false, archived = true) }
            is Outcome.Failure -> mutable.update { it.copy(busy = false, error = done.error) }
        }
    }
}

@Composable
fun ClientRecordScreen(
    repository: CrmRepository,
    clientId: String,
    modules: Set<String>,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpen: (CrmRoute) -> Unit,
    onBack: () -> Unit,
) {
    val vm = viewModel<ClientRecordViewModel>(
        key = "client-record:$clientId",
        factory = viewModelFactory { initializer { ClientRecordViewModel(repository, clientId, modules) } },
    )
    val state by vm.state.collectAsState()
    // Coming back from the edit form or a document shows what changed there.
    LaunchedEffect(clientId) { vm.load() }
    LaunchedEffect(state.archived) { if (state.archived) { vm.consumeArchived(); onBack() } }
    var confirmArchive by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { RecordHeader(strings[Txt.CRM_CLIENTS_TITLE], state.record?.client?.name ?: strings[Txt.CRM_CLIENTS_TITLE], strings, onBack) }
        state.error?.let { error -> item { ErrorPanel(strings.error(error), retryLabel = strings[Txt.ACTION_RETRY], onRetry = { vm.load() }) } }
        val record = state.record
        if (record == null) {
            item { if (state.loading) LoadingScreen() else EmptyState(strings[Txt.EMPTY_TITLE]) }
            return@LazyColumn
        }
        val client = record.client
        item {
            RecordCard(
                title = client.name,
                subtitle = client.number,
                status = if (client.archived) "ARCHIVED" else null,
                statusLabel = strings[Txt.CRM_CLIENT_ARCHIVED],
            ) {
                FieldRow(strings[Txt.CRM_CLIENT_PHONE], client.phone)
                FieldRow(strings[Txt.CRM_CLIENT_EMAIL], client.email)
                FieldRow(strings[Txt.CRM_CLIENT_TAX_ID], client.taxId)
                FieldRow(strings[Txt.CRM_CLIENT_CONTACT_PERSON], client.contactPerson)
                FieldRow(
                    strings[Txt.CRM_CLIENT_ADDRESS],
                    listOfNotNull(
                        client.address?.takeIf { it.isNotBlank() },
                        listOfNotNull(client.postalCode, client.city).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() },
                    ).joinToString(", "),
                )
                record.fields.custom.forEach { field ->
                    FieldRow(field.label, client.customFields[field.key]?.asText()?.let { readable(field, it, strings, clock) })
                }
                FieldRow(strings[Txt.CRM_NOTES], client.notes)
            }
        }
        item {
            ActionRow {
                PrimaryButton(strings[Txt.ACTION_EDIT], { onOpen(CrmRoute.EditClient(client.id)) })
                if (DashboardModules.SERVICES in modules) {
                    SecondaryButton(strings[Txt.CRM_SERVICE_NEW], { onOpen(CrmRoute.NewService(client.id)) })
                }
                SecondaryButton(
                    strings[if (client.archived) Txt.ACTION_RESTORE else Txt.ACTION_ARCHIVE],
                    { confirmArchive = true },
                    enabled = !state.busy,
                    tone = if (client.archived) Tone.Neutral else Tone.Bad,
                )
            }
        }
        if (DashboardModules.INVOICES in modules) {
            item {
                val overdue = record.invoices.filter { !it.closed && clock.isOverdue(it.dueDate) }.sumOf { it.outstandingEur }
                MoneyStrip(
                    Triple(strings[Txt.CRM_MONEY_BILLED], money(record.billedEur), Tone.Neutral),
                    Triple(strings[Txt.CRM_MONEY_TO_RECEIVE], money(record.toReceiveEur), Tone.Neutral),
                    Triple(strings[Txt.CRM_MONEY_OVERDUE], money(overdue), if (overdue > 0) Tone.Bad else Tone.Neutral),
                )
            }
        }
        if (record.quotes.isEmpty() && record.invoices.isEmpty() && record.services.isEmpty()) {
            item { InfoPanel(strings[Txt.CRM_CLIENT_NOTHING_YET]) }
        }
        if (record.invoices.isNotEmpty()) {
            item { SectionLabel(strings[Txt.CRM_INVOICES_TITLE]) }
            items(record.invoices, key = { "inv-${it.id}" }) { invoice -> InvoiceRow(invoice, strings, clock) { onOpen(CrmRoute.Invoice(invoice.id)) } }
        }
        if (record.quotes.isNotEmpty()) {
            item { SectionLabel(strings[Txt.CRM_QUOTES_TITLE]) }
            items(record.quotes, key = { "quote-${it.id}" }) { quote -> QuoteRow(quote, strings, clock) { onOpen(CrmRoute.Quote(quote.id)) } }
        }
        if (record.services.isNotEmpty()) {
            item { SectionLabel(strings.module(DashboardModules.SERVICES)) }
            items(record.services, key = { "svc-${it.id}" }) { service -> ServiceRow(service, strings, clock) { onOpen(CrmRoute.Service(service.id)) } }
        }
    }

    if (confirmArchive) {
        val client = state.record?.client
        ConfirmDialog(
            title = strings[if (client?.archived == true) Txt.ACTION_RESTORE else Txt.ACTION_ARCHIVE],
            message = if (client?.archived == true) strings[Txt.CONFIRM_TITLE] else strings.format(Txt.CRM_CLIENT_ARCHIVE_CONFIRM, "name" to client?.name),
            confirmLabel = strings[if (client?.archived == true) Txt.ACTION_RESTORE else Txt.ACTION_ARCHIVE],
            cancelLabel = strings[Txt.ACTION_CANCEL],
            destructive = client?.archived != true,
            onConfirm = { confirmArchive = false; vm.archive() },
            onDismiss = { confirmArchive = false },
        )
    }
}

/** A custom value as a person reads it: a date in the company's format, yes or no. */
private fun readable(field: CustomField, text: String, strings: Strings, clock: TenantClock): String? = when (field.type) {
    CustomField.CHECKBOX -> if (text == "true") strings[Txt.LABEL_YES] else null
    CustomField.DATE -> clock.day(text)
    else -> text
}

@Composable
internal fun QuoteRow(quote: Quote, strings: Strings, clock: TenantClock, onClick: () -> Unit) = ListRow(
    title = quote.clientName.ifBlank { quote.number },
    detail = listOfNotNull(
        quote.number.takeIf { it.isNotBlank() },
        quote.validUntil?.let { "${strings[Txt.CRM_QUOTE_VALID_UNTIL]} ${clock.day(it)}" },
    ).joinToString(" · "),
    leading = quote.clientName.ifBlank { quote.number },
    trailingLabel = money(quote.totalEur),
    status = quote.status,
    statusLabel = strings.status(quote.status),
    onClick = onClick,
)

@Composable
internal fun InvoiceRow(invoice: Invoice, strings: Strings, clock: TenantClock, onClick: () -> Unit) {
    val overdue = !invoice.closed && clock.isOverdue(invoice.dueDate)
    ListRow(
        title = invoice.clientName.ifBlank { invoice.number },
        detail = listOfNotNull(
            invoice.number.takeIf { it.isNotBlank() },
            invoice.dueDate.takeIf { it.isNotBlank() }?.let(clock::day),
        ).joinToString(" · "),
        leading = invoice.clientName.ifBlank { invoice.number },
        trailingLabel = money(invoice.totalEur),
        status = if (overdue) OVERDUE else invoice.status,
        statusLabel = strings.status(if (overdue) OVERDUE else invoice.status),
        emphasised = overdue,
        onClick = onClick,
    )
}

@Composable
internal fun ServiceRow(service: ClientService, strings: Strings, clock: TenantClock, onClick: () -> Unit) = ListRow(
    title = service.name,
    detail = listOfNotNull(
        service.clientName.takeIf { it.isNotBlank() },
        service.employeeName.takeIf { it.isNotBlank() },
        service.day.takeIf { it.isNotBlank() }?.let(clock::day),
    ).joinToString(" · "),
    leading = service.name,
    trailingLabel = money(service.totalEur),
    status = service.status.takeIf { it.isNotBlank() },
    statusLabel = service.status.takeIf { it.isNotBlank() }?.let(strings::status),
    onClick = onClick,
)

/** Not a stored status: the backend keeps an unpaid invoice PENDING and the date makes it late. */
internal const val OVERDUE = "OVERDUE"
