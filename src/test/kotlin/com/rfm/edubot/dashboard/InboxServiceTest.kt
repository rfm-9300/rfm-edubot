package com.rfm.edubot.dashboard

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.conversation.model.MessageAuthor
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.MessageStatus
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.whatsapp.TemplateDraft
import com.rfm.edubot.whatsapp.WhatsAppClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Testcontainers
class InboxServiceTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "inbox"))
            mongoModule.initialize()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }
    }

    private val agent = InboxService.Agent(userId = "user-1", name = "ana@example.com")
    private val sends = mutableListOf<JsonObject>()
    private var metaReply: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        json("""{"contacts":[{"wa_id":"351900000001"}],"messages":[{"id":"wamid.${sends.size}"}]}""")
    }

    private var templatesPage = """{"data":[{"name":"order_update","language":"pt_PT","status":"APPROVED","category":"UTILITY",
        "components":[{"type":"BODY","text":"Olá {{1}}, a sua encomenda está pronta."}]}]}"""
    private var templateFetches = 0
    private val templateSubmissions = mutableListOf<JsonObject>()

    private fun inbox() = InboxService(
        mongoModule,
        whatsApp = {
            WhatsAppClient("token", "pn-1", maxRetries = 1, httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val path = request.url.encodedPath
                        when {
                            path.endsWith("/message_templates") && request.method == HttpMethod.Get -> {
                                templateFetches += 1
                                json(templatesPage)
                            }
                            path.endsWith("/message_templates") && request.method == HttpMethod.Post -> {
                                templateSubmissions.add(Json.parseToJsonElement((request.body as TextContent).text).jsonObject)
                                json("""{"id":"tpl-new","status":"PENDING","category":"UTILITY"}""")
                            }
                            path == "/v21.0/media-1" -> json("""{"url":"https://lookaside.fbsbx.com/m1","mime_type":"image/jpeg","file_size":2}""")
                            request.url.host == "lookaside.fbsbx.com" ->
                                respond(content = byteArrayOf(9, 9), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
                            else -> {
                                sends.add(Json.parseToJsonElement((request.body as TextContent).text).jsonObject)
                                metaReply(request)
                            }
                        }
                    }
                }
            })
        },
        outbound = { _, _ -> error("only WhatsApp is used here") },
    )

    private fun tenant(wabaId: String? = "waba-1") = Clock.System.now().let { now ->
        Tenant(
            slug = "clean-${ObjectId()}",
            name = "Clean Co",
            channels = listOf(ChannelBinding(Platform.WHATSAPP, "pn-1", "token", wabaId = wabaId)),
            createdAt = now,
            updatedAt = now,
        )
    }

    /** A conversation whose customer last wrote [ago] ago, or never when null. */
    private suspend fun conversation(tenant: Tenant, ago: kotlin.time.Duration?): Conversation {
        val user = UserRepository(mongoModule, tenant.id).findOrCreate("351900000001")
        val conversations = ConversationRepository(mongoModule, tenant.id)
        val conversation = conversations.findOrCreate(user.id, user.waId)
        if (ago != null) conversations.recordInbound(conversation.id, Clock.System.now() - ago)
        return conversations.findById(conversation.id)!!
    }

    @Test
    fun `a reply inside the 24-hour window is sent, tracked, and pauses the AI`() = runBlocking {
        val tenant = tenant()
        val conversation = conversation(tenant, 10.minutes)

        val message = inbox().sendText(tenant, conversation, "  Olá Ana, já tratamos disso.  ", agent)

        assertEquals("Olá Ana, já tratamos disso.", sends.single()["text"]!!.jsonObject["body"]!!.jsonPrimitive.content)
        assertEquals("351900000001", sends.single()["to"]!!.jsonPrimitive.content)
        assertEquals(MessageStatus.SENT, message.status)
        assertEquals("wamid.1", message.waMessageId)
        assertEquals(MessageAuthor.AGENT, message.author)
        assertEquals("ana@example.com", message.agentName)
        val updated = ConversationRepository(mongoModule, tenant.id).findById(conversation.id)!!
        assertFalse(updated.autoReplyEnabled)
        assertEquals("ana@example.com", updated.autoReplyPausedBy)
        assertEquals(0, updated.unreadCount)
    }

    @Test
    fun `a free-form reply after 24 hours is refused before WhatsApp is called`() = runBlocking {
        val tenant = tenant()

        val late = assertFailsWith<InboxError> { inbox().sendText(tenant, conversation(tenant, 25.hours), "Olá", agent) }
        assertEquals("window_closed", late.key)
        assertTrue(sends.isEmpty())
    }

    @Test
    fun `a conversation the customer never wrote in has no open window`() = runBlocking {
        val tenant = tenant()

        val error = assertFailsWith<InboxError> { inbox().sendText(tenant, conversation(tenant, null), "Olá", agent) }

        assertEquals("window_closed", error.key)
    }

    @Test
    fun `a rejection from Meta comes back as a dashboard key and nothing is stored`() = runBlocking {
        val tenant = tenant()
        val conversation = conversation(tenant, 1.hours)
        metaReply = {
            json(
                """{"error":{"message":"Recipient phone number not in allowed list","code":131030,"error_data":{"details":"Add recipient phone number to recipient list"}}}""",
                HttpStatusCode.BadRequest,
            )
        }

        val error = assertFailsWith<InboxError> { inbox().sendText(tenant, conversation, "Olá", agent) }

        assertEquals("recipient_not_allowed", error.key)
        assertEquals("Add recipient phone number to recipient list", error.detail)
        assertTrue(MessageRepository(mongoModule, tenant.id).threadByConversation(conversation.id).isEmpty())
        assertTrue(ConversationRepository(mongoModule, tenant.id).findById(conversation.id)!!.autoReplyEnabled)
    }

    @Test
    fun `a template can be sent after the window closed, with its variables filled in`() = runBlocking {
        val tenant = tenant()
        val conversation = conversation(tenant, 3.days)
        val service = inbox()

        val message = service.sendTemplate(tenant, conversation, InboxService.TemplateRequest("order_update", "pt_PT", mapOf("1" to "Ana")), agent)

        assertEquals("template", sends.single()["type"]!!.jsonPrimitive.content)
        assertEquals(MessageContent.Template("order_update", "pt_PT", "Olá Ana, a sua encomenda está pronta."), message.content)
        assertEquals(MessageStatus.SENT, message.status)
        val missing = assertFailsWith<InboxError> {
            service.sendTemplate(tenant, conversation, InboxService.TemplateRequest("order_update", "pt_PT", emptyMap()), agent)
        }
        assertEquals("template_params", missing.key)
        assertEquals(1, sends.size)
    }

    @Test
    fun `a new conversation takes the WhatsApp id Meta resolved the number to`() = runBlocking {
        val tenant = tenant()
        metaReply = { json("""{"contacts":[{"input":"351912345678","wa_id":"351912345679"}],"messages":[{"id":"wamid.first"}]}""") }

        val (conversation, message) = inbox().startConversation(
            tenant, "+351 912 345 678", InboxService.TemplateRequest("order_update", "pt_PT", mapOf("1" to "Rui")), agent,
        )

        assertNotNull(UserRepository(mongoModule, tenant.id).findByWaId("351912345679"))
        assertEquals("351912345678", sends.single()["to"]!!.jsonPrimitive.content)
        assertEquals("351912345679", conversation.waId)
        assertEquals("wamid.first", message.waMessageId)
        assertFalse(conversation.autoReplyEnabled)
    }

    @Test
    fun `a number without enough digits is refused before anything is sent`() = runBlocking {
        val tenant = tenant()

        val error = assertFailsWith<InboxError> {
            inbox().startConversation(tenant, "12 34", InboxService.TemplateRequest("order_update", "pt_PT", mapOf("1" to "Rui")), agent)
        }

        assertEquals("invalid_phone", error.key)
        assertTrue(sends.isEmpty())
        assertEquals("351912345678", InboxService.normalizePhone("00351 912-345-678"))
    }

    @Test
    fun `a failed reply can be sent again`() = runBlocking {
        val tenant = tenant()
        val conversation = conversation(tenant, 1.hours)
        val service = inbox()
        val message = service.sendText(tenant, conversation, "Olá", agent)
        MessageRepository(mongoModule, tenant.id).applyDeliveryStatus(message.waMessageId!!, MessageStatus.FAILED, 131026, "Message undeliverable")

        val resent = service.retry(tenant, ConversationRepository(mongoModule, tenant.id).findById(conversation.id)!!, message.id)

        assertEquals(MessageStatus.SENT, resent.status)
        assertEquals("wamid.2", resent.waMessageId)
        assertEquals(2, sends.size)
    }

    @Test
    fun `only approved templates are offered for sending, all of them for managing`() = runBlocking {
        templatesPage = """{"data":[
            {"name":"order_update","language":"pt_PT","status":"APPROVED","components":[{"type":"BODY","text":"Pronto"}]},
            {"name":"spring_offer","language":"pt_PT","status":"PENDING","components":[{"type":"BODY","text":"Promoção"}]}]}"""
        val service = inbox()
        val tenant = tenant()

        assertEquals(listOf("order_update"), service.templates(tenant).map { it.name })
        assertEquals(listOf("order_update", "spring_offer"), service.allTemplates(tenant).map { it.name })
        val pending = assertFailsWith<InboxError> {
            service.sendTemplate(tenant, conversation(tenant, 1.hours), InboxService.TemplateRequest("spring_offer", "pt_PT", emptyMap()), agent)
        }
        assertEquals("template_not_found", pending.key)
    }

    @Test
    fun `a new template is checked before it reaches Meta, and the list is fetched again after`() = runBlocking {
        val service = inbox()
        val tenant = tenant()
        service.templates(tenant)
        val draft = TemplateDraft("order_ready", "pt_PT", "UTILITY", null, "Olá {{1}}, está pronto.", listOf("Ana"), null, emptyList())

        val invalid = assertFailsWith<InboxError> { service.createTemplate(tenant, draft.copy(body = "Olá {{2}}, está pronto.")) }
        val created = service.createTemplate(tenant, draft)
        service.templates(tenant)

        assertEquals("template_variables_invalid", invalid.key)
        assertEquals("PENDING", created.status)
        assertEquals(listOf("order_ready"), templateSubmissions.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(2, templateFetches)
    }

    @Test
    fun `media is served only for a message that belongs to the conversation`() = runBlocking {
        val tenant = tenant()
        val photoConversation = conversation(tenant, 1.hours)
        val other = ConversationRepository(mongoModule, tenant.id).findOrCreate(ObjectId(), "351900000099")
        val photo = MessageRepository(mongoModule, tenant.id).insert(
            com.rfm.edubot.conversation.model.Message(
                tenantId = tenant.id,
                conversationId = photoConversation.id,
                waId = photoConversation.waId,
                role = com.rfm.edubot.conversation.model.UserRole.USER,
                waMessageId = "wamid.photo-${ObjectId()}",
                content = MessageContent.Image("media-1", "A mancha"),
                createdAt = Clock.System.now(),
            ),
        )
        val service = inbox()

        val elsewhere = assertFailsWith<InboxError> { service.media(tenant, other, photo.id) }
        val (file, fileName) = service.media(tenant, photoConversation, photo.id)

        assertEquals("not_found", elsewhere.key)
        assertEquals("image/jpeg", file.mimeType)
        assertEquals(listOf<Byte>(9, 9), file.bytes.toList())
        assertEquals(null, fileName)
    }

    @Test
    fun `templates need the WhatsApp Business Account from Embedded Signup`() = runBlocking {
        val error = assertFailsWith<InboxError> { inbox().templates(tenant(wabaId = null)) }

        assertEquals("no_waba", error.key)
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}
