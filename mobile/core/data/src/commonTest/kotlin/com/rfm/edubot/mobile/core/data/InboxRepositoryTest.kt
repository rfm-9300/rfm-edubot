package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.InMemorySnapshotStore
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.model.ChannelAsset
import com.rfm.edubot.mobile.core.model.Contact
import com.rfm.edubot.mobile.core.model.Conversation
import com.rfm.edubot.mobile.core.model.StartedConversation
import com.rfm.edubot.mobile.core.model.ThreadMessage
import com.rfm.edubot.mobile.core.model.ThreadUpdates
import com.rfm.edubot.mobile.core.model.WhatsAppTemplate
import com.rfm.edubot.mobile.core.network.ApiException
import com.rfm.edubot.mobile.core.network.InboxApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun conversation(
    id: String = "c1",
    unread: Int = 2,
    autoReply: Boolean = true,
    channel: String = ChannelAsset.WHATSAPP,
) = Conversation(
    id = id,
    waId = "351900000000",
    channel = channel,
    state = "OPEN",
    lastMessageAt = "2026-01-01T10:00:00Z",
    unreadCount = unread,
    autoReplyEnabled = autoReply,
    waiting = unread > 0,
)

private fun message(id: String, text: String = "hi") = ThreadMessage(
    id = id,
    role = "USER",
    text = text,
    status = "DELIVERED",
    createdAt = "2026-01-01T10:00:00Z",
)

private open class FakeInboxApi(
    var conversations: List<Conversation> = listOf(conversation()),
    var messages: List<ThreadMessage> = listOf(message("m1")),
    var updates: ThreadUpdates = ThreadUpdates(cursor = "2026-01-01T10:00:05Z"),
    var markReadFails: Boolean = false,
) : InboxApi {
    var sentAssetId: String? = null
        private set
    var autoReplySetTo: Boolean? = null
        private set

    override suspend fun conversations(query: String?): List<Conversation> = conversations

    override suspend fun messages(conversationId: String): List<ThreadMessage> = messages

    override suspend fun updates(conversationId: String, since: String?): ThreadUpdates = updates

    override suspend fun markRead(conversationId: String): Conversation {
        if (markReadFails) throw ApiException(AppError.Unavailable(500))
        return conversations.first { it.id == conversationId }.copy(unreadCount = 0, waiting = false)
    }

    override suspend fun setAutoReply(conversationId: String, enabled: Boolean): Conversation {
        autoReplySetTo = enabled
        return conversations.first { it.id == conversationId }.copy(autoReplyEnabled = enabled)
    }

    override suspend fun sendMessage(conversationId: String, text: String, assetExternalId: String?): ThreadMessage {
        sentAssetId = assetExternalId
        return ThreadMessage("sent", "ASSISTANT", text, "QUEUED", "2026-01-01T10:01:00Z")
    }

    override suspend fun retryMessage(conversationId: String, messageId: String): ThreadMessage =
        message(messageId).copy(status = "QUEUED")

    override suspend fun sendTemplate(
        conversationId: String,
        name: String,
        language: String,
        params: Map<String, String>,
    ): ThreadMessage = message("template")

    override suspend fun startConversation(
        phone: String,
        name: String,
        language: String,
        params: Map<String, String>,
    ): StartedConversation = StartedConversation(conversation())

    override suspend fun templates(sendableOnly: Boolean): List<WhatsAppTemplate> = emptyList()

    override suspend fun contacts(query: String?): List<Contact> = listOf(
        Contact("ct1", "351900000000", ChannelAsset.WHATSAPP, status = "ACTIVE", lastSeenAt = "2026-01-01T09:00:00Z"),
    )

    override suspend fun setContactStatus(contactId: String, status: String): Contact =
        contacts(null).first().copy(status = status)
}

class InboxRepositoryTest {
    private fun repository(api: FakeInboxApi = FakeInboxApi()) =
        InboxRepository(api, SnapshotCache(InMemorySnapshotStore())) to api

    @Test
    fun `opening a thread clears the unread badge in the list too`() = runTest {
        val (repository, _) = repository()
        repository.conversations.load()

        val opened = repository.openThread(conversation())

        assertTrue(opened is Outcome.Success)
        assertEquals(0, opened.value.conversation.unreadCount)
        assertEquals(0, repository.conversations.state.value.value?.first()?.unreadCount)
    }

