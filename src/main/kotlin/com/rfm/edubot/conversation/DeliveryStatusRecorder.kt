package com.rfm.edubot.conversation

import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageStatus
import com.rfm.edubot.persistence.MongoModule
import org.bson.types.ObjectId

/** Applies WhatsApp status webhooks to the outbound messages the dashboard sent. */
class DeliveryStatusRecorder(private val mongo: MongoModule) {
    /** Returns the updated message, or null when the status is unknown, stale, or for a message we don't track. */
    suspend fun record(tenantId: ObjectId, waMessageId: String, status: String, errorCode: Int? = null, errorText: String? = null): Message? {
        val mapped = when (status.lowercase()) {
            "delivered" -> MessageStatus.DELIVERED
            "read" -> MessageStatus.READ
            "failed" -> MessageStatus.FAILED
            // "sent" is the state a message is stored in; Meta's other statuses ("deleted", "warning") don't apply.
            else -> return null
        }
        return MessageRepository(mongo, tenantId).applyDeliveryStatus(waMessageId, mapped, errorCode, errorText)
    }
}
