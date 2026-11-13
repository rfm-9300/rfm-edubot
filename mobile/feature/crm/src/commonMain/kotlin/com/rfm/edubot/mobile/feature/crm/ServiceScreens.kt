package com.rfm.edubot.mobile.feature.crm

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.data.PortalRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.CreateService
import com.rfm.edubot.mobile.core.model.DashboardModules
import com.rfm.edubot.mobile.core.model.ServiceSubmission
import com.rfm.edubot.mobile.core.model.SubmitService
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.ChipRow
import com.rfm.edubot.mobile.core.ui.ConfirmDialog
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.RefreshBar
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.Tone
import com.rfm.edubot.mobile.core.ui.rememberFormKey
import kotlinx.coroutines.launch

/** The team's New service: saved straight as a Serviços row, with who did the work. */
@Composable
fun NewServiceScreen(
    repository: CrmRepository,
    clientId: String?,
    modules: Set<String>,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val vm = viewModel<ServiceFormViewModel>(
        key = rememberFormKey("service-form:${clientId ?: "new"}"),
        factory = viewModelFactory {
            initializer {
                ServiceFormViewModel(
                    loadOptions = {
                        repository.clients.load()
                        if (DashboardModules.CATALOG in modules) repository.catalog.load()
                        val withEmployees = DashboardModules.EMPLOYEES in modules
                        if (withEmployees) repository.employees.load()
                        ServiceFormOptions(
                            clients = repository.clients.state.value.value.orEmpty().filterNot { it.archived }
                                .map { ClientOption(it.id, it.name, listOfNotNull(it.phone, it.taxId).joinToString(" ")) },
                            catalog = repository.catalog.state.value.value.orEmpty(),
                            employees = if (withEmployees) {
                                repository.employees.state.value.value.orEmpty().filter { it.active }.map { it.id to it.name }
                            } else {
                                null
                            },
                        )
                    },
                    submit = { draft ->
                        repository.createService(
                            CreateService(
                                clientId = draft.clientId,
                                name = draft.name,
                                items = draft.items,
                                performedAt = draft.day,
                                notes = draft.notes,
                                employeeId = draft.employeeId,
                                catalogItemId = draft.catalogItemId,
                            ),
                        )
                    },
                    today = clock.todayText(),
                    presetClientId = clientId,
                )
            }
        },
    )
    ServiceForm(vm, strings.module(DashboardModules.SERVICES), strings[Txt.CRM_SERVICE_NEW], strings, padding, onSaved, onCancel)
}

/**
 * An employee's My services: what they registered, by where it stands, and the way to register more.
 * Before this page an employee whose company has no time clock found only Settings in the app.
 */
@Composable
fun MyServicesScreen(
    repository: PortalRepository,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onRegister: () -> Unit,
    onOpen: (ServiceSubmission) -> Unit,
) {
    val state by repository.services.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) { repository.services.load() }
    var filter by rememberSaveable { mutableStateOf(ServiceSubmission.PENDING) }
    val rows = state.value.orEmpty()
    val shown = rows.filter { it.status.equals(filter, ignoreCase = true) }

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader(strings.module(DashboardModules.MY_SERVICES), strings.module(DashboardModules.MY_SERVICES)) {
                Row {
                    TextButton(onClick = { scope.launch { repository.services.refresh() } }) {
                        Text(strings[Txt.ACTION_REFRESH], color = BotColors.inkMuted)
                    }
                    TextButton(onClick = onRegister) { Text(strings[Txt.ACTION_NEW], color = BotColors.accentDeep) }
                }
            }
            RefreshBar(state.loading && state.hasValue)
        }
        state.error?.let { error ->
            item { ErrorPanel(strings.error(error), retryLabel = strings[Txt.ACTION_RETRY], onRetry = { scope.launch { repository.services.refresh() } }) }
        }
        item {
            ChipRow(
                options = listOf(ServiceSubmission.PENDING, ServiceSubmission.APPROVED, ServiceSubmission.REJECTED).map { it to strings.status(it) },
                selected = filter,
                onSelect = { filter = it },
                counts = rows.groupingBy { it.status.uppercase() }.eachCount(),
            )
        }
        when {
            rows.isEmpty() && state.loading -> item { LoadingScreen() }
            shown.isEmpty() -> item { EmptyState(strings[Txt.EMPTY_TITLE], strings[Txt.PORTAL_SERVICES_EMPTY]) }
            else -> items(shown, key = { it.id }) { submission ->
                ListRow(
                    title = submission.name,
                    detail = listOfNotNull(
                        submission.clientName.takeIf { it.isNotBlank() },
                        submission.performedAt.takeIf { it.isNotBlank() }?.let(clock::day),
                        submission.rejectionReason?.takeIf { it.isNotBlank() }?.let { strings.format(Txt.CRM_SUBMISSION_REJECTED_BECAUSE, "reason" to it) },
                        strings[Txt.CRM_SUBMISSION_ADJUSTED].takeIf { submission.adjusted },
                    ).joinToString(" · "),
                    leading = submission.name,
                    trailingLabel = money(submission.totalEur),
                    status = submission.status,
                    statusLabel = strings.status(submission.status),
                    onClick = if (submission.pending) ({ onOpen(submission) }) else null,
                )
            }
        }
        if (filter == ServiceSubmission.PENDING && shown.isNotEmpty()) {
            item { InfoPanel(strings[Txt.PORTAL_SERVICES_PENDING_HINT], tone = Tone.Info) }
        }
    }
}

