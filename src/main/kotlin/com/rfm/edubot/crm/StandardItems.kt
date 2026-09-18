package com.rfm.edubot.crm

import kotlinx.serialization.Serializable

@Serializable
data class StandardItem(
    val id: String,
    val type: String,
    val category: String,
    val description: String,
    val unit: String,
    val defaultUnitPriceEur: Double,
)
