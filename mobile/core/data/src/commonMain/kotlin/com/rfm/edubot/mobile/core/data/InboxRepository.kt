package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.common.map
import com.rfm.edubot.mobile.core.model.ChannelAsset
import com.rfm.edubot.mobile.core.model.Contact
import com.rfm.edubot.mobile.core.model.Conversation
import com.rfm.edubot.mobile.core.model.ThreadMessage
import com.rfm.edubot.mobile.core.model.ThreadUpdates
import com.rfm.edubot.mobile.core.network.InboxApi
import kotlinx.serialization.builtins.ListSerializer

/** One open conversation: its messages plus the cursor the next poll resumes from. */
data class ThreadSnapshot(
    val conversation: Conversation,
    val messages: List<ThreadMessage> = emptyList(),
    val cursor: String? = null,
)

class InboxRepository(
    private val api: InboxApi,
    cache: SnapshotCache,
) {
    val conversations = CachedResource(
        key = "inbox.conversations",
        serializer = ListSerializer(Conversation.serializer()),
        cache = cache,
        fetch = { api.conversations() },
    )

    val contacts = CachedResource(
        key = "inbox.contacts",
        serializer = ListSerializer(Contact.serializer()),
        cache = cache,
        fetch = { api.contacts() },
    )

    suspend fun openThread(conversation: Conversation): Outcome<ThreadSnapshot> {
        val messages = apiCall { api.messages(conversation.id) }
        return when (messages) {
            is Outcome.Failure -> messages
            is Outcome.Success -> {
                // Clearing the badge is a courtesy; a failure here must not stop the thread opening.
                val read = apiCall { api.markRead(conversation.id) }
                read.valueOrNull?.let { updated -> replaceConversation(updated) }
                Outcome.Success(
                    ThreadSnapshot(
                        conversation = read.valueOrNull ?: conversation,
                        messages = messages.value,
                    ),
                )
            }
        }
    }

    /**
     * Pulls only what changed since [snapshot]'s cursor. Returns the same snapshot when nothing did,
     * so a poll loop can run without re-rendering the thread.
     */
    suspend fun pollThread(snapshot: ThreadSnapshot): Outcome<ThreadSnapshot> {
        val updates = apiCall { api.updates(snapshot.conversation.id, snapshot.cursor) }
        return updates.map { it.applyTo(snapshot) }
    }

    suspend fun send(conversation: Conversation, text: String, assets: List<ChannelAsset>): Outcome<ThreadMessage> {
        val asset = assets.firstOrNull { it.platform == conversation.channel }
        return apiCall { api.sendMessage(conversation.id, text.trim(), asset?.externalId) }
    }

    suspend fun retry(conversation: Conversation, messageId: String): Outcome<ThreadMessage> =
        apiCall { api.retryMessage(conversation.id, messageId) }

    suspend fun setAutoReply(conversation: Conversation, enabled: Boolean): Outcome<Conversation> {
        val updated = apiCall { api.setAutoReply(conversation.id, enabled) }
        updated.valueOrNull?.let { replaceConversation(it) }
        return updated
    }

    suspend fun setContactStatus(contact: Contact, blocked: Boolean): Outcome<Contact> {
        val updated = apiCall { api.setContactStatus(contact.id, if (blocked) "BLOCKED" else "ACTIVE") }
        updated.valueOrNull?.let { saved ->
            contacts.mutate { current -> current.map { if (it.id == saved.id) saved else it } }
        }
        return updated
    }

    private suspend fun replaceConversation(updated: Conversation) {
        conversations.mutate { current -> current.map { if (it.id == updated.id) updated else it } }
    }
}

/** Merges an updates response into the thread, replacing messages the backend resent by id. */
internal fun ThreadUpdates.applyTo(snapshot: ThreadSnapshot): ThreadSnapshot {
    if (messages.isEmpty() && conversation == null) return snapshot.copy(cursor = cursor)
    val byId = snapshot.messages.associateByTo(LinkedHashMap()) { it.id }
    messages.forEach { byId[it.id] = it }
    return ThreadSnapshot(
        conversation = conversation ?: snapshot.conversation,
        messages = byId.values.toList(),
        cursor = cursor,
    )
}
