package com.rfm.edubot.mobile.feature.crm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.model.CatalogItem
import com.rfm.edubot.mobile.core.model.SaveCatalogItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The two kinds of catalog item the backend stores, by the names it stores them under. */
enum class CatalogItemType(val wireValue: String) {
    Service("service"),
    Product("material"),
}

data class CatalogItemFormState(
    val type: CatalogItemType = CatalogItemType.Service,
    val title: String = "",
    val description: String = "",
    val category: String = "",
    val unit: String = "",
    val price: String = "",
    val code: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: AppError? = null,
    /** The company's catalog categories, offered as chips; a new name can still be typed. */
    val categories: List<String> = emptyList(),
    /** Set while editing: the item as it is stored. */
    val existing: CatalogItem? = null,
) {
    /** The backend answers `id_taken`/`code_too_long` about the code specifically. */
    val codeRejected: Boolean
        get() = (error as? AppError.Rejected)?.code in setOf("id_taken", "code_taken", "code_too_long")

    val canSave: Boolean get() = !saving && title.isNotBlank()
}

/**
 * Adding a catalog item, or editing one ([itemId]): the web's catalog record opens the same way from
 * its row. Booking fields the app doesn't show (duration, bookable) are left out of the request, and
 * the backend keeps what is stored for them.
 */
class CatalogItemFormViewModel(
    private val repository: CrmRepository,
    private val itemId: String? = null,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(CatalogItemFormState())
    val state: StateFlow<CatalogItemFormState> = mutable.asStateFlow()

    fun load() = scope.launch {
        repository.categories.load()
        val categories = repository.categories.state.value.value.orEmpty().map { it.name }
        val existing = itemId?.let { id ->
            repository.catalog.load()
            repository.catalog.state.value.value?.firstOrNull { it.id == id }
        }
        mutable.update { current ->
            if (existing == null) {
                current.copy(categories = categories)
            } else {
                current.copy(
                    type = if (existing.isService) CatalogItemType.Service else CatalogItemType.Product,
                    title = existing.label,
                    description = existing.description.takeIf { it != existing.label }.orEmpty(),
                    category = existing.category,
                    unit = existing.unit,
                    price = formatQuantity(existing.defaultUnitPriceEur),
                    code = existing.code.orEmpty(),
                    categories = categories,
                    existing = existing,
                )
            }
        }
    }

    fun delete() = scope.launch {
        val item = mutable.value.existing ?: return@launch
        mutable.update { it.copy(saving = true, error = null) }
        when (val done = repository.deleteCatalogItem(item)) {
            is Outcome.Success -> mutable.update { it.copy(saving = false, saved = true) }
            is Outcome.Failure -> mutable.update { it.copy(saving = false, error = done.error) }
        }
    }

    fun setDescription(value: String) = edit { copy(description = value) }

    fun setType(value: CatalogItemType) = edit { copy(type = value) }

    fun setTitle(value: String) = edit { copy(title = value) }

    fun setCategory(value: String) = edit { copy(category = value) }

    fun setUnit(value: String) = edit { copy(unit = value) }

    fun setPrice(value: String) = edit { copy(price = value) }

    fun setCode(value: String) = edit { copy(code = value) }

    fun save() = scope.launch {
        val current = mutable.value
        if (!current.canSave) return@launch
        mutable.value = current.copy(saving = true, error = null)
        val request = SaveCatalogItem(
            type = current.type.wireValue,
            category = current.category.trim(),
            unit = current.unit.trim(),
            defaultUnitPriceEur = current.price.toNumber() ?: 0.0,
            title = current.title.trim(),
            description = current.description.trim(),
            // Left empty the backend numbers it per type (SRV-001, MAT-001), or keeps the stored one.
            code = current.code.trim().ifBlank { null },
        )
        when (val saved = repository.saveCatalogItem(request, current.existing?.id)) {
            is Outcome.Success -> mutable.update { it.copy(saving = false, saved = true) }
            is Outcome.Failure -> mutable.update { it.copy(saving = false, error = saved.error) }
        }
    }

    private fun edit(transform: CatalogItemFormState.() -> CatalogItemFormState) =
        mutable.update { it.transform().copy(error = null) }
}
