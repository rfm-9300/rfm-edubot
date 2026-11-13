package com.rfm.edubot.mobile.feature.crm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.common.monthBounds
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.ClientService
import com.rfm.edubot.mobile.core.model.DashboardModules
import com.rfm.edubot.mobile.core.model.Employee
import com.rfm.edubot.mobile.core.model.Payment
import com.rfm.edubot.mobile.core.model.ServiceSubmission
import com.rfm.edubot.mobile.core.model.Supplier
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.FieldRow
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.Panel
import com.rfm.edubot.mobile.core.ui.PromptDialog
import com.rfm.edubot.mobile.core.ui.RecordCard
import com.rfm.edubot.mobile.core.ui.RecordViewModel
import com.rfm.edubot.mobile.core.ui.SectionLabel
import com.rfm.edubot.mobile.core.ui.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

data class EmployeeRecordState(
    val employee: Employee? = null,
    /** 0 is this month, -1 the one before. */
    val monthOffset: Int = 0,
    val month: Pair<LocalDate, LocalDate>? = null,
    val services: List<ClientService> = emptyList(),
    val toApprove: List<ServiceSubmission> = emptyList(),
    val payments: List<Payment> = emptyList(),
    val loading: Boolean = false,
    val busy: String? = null,
    val error: AppError? = null,
) {
    /** What the month's services are worth, cancelled ones left out as on the web's report. */
    val monthEur: Double get() = services.filterNot { it.cancelled }.sumOf { it.totalEur }
}

/**
 * An employee's record: who they are, the services they did in one month (the web's monthly filter),
 * what they registered from their own sign-in for the team to approve, and their payments.
 */
class EmployeeRecordViewModel(
    private val repository: CrmRepository,
    private val employeeId: String,
    private val modules: Set<String>,
    private val today: () -> LocalDate,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(EmployeeRecordState())
    val state: StateFlow<EmployeeRecordState> = mutable.asStateFlow()

    private val withServices get() = DashboardModules.SERVICES in modules

    fun load() = scope.launch {
        mutable.update { it.copy(loading = true, error = null) }
        val submissions = async {
            if (withServices) repository.submissions(employeeId, ServiceSubmission.PENDING).valueOrNull.orEmpty() else emptyList()
        }
        val payments = async {
            if (DashboardModules.PAYMENTS in modules) repository.paymentsTo(employeeId = employeeId).valueOrNull.orEmpty() else emptyList()
        }
        when (val employee = repository.employee(employeeId)) {
            is Outcome.Success -> mutable.update {
                it.copy(employee = employee.value, toApprove = submissions.await(), payments = payments.await(), loading = false)
            }
            is Outcome.Failure -> mutable.update { it.copy(loading = false, error = employee.error) }
        }
        loadMonth(mutable.value.monthOffset)
    }

    fun previousMonth() = scope.launch { loadMonth(mutable.value.monthOffset - 1) }

    fun nextMonth() = scope.launch { if (mutable.value.monthOffset < 0) loadMonth(mutable.value.monthOffset + 1) }

    private suspend fun loadMonth(offset: Int) {
        if (!withServices) return
        val month = monthBounds(today(), offset)
        mutable.update { it.copy(monthOffset = offset, month = month) }
        when (val rows = repository.servicesFor(employeeId = employeeId, from = month.first.toString(), to = month.second.toString())) {
            is Outcome.Success -> mutable.update { it.copy(services = rows.value.sortedByDescending { service -> service.day }) }
            is Outcome.Failure -> mutable.update { it.copy(error = rows.error) }
        }
    }

    fun approve(submission: ServiceSubmission) = decide(submission) { repository.approveSubmission(it) }

    fun reject(submission: ServiceSubmission, reason: String?) = decide(submission) { repository.rejectSubmission(it, reason) }

    private fun decide(submission: ServiceSubmission, action: suspend (ServiceSubmission) -> Outcome<ServiceSubmission>) = scope.launch {
        if (mutable.value.busy != null) return@launch
        mutable.update { it.copy(busy = submission.id, error = null) }
        when (val done = action(submission)) {
            is Outcome.Success -> {
                mutable.update { state -> state.copy(busy = null, toApprove = state.toApprove.filterNot { it.id == submission.id }) }
                loadMonth(mutable.value.monthOffset)
            }
            is Outcome.Failure -> mutable.update { it.copy(busy = null, error = done.error) }
        }
    }
}

