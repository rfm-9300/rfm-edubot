package com.rfm.edubot.mobile.feature.crm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.common.parseDay
import com.rfm.edubot.mobile.core.model.CatalogItem
import com.rfm.edubot.mobile.core.model.LineItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the form picks from. [employees] is null where nobody is picked (an employee registers their own). */
data class ServiceFormOptions(
    val clients: List<ClientOption> = emptyList(),
    val catalog: List<CatalogItem> = emptyList(),
    val employees: List<Pair<String, String>>? = null,
)

/** A service as the form sends it, whoever receives it. */
data class ServiceDraft(
    val clientId: String,
    val day: String,
    val name: String,
    val items: List<LineItem>,
    val notes: String?,
    val employeeId: String?,
    val catalogItemId: String?,
)

data class ServiceFormState(
    val options: ServiceFormOptions = ServiceFormOptions(),
    val clientId: String? = null,
    val clientQuery: String = "",
    val day: String = "",
    val name: String = "",
    val notes: String = "",
    val employeeId: String = "",
    val lines: List<LineDraft> = listOf(LineDraft()),
    /** The catalog item the first line came from, which the backend keeps on the row. */
    val catalogItemId: String? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: AppError? = null,
) {
    val totalEur: Double get() = lines.sumOf { it.totalEur }

    val dayValid: Boolean get() = parseDay(day) != null

    val canSave: Boolean get() = !saving && clientId != null && dayValid && lines.any { it.complete }
}

/**
 * Registering a service: the team's New service (saved straight as a Serviços row) and an
 * employee's My services (sent for the team to approve) are the same form. [loadOptions] and
 * [submit] say where the data comes from and goes; [initial] fills it in to change a pending one.
 */
class ServiceFormViewModel(
    private val loadOptions: suspend () -> ServiceFormOptions,
    private val submit: suspend (ServiceDraft) -> Outcome<*>,
    private val today: String,
    private val initial: ServiceFormState? = null,
    private val presetClientId: String? = null,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(initial ?: ServiceFormState(day = today))
    val state: StateFlow<ServiceFormState> = mutable.asStateFlow()

    fun load() = scope.launch {
        mutable.update { it.copy(loading = true) }
        val options = loadOptions()
        mutable.update { current ->
            val preset = presetClientId?.let { id -> options.clients.firstOrNull { it.id == id } }
            current.copy(
                options = options,
                loading = false,
                clientId = preset?.id ?: current.clientId,
                clientQuery = preset?.name ?: current.clientQuery.ifBlank {
                    options.clients.firstOrNull { it.id == current.clientId }?.name.orEmpty()
                },
            )
        }
    }

    fun setClientQuery(value: String) = mutable.update { it.copy(clientQuery = value, clientId = null, error = null) }

    fun pickClient(option: ClientOption) = mutable.update { it.copy(clientId = option.id, clientQuery = option.name, error = null) }

    fun setDay(value: String) = mutable.update { it.copy(day = value, error = null) }

    fun setName(value: String) = mutable.update { it.copy(name = value) }

    fun setNotes(value: String) = mutable.update { it.copy(notes = value) }

    fun setEmployee(id: String) = mutable.update { it.copy(employeeId = id) }

    fun addLine() = mutable.update { it.copy(lines = it.lines + LineDraft()) }

    fun removeLine(index: Int) = mutable.update {
        if (it.lines.size <= 1) it else it.copy(lines = it.lines.filterIndexed { i, _ -> i != index })
    }

    fun editLine(index: Int, transform: LineDraft.() -> LineDraft) = mutable.update { current ->
        current.copy(lines = current.lines.mapIndexed { i, line -> if (i == index) line.transform() else line }, error = null)
    }

    fun applyCatalogItem(index: Int, item: CatalogItem) = mutable.update { current ->
        current.copy(
            lines = current.lines.mapIndexed { i, line ->
                if (i == index) line.copy(description = item.label, unit = item.unit, unitPrice = formatQuantity(item.defaultUnitPriceEur)) else line
            },
            catalogItemId = current.catalogItemId ?: item.id.takeIf { index == 0 && it.isNotBlank() },
        )
    }

    fun save() = scope.launch {
        val current = mutable.value
        val clientId = current.clientId ?: return@launch
        if (!current.canSave) return@launch
        mutable.value = current.copy(saving = true, error = null)
        val items = current.lines.filter { it.complete }.map { it.toLineItem() }
        val draft = ServiceDraft(
            clientId = clientId,
            day = current.day.trim(),
            name = current.name.trim().ifBlank { nameFromLines(items) },
            items = items,
            notes = current.notes.trim().ifBlank { null },
            employeeId = current.employeeId.ifBlank { null },
            catalogItemId = current.catalogItemId,
        )
        when (val saved = submit(draft)) {
            is Outcome.Success -> mutable.update { it.copy(saving = false, saved = true) }
            is Outcome.Failure -> mutable.update { it.copy(saving = false, error = saved.error) }
        }
    }
}

/** "Pintura + Limpeza": what the web and the backend call a service named after its lines. */
internal fun nameFromLines(items: List<LineItem>): String =
    items.joinToString(" + ") { it.description.substringBefore(" - ").trim() }.take(MAX_NAME)

private const val MAX_NAME = 200
