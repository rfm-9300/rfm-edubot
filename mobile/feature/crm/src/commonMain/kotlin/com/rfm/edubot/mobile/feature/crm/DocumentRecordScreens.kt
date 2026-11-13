package com.rfm.edubot.mobile.feature.crm

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.common.parseDay
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.ClientService
import com.rfm.edubot.mobile.core.model.DashboardModules
import com.rfm.edubot.mobile.core.model.Invoice
import com.rfm.edubot.mobile.core.model.Quote
import com.rfm.edubot.mobile.core.model.UpdateService
import com.rfm.edubot.mobile.core.ui.ActionRow
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.ChoiceDialog
import com.rfm.edubot.mobile.core.ui.ConfirmDialog
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.FieldRow
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.Panel
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.PromptDialog
import com.rfm.edubot.mobile.core.ui.RecordCard
import com.rfm.edubot.mobile.core.ui.RecordState
import com.rfm.edubot.mobile.core.ui.RecordViewModel
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.SectionLabel
import com.rfm.edubot.mobile.core.ui.StatusBadge
import com.rfm.edubot.mobile.core.ui.Tone

/** The frame of a record screen: header, failure, loading, then the record's own items. */
@Composable
internal fun <T : Any> RecordFrame(
    state: RecordState<T>,
    eyebrow: String,
    title: (T?) -> String,
    strings: Strings,
    padding: PaddingValues,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    content: LazyListScope.(T) -> Unit,
) = LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
    item { RecordHeader(eyebrow, title(state.value), strings, onBack) }
    state.error?.let { error -> item { ErrorPanel(strings.error(error), retryLabel = strings[Txt.ACTION_RETRY], onRetry = onRetry) } }
    val value = state.value
    if (value == null) {
        item { if (state.loading) LoadingScreen() else EmptyState(strings[Txt.EMPTY_TITLE]) }
    } else {
        content(value)
    }
}

/** The dialog a record screen may have open. */
internal sealed interface PendingAction {
    data class Confirm(val message: String, val label: String, val destructive: Boolean, val run: () -> Unit) : PendingAction
    data class AskDate(val title: String, val confirmLabel: String, val run: (String) -> Unit) : PendingAction
    data class AskText(val title: String, val label: String, val confirmLabel: String, val run: (String) -> Unit) : PendingAction
}

@Composable
internal fun PendingActionDialog(action: PendingAction?, strings: Strings, clock: TenantClock, onDone: () -> Unit) {
    when (action) {
        null -> Unit
        is PendingAction.Confirm -> ConfirmDialog(
            title = strings[Txt.CONFIRM_TITLE],
            message = action.message,
            confirmLabel = action.label,
            cancelLabel = strings[Txt.ACTION_CANCEL],
            destructive = action.destructive,
            onConfirm = { onDone(); action.run() },
            onDismiss = onDone,
        )
        is PendingAction.AskDate -> PromptDialog(
            title = action.title,
            label = strings[Txt.CRM_INVOICE_DUE_DATE],
            supporting = strings[Txt.CRM_DATE_FORMAT_HINT],
            confirmLabel = action.confirmLabel,
            cancelLabel = strings[Txt.ACTION_CANCEL],
            initial = clock.todayText(),
            canConfirm = { parseDay(it) != null },
            onConfirm = { onDone(); action.run(it.trim()) },
            onDismiss = onDone,
        )
        is PendingAction.AskText -> PromptDialog(
            title = action.title,
            label = action.label,
            confirmLabel = action.confirmLabel,
            cancelLabel = strings[Txt.ACTION_CANCEL],
            singleLine = false,
            onConfirm = { onDone(); action.run(it.trim()) },
            onDismiss = onDone,
        )
    }
}

