package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.data.CatalogItem
import com.rfm.edubot.mobile.data.CrmClient
import com.rfm.edubot.mobile.data.CreateInvoice
import com.rfm.edubot.mobile.data.CreateQuote
import com.rfm.edubot.mobile.data.DashboardApi
import com.rfm.edubot.mobile.data.Invoice
import com.rfm.edubot.mobile.data.Quote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CrmUiState(
    val clients: List<CrmClient> = emptyList(),
    val quotes: List<Quote> = emptyList(),
    val invoices: List<Invoice> = emptyList(),
    val catalog: List<CatalogItem> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
)

class CrmController(
    private val api: DashboardApi,
    private val token: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutableState = MutableStateFlow(CrmUiState())
    val state: StateFlow<CrmUiState> = mutableState.asStateFlow()

    fun load(module: String) = scope.launch {
        mutableState.value = mutableState.value.copy(loading = true, error = false)
        try {
            val current = mutableState.value
            mutableState.value = when (module) {
                "clients" -> current.copy(clients = api.clients(token), loading = false)
                "quotes" -> current.copy(quotes = api.quotes(token), loading = false)
                "invoices" -> current.copy(invoices = api.invoices(token), loading = false)
                "catalog" -> current.copy(catalog = api.catalog(token), loading = false)
                else -> current.copy(loading = false)
            }
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(loading = false, error = true)
        }
    }

    fun createClient(name: String, phone: String, address: String?) = scope.launch {
        if (name.isBlank() || phone.isBlank() || mutableState.value.loading) return@launch
        mutableState.value = mutableState.value.copy(loading = true, error = false)
        try {
            val client = api.createClient(token, name.trim(), phone.trim(), address?.trim()?.ifBlank { null })
            mutableState.value = mutableState.value.copy(clients = listOf(client) + mutableState.value.clients, loading = false)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(loading = false, error = true)
        }
    }

    fun createCatalogItem(item: CatalogItem) = scope.launch {
        if (item.id.isBlank() || item.category.isBlank() || item.description.isBlank() || item.unit.isBlank() || mutableState.value.loading) return@launch
        mutableState.value = mutableState.value.copy(loading = true, error = false)
        try {
            val created = api.createCatalogItem(token, item)
            mutableState.value = mutableState.value.copy(catalog = listOf(created) + mutableState.value.catalog, loading = false)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(loading = false, error = true)
        }
    }

    fun createQuote(request: CreateQuote) = scope.launch {
        if (request.clientId.isBlank() || request.items.isEmpty() || mutableState.value.loading) return@launch
        mutableState.value = mutableState.value.copy(loading = true, error = false)
        try {
            val created = api.createQuote(token, request)
            mutableState.value = mutableState.value.copy(quotes = listOf(created) + mutableState.value.quotes, loading = false)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(loading = false, error = true)
        }
    }

    fun createInvoice(request: CreateInvoice) = scope.launch {
        if (request.clientId.isBlank() || request.dueDate.isBlank() || request.items.isEmpty() || mutableState.value.loading) return@launch
        mutableState.value = mutableState.value.copy(loading = true, error = false)
        try {
            val created = api.createInvoice(token, request)
            mutableState.value = mutableState.value.copy(invoices = listOf(created) + mutableState.value.invoices, loading = false)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(loading = false, error = true)
        }
    }
}
