package com.rfm.edubot.dashboard

import at.favre.lib.crypto.bcrypt.BCrypt
import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.ai.TenantUsageRepository
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.flows.BoardPoint
import com.rfm.edubot.flows.ChatFlow
import com.rfm.edubot.flows.ChatFlowLimits
import com.rfm.edubot.flows.ChatFlowRepository
import com.rfm.edubot.flows.ChatFlows
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.tenant.TenantPipelineFactory
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.FakeModel
import com.rfm.edubot.testing.TestMongo
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/**
 * The Chatbot page's API over HTTP with the real auth, routes and MongoDB: flows are read by everyone with the module,
 * saved by admins as the customers will get them, refused with what is wrong and where, and tried in a dry run that
 * writes nothing.
 */
class ChatbotRoutesTest {
    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("chatbot_routes")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() = mongo.shutdown()

        private const val PASSWORD = "correct horse"
        private const val SECRET = "test-secret"
    }

    private val now = Clock.System.now()
    private val users get() = DashboardUserRepository(mongo)
    private val tenants get() = TenantRepository(mongo)
    private val json = Json { ignoreUnknownKeys = true }
    private val runtime = RuntimeConfig(
        AppConfig(
            port = 8080,
            whatsapp = AppConfig.WhatsAppConfig(verifyToken = "v", appSecret = "s", phoneNumberId = "1", accessToken = "t"),
            instagram = AppConfig.InstagramConfig(appId = "ig", appSecret = "s", redirectUri = "https://example.com/cb"),
            openrouter = AppConfig.OpenRouterConfig(apiKey = "k", primaryModel = "a", fallbackModel = "b", maxTokens = 512),
            mongo = AppConfig.MongoConfig(uri = "unused", database = "unused"),
            rateLimit = AppConfig.RateLimitConfig(),
            admin = AppConfig.AdminConfig(jwtSecret = SECRET),
            pdfStoragePath = "/tmp/pdfs",
        ),
    )

    private inner class Env(val http: HttpClient, val model: FakeModel) {
        suspend fun send(method: String, path: String, token: String, body: JsonObject? = null, raw: String? = null): HttpResponse {
            val request: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                bearerAuth(token)
                if (body != null || raw != null) {
                    contentType(ContentType.Application.Json)
                    setBody(raw ?: body.toString())
                }
            }
            return when (method) {
                "GET" -> http.get(path, request)
                "PUT" -> http.put(path, request)
                else -> http.post(path, request)
            }
        }

        suspend fun token(email: String): String =
            http.post("/app/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("email", email); put("password", PASSWORD) }.toString())
            }.obj().text("token")!!
    }

    private fun chatbotTest(block: suspend Env.() -> Unit): Unit = testApplication {
        val model = FakeModel { FakeModel.text("""{"decision":"REPEAT"}""") }
        application {
            configureSerialization()
            configureAdminAuth(runtime, tenants, users)
            routing {
                dashboardAccountRoutes(tenants, users, runtime)
                dashboardRoutes(
                    mongo = mongo, tenantRepository = tenants, dashboardUsers = users, pipelineFactory = mockk<TenantPipelineFactory>(relaxed = true),
                    personaCompiler = mockk(relaxed = true), aiClient = model.client, runtimeConfig = runtime,
                    channelBindingService = mockk(relaxed = true), instagramSocial = mockk(relaxed = true),
                )
            }
        }
        Env(client, model).block()
    }

    private inner class Company(modules: List<String>? = null, channels: List<Platform> = emptyList(), budget: Long = 2_000_000L) {
        val id = ObjectId()
        val tenant = Tenant(
            id = id, slug = "t-${id.toHexString()}", name = "Clínica Sorriso", openrouterModel = "tenant/model", timezone = "Europe/Lisbon",
            channels = channels.map { ChannelBinding(it, "${it.name.lowercase()}-${id.toHexString()}") },
            enabledModules = modules, monthlyTokenBudget = budget, createdAt = now, updatedAt = now,
        )
        lateinit var admin: DashboardUser
        lateinit var member: DashboardUser

        suspend fun create(): Company {
            tenants.create(tenant)
            admin = newUser(DashboardUserRole.TENANT_ADMIN)
            member = newUser(DashboardUserRole.TENANT_MEMBER)
            return this
        }

        private suspend fun newUser(role: DashboardUserRole): DashboardUser {
            val userId = ObjectId()
            return users.create(
                DashboardUser(
                    id = userId, tenantId = id, email = "${role.name.lowercase()}-${userId.toHexString()}@sorriso.test",
                    passwordHash = BCrypt.withDefaults().hashToString(4, PASSWORD.toCharArray()), role = role, createdAt = now,
                ),
            )
        }
    }

    private suspend fun company(modules: List<String>? = null, channels: List<Platform> = emptyList(), budget: Long = 2_000_000L) = Company(modules, channels, budget).create()

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.content?.takeUnless { this[name].toString() == "null" }
    private fun JsonObject.int(name: String): Int = this[name]!!.jsonPrimitive.content.toInt()
    private fun JsonObject.bool(name: String): Boolean = this[name]!!.jsonPrimitive.content.toBoolean()
    private fun JsonObject.obj(name: String): JsonObject = this[name]!!.jsonObject
    private fun JsonObject.array(name: String): JsonArray = this[name]!!.jsonArray
    private fun JsonObject.strings(name: String): List<String> = array(name).map { it.jsonPrimitive.content }

    private fun option(title: String, next: String, vararg words: String) = buildJsonObject {
        put("title", title); put("next", next)
        putJsonArray("words") { words.forEach { add(it) } }
    }

    private fun step(
        id: String, text: String = "", kind: String = "MESSAGE", options: List<JsonObject> = emptyList(), next: String = "", key: String = "", expect: String = "TEXT",
        check: String = "HOURS", hours: List<JsonObject> = emptyList(), whenTrue: String = "", whenFalse: String = "", action: String = "NOTIFY_TEAM", failNext: String = "",
        show: String = "AUTO", retryText: String = "", at: Pair<Int, Int>? = null,
    ) = buildJsonObject {
        put("id", id); put("kind", kind); put("text", text)
        putJsonArray("options") { options.forEach { add(it) } }
        put("show", show); put("listLabel", ""); put("retryText", retryText); put("next", next); put("key", key); put("expect", expect)
        put("check", check)
        putJsonArray("hours") { hours.forEach { add(it) } }
        put("answerKey", ""); put("answerIs", ""); put("whenTrue", whenTrue); put("whenFalse", whenFalse); put("action", action); put("serviceId", ""); put("failNext", failNext)
        if (at != null) { put("x", at.first); put("y", at.second) }
    }

    private fun flow(
        id: String = "marcar", name: String = "Marcações", keywords: List<String> = listOf("Olá"), channels: List<String> = listOf("WHATSAPP", "WEB"),
        steps: List<JsonObject> = listOf(step("menu", "Qual serviço?", options = listOf(option("Unhas", "fim", "manicure"))), step("fim", "Fale connosco")),
        aiReads: Boolean = false, startAt: Pair<Int, Int>? = null,
    ) = buildJsonObject {
        put("id", id); put("name", name); put("enabled", true)
        putJsonArray("channels") { channels.forEach { add(it) } }
        putJsonArray("keywords") { keywords.forEach { add(it) } }
        putJsonArray("steps") { steps.forEach { add(it) } }
        put("aiReads", aiReads); put("afterMisses", "HAND_OVER"); put("handoverText", "")
        if (startAt != null) { put("startX", startAt.first); put("startY", startAt.second) }
    }

    private fun flows(vararg flows: JsonObject) = buildJsonObject { putJsonArray("flows") { flows.forEach { add(it) } } }

    private fun turn(text: String, state: JsonObject? = null, flows: JsonObject? = null, channel: String = "WHATSAPP", payload: String? = null, knownClient: Boolean = false, at: String? = null) = buildJsonObject {
        put("text", text); put("channel", channel); put("knownClient", knownClient)
        if (payload != null) put("payload", payload)
        if (state != null) put("state", state)
        if (flows != null) put("flows", flows.array("flows"))
        if (at != null) put("at", at)
    }

    @Test
    fun `a company without the persona module gets 403 on the chatbot endpoints`() = chatbotTest {
        val c = company(modules = listOf(DashboardModules.CONVERSATIONS))
        val token = token(c.admin.email)
        for ((method, path) in listOf("GET" to "/app/api/chatbot/flows", "PUT" to "/app/api/chatbot/flows", "POST" to "/app/api/chatbot/test")) {
            assertEquals(HttpStatusCode.Forbidden, send(method, path, token, buildJsonObject { }).status, "$method $path")
        }
    }

    @Test
    fun `flows start empty, members only read them, and admins save them the way customers get them`() = chatbotTest {
        val c = company(channels = listOf(Platform.WHATSAPP))
        val admin = token(c.admin.email)
        val empty = send("GET", "/app/api/chatbot/flows", admin).obj()
        assertEquals(0, empty.array("flows").size)
        assertEquals(true, empty.bool("canEdit"))
        assertEquals(listOf("WHATSAPP"), empty.strings("connected"))
        assertEquals(ChatFlowLimits.MAX_STEPS, empty.obj("limits").int("steps"))
        assertEquals(true, empty.bool("clientsModule"))
        assertNull(empty.text("updatedAt"))

        val member = token(c.member.email)
        assertEquals(false, send("GET", "/app/api/chatbot/flows", member).obj().bool("canEdit"))
        val refused = send("PUT", "/app/api/chatbot/flows", member, flows(flow()))
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("not_allowed", refused.obj().text("error"))
        assertNull(ChatFlowRepository(mongo, c.id).load())

        val saved = send("PUT", "/app/api/chatbot/flows", admin, flows(flow(name = "  Marcações ", keywords = listOf(" Olá ", "", "ola", "?!", "Bom dia")))).obj()
        val one = saved.array("flows").single().jsonObject
        assertEquals("Marcações", one.text("name"))
        assertEquals(listOf("Olá", "Bom dia"), one.strings("keywords"), "trimmed, without blanks or repeats")
        assertEquals(listOf("manicure"), one.array("steps")[0].jsonObject.array("options")[0].jsonObject.strings("words"))
        assertEquals(c.admin.email, saved.text("updatedBy"))
        assertEquals(setOf(Platform.WHATSAPP, Platform.WEB), ChatFlowRepository(mongo, c.id).find()!!.flows.single().channels)
        assertEquals("Marcações", send("GET", "/app/api/chatbot/flows", member).obj().array("flows").single().jsonObject.text("name"))
    }

    @Test
    fun `saving the flows leaves the AI switch and start words alone`() = chatbotTest {
        val c = company()
        val admin = token(c.admin.email)
        send("PUT", "/app/api/persona/channels", admin, buildJsonObject {
            putJsonArray("teamOnlyChannels") { add("WHATSAPP") }
            putJsonArray("startGates") { addJsonObject { put("channel", "WHATSAPP"); put("enabled", true); putJsonArray("words") { add("oi") } } }
        })
        val saved = send("PUT", "/app/api/chatbot/flows", admin, flows(flow())).obj()
        assertEquals(listOf("WHATSAPP"), saved.strings("teamOnlyChannels"))
        val stored = ChatFlowRepository(mongo, c.id).load()!!
        assertEquals(setOf(Platform.WHATSAPP), stored.teamOnlyChannels)
        assertEquals(listOf("oi"), stored.startGates[Platform.WHATSAPP]?.words)

        val again = send("PUT", "/app/api/persona/channels", admin, buildJsonObject { putJsonArray("teamOnlyChannels") { } })
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(1, ChatFlowRepository(mongo, c.id).load()!!.flows.size, "saving the channels leaves the flows")
    }

    @Test
    fun `where the cards sit on the board is saved with the flows, and a flow nobody arranged has no places`() = chatbotTest {
        val c = company()
        val token = token(c.admin.email)
        val unplaced = send("PUT", "/app/api/chatbot/flows", token, flows(flow())).obj().array("flows").single().jsonObject
        assertNull(unplaced["startX"], "left out while the board lays the flow out itself")
        assertNull(unplaced.array("steps")[0].jsonObject["x"])

        val arranged = flow(
            startAt = 0 to 48,
            steps = listOf(step("menu", "Qual serviço?", options = listOf(option("Unhas", "fim")), at = 320 to -40), step("fim", "Fale connosco", at = 9_000_000 to 16)),
        )
        val saved = send("PUT", "/app/api/chatbot/flows", token, flows(arranged)).obj().array("flows").single().jsonObject
        assertEquals(0 to 48, saved.int("startX") to saved.int("startY"))
        assertEquals(listOf(320 to -40, BoardPoint.LIMIT to 16), saved.array("steps").map { it.jsonObject.int("x") to it.jsonObject.int("y") }, "a place past the board's edge is pulled back")
        assertEquals(BoardPoint(320, -40), ChatFlowRepository(mongo, c.id).find()!!.flows.single().steps.first().at)
    }

    @Test
    fun `bookable services are offered to the editor only when the company has bookings`() = chatbotTest {
        val withBookings = company(modules = listOf(DashboardModules.PERSONA, DashboardModules.BOOKINGS))
        val without = company(modules = listOf(DashboardModules.PERSONA))
        assertEquals(true, send("GET", "/app/api/chatbot/flows", token(withBookings.admin.email)).obj().bool("bookingsModule"))
        val plain = send("GET", "/app/api/chatbot/flows", token(without.admin.email)).obj()
        assertEquals(false, plain.bool("bookingsModule"))
        assertEquals(false, plain.bool("clientsModule"))
        assertEquals(0, plain.array("services").size)
    }

    @Test
    fun `flows come with what each one sent in the last 30 days, per step and per customer`() = chatbotTest {
        val c = company()
        val other = company()
        val token = token(c.admin.email)
        send("PUT", "/app/api/chatbot/flows", token, flows(flow()))
        val ana = ObjectId()
        val rui = ObjectId()
        suspend fun sent(tenant: ObjectId, conversation: ObjectId, origin: String?, daysAgo: Int = 0, status: String = "DELIVERED") {
            mongo.database.getCollection<Document>("messages").insertOne(
                Document("tenantId", tenant).append("conversationId", conversation).append("role", "ASSISTANT").append("status", status)
                    .append("origin", origin).append("createdAt", Date((now - daysAgo.days).toEpochMilliseconds())),
            )
        }
        sent(c.id, ana, "flow:marcar:menu")
        sent(c.id, ana, "flow:marcar:fim")
        sent(c.id, rui, "flow:marcar:menu", status = "FAILED")
        sent(c.id, rui, "flow:marcar:menu", daysAgo = 31)
        sent(c.id, ana, "flow:removed:menu")
        sent(other.id, ana, "flow:marcar:menu")

        val usage = send("GET", "/app/api/chatbot/flows", token).obj().obj("usage")
        assertEquals(30, usage.int("days"))
        val marcar = usage.array("flows").single().jsonObject
        assertEquals(Triple(2, 1, 1), Triple(marcar.int("sent"), marcar.int("failed"), marcar.int("customers")), "a refused message is counted apart, and its customer isn't one who got it")
        assertEquals(mapOf("menu" to 1, "fim" to 1), marcar.obj("steps").mapValues { it.value.jsonPrimitive.content.toInt() })
    }

    @Test
    fun `a flow that can't run is refused with what is wrong and where, and nothing is saved`() = chatbotTest {
        val c = company()
        val token = token(c.admin.email)
        suspend fun refused(body: JsonObject): JsonObject {
            val response = send("PUT", "/app/api/chatbot/flows", token, body)
            assertEquals(HttpStatusCode.BadRequest, response.status)
            return response.obj().also { assertEquals("flow_problem", it.text("error")) }
        }

        val noKeywords = refused(flows(flow(keywords = listOf("?!"))))
        assertEquals("keywords" to 0, noKeywords.text("problem") to noKeywords.int("flow"))
        val leadsNowhere = refused(flows(flow(), flow(id = "outro", steps = listOf(step("menu", "Qual?", options = listOf(option("Unhas", "nowhere")))))))
        assertEquals(Triple("next", 1, 0), Triple(leadsNowhere.text("problem"), leadsNowhere.int("flow"), leadsNowhere.int("step")))
        val noKey = refused(flows(flow(steps = listOf(step("q", "Nome?", kind = "ASK", next = "", key = "Nome!")))))
        assertEquals("key", noKey.text("problem"))
        val noHours = refused(flows(flow(steps = listOf(step("c", kind = "CONDITION", check = "HOURS", whenTrue = "c", whenFalse = "c")))))
        assertEquals("hours", noHours.text("problem"))
        val loop = refused(flows(flow(steps = listOf(step("a", "a", next = "b"), step("b", "b", next = "a")))))
        assertEquals("loop", loop.text("problem"))
        assertEquals("invalid_request", send("PUT", "/app/api/chatbot/flows", token, buildJsonObject { put("flows", 3) }).obj().text("error"))
        assertNull(ChatFlowRepository(mongo, c.id).load())
    }

    @Test
    fun `the test chat runs a flow turn by turn, keeping the customer's place in the state it hands back`() = chatbotTest {
        val c = company()
        val token = token(c.admin.email)
        send("PUT", "/app/api/chatbot/flows", token, flows(flow()))

        val opened = send("POST", "/app/api/chatbot/test", token, turn("Olá, bom dia")).obj()
        assertEquals(true, opened.bool("handled"))
        assertEquals("Marcações", opened.text("flow"))
        val message = opened.array("messages").single().jsonObject
        assertEquals("Qual serviço?", message.text("text"))
        assertEquals(listOf("Unhas" to "flow:marcar:fim:0"), message.array("replies").map { it.jsonObject.text("title") to it.jsonObject.text("payload") }, "WhatsApp gets taps")
        val state = opened.obj("state")
        assertEquals("menu", state.text("stepId"))
        assertEquals("marcar" to listOf("menu"), opened.text("flowId") to opened.strings("path"), "the board lights up the way the customer took")

        val typed = send("POST", "/app/api/chatbot/test", token, turn("manicure", state = state)).obj()
        assertEquals("Fale connosco", typed.array("messages").single().jsonObject.text("text"))
        assertNull(typed["state"]!!.takeUnless { it.toString() == "null" }, "the flow ended")
        assertEquals(listOf("fim"), typed.strings("path"))

        val web = send("POST", "/app/api/chatbot/test", token, turn("olá", channel = "WEB")).obj()
        assertEquals("Qual serviço?\n\n1. Unhas", web.array("messages").single().jsonObject.text("text"), "the website has no taps")

        val none = send("POST", "/app/api/chatbot/test", token, turn("Têm vaga no sábado?")).obj()
        assertEquals(false, none.bool("handled"), "no flow answers: the AI would")
        assertEquals(true, none.bool("aiAnswers"))
        assertEquals(0, model.requests.size, "a fixed flow never calls the model")
    }

    @Test
    fun `the test chat tries unsaved flows, pretends the time and a known client, and writes nothing`() = chatbotTest {
        val c = company()
        val token = token(c.admin.email)
        val hours = listOf(buildJsonObject { put("day", 1); put("from", "09:00"); put("to", "18:00") })
        val draft = flows(
            flow(
                keywords = listOf("horário"),
                steps = listOf(
                    step("gate", kind = "CONDITION", check = "HOURS", hours = hours, whenTrue = "open", whenFalse = "closed"),
                    step("open", "Estamos abertos."),
                    step("closed", "Estamos fechados."),
                ),
            ),
            flow(
                id = "cliente", keywords = listOf("conta"),
                steps = listOf(
                    step("who", kind = "CONDITION", check = "KNOWN_CLIENT", whenTrue = "back", whenFalse = "new"),
                    step("back", "Bem-vinda de volta."),
                    step("new", "Prazer!"),
                ),
            ),
        )
        fun text(response: JsonObject) = response.array("messages").map { it.jsonObject.text("text") }

        assertEquals(listOf("Estamos abertos."), text(send("POST", "/app/api/chatbot/test", token, turn("horário", flows = draft, at = "2026-11-16T10:00")).obj()), "a Monday morning")
        assertEquals(listOf("Estamos fechados."), text(send("POST", "/app/api/chatbot/test", token, turn("horário", flows = draft, at = "2026-11-17T10:00")).obj()), "a Tuesday")
        assertEquals(listOf("Prazer!"), text(send("POST", "/app/api/chatbot/test", token, turn("conta", flows = draft)).obj()))
        assertEquals(listOf("Bem-vinda de volta."), text(send("POST", "/app/api/chatbot/test", token, turn("conta", flows = draft, knownClient = true)).obj()))
        assertNull(ChatFlowRepository(mongo, c.id).load(), "trying flows saves nothing")

        val broken = send("POST", "/app/api/chatbot/test", token, turn("olá", flows = flows(flow(keywords = listOf("?!")))))
        assertEquals(HttpStatusCode.BadRequest, broken.status)
        assertEquals("keywords", broken.obj().text("problem"))
    }

    @Test
    fun `the test chat shows what the actions would do without doing it`() = chatbotTest {
        val c = company()
        val token = token(c.admin.email)
        val draft = flows(
            flow(
                keywords = listOf("guardar"),
                steps = listOf(
                    step("name", "Nome?", kind = "ASK", key = "name", expect = "NAME", next = "save"),
                    step("save", "Guardado, {name}.", kind = "ACTION", action = "CREATE_CLIENT", next = ""),
                ),
            ),
        )
        val first = send("POST", "/app/api/chatbot/test", token, turn("guardar", flows = draft)).obj()
        val done = send("POST", "/app/api/chatbot/test", token, turn("Sou a Rita Lopes", flows = draft, state = first.obj("state"))).obj()

        assertEquals("Guardado, Rita Lopes.", done.array("messages").single().jsonObject.text("text"))
        assertEquals(1, done.array("effects").size, done.toString())
        val effect = done.array("effects").single().jsonObject
        assertEquals("CREATE_CLIENT", effect.text("action"))
        assertEquals(true, effect.bool("ok"))
        assertTrue("Rita Lopes" in effect.text("detail")!!)
        assertEquals(0L, mongo.database.getCollection<Document>("crm.clients").countDocuments(Document("tenantId", c.id)), "no client was created")
    }

    @Test
    fun `the test chat reads with the AI only when the flow asks, counts the tokens, and stops at the budget`() = chatbotTest {
        val c = company()
        val token = token(c.admin.email)
        model.answer = { FakeModel.text("""{"decision":"ACCEPT","option":1}""", prompt = 50, completion = 5) }
        val draft = flows(flow(aiReads = true))
        val first = send("POST", "/app/api/chatbot/test", token, turn("olá", flows = draft)).obj()

        val read = send("POST", "/app/api/chatbot/test", token, turn("o primeiro, por favor", flows = draft, state = first.obj("state"))).obj()
        assertEquals("Fale connosco", read.array("messages").single().jsonObject.text("text"))
        assertEquals(55L, TenantUsageRepository(mongo, c.id).tokensBySourceThisMonth()["chatbot"])

        val broke = company(budget = 1L)
        val brokeToken = token(broke.admin.email)
        TenantUsageRepository(mongo, broke.id).recordUsage(10L)
        val calls = model.requests.size
        val brokeFirst = send("POST", "/app/api/chatbot/test", brokeToken, turn("olá", flows = draft)).obj()
        val miss = send("POST", "/app/api/chatbot/test", brokeToken, turn("o primeiro, por favor", flows = draft, state = brokeFirst.obj("state"))).obj()
        assertEquals("menu", miss.obj("state").text("stepId"), "out of tokens, the reply is just not understood")
        assertEquals(calls, model.requests.size)
    }

    @Test
    fun `the AI switch and start words are read and saved in Persona channels, and members only read`() = chatbotTest {
        val c = company(channels = listOf(Platform.INSTAGRAM))
        val admin = token(c.admin.email)
        val read = send("GET", "/app/api/persona/channels", admin).obj()
        assertEquals(true, read.bool("instagramConnected"))
        assertEquals(0, read.int("flowsOn"))
        send("PUT", "/app/api/chatbot/flows", admin, flows(flow()))
        assertEquals(1, send("GET", "/app/api/persona/channels", admin).obj().int("flowsOn"))
        val refused = send("PUT", "/app/api/persona/channels", token(c.member.email), buildJsonObject { putJsonArray("teamOnlyChannels") { } })
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertFalse(ChatFlowRepository(mongo, c.id).load()!!.teamOnlyChannels.isNotEmpty())
        assertNotNull(ChatFlows(listOf<ChatFlow>()))
    }
}