/** Register a service ([submissionId] null) or change one still pending, which can also be withdrawn. */
@Composable
fun MyServiceFormScreen(
    repository: PortalRepository,
    submissionId: String?,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onDone: () -> Unit,
) {
    val existing = submissionId?.let { id -> repository.services.state.value.value?.firstOrNull { it.id == id } }
    val vm = viewModel<ServiceFormViewModel>(
        key = rememberFormKey("my-service:${submissionId ?: "new"}:${repository.employeeId}"),
        factory = viewModelFactory {
            initializer {
                ServiceFormViewModel(
                    loadOptions = {
                        repository.clients.load()
                        repository.catalog.load()
                        ServiceFormOptions(
                            clients = repository.clients.state.value.value.orEmpty().map { ClientOption(it.id, it.name, it.number) },
                            catalog = repository.catalog.state.value.value.orEmpty(),
                        )
                    },
                    submit = { draft ->
                        repository.save(
                            SubmitService(
                                clientId = draft.clientId,
                                performedAt = draft.day,
                                items = draft.items,
                                name = draft.name,
                                notes = draft.notes,
                                catalogItemId = draft.catalogItemId,
                            ),
                            id = submissionId,
                        )
                    },
                    today = clock.todayText(),
                    initial = existing?.let { submission ->
                        ServiceFormState(
                            clientId = submission.clientId,
                            clientQuery = submission.clientName,
                            day = submission.performedAt,
                            // A name made from the lines is left empty, so it follows the lines if they change.
                            name = submission.name.takeIf { it != nameFromLines(submission.items) }.orEmpty(),
                            notes = submission.notes.orEmpty(),
                            lines = submission.items.map { LineDraft(it.description, formatQuantity(it.quantity), it.unit, formatQuantity(it.unitPriceEur)) }
                                .ifEmpty { listOf(LineDraft()) },
                            catalogItemId = submission.catalogItemId,
                        )
                    },
                )
            }
        },
    )
    val scope = rememberCoroutineScope()
    var confirmWithdraw by remember { mutableStateOf(false) }
    var withdrawError by remember { mutableStateOf<AppError?>(null) }
    ServiceForm(
        vm = vm,
        eyebrow = strings.module(DashboardModules.MY_SERVICES),
        title = strings[if (existing == null) Txt.PORTAL_SERVICES_REGISTER else Txt.PORTAL_SERVICES_EDIT],
        strings = strings,
        padding = padding,
        onSaved = onDone,
        onCancel = onDone,
        headerAction = if (existing == null) null else ({
            TextButton(onClick = { confirmWithdraw = true }) { Text(strings[Txt.ACTION_WITHDRAW], color = BotColors.badInk) }
        }),
        extraError = withdrawError?.let(strings::error),
    )
    if (confirmWithdraw && existing != null) {
        ConfirmDialog(
            title = strings[Txt.ACTION_WITHDRAW],
            message = strings[Txt.PORTAL_SERVICES_WITHDRAW_CONFIRM],
            confirmLabel = strings[Txt.ACTION_WITHDRAW],
            cancelLabel = strings[Txt.ACTION_CANCEL],
            destructive = true,
            onConfirm = {
                confirmWithdraw = false
                scope.launch {
                    when (val done = repository.withdraw(existing)) {
                        is Outcome.Success -> onDone()
                        is Outcome.Failure -> withdrawError = done.error
                    }
                }
            },
            onDismiss = { confirmWithdraw = false },
        )
    }
}
