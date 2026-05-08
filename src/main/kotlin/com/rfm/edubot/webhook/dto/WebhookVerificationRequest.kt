package com.rfm.edubot.webhook.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WebhookVerificationRequest(
    @SerialName("hub.mode")
    val mode: String,
    @SerialName("hub.verify_token")
    val verifyToken: String,
    @SerialName("hub.challenge")
    val challenge: String,
)