@Composable
fun EmployeeRecordScreen(
    repository: CrmRepository,
    employeeId: String,
    modules: Set<String>,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpen: (CrmRoute) -> Unit,
    onBack: () -> Unit,
) {
    val vm = viewModel<EmployeeRecordViewModel>(
        key = "employee:$employeeId",
        factory = viewModelFactory { initializer { EmployeeRecordViewModel(repository, employeeId, modules, { clock.today() }) } },
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(employeeId) { vm.load() }
    var rejecting by remember { mutableStateOf<ServiceSubmission?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { RecordHeader(strings[Txt.CRM_EMPLOYEES_TITLE], state.employee?.name ?: strings[Txt.CRM_EMPLOYEES_TITLE], strings, onBack) }
        state.error?.let { error -> item { ErrorPanel(strings.error(error), retryLabel = strings[Txt.ACTION_RETRY], onRetry = { vm.load() }) } }
        val employee = state.employee
        if (employee == null) {
            item { if (state.loading) LoadingScreen() else EmptyState(strings[Txt.EMPTY_TITLE]) }
            return@LazyColumn
        }
        item {
            RecordCard(
                employee.name,
                subtitle = employee.number,
                status = if (employee.active) null else "ARCHIVED",
                statusLabel = strings[Txt.CRM_EMPLOYEE_INACTIVE],
            ) {
                FieldRow(strings[Txt.CRM_EMPLOYEE_ROLE], employee.role)
                FieldRow(strings[Txt.CRM_CLIENT_PHONE], employee.phone)
                FieldRow(strings[Txt.CRM_CLIENT_EMAIL], employee.email)
                FieldRow(strings[Txt.CRM_CLIENT_TAX_ID], employee.taxId)
                FieldRow(strings[Txt.CRM_CLIENT_ADDRESS], employee.address)
                FieldRow(strings[Txt.CRM_EMPLOYEE_BIRTH_DATE], employee.birthDate?.let(clock::day))
            }
        }
        if (state.toApprove.isNotEmpty()) {
            item { SectionLabel(strings[Txt.CRM_EMPLOYEE_TO_APPROVE]) }
            items(state.toApprove, key = { "sub-${it.id}" }) { submission ->
                SubmissionPanel(
                    submission = submission,
                    strings = strings,
                    clock = clock,
                    busy = state.busy == submission.id,
                    onApprove = { vm.approve(submission) },
                    onReject = { rejecting = submission },
                )
            }
        }
        state.month?.let { (first, _) ->
            item {
                SectionLabel(strings.format(Txt.CRM_EMPLOYEE_SERVICES_MONTH, "month" to monthLabel(first))) {
                    Row {
                        TextButton(onClick = { vm.previousMonth() }) { Text(strings[Txt.ACTION_PREVIOUS], color = BotColors.accentDeep) }
                        TextButton(onClick = { vm.nextMonth() }, enabled = state.monthOffset < 0) { Text(strings[Txt.ACTION_NEXT], color = BotColors.accentDeep) }
                    }
                }
            }
            item {
                InfoPanel("${strings.plural(Txt.CRM_EMPLOYEE_SERVICES_COUNT, state.services.count { !it.cancelled })} · ${money(state.monthEur)}")
            }
            items(state.services, key = { "svc-${it.id}" }) { service -> ServiceRow(service, strings, clock) { onOpen(CrmRoute.Service(service.id)) } }
        }
        if (state.payments.isNotEmpty()) {
            item { SectionLabel(strings[Txt.CRM_PAYMENTS_FOR]) }
            items(state.payments, key = { "pay-${it.id}" }) { payment -> PaymentRow(payment, strings, clock) }
        }
    }

    rejecting?.let { submission ->
        PromptDialog(
            title = strings[Txt.ACTION_REJECT],
            label = strings[Txt.CRM_SUBMISSION_REJECT_REASON],
            confirmLabel = strings[Txt.ACTION_REJECT],
            cancelLabel = strings[Txt.ACTION_CANCEL],
            singleLine = false,
            onConfirm = { reason -> rejecting = null; vm.reject(submission, reason.trim().ifBlank { null }) },
            onDismiss = { rejecting = null },
        )
    }
}

@Composable
private fun SubmissionPanel(
    submission: ServiceSubmission,
    strings: Strings,
    clock: TenantClock,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) = Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
    Text(submission.name, style = MaterialTheme.typography.titleMedium, color = BotColors.ink)
    Text(
        listOfNotNull(submission.clientName, clock.day(submission.performedAt), money(submission.totalEur)).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = BotColors.inkMuted,
    )
    submission.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BotColors.inkSecondary) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onReject, enabled = !busy) { Text(strings[Txt.ACTION_REJECT], color = BotColors.badInk) }
        TextButton(onClick = onApprove, enabled = !busy) { Text(strings[Txt.ACTION_APPROVE], color = BotColors.accentDeep) }
    }
}

