package com.rfm.edubot.mobile.feature.crm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.data.CachedResource
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.model.DashboardModules
import com.rfm.edubot.mobile.core.model.Invoice
import com.rfm.edubot.mobile.core.model.Payment
import com.rfm.edubot.mobile.core.model.Quote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One view model for every CRM list.
 *
 * The lists differ only in which resource they read and which actions a row offers, so [section]
 * picks the resource and the screen asks for the actions it needs. This is what lets the app cover
 * jobs, payments, suppliers and employees — four modules it had no screen for — without four
 * near-identical files.
 */
class CrmViewModel(
    private val repository: CrmRepository,
    private val section: String,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope

    private val mutablePending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = mutablePending.asStateFlow()

    private val mutableFailure = MutableStateFlow<AppError?>(null)
    val failure: StateFlow<AppError?> = mutableFailure.asStateFlow()

    fun load() = scope.launch { resource()?.load() }

    fun refresh() = scope.launch {
        mutableFailure.value = null
        resource()?.refresh()
    }

    fun markInvoicePaid(invoice: Invoice) = act(invoice.id) { repository.markInvoicePaid(invoice) }

    fun markPaymentPaid(payment: Payment) = act(payment.id) { repository.markPaymentPaid(payment) }

    fun setQuoteStatus(quote: Quote, status: String) = act(quote.id) { repository.setQuoteStatus(quote, status) }

    fun convertQuote(quote: Quote, dueDate: String) = act(quote.id) { repository.convertQuote(quote, dueDate) }

    private fun act(id: String, block: suspend () -> Outcome<*>) = scope.launch {
        if (mutablePending.value != null) return@launch
        mutablePending.value = id
        mutableFailure.value = null
        val result = block()
        mutablePending.value = null
        if (result is Outcome.Failure) mutableFailure.value = result.error
    }

    private fun resource(): CachedResource<*>? = when (section) {
        DashboardModules.CLIENTS -> repository.clients
        DashboardModules.QUOTES -> repository.quotes
        DashboardModules.INVOICES -> repository.invoices
        DashboardModules.CATALOG -> repository.catalog
        DashboardModules.SERVICES -> repository.services
        DashboardModules.PAYMENTS -> repository.payments
        DashboardModules.SUPPLIERS -> repository.suppliers
        DashboardModules.EMPLOYEES -> repository.employees
        else -> null
    }

}