@Composable
fun QuoteRecordScreen(
    repository: CrmRepository,
    quoteId: String,
    modules: Set<String>,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpen: (CrmRoute) -> Unit,
    onBack: () -> Unit,
) {
    val vm = viewModel<RecordViewModel<Quote>>(
        key = "quote:$quoteId",
        factory = viewModelFactory { initializer { RecordViewModel({ repository.quote(quoteId) }) } },
    )
    val state by vm.state.collectAsState()
    val invoices by repository.invoices.state.collectAsState()
    val services by repository.services.state.collectAsState()
    LaunchedEffect(quoteId) {
        vm.load()
        // What billed the quote is read from the lists, so they have to be there.
        if (DashboardModules.INVOICES in modules) repository.invoices.load()
        if (DashboardModules.SERVICES in modules) repository.services.load()
    }
    LaunchedEffect(state.gone) { if (state.gone) { vm.consumeGone(); onBack() } }
    var pending by remember { mutableStateOf<PendingAction?>(null) }

    RecordFrame(state, strings[Txt.CRM_QUOTES_TITLE], { it?.number ?: strings[Txt.CRM_QUOTES_TITLE] }, strings, padding, onBack, { vm.load() }) { quote ->
        // A quote is billed once, by an invoice or a service; the lists already held say which.
        val invoice = invoices.value?.firstOrNull { it.quoteId == quote.id && !it.cancelled }
        val service = services.value?.firstOrNull { it.quoteId == quote.id && !it.cancelled }
        item {
            RecordCard(quote.number, subtitle = quote.clientName, status = quote.status, statusLabel = strings.status(quote.status)) {
                FieldRow(strings[Txt.CRM_QUOTE_VALID_UNTIL], quote.validUntil?.let(clock::day))
                FieldRow(strings[Txt.LABEL_DATE], quote.createdAt.takeIf { it.isNotBlank() }?.let(clock::day))
                FieldRow(strings[Txt.LABEL_TOTAL], money(quote.totalEur))
                FieldRow(strings[Txt.CRM_NOTES], quote.notes)
            }
        }
        if (quote.projectId != null) item { InfoPanel(strings[Txt.CRM_QUOTE_MEASURED], tone = Tone.Info) }
        item {
            ActionRow {
                quote.clientId.takeIf { it.isNotBlank() && DashboardModules.CLIENTS in modules }?.let { id ->
                    SecondaryButton(strings[Txt.CRM_CLIENTS_TITLE], { onOpen(CrmRoute.Client(id)) })
                }
                nextQuoteStatus(quote.status)?.let { next ->
                    PrimaryButton(strings.status(next), { vm.act { repository.setQuoteStatus(it, next) } }, busy = state.busy)
                }
                val billable = quote.accepted && quote.projectId == null && invoice == null && service == null
                if (billable && DashboardModules.INVOICES in modules) {
                    PrimaryButton(strings[Txt.CRM_QUOTE_CONVERT], {
                        pending = PendingAction.AskDate(strings[Txt.CRM_QUOTE_CONVERT], strings[Txt.CRM_QUOTE_CONVERT]) { due ->
                            vm.actThenReload { repository.convertQuote(it, due) }
                        }
                    }, enabled = !state.busy)
                }
                if (billable && DashboardModules.SERVICES in modules) {
                    SecondaryButton(strings[Txt.CRM_QUOTE_TO_SERVICE], {
                        pending = PendingAction.Confirm(strings[Txt.CRM_QUOTE_TO_SERVICE_DETAIL], strings[Txt.CRM_QUOTE_TO_SERVICE], destructive = false) {
                            vm.actThenReload { repository.quoteToService(it) }
                        }
                    }, enabled = !state.busy)
                }
                invoice?.let { SecondaryButton(it.number.ifBlank { strings[Txt.CRM_SERVICE_INVOICE] }, { onOpen(CrmRoute.Invoice(it.id)) }) }
                service?.let { SecondaryButton(it.name, { onOpen(CrmRoute.Service(it.id)) }) }
                if (invoice == null && service == null && quote.projectId == null) {
                    SecondaryButton(strings[Txt.ACTION_DELETE], {
                        pending = PendingAction.Confirm(strings.format(Txt.CRM_QUOTE_DELETE_CONFIRM, "number" to quote.number), strings[Txt.ACTION_DELETE], destructive = true) {
                            vm.remove { repository.deleteQuote(it) }
                        }
                    }, enabled = !state.busy, tone = Tone.Bad)
                }
            }
        }
        item { LinesPanel(quote.items, quote.totalEur, strings) }
    }
    PendingActionDialog(pending, strings, clock) { pending = null }
}

