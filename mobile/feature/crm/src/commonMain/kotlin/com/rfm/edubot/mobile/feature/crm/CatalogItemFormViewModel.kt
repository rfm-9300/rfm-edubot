package com.rfm.edubot.mobile.feature.crm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.data.CrmRepository
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
    val category: String = "",
    val unit: String = "",
    val price: String = "",
    val code: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: AppError? = null,
) {
    /** The backend answers `id_taken`/`code_too_long` about the code specifically. */
    val codeRejected: Boolean
        get() = (error as? AppError.Rejected)?.code in setOf("id_taken", "code_taken", "code_too_long")

    val canSave: Boolean get() = !saving && title.isNotBlank()
}

class CatalogItemFormViewModel(
    private val repository: CrmRepository,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(CatalogItemFormState())
    val state: StateFlow<CatalogItemFormState> = mutable.asStateFlow()

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
            // Left empty the backend numbers it per type (SRV-001, MAT-001).
            code = current.code.trim().ifBlank { null },
        )
        when (val saved = repository.saveCatalogItem(request)) {
            is Outcome.Success -> mutable.update { it.copy(saving = false, saved = true) }
            is Outcome.Failure -> mutable.update { it.copy(saving = false, error = saved.error) }
        }
    }

    private fun edit(transform: CatalogItemFormState.() -> CatalogItemFormState) =
        mutable.update { it.transform().copy(error = null) }
}
