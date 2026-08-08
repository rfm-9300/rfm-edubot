package com.rfm.edubot.mobile.feature.crm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.localization.MobileCopy
import com.rfm.edubot.mobile.core.model.CatalogItem
import com.rfm.edubot.mobile.core.model.CreateInvoice
import com.rfm.edubot.mobile.core.model.CreateQuote
import com.rfm.edubot.mobile.core.model.LineItem
import com.rfm.edubot.mobile.core.network.DashboardApi
import com.rfm.edubot.mobile.core.ui.BotColor
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader

@Composable
fun CrmScreen(api: DashboardApi, token: String, module: String, strings: MobileCopy, padding: PaddingValues) {
    val vm = viewModel<CrmViewModel>(key = "crm:$token", factory = viewModelFactory { initializer { CrmViewModel(api, token) } })
    val crm by vm.state.collectAsState()
    var clientName by remember(module) { mutableStateOf("") }
    var clientPhone by remember(module) { mutableStateOf("") }
    var clientAddress by remember(module) { mutableStateOf("") }
    var itemId by remember(module) { mutableStateOf("") }
    var itemDescription by remember(module) { mutableStateOf("") }
    var itemCategory by remember(module) { mutableStateOf("") }
    var itemUnit by remember(module) { mutableStateOf("") }
    var itemPrice by remember(module) { mutableStateOf("") }
    var showCreateForm by remember(module) { mutableStateOf(false) }
    var documentClientId by remember(module) { mutableStateOf("") }
    var documentItemDescription by remember(module) { mutableStateOf("") }
    var documentQuantity by remember(module) { mutableStateOf("1") }
    var documentUnit by remember(module) { mutableStateOf("") }
    var documentUnitPrice by remember(module) { mutableStateOf("") }
    var quoteNotes by remember(module) { mutableStateOf("") }
    var quoteValidUntil by remember(module) { mutableStateOf("") }
    var invoiceDueDate by remember(module) { mutableStateOf("") }
    LaunchedEffect(vm, module) { vm.load(module) }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            ScreenHeader(strings.module(module).uppercase(), strings.module(module)) {
                if (module == "quotes" || module == "invoices") {
                    TextButton(onClick = { showCreateForm = !showCreateForm }) { Text(strings.newDocument, color = BotColor.Accent) }
                }
            }
        }
        if (module == "clients") item {
            Surface(Modifier.fillMaxWidth().padding(20.dp, 12.dp), color = BotColor.Panel, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, BotColor.Border)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotField(clientName, { clientName = it }, strings.clientName, Modifier.fillMaxWidth())
                    BotField(clientPhone, { clientPhone = it }, strings.clientPhone, Modifier.fillMaxWidth())
                    BotField(clientAddress, { clientAddress = it }, strings.clientAddress, Modifier.fillMaxWidth())
                    PrimaryButton(strings.createClient, {
                        vm.createClient(clientName, clientPhone, clientAddress)
                        clientName = ""; clientPhone = ""; clientAddress = ""
                    }, Modifier.fillMaxWidth(), clientName.isNotBlank() && clientPhone.isNotBlank() && !crm.loading)
                }
            }
        }
        if (module == "catalog") item {
            Surface(Modifier.fillMaxWidth().padding(20.dp, 12.dp), color = BotColor.Panel, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, BotColor.Border)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotField(itemId, { itemId = it }, strings.itemId, Modifier.fillMaxWidth())
                    BotField(itemDescription, { itemDescription = it }, strings.itemDescription, Modifier.fillMaxWidth())
                    BotField(itemCategory, { itemCategory = it }, strings.itemCategory, Modifier.fillMaxWidth())
                    BotField(itemUnit, { itemUnit = it }, strings.itemUnit, Modifier.fillMaxWidth())
                    BotField(itemPrice, { itemPrice = it }, strings.itemPrice, Modifier.fillMaxWidth())
                    PrimaryButton(strings.createCatalogItem, {
                        vm.createCatalogItem(CatalogItem(itemId, "service", itemCategory, itemDescription, itemUnit, itemPrice.toDoubleOrNull() ?: 0.0))
                        itemId = ""; itemDescription = ""; itemCategory = ""; itemUnit = ""; itemPrice = ""
                    }, Modifier.fillMaxWidth(), itemId.isNotBlank() && itemDescription.isNotBlank() && itemCategory.isNotBlank() && itemUnit.isNotBlank() && !crm.loading)
                }
            }
        }
        if ((module == "quotes" || module == "invoices") && showCreateForm) item {
            val isQuote = module == "quotes"
            val quantity = documentQuantity.toDoubleOrNull()
            val unitPrice = documentUnitPrice.toDoubleOrNull()
            val canCreate = documentClientId.isNotBlank() && documentItemDescription.isNotBlank() && documentUnit.isNotBlank() && quantity != null && quantity > 0 && unitPrice != null && unitPrice >= 0 && (isQuote || invoiceDueDate.isNotBlank()) && !crm.loading
            Surface(Modifier.fillMaxWidth().padding(20.dp, 12.dp), color = BotColor.Panel, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, BotColor.Border)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotField(documentClientId, { documentClientId = it }, strings.clientId, Modifier.fillMaxWidth())
                    BotField(documentItemDescription, { documentItemDescription = it }, strings.itemDescription, Modifier.fillMaxWidth())
                    BotField(documentQuantity, { documentQuantity = it }, strings.quantity, Modifier.fillMaxWidth())
                    BotField(documentUnit, { documentUnit = it }, strings.itemUnit, Modifier.fillMaxWidth())
                    BotField(documentUnitPrice, { documentUnitPrice = it }, strings.itemPrice, Modifier.fillMaxWidth())
                    if (isQuote) {
                        BotField(quoteValidUntil, { quoteValidUntil = it }, strings.validUntil, Modifier.fillMaxWidth())
                        BotField(quoteNotes, { quoteNotes = it }, strings.notes, Modifier.fillMaxWidth())
                    } else {
                        BotField(invoiceDueDate, { invoiceDueDate = it }, strings.dueDate, Modifier.fillMaxWidth())
                    }
                    PrimaryButton(if (isQuote) strings.createQuote else strings.createInvoice, {
                        val lineItem = LineItem(documentItemDescription, quantity ?: 0.0, documentUnit, unitPrice ?: 0.0)
                        if (isQuote) {
                            vm.createQuote(CreateQuote(documentClientId, listOf(lineItem), quoteNotes.ifBlank { null }, quoteValidUntil.ifBlank { null }))
                        } else {
                            vm.createInvoice(CreateInvoice(documentClientId, items = listOf(lineItem), dueDate = invoiceDueDate))
                        }
                        showCreateForm = false
                    }, Modifier.fillMaxWidth(), canCreate)
                }
            }
        }
        if (crm.loading) item { LoadingScreen() }
        when (module) {
            "clients" -> items(crm.clients, key = { it.id }) { ListRow(it.name, "${it.phone} · ${it.number}", null) {} }
            "quotes" -> items(crm.quotes, key = { it.id }) { ListRow(it.number, "${it.clientName ?: ""} · ${it.totalEur}", it.status) {} }
            "invoices" -> items(crm.invoices, key = { it.id }) { ListRow(it.number, "${it.clientName ?: ""} · ${it.totalEur}", it.status) {} }
            "catalog" -> items(crm.catalog, key = { it.id }) { ListRow(it.description, "${it.category} · ${it.defaultUnitPriceEur}/${it.unit}", null) {} }
        }
    }
}