@Composable
internal fun PaymentRow(payment: Payment, strings: Strings, clock: TenantClock) {
    val overdue = !payment.paid && !payment.cancelled && clock.isOverdue(payment.dueDate)
    ListRow(
        title = payment.number.ifBlank { payment.payeeName },
        detail = listOfNotNull(
            payment.projectLabel.takeIf { it.isNotBlank() },
            payment.dueDate.takeIf { it.isNotBlank() }?.let(clock::day),
        ).joinToString(" · "),
        leading = payment.payeeName.ifBlank { payment.number },
        trailingLabel = money(payment.totalEur),
        status = if (overdue) OVERDUE else payment.status,
        statusLabel = strings.status(if (overdue) OVERDUE else payment.status),
        emphasised = overdue,
    )
}

/** `11/2026`: months are numbers here, so no month names need translating. */
private fun monthLabel(first: LocalDate): String = "${first.monthNumber.toString().padStart(2, '0')}/${first.year}"

@Composable
fun SupplierRecordScreen(
    repository: CrmRepository,
    supplierId: String,
    modules: Set<String>,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    val vm = viewModel<RecordViewModel<SupplierRecord>>(
        key = "supplier:$supplierId",
        factory = viewModelFactory {
            initializer {
                RecordViewModel({
                    when (val supplier = repository.supplier(supplierId)) {
                        is Outcome.Failure -> supplier
                        is Outcome.Success -> Outcome.Success(
                            SupplierRecord(
                                supplier.value,
                                if (DashboardModules.PAYMENTS in modules) repository.paymentsTo(supplierId = supplierId).valueOrNull.orEmpty() else emptyList(),
                            ),
                        )
                    }
                })
            }
        },
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(supplierId) { vm.load() }
    RecordFrame(state, strings[Txt.CRM_SUPPLIERS_TITLE], { it?.supplier?.name ?: strings[Txt.CRM_SUPPLIERS_TITLE] }, strings, padding, onBack, { vm.load() }) { record ->
        val supplier = record.supplier
        val unpaid = record.payments.filter { !it.paid && !it.cancelled }
        item {
            RecordCard(supplier.name, subtitle = supplier.number) {
                FieldRow(strings[Txt.CRM_SUPPLIER_CONTACT], supplier.contactPerson)
                FieldRow(strings[Txt.CRM_CLIENT_PHONE], supplier.phone)
                FieldRow(strings[Txt.CRM_SUPPLIER_TYPE], supplier.type)
                FieldRow(strings[Txt.CRM_CLIENT_ADDRESS], supplier.address)
            }
        }
        if (DashboardModules.PAYMENTS in modules) {
            item {
                val overdue = unpaid.filter { clock.isOverdue(it.dueDate) }.sumOf { it.totalEur }
                MoneyStrip(
                    Triple(strings[Txt.CRM_MONEY_TO_PAY], money(unpaid.sumOf { it.totalEur }), Tone.Neutral),
                    Triple(strings[Txt.CRM_MONEY_OVERDUE], money(overdue), if (overdue > 0) Tone.Bad else Tone.Neutral),
                )
            }
        }
        if (supplier.services.isNotEmpty()) {
            item { SectionLabel(strings[Txt.CRM_SUPPLIER_SERVICES]) }
            item {
                Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
                    supplier.services.forEach { service ->
                        com.rfm.edubot.mobile.core.ui.LineRow(
                            description = service.description,
                            detail = service.unit,
                            amount = service.unitPriceEur?.let(::money).orEmpty(),
                        )
                    }
                }
            }
        }
        if (record.payments.isNotEmpty()) {
            item { SectionLabel(strings[Txt.CRM_PAYMENTS_FOR]) }
            items(record.payments, key = { it.id }) { payment -> PaymentRow(payment, strings, clock) }
        }
    }
}

data class SupplierRecord(val supplier: Supplier, val payments: List<Payment>)
