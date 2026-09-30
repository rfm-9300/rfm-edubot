package com.rfm.edubot.whatsapp

import com.rfm.edubot.channel.OutboundDeliveryException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A 4xx answer from the WhatsApp Cloud API. [code] is Meta's error code (131047, 190…). */
class WhatsAppApiException(
    val httpStatus: Int,
    val code: Int?,
    val subcode: Int?,
    val title: String?,
    val details: String?,
    val traceId: String?,
) : OutboundDeliveryException("WhatsApp API error: $httpStatus code=$code ${listOfNotNull(title, details).joinToString(" - ")}".trim()) {
    /** Dashboard error key for this failure; see [WhatsAppErrors.key]. */
    val key: String get() = WhatsAppErrors.key(code)

    /** Meta's own explanation, for errors the dashboard has no translation for. */
    val detail: String? get() = details?.takeIf { it.isNotBlank() } ?: title

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromResponse(httpStatus: Int, body: String): WhatsAppApiException {
            val error = runCatching { json.decodeFromString(GraphErrorEnvelope.serializer(), body).error }.getOrNull()
            return WhatsAppApiException(
                httpStatus = httpStatus,
                code = error?.code,
                subcode = error?.subcode,
                title = error?.userTitle ?: error?.message,
                details = error?.errorData?.details ?: error?.userMessage,
                traceId = error?.traceId,
            )
        }
    }
}

object WhatsAppErrors {
    /**
     * Maps Meta's error codes to the keys the dashboard translates (`inboxErr_<key>` in the catalogs).
     * The same codes arrive synchronously from the send call and later in failed-status webhooks.
     */
    fun key(code: Int?): String = when (code) {
        131047, 470 -> "window_closed"
        131030 -> "recipient_not_allowed"
        131026 -> "undeliverable"
        131049, 131050 -> "marketing_limited"
        4, 80007, 130429, 131048, 131056 -> "rate_limited"
        132000, 132018 -> "template_params"
        132001, 132005, 132007, 132012, 132015, 132016 -> "template_unavailable"
        131045, 133010 -> "not_registered"
        131031, 131042, 368 -> "account_restricted"
        102, 190, 463, 467 -> "token_invalid"
        3, 10, 200 -> "permission_denied"
        else -> "send_failed"
    }
}

@Serializable
private data class GraphErrorEnvelope(val error: GraphError? = null)

@Serializable
private data class GraphError(
    val message: String? = null,
    val code: Int? = null,
    @SerialName("error_subcode") val subcode: Int? = null,
    @SerialName("error_user_title") val userTitle: String? = null,
    @SerialName("error_user_msg") val userMessage: String? = null,
    @SerialName("error_data") val errorData: GraphErrorData? = null,
    @SerialName("fbtrace_id") val traceId: String? = null,
)

@Serializable
private data class GraphErrorData(val details: String? = null)
