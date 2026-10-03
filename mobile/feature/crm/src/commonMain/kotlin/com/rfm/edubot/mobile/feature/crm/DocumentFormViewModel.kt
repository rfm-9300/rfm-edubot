package com.rfm.edubot.mobile.feature.crm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.data.ResourceState
import com.rfm.edubot.mobile.core.model.CatalogItem
import com.rfm.edubot.mobile.core.model.CreateInvoice
import com.rfm.edubot.mobile.core.model.CreateQuote
import com.rfm.edubot.mobile.core.model.CrmClient
import com.rfm.edubot.mobile.core.model.LineItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DocumentKind { Quote, Invoice }

/** One editable line. Kept as strings so a half-typed price does not reset the field. */
data class LineDraft(
    val description: String = "",
    val quantity: String = "1",
    val unit: String = "",
    val unitPrice: String = "",
) {
    val quantityValue: Double get() = quantity.toNumber() ?: 0.0

    val unitPriceValue: Double get() = unitPrice.toNumber() ?: 0.0

    val totalEur: Double get() = quantityValue * unitPriceValue

    val complete: Boolean get() = description.isNotBlank() && quantityValue > 0

    fun toLineItem() = LineItem(
        description = description.trim(),
        quantity = quantityValue,
        unit = unit.trim(),
        unitPriceEur = unitPriceValue,
    )
}

/** Accepts both decimal separators, because a Portuguese keyboard offers a comma. */
internal fun String.toNumber(): Double? = trim().replace(',', '.').toDoubleOrNull()

data class DocumentFormState(
    val clientId: String? = null,
    val clientQuery: String = "",
    val lines: List<LineDraft> = listOf(LineDraft()),
    val date: String = "",
    val notes: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: AppError? = null,
) {
    val totalEur: Double get() = lines.sumOf { it.totalEur }

    val canSave: Boolean get() = !saving && clientId != null && lines.any { it.complete }
}

/**
 * Creating a quote or an invoice.
 *
 * The previous form asked somebody to type a client's `ObjectId` by hand and allowed exactly one
 * line. This picks the client from the directory and takes as many lines as the job has.
 */
class DocumentFormViewModel(
    private val repository: CrmRepository,
    private val kind: DocumentKind,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(DocumentFormState())
    val state: StateFlow<DocumentFormState> = mutable.asStateFlow()

    val clients: StateFlow<ResourceState<List<CrmClient>>> = repository.clients.state
    val catalog: StateFlow<ResourceState<List<CatalogItem>>> = repository.catalog.state

    fun load() = scope.launch {
        repository.clients.load()
        // The catalog is a convenience: picking an item fills a line in, and a tenant without the
        // module simply gets no suggestions.
        repository.catalog.load()
    }

    fun matchingClients(all: List<CrmClient>, query: String): List<CrmClient> {
        val needle = query.trim().lowercase()
        val active = all.filterNot { it.archived }
        if (needle.isBlank()) return active.take(CLIENT_SUGGESTIONS)
        return active.filter {
            it.name.lowercase().contains(needle) ||
                it.phone.contains(needle) ||
                it.taxId?.contains(needle) == true
        }.take(CLIENT_SUGGESTIONS)
    }

    fun setClient(client: CrmClient) = mutable.update {
        it.copy(clientId = client.id, clientQuery = client.name, error = null)
    }

    fun setClientQuery(value: String) = mutable.update {
        // Typing again clears the choice, so the form cannot save against a client nobody picked.
        it.copy(clientQuery = value, clientId = null, error = null)
    }

    fun setDate(value: String) = mutable.update { it.copy(date = value, error = null) }

    fun setNotes(value: String) = mutable.update { it.copy(notes = value) }

    fun addLine() = mutable.update { it.copy(lines = it.lines + LineDraft()) }

    fun removeLine(index: Int) = mutable.update {
        if (it.lines.size <= 1) it else it.copy(lines = it.lines.filterIndexed { i, _ -> i != index })
    }

    fun editLine(index: Int, transform: LineDraft.() -> LineDraft) = mutable.update { current ->
        current.copy(
            lines = current.lines.mapIndexed { i, line -> if (i == index) line.transform() else line },
            error = null,
        )
    }

    /** Filling a line from the catalog, so prices stay consistent with what was quoted before. */
    fun applyCatalogItem(index: Int, item: CatalogItem) = editLine(index) {
        copy(
            description = item.label,
            unit = item.unit,
            unitPrice = item.defaultUnitPriceEur.toString(),
        )
    }

    fun save() = scope.launch {
        val current = mutable.value
        val clientId = current.clientId ?: return@launch
        if (!current.canSave) return@launch
        mutable.value = current.copy(saving = true, error = null)
        val items = current.lines.filter { it.complete }.map { it.toLineItem() }
        val result = when (kind) {
            DocumentKind.Quote -> repository.createQuote(
                CreateQuote(
                    clientId = clientId,
                    items = items,
                    notes = current.notes.trim().ifBlank { null },
                    validUntil = current.date.trim().ifBlank { null },
                ),
            )
            DocumentKind.Invoice -> repository.createInvoice(
                CreateInvoice(clientId = clientId, items = items, dueDate = current.date.trim()),
            )
        }
        mutable.update {
            when (result) {
                is Outcome.Success -> it.copy(saving = false, saved = true)
                is Outcome.Failure -> it.copy(saving = false, error = result.error)
            }
        }
    }

    private companion object {
        const val CLIENT_SUGGESTIONS = 6
    }
}
