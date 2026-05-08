package com.rfm.edubot.webhook.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WebhookPayload(
    val `object`: String,
    val entry: List<Entry>,
)

@Serializable
data class Entry(
    val id: String,
    val changes: List<Change>,
)

@Serializable
data class Change(
    val field: String,
    val value: Value,
)

@Serializable
data class Value(
    val messaging_product: String? = null,
    val metadata: Metadata? = null,
    val contacts: List<Contact>? = null,
    val messages: List<IncomingMessage>? = null,
    val statuses: List<Status>? = null,
)

@Serializable
data class Metadata(
    @SerialName("display_phone_number")
    val displayPhoneNumber: String,
    @SerialName("phone_number_id")
    val phoneNumberId: String,
)

@Serializable
data class Contact(
    val profile: Profile,
    @SerialName("wa_id")
    val waId: String,
)

@Serializable
data class Profile(
    val name: String,
)

@Serializable
data class IncomingMessage(
    val from: String,
    val id: String,
    val timestamp: String,
    val type: String? = null,
    val text: TextContent? = null,
    val image: MediaContent? = null,
    val audio: MediaContent? = null,
    val document: MediaContent? = null,
    val video: MediaContent? = null,
)

@Serializable
data class TextContent(
    val body: String,
)

@Serializable
data class MediaContent(
    val id: String,
    val mime_type: String? = null,
    val sha256: String? = null,
)

@Serializable
data class Status(
    val id: String,
    @SerialName("recipient_id")
    val recipientId: String,
    val status: String,
    val timestamp: String,
    val conversation: ConversationStatus? = null,
    val pricing: PricingStatus? = null,
)

@Serializable
data class ConversationStatus(
    val id: String,
    val origin: OriginStatus? = null,
)

@Serializable
data class OriginStatus(
    val type: String,
)

@Serializable
data class PricingStatus(
    val pricing_model: String,
    val billable: Boolean,
    val category: String,
)
