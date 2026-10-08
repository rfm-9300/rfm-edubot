package com.rfm.edubot.conversation

import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageAuthor
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.MessageStatus
import com.rfm.edubot.conversation.model.UserRole
import kotlinx.datetime.Clock
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the inbox counts a chat as waiting on the team. */
class ConversationNeedsReplyTest {
    private val now = Clock.System.now()
    private val open = Conversation(tenantId = ObjectId(), userId = ObjectId(), waId = "351910000000", lastMessageAt = now, createdAt = now)
    private val handedOver = open.copy(autoReplyEnabled = false, autoReplyPausedBy = Conversation.BOT_HANDOFF)
    private val pausedByPerson = open.copy(autoReplyEnabled = false, autoReplyPausedBy = "ana@sorriso.pt")

    private fun message(role: UserRole, author: MessageAuthor? = null) = Message(
        tenantId = open.tenantId, conversationId = open.id, waId = open.waId, role = role,
        content = MessageContent.Text("…"), status = MessageStatus.DELIVERED, createdAt = now, author = author,
    )

    @Test
    fun `a customer's last message always waits for a reply`() {
        listOf(open, handedOver, pausedByPerson).forEach { assertTrue(it.needsTeamReply(message(UserRole.USER))) }
    }

    @Test
    fun `the bot's handover message still leaves the chat waiting, until a person writes`() {
        assertTrue(handedOver.needsTeamReply(message(UserRole.ASSISTANT, MessageAuthor.AI)))
        assertFalse(handedOver.needsTeamReply(message(UserRole.ASSISTANT, MessageAuthor.AGENT)))
        assertFalse(handedOver.copy(autoReplyEnabled = true).needsTeamReply(message(UserRole.ASSISTANT, MessageAuthor.AI)))
    }

    @Test
    fun `a bot reply in an ordinary or person-paused chat is not waiting`() {
        assertFalse(open.needsTeamReply(message(UserRole.ASSISTANT, MessageAuthor.AI)))
        assertFalse(pausedByPerson.needsTeamReply(message(UserRole.ASSISTANT, MessageAuthor.AI)))
        assertFalse(open.needsTeamReply(null))
    }
}
