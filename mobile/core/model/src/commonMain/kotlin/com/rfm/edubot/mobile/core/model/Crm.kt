package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
data class CrmClient(val id: String, val number: String, val name: String, val phone: String, val address: String? = null)

@Serializable
data class Quote(val id: String, val number: String, val clientName: String? = null, val status: String, val totalEur: Double)

@Serializable
data class Invoice(
    val id: String,
    val number: String,
    val clientName: String? = null,
    val status: String,
    val dueDate: String,
    val totalEur: Double,
)

@Serializable
data class CatalogItem(
    val id: String,
    val type: String,
    val category: String,
    val description: String,
    val unit: String,
    val defaultUnitPriceEur: Double,
)

@Serializable
data class LineItem(val description: String, val quantity: Double, val unit: String, val unitPriceEur: Double)

@Serializable
data class CreateQuote(
    val clientId: String,
    val items: List<LineItem>,
    val notes: String? = null,
    val validUntil: String? = null,
)

@Serializable
data class CreateInvoice(
    val clientId: String,
    val quoteId: String? = null,
    val items: List<LineItem>,
    val dueDate: String,
)
