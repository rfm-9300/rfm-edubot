package com.rfm.edubot.mobile.feature.crm

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.common.formatEuros
import com.rfm.edubot.mobile.core.data.CachedResource
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.DashboardModules
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.RefreshBar
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.Tone

/**
 * Every CRM list: clients, quotes, invoices, the catalog, jobs, payments, suppliers and employees.
 *
 * The last four had no screen at all. Invoices and payments can be marked paid, and a quote moved
 * along its pipeline, from the phone.
 */
@Composable
fun CrmScreen(
    repository: CrmRepository,
    section: String,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onNewClient: () -> Unit,
    onOpenClient: (String) -> Unit,
) {
    val vm = viewModel<CrmViewModel>(
        key = "crm:$section",
        factory = viewModelFactory { initializer { CrmViewModel(repository, section) } },
    )
    val pending by vm.pending.collectAsState()
    val failure by vm.failure.collectAsState()
    LaunchedEffect(section) { vm.load() }

    val header: @Composable () -> Unit = {
        ScreenHeader(strings[Txt.NAV_GROUP_BUSINESS], strings.module(section)) {
            if (section == DashboardModules.CLIENTS) {
                TextButton(onClick = onNewClient) {
                    Text(strings[Txt.ACTION_NEW], color = BotColors.accentDeep)
                }
            } else {
                TextButton(onClick = { vm.refresh() }) {
                    Text(strings[Txt.ACTION_REFRESH], color = BotColors.accentDeep)
                }
            }
        }
    }

    when (section) {
        DashboardModules.CLIENTS -> CrmList(
            resource = repository.clients,
            strings = strings,
            emptyKey = Txt.CRM_CLIENTS_EMPTY,
            padding = padding,
            header = header,
            actionError = failure?.let(strings::error),
            onRetry = { vm.refresh() },
            key = { it.id },
        ) { client ->
            ListRow(
                title = client.name,
                detail = listOfNotNull(
                    client.phone.takeIf { it.isNotBlank() },
                    client.taxId?.let { "NIF $it" },
                    client.city,
                ).joinToString(" · "),
                leading = client.name,
                trailingLabel = client.number.takeIf { it.isNotBlank() },
                onClick = { onOpenClient(client.id) },
            )
        }

        DashboardModules.QUOTES -> CrmList(
            resource = repository.quotes,
            strings = strings,
            emptyKey = Txt.CRM_QUOTES_EMPTY,
            padding = padding,
            header = header,
            actionError = failure?.let(strings::error),
            onRetry = { vm.refresh() },
            key = { it.id },
        ) { quote ->
            val next = nextQuoteStatus(quote.status)
            ListRow(
                title = quote.clientName.ifBlank { quote.number },
                detail = listOfNotNull(
                    quote.number.takeIf { it.isNotBlank() },
                    quote.validUntil?.let { "${strings[Txt.CRM_QUOTE_VALID_UNTIL]} ${clock.fullDate(it) ?: it}" },
                ).joinToString(" · "),
                leading = quote.clientName.ifBlank { quote.number },
                trailingLabel = "€ ${formatEuros(quote.totalEur)}",
                status = quote.status,
                statusLabel = strings.status(quote.status),
                actionLabel = next?.let(strings::status),
                onAction = if (next == null || pending != null) null else ({ vm.setQuoteStatus(quote, next); Unit }),
            )
        }

        DashboardModules.INVOICES -> CrmList(
            resource = repository.invoices,
            strings = strings,
            emptyKey = Txt.CRM_INVOICES_EMPTY,
            padding = padding,
            header = header,
            actionError = failure?.let(strings::error),
            onRetry = { vm.refresh() },
            key = { it.id },
        ) { invoice ->
            val overdue = !invoice.paid && clock.isOverdue(invoice.dueDate)
            ListRow(
                title = invoice.clientName.ifBlank { invoice.number },
                detail = listOfNotNull(
                    invoice.number.takeIf { it.isNotBlank() },
                    clock.fullDate(invoice.dueDate) ?: invoice.dueDate.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                leading = invoice.clientName.ifBlank { invoice.number },
                trailingLabel = "€ ${formatEuros(invoice.totalEur)}",
                status = if (overdue) OVERDUE else invoice.status,
                statusLabel = strings.status(if (overdue) OVERDUE else invoice.status),
                emphasised = overdue,
                actionLabel = strings[Txt.ACTION_MARK_PAID].takeIf { !invoice.paid },
                onAction = if (invoice.paid || pending != null) null else ({ vm.markInvoicePaid(invoice); Unit }),
            )
        }

        DashboardModules.CATALOG -> CrmList(
            resource = repository.catalog,
            strings = strings,
            emptyKey = Txt.CRM_CATALOG_EMPTY,
            padding = padding,
            header = header,
            actionError = failure?.let(strings::error),
            onRetry = { vm.refresh() },
            key = { it.id },
        ) { item ->
            ListRow(
                title = item.label,
                detail = listOfNotNull(
                    item.category.takeIf { it.isNotBlank() },
                    item.code,
                    item.unit.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                leading = item.label,
                trailingLabel = "€ ${formatEuros(item.defaultUnitPriceEur)}",
            )
        }

        DashboardModules.SERVICES -> CrmList(
            resource = repository.services,
            strings = strings,
            emptyKey = Txt.CRM_SERVICES_EMPTY,
            padding = padding,
            header = header,
            actionError = failure?.let(strings::error),
            onRetry = { vm.refresh() },
            key = { it.id },
        ) { service ->
            ListRow(
                title = service.name,
                detail = listOfNotNull(
                    service.clientName.takeIf { it.isNotBlank() },
                    service.performedAt?.let { clock.fullDate(it) ?: it },
                ).joinToString(" · "),
                leading = service.name,
                trailingLabel = "€ ${formatEuros(service.totalEur)}",
                status = service.status.takeIf { it.isNotBlank() },
                statusLabel = service.status.takeIf { it.isNotBlank() }?.let(strings::status),
            )
        }

        DashboardModules.PAYMENTS -> CrmList(
            resource = repository.payments,
            strings = strings,
            emptyKey = Txt.CRM_PAYMENTS_EMPTY,
            padding = padding,
            header = header,
            actionError = failure?.let(strings::error),
            onRetry = { vm.refresh() },
            key = { it.id },
        ) { payment ->
            val overdue = !payment.paid && clock.isOverdue(payment.dueDate)
            ListRow(
                title = payment.payeeName.ifBlank { payment.number },
                detail = listOfNotNull(
                    payment.number.takeIf { it.isNotBlank() },
                    clock.fullDate(payment.dueDate) ?: payment.dueDate.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                leading = payment.payeeName.ifBlank { payment.number },
                trailingLabel = "€ ${formatEuros(payment.totalEur)}",
                status = if (overdue) OVERDUE else payment.status,
                statusLabel = strings.status(if (overdue) OVERDUE else payment.status),
                emphasised = overdue,
                actionLabel = strings[Txt.ACTION_MARK_PAID].takeIf { !payment.paid },
                onAction = if (payment.paid || pending != null) null else ({ vm.markPaymentPaid(payment); Unit }),
            )
        }

        DashboardModules.SUPPLIERS -> CrmList(
            resource = repository.suppliers,
            strings = strings,
            emptyKey = Txt.CRM_SUPPLIERS_EMPTY,
            padding = padding,
            header = header,
            actionError = failure?.let(strings::error),
            onRetry = { vm.refresh() },
            key = { it.id },
        ) { supplier ->
            ListRow(
                title = supplier.name,
                detail = listOfNotNull(supplier.phone.takeIf { it.isNotBlank() }, supplier.type).joinToString(" · "),
                leading = supplier.name,
                trailingLabel = supplier.number.takeIf { it.isNotBlank() },
            )
        }

        DashboardModules.EMPLOYEES -> CrmList(
            resource = repository.employees,
            strings = strings,
            emptyKey = Txt.CRM_EMPLOYEES_EMPTY,
            padding = padding,
            header = header,
            actionError = failure?.let(strings::error),
            onRetry = { vm.refresh() },
            key = { it.id },
        ) { employee ->
            ListRow(
                title = employee.name,
                detail = listOfNotNull(employee.role, employee.phone.takeIf { it.isNotBlank() })
                    .joinToString(" · "),
                leading = employee.name,
                trailingLabel = employee.number.takeIf { it.isNotBlank() },
            )
        }

        else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { header() }
            item { EmptyState(strings[Txt.EMPTY_TITLE]) }
        }
    }
}

/**
 * The frame every CRM list shares: header, refresh hairline, snapshot notice, error with a retry,
 * empty state, rows.
 */
@Composable
private fun <T> CrmList(
    resource: CachedResource<List<T>>,
    strings: Strings,
    emptyKey: String,
    padding: PaddingValues,
    header: @Composable () -> Unit,
    actionError: String?,
    onRetry: () -> Unit,
    key: (T) -> Any,
    row: @Composable (T) -> Unit,
) {
    val state by resource.state.collectAsState()
    val rows = state.value.orEmpty()
    val loadError = state.error
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            header()
            RefreshBar(state.loading && state.hasValue)
        }
        val message = actionError ?: loadError?.let(strings::error)
        if (message != null) {
            item { ErrorPanel(message, retryLabel = strings[Txt.ACTION_RETRY], onRetry = onRetry) }
        }
        if (state.fromCache) {
            item { InfoPanel(strings[Txt.OFFLINE_SNAPSHOT], tone = Tone.Info) }
        }
        when {
            rows.isEmpty() && state.loading -> item { LoadingScreen() }
            rows.isEmpty() -> item { EmptyState(strings[Txt.EMPTY_TITLE], strings[emptyKey]) }
            else -> items(rows, key = key) { row(it) }
        }
    }
}

/**
 * The next step along the backend's quote pipeline (`QuoteStatus`: PENDENTE, SENT, ACEITO), or null
 * once a quote is accepted and there is nothing to advance it to.
 */
private fun nextQuoteStatus(current: String): String? = when (current.uppercase()) {
    "PENDENTE" -> "SENT"
    "SENT" -> "ACEITO"
    else -> null
}

/** Not a stored status: the backend keeps an unpaid invoice PENDING and the date makes it late. */
private const val OVERDUE = "OVERDUE"
