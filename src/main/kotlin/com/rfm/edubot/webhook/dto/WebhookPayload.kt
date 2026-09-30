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
    val profile: Profile? = null,
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
    val sticker: MediaContent? = null,
    val location: LocationContent? = null,
    val contacts: List<SharedContact>? = null,
    /** A tap on a template's quick-reply button. */
    val button: ButtonContent? = null,
    /** A reply to an interactive button or list message. */
    val interactive: InteractiveContent? = null,
)

@Serializable
data class TextContent(
    val body: String,
)

@Serializable
data class MediaContent(
    val id: String? = null,
    val mime_type: String? = null,
    val sha256: String? = null,
    val caption: String? = null,
    val filename: String? = null,
)

@Serializable
data class LocationContent(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val name: String? = null,
    val address: String? = null,
    val url: String? = null,
)

@Serializable
data class SharedContact(
    val name: SharedContactName? = null,
    val phones: List<SharedContactPhone>? = null,
)

@Serializable
data class SharedContactName(
    @SerialName("formatted_name") val formattedName: String? = null,
    @SerialName("first_name") val firstName: String? = null,
)

@Serializable
data class SharedContactPhone(
    val phone: String? = null,
    @SerialName("wa_id") val waId: String? = null,
)

@Serializable
data class ButtonContent(
    val text: String? = null,
    val payload: String? = null,
)

@Serializable
data class InteractiveContent(
    val type: String? = null,
    @SerialName("button_reply") val buttonReply: InteractiveReply? = null,
    @SerialName("list_reply") val listReply: InteractiveReply? = null,
)

@Serializable
data class InteractiveReply(
    val id: String? = null,
    val title: String? = null,
    val description: String? = null,
)

// Status fields are optional: a status the parser rejects would drop the messages in the same payload.
@Serializable
data class Status(
    val id: String,
    @SerialName("recipient_id")
    val recipientId: String? = null,
    val status: String,
    val timestamp: String? = null,
    val conversation: ConversationStatus? = null,
    val pricing: PricingStatus? = null,
    val errors: List<StatusError>? = null,
)

@Serializable
data class ConversationStatus(
    val id: String? = null,
    val origin: OriginStatus? = null,
)

@Serializable
data class OriginStatus(
    val type: String? = null,
)

@Serializable
data class PricingStatus(
    val pricing_model: String? = null,
    val billable: Boolean? = null,
    val category: String? = null,
)

@Serializable
data class StatusError(
    val code: Int? = null,
    val title: String? = null,
    val message: String? = null,
    @SerialName("error_data")
    val errorData: StatusErrorData? = null,
) {
    val text: String? get() = errorData?.details?.takeIf { it.isNotBlank() } ?: message ?: title
}

@Serializable
data class StatusErrorData(
    val details: String? = null,
)
