package com.rfm.edubot.dashboard.inbox

import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.User
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.Clock
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `/app/api/conversations`, `/whatsapp/templates` and `/contacts` over HTTP: their gates, isolation between companies, error codes. */
class InboxApiTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("inbox_api")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val inbox = "/app/api/conversations"
    private val contacts = "/app/api/contacts"

    /** A customer of [company] who wrote "Bom dia" on WhatsApp just now. */
    private suspend fun DashboardApi.customer(company: TestCompany, name: String = "Maria Silva"): Pair<User, Conversation> {
        val waId = "35191" + (1..7).map { (0..9).random() }.joinToString("")
        val user = UserRepository(mongo, company.tenant.id).findOrCreate(waId, name)
        val conversations = ConversationRepository(mongo, company.tenant.id)
        val conversation = conversations.findOrCreate(user.id, waId)
        val now = Clock.System.now()
        MessageRepository(mongo, company.tenant.id).insert(
            Message(tenantId = company.tenant.id, conversationId = conversation.id, waId = waId, role = UserRole.USER, content = MessageContent.Text("Bom dia"), createdAt = now),
        )
        conversations.recordInbound(conversation.id, now)
        return user to conversations.findById(conversation.id)!!
    }

    @Test
    fun `the inbox answers 403 without conversations, and contacts without contacts`() = dashboardApiTest(mongo) {
        val contactsOnly = company(listOf(DashboardModules.CONTACTS))
        val (_, conversation) = customer(contactsOnly)
        for (path in listOf(inbox, "$inbox/${conversation.id}/messages", "$inbox/${conversation.id}/updates", "/app/api/whatsapp/templates")) {
            assertEquals(HttpStatusCode.Forbidden, contactsOnly.get(path).status, path)
        }
        assertEquals(HttpStatusCode.Forbidden, contactsOnly.post("$inbox/${conversation.id}/messages") { put("text", "Olá") }.status)
        assertEquals(HttpStatusCode.Forbidden, contactsOnly.post("$inbox/${conversation.id}/read").status)
        assertEquals(1, contactsOnly.get(contacts).array.size)

        val inboxOnly = company(listOf(DashboardModules.CONVERSATIONS))
        val (user, _) = customer(inboxOnly)
        assertEquals(HttpStatusCode.Forbidden, inboxOnly.get(contacts).status)
        assertEquals(HttpStatusCode.Forbidden, inboxOnly.patch("$contacts/${user.id}/status") { put("status", "BLOCKED") }.status)
        assertEquals(1, inboxOnly.get(inbox).array.size)
    }

    @Test
    fun `a company never reads, answers or blocks another company's customers`() = dashboardApiTest(mongo) {
        val a = company()
        val b = company()
        val (user, conversation) = customer(a)
        assertTrue(b.get(inbox).array.isEmpty())
        assertTrue(b.get("$inbox?q=Maria").array.isEmpty())
        assertTrue(b.get(contacts).array.isEmpty())
        assertEquals(HttpStatusCode.NotFound, b.get("$inbox/${conversation.id}/messages").status)
        assertEquals(HttpStatusCode.NotFound, b.get("$inbox/${conversation.id}/updates").status)
        assertEquals(HttpStatusCode.NotFound, b.post("$inbox/${conversation.id}/read").status)
        assertEquals(HttpStatusCode.NotFound, b.patch("$inbox/${conversation.id}/auto-reply") { put("enabled", false) }.status)
        b.post("$inbox/${conversation.id}/messages") { put("text", "Olá") }.let {
            assertEquals(HttpStatusCode.NotFound, it.status)
            assertEquals("not_found", it.error)
        }
        assertEquals(HttpStatusCode.NotFound, b.patch("$contacts/${user.id}/status") { put("status", "BLOCKED") }.status)

        val row = a.get(inbox).array.single().jsonObject
        assertEquals(1, row["unreadCount"]!!.jsonPrimitive.int, "b reading it didn't mark it read")
        assertEquals(null, row["autoReplyEnabled"], "b didn't pause the AI (true is the default, so it isn't sent)")
        assertEquals("ACTIVE", a.get(contacts).array.single().jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the inbox and contacts answer stable error codes`() = dashboardApiTest(mongo) {
        val a = company()
        val (user, conversation) = customer(a)
        a.patch("$contacts/nope/status") { put("status", "BLOCKED") }.let {
            assertEquals(HttpStatusCode.BadRequest, it.status)
            assertEquals("invalid_id", it.error)
            assertEquals("id", it.detail)
        }
        a.patch("$contacts/${user.id}/status") { put("status", "blocked") }.let {
            assertEquals(HttpStatusCode.BadRequest, it.status)
            assertEquals("invalid_status", it.error)
            assertEquals("status", it.detail)
        }
        assertEquals("invalid_body", a.sendMalformed("PATCH", "$contacts/${user.id}/status").error)
        assertEquals("invalid_body", a.patch("$contacts/${user.id}/status").error, "status is required")
        assertEquals(HttpStatusCode.NotFound, a.patch("$contacts/${ObjectId()}/status") { put("status", "BLOCKED") }.status)
        assertEquals("BLOCKED", a.patch("$contacts/${user.id}/status") { put("status", "BLOCKED") }.text("status"))

        assertEquals("invalid_id", a.get("$inbox/nope/messages").error)
        a.post("$inbox/nope/read").let {
            assertEquals(HttpStatusCode.BadRequest, it.status)
            assertEquals("not_found", it.error)
        }
        assertEquals("invalid conversation id", a.patch("$inbox/nope/auto-reply") { put("enabled", false) }.error)
        assertEquals("invalid_body", a.sendMalformed("PATCH", "$inbox/${conversation.id}/auto-reply").error)
        assertEquals("invalid_text", a.post("$inbox/${conversation.id}/messages") { put("text", "  ") }.error)
        a.post("$inbox/${conversation.id}/messages") { put("text", "Olá") }.let {
            assertEquals(HttpStatusCode.Conflict, it.status)
            assertEquals("no_channel", it.error)
        }
        assertEquals("no_channel", a.get("/app/api/whatsapp/templates").error)
        assertEquals(HttpStatusCode.NotFound, a.get("$inbox/${ObjectId()}/messages").status)
        assertEquals("Bom dia", a.get("$inbox/${conversation.id}/messages").array.single().jsonObject["text"]!!.jsonPrimitive.content)
    }
}
