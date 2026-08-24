package com.rfm.edubot.webhook.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

internal val instagramPayloadJson = Json { ignoreUnknownKeys = true }

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
        get() = messaging.orEmpty() + changes.orEmpty()
            .filter { it.field == "messages" }
            .mapNotNull { change ->
                change.value?.let { runCatching { instagramPayloadJson.decodeFromJsonElement<InstagramMessaging>(it) }.getOrNull() }
            }

    val commentEvents: List<InstagramCommentValue>
        get() = changes.orEmpty()
            .filter { it.field == "comments" || it.field == "live_comments" }
            .mapNotNull { change ->
                change.value?.let { runCatching { instagramPayloadJson.decodeFromJsonElement<InstagramCommentValue>(it) }.getOrNull() }
            }
}

@Serializable
data class InstagramChange(
    val field: String? = null,
    val value: JsonObject? = null,
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

@Serializable
data class InstagramCommentValue(
    val id: String,
    val text: String? = null,
    val from: InstagramCommentFrom? = null,
    val media: InstagramCommentMedia? = null,
)

@Serializable
data class InstagramCommentFrom(
    val id: String? = null,
    val username: String? = null,
)

@Serializable
data class InstagramCommentMedia(
    val id: String? = null,
    @SerialName("media_product_type") val mediaProductType: String? = null,
)