@Composable
fun InvoiceRecordScreen(
    repository: CrmRepository,
    invoiceId: String,
    modules: Set<String>,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpen: (CrmRoute) -> Unit,
    onBack: () -> Unit,
) {
    val vm = viewModel<RecordViewModel<Invoice>>(
        key = "invoice:$invoiceId",
        factory = viewModelFactory { initializer { RecordViewModel({ repository.invoice(invoiceId) }) } },
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(invoiceId) { vm.load() }
    LaunchedEffect(state.gone) { if (state.gone) { vm.consumeGone(); onBack() } }
    var pending by remember { mutableStateOf<PendingAction?>(null) }

    RecordFrame(state, strings[Txt.CRM_INVOICES_TITLE], { it?.number ?: strings[Txt.CRM_INVOICES_TITLE] }, strings, padding, onBack, { vm.load() }) { invoice ->
        val overdue = !invoice.closed && clock.isOverdue(invoice.dueDate)
        item {
            RecordCard(
                invoice.number,
                subtitle = invoice.clientName,
                status = if (overdue) OVERDUE else invoice.status,
                statusLabel = strings.status(if (overdue) OVERDUE else invoice.status),
            ) {
                FieldRow(strings[Txt.CRM_INVOICE_DUE_DATE], invoice.dueDate.takeIf { it.isNotBlank() }?.let(clock::day), tone = if (overdue) Tone.Bad else Tone.Neutral)
                FieldRow(strings[Txt.LABEL_TOTAL], money(invoice.totalEur))
                FieldRow(strings[Txt.CRM_INVOICE_TAX_CODE], invoice.taxOfficeCode)
                FieldRow(strings[Txt.CRM_INVOICE_FROM_QUOTE], invoice.quoteNumber)
                FieldRow(strings.module(DashboardModules.PROJECTS), invoice.projectLabel)
                FieldRow(strings.module(DashboardModules.MEASUREMENTS), invoice.measurementNumber?.let { strings.format(Txt.CRM_INVOICE_MEASUREMENT, "number" to it) })
                if (invoice.retentionRelease) FieldRow(strings[Txt.BUILDING_RETENTION_HELD], strings[Txt.CRM_INVOICE_RETENTION])
            }
        }
        if (!invoice.cancelled) {
            item {
                MoneyStrip(
                    Triple(strings[Txt.CRM_INVOICE_RECEIVED], money(invoice.paidEur), Tone.Ok),
                    Triple(strings[Txt.CRM_MONEY_TO_RECEIVE], money(invoice.outstandingEur), if (overdue) Tone.Bad else Tone.Neutral),
                )
            }
        }
        item {
            ActionRow {
                invoice.clientId.takeIf { it.isNotBlank() && DashboardModules.CLIENTS in modules }?.let { id ->
                    SecondaryButton(strings[Txt.CRM_CLIENTS_TITLE], { onOpen(CrmRoute.Client(id)) })
                }
                invoice.quoteId?.takeIf { DashboardModules.QUOTES in modules }?.let { id ->
                    SecondaryButton(invoice.quoteNumber ?: strings[Txt.CRM_QUOTES_TITLE], { onOpen(CrmRoute.Quote(id)) })
                }
                if (!invoice.closed && invoice.installments.isEmpty()) {
                    PrimaryButton(strings[Txt.ACTION_MARK_PAID], { vm.act { repository.markInvoicePaid(it) } }, busy = state.busy)
                }
                if (!invoice.cancelled) {
                    SecondaryButton(strings[Txt.CRM_INVOICE_CANCEL], {
                        pending = PendingAction.Confirm(strings.format(Txt.CRM_INVOICE_CANCEL_CONFIRM, "number" to invoice.number), strings[Txt.CRM_INVOICE_CANCEL], destructive = true) {
                            vm.act { repository.cancelInvoice(it) }
                        }
                    }, enabled = !state.busy, tone = Tone.Bad)
                }
                SecondaryButton(strings[Txt.ACTION_DELETE], {
                    pending = PendingAction.Confirm(strings.format(Txt.CRM_INVOICE_DELETE_CONFIRM, "number" to invoice.number), strings[Txt.ACTION_DELETE], destructive = true) {
                        vm.remove { repository.deleteInvoice(it) }
                    }
                }, enabled = !state.busy, tone = Tone.Bad)
            }
        }
        if (invoice.installments.isNotEmpty()) {
            item { SectionLabel(strings[Txt.CRM_INVOICE_INSTALLMENTS]) }
            item {
                Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
                    invoice.installments.forEachIndexed { index, part ->
                        Row(Modifier.fillMaxWidth().padding(vertical = BotSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(money(part.amountEur), style = MaterialTheme.typography.bodyMedium, color = BotColors.ink)
                                Text(clock.day(part.dueDate), style = MaterialTheme.typography.bodySmall, color = BotColors.inkMuted)
                            }
                            if (part.paid) {
                                StatusBadge("PAID", strings.status("PAID"))
                            } else if (!invoice.closed) {
                                TextButton(onClick = { vm.act { repository.receiveInstallment(it, index) } }, enabled = !state.busy) {
                                    Text(strings[Txt.CRM_INVOICE_RECEIVE], color = BotColors.accentDeep)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { LinesPanel(invoice.items, invoice.totalEur, strings) }
    }
    PendingActionDialog(pending, strings, clock) { pending = null }
}

@Composable
fun ServiceRecordScreen(
    repository: CrmRepository,
    serviceId: String,
    modules: Set<String>,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpen: (CrmRoute) -> Unit,
    onBack: () -> Unit,
) {
    val vm = viewModel<RecordViewModel<ClientService>>(
        key = "service:$serviceId",
        factory = viewModelFactory { initializer { RecordViewModel({ repository.service(serviceId) }) } },
    )
    val state by vm.state.collectAsState()
    val employees by repository.employees.state.collectAsState()
    val withEmployees = DashboardModules.EMPLOYEES in modules
    LaunchedEffect(serviceId) {
        vm.load()
        if (withEmployees) repository.employees.load()
    }
    LaunchedEffect(state.gone) { if (state.gone) { vm.consumeGone(); onBack() } }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var pickEmployee by remember { mutableStateOf(false) }

    RecordFrame(state, strings.module(DashboardModules.SERVICES), { it?.name ?: strings.module(DashboardModules.SERVICES) }, strings, padding, onBack, { vm.load() }) { service ->
        item {
            RecordCard(service.name, subtitle = service.clientName, status = service.status.takeIf { it.isNotBlank() }, statusLabel = strings.status(service.status)) {
                FieldRow(strings[Txt.CRM_SERVICE_DAY], service.day.takeIf { it.isNotBlank() }?.let(clock::day))
                if (withEmployees) FieldRow(strings[Txt.CRM_SERVICE_DONE_BY], service.employeeName.ifBlank { strings[Txt.CRM_SERVICE_NOBODY] })
                FieldRow(strings[Txt.LABEL_TOTAL], money(service.totalEur))
                FieldRow(strings[Txt.CRM_NOTES], service.notes)
                if (service.quoteId != null) FieldRow(strings[Txt.CRM_QUOTES_TITLE], strings[Txt.CRM_SERVICE_FROM_QUOTE])
            }
        }
        item {
            ActionRow {
                service.clientId.takeIf { it.isNotBlank() && DashboardModules.CLIENTS in modules }?.let { id ->
                    SecondaryButton(strings[Txt.CRM_CLIENTS_TITLE], { onOpen(CrmRoute.Client(id)) })
                }
                service.invoiceId?.takeIf { DashboardModules.INVOICES in modules }?.let { id ->
                    SecondaryButton(strings[Txt.CRM_SERVICE_INVOICE], { onOpen(CrmRoute.Invoice(id)) })
                }
                if (withEmployees && !service.invoiced && !service.cancelled) {
                    SecondaryButton(strings[Txt.CRM_SERVICE_DONE_BY], { pickEmployee = true }, enabled = !state.busy)
                }
                if (!service.invoiced) {
                    if (service.cancelled) {
                        PrimaryButton(strings[Txt.ACTION_REOPEN], { vm.act { repository.updateService(it, UpdateService(status = "OPEN")) } }, busy = state.busy)
                    } else {
                        SecondaryButton(strings[Txt.CRM_SERVICE_CANCEL], {
                            vm.act { repository.updateService(it, UpdateService(status = "CANCELLED")) }
                        }, enabled = !state.busy, tone = Tone.Bad)
                    }
                    SecondaryButton(strings[Txt.ACTION_DELETE], {
                        pending = PendingAction.Confirm(strings[Txt.CRM_SERVICE_DELETE_CONFIRM], strings[Txt.ACTION_DELETE], destructive = true) {
                            vm.remove { repository.deleteService(it) }
                        }
                    }, enabled = !state.busy, tone = Tone.Bad)
                }
            }
        }
        item { LinesPanel(service.items, service.totalEur, strings) }
    }
    PendingActionDialog(pending, strings, clock) { pending = null }
    if (pickEmployee) {
        val options = listOf("" to strings[Txt.CRM_SERVICE_NOBODY]) +
            employees.value.orEmpty().filter { it.active }.map { it.id to it.name }
        ChoiceDialog(
            title = strings[Txt.CRM_SERVICE_DONE_BY],
            options = options,
            selected = state.value?.employeeId.orEmpty(),
            cancelLabel = strings[Txt.ACTION_CANCEL],
            onPick = { id ->
                pickEmployee = false
                vm.act { repository.updateService(it, UpdateService(employeeId = id)) }
            },
            onDismiss = { pickEmployee = false },
        )
    }
}

/**
 * The next step along the backend's quote pipeline (`QuoteStatus`: PENDENTE, SENT, ACEITO), or null
 * once a quote is accepted and there is nothing to advance it to.
 */
internal fun nextQuoteStatus(current: String): String? = when (current.uppercase()) {
    "PENDENTE" -> "SENT"
    "SENT" -> "ACEITO"
    else -> null
}