    @Test
    fun `a thread still opens when clearing the badge fails`() = runTest {
        val (repository, _) = repository(FakeInboxApi(markReadFails = true))
        repository.conversations.load()

        val opened = repository.openThread(conversation())

        assertTrue(opened is Outcome.Success, "a failed courtesy call must not block reading the thread")
        assertEquals(listOf("m1"), opened.value.messages.map { it.id })
    }

    @Test
    fun `a thread that cannot be read reports the failure`() = runTest {
        val api = object : FakeInboxApi() {
            override suspend fun messages(conversationId: String): List<ThreadMessage> =
                throw ApiException(AppError.Offline(null))
        }
        val (repository, _) = repository(api)
        val opened = repository.openThread(conversation())
        assertTrue((opened as Outcome.Failure).error is AppError.Offline)
    }

    @Test
    fun `polling appends new messages and advances the cursor`() = runTest {
        val api = FakeInboxApi(
            updates = ThreadUpdates(cursor = "cursor-2", messages = listOf(message("m2", "new"))),
        )
        val (repository, _) = repository(api)
        val snapshot = ThreadSnapshot(conversation(), listOf(message("m1")), cursor = "cursor-1")

        val polled = repository.pollThread(snapshot)

        assertTrue(polled is Outcome.Success)
        assertEquals(listOf("m1", "m2"), polled.value.messages.map { it.id })
        assertEquals("cursor-2", polled.value.cursor)
    }

    @Test
    fun `polling replaces a message the backend resent rather than duplicating it`() = runTest {
        val api = FakeInboxApi(
            updates = ThreadUpdates(
                cursor = "cursor-2",
                messages = listOf(message("m1").copy(status = "READ")),
            ),
        )
        val (repository, _) = repository(api)
        val snapshot = ThreadSnapshot(conversation(), listOf(message("m1")), cursor = "cursor-1")

        val polled = repository.pollThread(snapshot)

        val messages = (polled as Outcome.Success).value.messages
        assertEquals(1, messages.size, "the overlap window resends messages; they must merge by id")
        assertEquals("READ", messages.single().status)
    }

    @Test
    fun `an empty poll only moves the cursor`() = runTest {
        val api = FakeInboxApi(updates = ThreadUpdates(cursor = "cursor-2"))
        val (repository, _) = repository(api)
        val snapshot = ThreadSnapshot(conversation(), listOf(message("m1")), cursor = "cursor-1")

        val polled = (repository.pollThread(snapshot) as Outcome.Success).value

        assertEquals(listOf("m1"), polled.messages.map { it.id })
        assertEquals("cursor-2", polled.cursor)
    }

    @Test
    fun `sending picks the asset matching the conversation's channel`() = runTest {
        val (repository, api) = repository()
        val assets = listOf(
            ChannelAsset(ChannelAsset.INSTAGRAM, "ig-asset"),
            ChannelAsset(ChannelAsset.WHATSAPP, "wa-asset"),
        )

        repository.send(conversation(), " hello ", assets)

        assertEquals("wa-asset", api.sentAssetId)
    }

    @Test
    fun `sending with no matching asset still goes out and lets the backend decide`() = runTest {
        val (repository, api) = repository()
        val result = repository.send(conversation(channel = ChannelAsset.WEB), "hello", emptyList())
        assertTrue(result is Outcome.Success)
        assertNull(api.sentAssetId, "no asset is a null, not an empty string the backend would reject")
    }

    @Test
    fun `pausing the assistant updates the conversation in the list`() = runTest {
        val (repository, api) = repository()
        repository.conversations.load()

        repository.setAutoReply(conversation(), enabled = false)

        assertEquals(false, api.autoReplySetTo)
        assertEquals(false, repository.conversations.state.value.value?.first()?.autoReplyEnabled)
    }

    @Test
    fun `blocking a contact updates the row in place`() = runTest {
        val (repository, _) = repository()
        repository.contacts.load()
        val contact = repository.contacts.state.value.value!!.first()

        repository.setContactStatus(contact, blocked = true)

        assertEquals("BLOCKED", repository.contacts.state.value.value?.first()?.status)
    }
}
