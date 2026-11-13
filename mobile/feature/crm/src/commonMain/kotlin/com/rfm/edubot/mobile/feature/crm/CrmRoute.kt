package com.rfm.edubot.mobile.feature.crm

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.localization.Strings

/**
 * The CRM screens above a list: records, forms and documents. The shell wraps one in its own
 * destination and pushes it, so Back steps out of a record the way it came in.
 */
sealed interface CrmRoute {
    data class Client(val id: String) : CrmRoute

    /** [id] null creates a client. */
    data class EditClient(val id: String?) : CrmRoute

    data class Quote(val id: String) : CrmRoute

    data class Invoice(val id: String) : CrmRoute

    data class Service(val id: String) : CrmRoute

    data class NewService(val clientId: String? = null) : CrmRoute

    data class NewDocument(val kind: DocumentKind) : CrmRoute

    data class Employee(val id: String) : CrmRoute

    data class Supplier(val id: String) : CrmRoute

    /** [id] null adds an item. */
    data class CatalogItem(val id: String?) : CrmRoute
}

/**
 * Renders one [CrmRoute]. [modules] are the company's, so a record only offers links and actions
 * into modules it has: asking for one it lacks answers 403.
 */
@Composable
fun CrmRouteScreen(
    route: CrmRoute,
    repository: CrmRepository,
    modules: Set<String>,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
    onOpen: (CrmRoute) -> Unit,
    onBack: () -> Unit,
) = when (route) {
    is CrmRoute.Client -> ClientRecordScreen(repository, route.id, modules, strings, clock, padding, onOpen, onBack)
    is CrmRoute.EditClient -> ClientFormScreen(repository, strings, padding, onSaved = onBack, onCancel = onBack, clientId = route.id)
    is CrmRoute.Quote -> QuoteRecordScreen(repository, route.id, modules, strings, clock, padding, onOpen, onBack)
    is CrmRoute.Invoice -> InvoiceRecordScreen(repository, route.id, modules, strings, clock, padding, onOpen, onBack)
    is CrmRoute.Service -> ServiceRecordScreen(repository, route.id, modules, strings, clock, padding, onOpen, onBack)
    is CrmRoute.NewService -> NewServiceScreen(repository, route.clientId, modules, strings, clock, padding, onSaved = onBack, onCancel = onBack)
    is CrmRoute.NewDocument -> DocumentFormScreen(repository, route.kind, strings, padding, onSaved = onBack, onCancel = onBack)
    is CrmRoute.Employee -> EmployeeRecordScreen(repository, route.id, modules, strings, clock, padding, onOpen, onBack)
    is CrmRoute.Supplier -> SupplierRecordScreen(repository, route.id, modules, strings, clock, padding, onBack)
    is CrmRoute.CatalogItem -> CatalogItemFormScreen(repository, strings, padding, onSaved = onBack, onCancel = onBack, itemId = route.id)
}
