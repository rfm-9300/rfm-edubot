package com.rfm.edubot.webhook.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InstagramPayload(
    val `object`: String,
    val entry: List<InstagramEntry>,
)

@Serializable
data class InstagramEntry(
    val id: String,
    val time: Long? = null,
    // Instagram-Login (graph.instagram.com) delivers messages in the field-based `changes[]` shape;
    // the older Messenger-style `messaging[]` shape is also accepted. Both carry the same value body.
    val messaging: List<InstagramMessaging>? = null,
    val changes: List<InstagramChange>? = null,
) {
    /** Normalizes both webhook shapes into a single list of message events. */
    val events: List<InstagramMessaging>
        get() = messaging.orEmpty() + changes.orEmpty().filter { it.field == "messages" }.mapNotNull { it.value }
}

@Serializable
data class InstagramChange(
    val field: String? = null,
    val value: InstagramMessaging? = null,
)

@Serializable
data class InstagramMessaging(
    // Nullable: Instagram sends events (read receipts, reactions, etc.) without a sender; a required
    // field would make the whole payload fail to deserialize and drop real messages too.
    val sender: InstagramParticipant? = null,
    val recipient: InstagramParticipant? = null,
    // `timestamp` is intentionally omitted: it arrives as a number in messaging[] but a string in
    // changes[], which breaks strict typing. We fall back to entry.time, which is always present.
    val message: InstagramMessage? = null,
    val read: InstagramRead? = null,
)

@Serializable
data class InstagramParticipant(val id: String)

@Serializable
data class InstagramMessage(
    val mid: String? = null,
    val text: String? = null,
    @SerialName("is_echo") val isEcho: Boolean = false,
    @SerialName("is_self") val isSelf: Boolean = false,
)

@Serializable
data class InstagramRead(val mid: String? = null)
