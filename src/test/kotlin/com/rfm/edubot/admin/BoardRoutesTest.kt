package com.rfm.edubot.admin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.rfm.edubot.admin.board.BoardService
import com.rfm.edubot.admin.board.boardRoutes
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.plugins.configureStatusPages
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
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
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The backoffice's boards over HTTP: keys and numbers, ordering, partial edits, columns, comments, tenants. */
@Testcontainers
class BoardRoutesTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "backoffice_board"))
            mongoModule.initialize()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }

        private const val JWT_SECRET = "test-secret"
        private const val ANA = "ana@thebots.test"
        private const val RUI = "rui@thebots.test"
    }

    private val base = AppConfig(
        port = 8080,
        whatsapp = AppConfig.WhatsAppConfig(verifyToken = "v", appSecret = "s", phoneNumberId = "1", accessToken = "t"),
        instagram = AppConfig.InstagramConfig(appId = "ig", appSecret = "s", redirectUri = "https://example.com/cb"),
        openrouter = AppConfig.OpenRouterConfig(apiKey = "k", primaryModel = "a", fallbackModel = "b", maxTokens = 512),
        mongo = AppConfig.MongoConfig(uri = "unused", database = "unused"),
        rateLimit = AppConfig.RateLimitConfig(),
        // A Google session's email must be on the allowlist, or the admin sign-in refuses it.
        admin = AppConfig.AdminConfig(
            jwtSecret = JWT_SECRET,
            googleSignIn = AppConfig.GoogleSignInConfig(firebaseProjectId = "project", webApiKey = "key", allowedEmails = setOf(ANA, RUI)),
        ),
        pdfStoragePath = "/tmp/pdfs",
    )
    private val runtime = RuntimeConfig(base)
    private val tenants = TenantRepository(mongoModule)
    private val json = Json { ignoreUnknownKeys = true }

    private fun boardTest(block: suspend (HttpClient) -> Unit): Unit = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            configureAdminAuth(runtime, tenants, DashboardUserRepository(mongoModule))
            routing { boardRoutes(BoardService.of(mongoModule, tenants)) }
        }
        block(client)
    }

    private fun token(email: String?): String = JWT.create()
        .withIssuer(base.admin.jwtIssuer)
        .withSubject("admin")
        .apply { email?.let { withClaim("email", it) } }
        .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
        .sign(Algorithm.HMAC256(JWT_SECRET))

    private suspend fun HttpClient.send(method: String, path: String, body: JsonElement? = null, email: String? = ANA): HttpResponse {
        val request: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            email?.let { bearerAuth(token(it)) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        return when (method) {
            "GET" -> get(path, request)
            "PATCH" -> patch(path, request)
            "PUT" -> put(path, request)
            "DELETE" -> delete(path, request)
            else -> post(path, request)
        }
    }

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.arr(): JsonArray = json.parseToJsonElement(bodyAsText()).jsonArray
    private suspend fun HttpResponse.error(): String? = obj()["error"]?.jsonPrimitive?.content
    private fun JsonElement.text(name: String): String? = (jsonObject[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    // An ObjectId ends in a counter, so its last five hex digits differ from one call to the next.
    private fun uniqueKey() = "B" + ObjectId().toHexString().takeLast(5).uppercase()

    /** A board with three columns; returns it. */
    private suspend fun HttpClient.board(key: String = uniqueKey()): JsonObject = send(
        "POST", "/admin/api/board/boards",
        buildJsonObject {
            put("name", "Operations")
            put("key", key.lowercase())
            putJsonArray("columns") {
                addJsonObject { put("name", "To do") }
                addJsonObject { put("name", "Doing") }
                addJsonObject { put("name", "Done"); put("done", true) }
            }
        },
    ).obj()

    private fun JsonObject.columnId(i: Int): String = this["columns"]!!.jsonArray[i].text("id")!!
    private fun JsonObject.id(): String = text("id")!!

    private suspend fun HttpClient.card(board: JsonObject, column: Int, title: String, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        send("POST", "/admin/api/board/boards/${board.id()}/cards", buildJsonObject { put("columnId", board.columnId(column)); put("title", title); extra() }).obj()

    private suspend fun HttpClient.titles(board: JsonObject, column: Int, archived: Boolean = false): List<String> =
        send("GET", "/admin/api/board/boards/${board.id()}/cards${if (archived) "?archived=true" else ""}").arr()
            .filter { it.text("columnId") == board.columnId(column) }
            .map { it.text("title")!! }

    @Test
    fun `the board needs a backoffice sign-in`() = boardTest { http ->
        assertEquals(HttpStatusCode.Unauthorized, http.send("GET", "/admin/api/board/boards", email = null).status)
    }

    @Test
    fun `a board takes its key once, and its cards are numbered from it in order`() = boardTest { http ->
        val key = uniqueKey()
        val board = http.board(key)
        assertEquals(key, board.text("key"), "keys are upper case")
        assertEquals(listOf("To do", "Doing", "Done"), board["columns"]!!.jsonArray.map { it.text("name") })
        assertEquals("true", board["columns"]!!.jsonArray[2].text("done"))
        assertEquals("key_taken", http.send("POST", "/admin/api/board/boards", buildJsonObject {
            put("name", "Other"); put("key", key); putJsonArray("columns") { addJsonObject { put("name", "A") } }
        }).error())
        for (bad in listOf("1AB", "A", "TOOLONGKEY", "A-B")) {
            val reply = http.send("POST", "/admin/api/board/boards", buildJsonObject {
                put("name", "Other"); put("key", bad); putJsonArray("columns") { addJsonObject { put("name", "A") } }
            })
            assertEquals("invalid_key", reply.error(), bad)
        }
        assertEquals("required", http.send("POST", "/admin/api/board/boards", buildJsonObject {
            put("name", "Other"); put("key", uniqueKey()); putJsonArray("columns") {}
        }).error())

        val first = http.card(board, 0, "Connect the new number")
        val second = http.card(board, 0, "Answer Meta's review", email = RUI)
        assertEquals(1, first["number"]!!.jsonPrimitive.int)
        assertEquals(2, second["number"]!!.jsonPrimitive.int)
        assertEquals(ANA, first.text("createdBy"))
        assertEquals(RUI, second.text("createdBy"))
        assertEquals("MEDIUM", first.text("priority"))
        val listed = http.send("GET", "/admin/api/board/boards").arr().single { it.text("id") == board.id() }
        assertEquals(2, listed.jsonObject["cardCount"]!!.jsonPrimitive.int)
        assertEquals("column_not_found", http.send("POST", "/admin/api/board/boards/${board.id()}/cards", buildJsonObject {
            put("columnId", "nope"); put("title", "x")
        }).error())
        assertEquals("required", http.send("POST", "/admin/api/board/boards/${board.id()}/cards", buildJsonObject {
            put("columnId", board.columnId(0)); put("title", "   ")
        }).error())
        assertEquals("invalid_id", http.send("GET", "/admin/api/board/boards/nope/cards").error())
        assertEquals("board_not_found", http.send("GET", "/admin/api/board/boards/${ObjectId().toHexString()}/cards").error())
    }

    private suspend fun HttpClient.card(board: JsonObject, column: Int, title: String, email: String): JsonObject =
        send("POST", "/admin/api/board/boards/${board.id()}/cards", buildJsonObject { put("columnId", board.columnId(column)); put("title", title) }, email).obj()

    @Test
    fun `cards move between and within columns and keep their order`() = boardTest { http ->
        val board = http.board()
        val a = http.card(board, 0, "A")
        val b = http.card(board, 0, "B")
        val c = http.card(board, 0, "C")
        val move: suspend (JsonObject, Int, Int) -> JsonObject = { card, column, index ->
            http.send("POST", "/admin/api/board/cards/${card.id()}/move", buildJsonObject { put("columnId", board.columnId(column)); put("index", index) }).obj()
        }
        move(c, 0, 0)
        assertEquals(listOf("C", "A", "B"), http.titles(board, 0))
        assertEquals(board.columnId(1), move(a, 1, 0).text("columnId"))
        assertEquals(listOf("C", "B"), http.titles(board, 0))
        assertEquals(listOf("A"), http.titles(board, 1))
        move(b, 1, 5)
        assertEquals(listOf("A", "B"), http.titles(board, 1), "an index past the end puts the card last")

        // Dropping cards again and again between the same two neighbours runs out of room between their positions.
        val d = http.card(board, 2, "D")
        http.card(board, 2, "E")
        val moved = (1..60).map { http.card(board, 0, "M$it") }
        moved.forEach { move(it, 2, 1) }
        val column = http.titles(board, 2)
        assertEquals("D", column.first())
        assertEquals("E", column.last())
        assertEquals(moved.reversed().map { it.text("title") }, column.drop(1).dropLast(1), "each card dropped right under D")
        assertEquals("column_not_found", http.send("POST", "/admin/api/board/cards/${d.id()}/move", buildJsonObject { put("columnId", "x"); put("index", 0) }).error())
    }

    @Test
    fun `a card's edit changes only the fields it sends, and null clears one`() = boardTest { http ->
        val board = http.board()
        val card = http.card(board, 0, "Renew the domain") {
            put("assignee", " Ana@TheBots.test ")
            put("dueDate", "2026-11-01")
            put("priority", "HIGH")
            putJsonArray("labels") { add(kotlinx.serialization.json.JsonPrimitive("ops")) }
        }
        assertEquals(ANA, card.text("assignee"), "emails are kept trimmed and in lower case")
        val path = "/admin/api/board/cards/${card.id()}"
        val renamed = http.send("PATCH", path, buildJsonObject { put("title", "Renew thebotslab.pt") }, RUI).obj()
        assertEquals("Renew thebotslab.pt", renamed.text("title"))
        assertEquals(ANA, renamed.text("assignee"), "fields not sent stay")
        assertEquals("2026-11-01", renamed.text("dueDate"))
        assertEquals(RUI, renamed.text("updatedBy"))

        val cleared = http.send("PATCH", path, buildJsonObject { put("assignee", JsonNull); put("dueDate", JsonNull) }).obj()
        assertNull(cleared.text("assignee"))
        assertNull(cleared.text("dueDate"))

        val tidy = http.send("PATCH", path, buildJsonObject {
            putJsonArray("labels") { listOf(" meta ", "Meta", "", "billing").forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            putJsonArray("checklist") {
                addJsonObject { put("text", "Pay the invoice"); put("done", true) }
                addJsonObject { put("id", "keep-me"); put("text", " Check DNS ") }
            }
        }).obj()
        assertEquals(listOf("meta", "billing"), tidy["labels"]!!.jsonArray.map { it.jsonPrimitive.content }, "trimmed, blank and repeated labels dropped")
        val items = tidy["checklist"]!!.jsonArray
        assertEquals(listOf("Pay the invoice", "Check DNS"), items.map { it.text("text") })
        assertEquals("true", items[0].text("done"))
        assertEquals("keep-me", items[1].text("id"))
        assertTrue(!items[0].text("id").isNullOrBlank(), "a new item gets an id")

        assertEquals("invalid_priority", http.send("PATCH", path, buildJsonObject { put("priority", "high") }).error())
        assertEquals("required", http.send("PATCH", path, buildJsonObject { put("title", " ") }).error())
        assertEquals("invalid_date", http.send("PATCH", path, buildJsonObject { put("dueDate", "01/11/2026") }).error())
        assertEquals("invalid_assignee", http.send("PATCH", path, buildJsonObject { put("assignee", "not an email") }).error())
        assertEquals("invalid_body", http.send("PATCH", path, buildJsonObject { put("title", 3) }).error())

        assertEquals("true", http.send("PATCH", path, buildJsonObject { put("archived", true) }).obj().text("archived"))
        assertEquals(emptyList(), http.titles(board, 0))
        assertEquals(listOf("Renew thebotslab.pt"), http.titles(board, 0, archived = true))
        assertEquals("false", http.send("PATCH", path, buildJsonObject { put("archived", false) }).obj().text("archived"))
        assertEquals(listOf("Renew thebotslab.pt"), http.titles(board, 0))
    }

    @Test
    fun `columns are renamed, reordered and added together, and one that holds cards can't go`() = boardTest { http ->
        val board = http.board()
        val todo = board.columnId(0)
        val doing = board.columnId(1)
        val done = board.columnId(2)
        val card = http.card(board, 1, "Busy")
        val path = "/admin/api/board/boards/${board.id()}/columns"
        val reordered = http.send("PUT", path, buildJsonObject {
            putJsonArray("columns") {
                addJsonObject { put("id", doing); put("name", "In progress") }
                addJsonObject { put("id", todo); put("name", "Backlog") }
                addJsonObject { put("name", "Review") }
                addJsonObject { put("id", done); put("name", "Done"); put("done", true) }
            }
        }).obj()
        val columns = reordered["columns"]!!.jsonArray
        assertEquals(listOf("In progress", "Backlog", "Review", "Done"), columns.map { it.text("name") })
        assertEquals(listOf(doing, todo), columns.take(2).map { it.text("id") }, "existing columns keep their ids")
        assertTrue(columns[2].text("id") !in setOf(todo, doing, done))

        val withoutDoing = buildJsonObject {
            putJsonArray("columns") { addJsonObject { put("id", todo); put("name", "Backlog") } }
        }
        val refused = http.send("PUT", path, withoutDoing)
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("column_not_empty", refused.error())
        assertEquals(doing, refused.obj().text("detail"))

        http.send("PATCH", "/admin/api/board/cards/${card.id()}", buildJsonObject { put("archived", true) })
        assertEquals(HttpStatusCode.OK, http.send("PUT", path, withoutDoing).status, "archived cards don't hold a column")
        val restored = http.send("PATCH", "/admin/api/board/cards/${card.id()}", buildJsonObject { put("archived", false) }).obj()
        assertEquals(todo, restored.text("columnId"), "its archived card went to the first column left")
    }

    @Test
    fun `comments count on their card, and only their author deletes them`() = boardTest { http ->
        val board = http.board()
        val card = http.card(board, 0, "Discuss pricing")
        val comment = http.send("POST", "/admin/api/board/cards/${card.id()}/comments", buildJsonObject { put("body", "  Let's keep 3 tiers.  ") }).obj()
        assertEquals("Let's keep 3 tiers.", comment.text("body"))
        assertEquals(ANA, comment.text("author"))
        val detail = http.send("GET", "/admin/api/board/cards/${card.id()}").obj()
        assertEquals(1, detail["card"]!!.jsonObject["commentCount"]!!.jsonPrimitive.int)
        assertEquals(listOf("Let's keep 3 tiers."), detail["comments"]!!.jsonArray.map { it.text("body") })
        assertEquals(board.text("key"), detail["board"]!!.text("key"))

        assertEquals("required", http.send("POST", "/admin/api/board/cards/${card.id()}/comments", buildJsonObject { put("body", " ") }).error())
        val path = "/admin/api/board/comments/${comment.id()}"
        assertEquals("not_author", http.send("DELETE", path, email = RUI).error())
        assertEquals(HttpStatusCode.OK, http.send("DELETE", path).status)
        assertEquals(0, http.send("GET", "/admin/api/board/cards/${card.id()}").obj()["card"]!!.jsonObject["commentCount"]!!.jsonPrimitive.int)
        assertEquals("comment_not_found", http.send("DELETE", path).error())
    }

    @Test
    fun `a card links to a tenant, whose record lists it`() = boardTest { http ->
        val now = Clock.System.now()
        val id = ObjectId()
        val tenant = tenants.create(Tenant(id = id, slug = "t-${id.toHexString()}", name = "Padaria Central", channels = emptyList(), status = TenantStatus.ACTIVE, createdAt = now, updatedAt = now))
        val board = http.board()
        val linked = http.card(board, 1, "Set up the menu") { put("tenantId", tenant.id.toHexString()) }
        http.card(board, 0, "Unrelated")
        assertEquals(tenant.id.toHexString(), linked.text("tenantId"))
        val listed = http.send("GET", "/admin/api/board/cards?tenantId=${tenant.id.toHexString()}").arr()
        assertEquals(listOf("Set up the menu"), listed.map { it.jsonObject["card"]!!.text("title") })
        assertEquals(board.text("key"), listed.single().text("boardKey"))
        assertEquals("Doing", listed.single().text("columnName"))

        assertEquals("tenant_not_found", http.send("PATCH", "/admin/api/board/cards/${linked.id()}", buildJsonObject {
            put("tenantId", ObjectId().toHexString())
        }).error())
        assertNull(http.send("PATCH", "/admin/api/board/cards/${linked.id()}", buildJsonObject { put("tenantId", JsonNull) }).obj().text("tenantId"))
        assertEquals(0, http.send("GET", "/admin/api/board/cards?tenantId=${tenant.id.toHexString()}").arr().size)
        assertEquals("invalid_id", http.send("GET", "/admin/api/board/cards").error())
    }

    @Test
    fun `deleting a board deletes its cards and their comments`() = boardTest { http ->
        val board = http.board()
        val card = http.card(board, 0, "Doomed")
        val comment = http.send("POST", "/admin/api/board/cards/${card.id()}/comments", buildJsonObject { put("body", "bye") }).obj()
        assertEquals(HttpStatusCode.OK, http.send("DELETE", "/admin/api/board/boards/${board.id()}").status)
        assertEquals("card_not_found", http.send("GET", "/admin/api/board/cards/${card.id()}").error())
        assertEquals("comment_not_found", http.send("DELETE", "/admin/api/board/comments/${comment.id()}").error())
        assertTrue(http.send("GET", "/admin/api/board/boards").arr().none { it.text("id") == board.id() })
        assertEquals("board_not_found", http.send("DELETE", "/admin/api/board/boards/${board.id()}").error())
    }

    @Test
    fun `a board's name and description change on their own`() = boardTest { http ->
        val board = http.board()
        val path = "/admin/api/board/boards/${board.id()}"
        val renamed = http.send("PATCH", path, buildJsonObject { put("name", "Ops"); put("description", "  What keeps the lights on  ") }).obj()
        assertEquals("Ops", renamed.text("name"))
        assertEquals("What keeps the lights on", renamed.text("description"))
        assertEquals(board.text("key"), renamed.text("key"), "the key never changes")
        assertNull(http.send("PATCH", path, buildJsonObject { put("description", JsonNull) }).obj().text("description"))
        assertEquals("required", http.send("PATCH", path, buildJsonObject { put("name", "") }).error())
    }
}
