package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
data class WebWidget(val publicKey: String? = null, val allowedOrigins: List<String> = emptyList())
