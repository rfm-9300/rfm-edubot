package com.rfm.edubot.flows

import com.mongodb.MongoWriteException
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.testing.TestMongo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

/** How a company's flows are read from its hand-written `chat_flows` document, against a real MongoDB. */
class ChatFlowRepositoryTest {
    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("chat_flows")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() = mongo.shutdown()
    }

    private val collection get() = mongo.database.getCollection<Document>("chat_flows")

    private fun step(id: String, text: String, vararg buttons: Pair<String, String>) = Document("id", id)
        .append("text", text)
        .append("buttons", buttons.map { (title, next) -> Document("title", title).append("next", next) })

    private fun flow(id: String, vararg steps: Document) = Document("id", id)
        .append("name", "Menu")
        .append("enabled", true)
        .append("channels", listOf("INSTAGRAM"))
        .append("keywords", listOf("Olá", "Bom dia"))
        .append("steps", steps.toList())

    @Test
    fun `a flows document written by hand reads back as the company's flows`(): Unit = runBlocking {
        val tenantId = ObjectId()
        collection.insertOne(
            Document("tenantId", tenantId)
                .append("teamOnlyChannels", listOf("INSTAGRAM"))
                .append("flows", listOf(flow("servicos", step("menu", "Qual serviço?", "Unhas" to "contacto"), step("contacto", "Fale connosco")))),
        )

        val expected = ChatFlows(
            flows = listOf(
                ChatFlow(
                    id = "servicos",
                    name = "Menu",
                    channels = setOf(Platform.INSTAGRAM),
                    keywords = listOf("Olá", "Bom dia"),
                    steps = listOf(FlowStep("menu", "Qual serviço?", listOf(FlowButton("Unhas", "contacto"))), FlowStep("contacto", "Fale connosco")),
                    // A document written before these settings existed: an unreadable reply went to the AI at once, and still does.
                    afterMisses = AfterMisses.LET_AI,
                    retries = 0,
                ),
            ),
            teamOnlyChannels = setOf(Platform.INSTAGRAM),
        )
        assertEquals(expected, ChatFlowRepository(mongo, tenantId).find())
    }

    @Test
    fun `a flow that can't run is left out and the company's other flows still answer`(): Unit = runBlocking {
        val tenantId = ObjectId()
        collection.insertOne(
            Document("tenantId", tenantId).append(
                "flows",
                listOf(
                    flow("broken", step("menu", "Qual serviço?", "Unhas" to "nowhere")),
                    Document("name", "Sem id"),
                    flow("servicos", step("menu", "Qual serviço?")),
                ),
            ),
        )

        val found = ChatFlowRepository(mongo, tenantId).find()!!

        assertEquals(listOf("servicos"), found.flows.map { it.id })
        assertEquals(emptySet(), found.teamOnlyChannels)
    }

    @Test
    fun `a company without a document has no flows, whatever other companies have`(): Unit = runBlocking {
        collection.insertOne(Document("tenantId", ObjectId()).append("flows", listOf(flow("servicos", step("menu", "Qual serviço?")))))

        assertNull(ChatFlowRepository(mongo, ObjectId()).find())
    }

    @Test
    fun `the editor sees a flow with problems so it can be fixed, while customers only get the flows that can run`(): Unit = runBlocking {
        val tenantId = ObjectId()
        collection.insertOne(
            Document("tenantId", tenantId).append("flows", listOf(flow("broken", step("menu", "Qual serviço?", "Unhas" to "nowhere")), flow("servicos", step("menu", "Qual serviço?")))),
        )
        val repo = ChatFlowRepository(mongo, tenantId)

        assertEquals(listOf("broken", "servicos"), repo.load()!!.flows.map { it.id })
        assertEquals(listOf("servicos"), repo.find()!!.flows.map { it.id })
    }

    @Test
    fun `saving replaces the company's flows in the same shape and records who saved them`(): Unit = runBlocking {
        val tenantId = ObjectId()
        val repo = ChatFlowRepository(mongo, tenantId)
        val menu = ChatFlow(
            id = "servicos",
            name = "Menu",
            channels = setOf(Platform.INSTAGRAM),
            keywords = listOf("Olá", "Bom dia"),
            steps = listOf(FlowStep("menu", "Qual serviço?", listOf(FlowButton("Unhas", "contacto"))), FlowStep("contacto", "Fale connosco")),
        )

        val saved = repo.save(ChatFlows(listOf(menu), setOf(Platform.INSTAGRAM)), "ana@clinica.pt")
        assertEquals(listOf(menu), saved.flows)
        assertEquals(setOf(Platform.INSTAGRAM), saved.teamOnlyChannels)
        assertEquals("ana@clinica.pt", saved.updatedBy)
        assertNotNull(saved.updatedAt)
        val createdAt = collection.find(Document("tenantId", tenantId)).first().getDate("createdAt")

        val again = repo.save(ChatFlows(listOf(menu.copy(name = "Menu novo"))), "operator")
        assertEquals("Menu novo", again.flows.single().name)
        assertEquals(emptySet(), again.teamOnlyChannels)
        assertEquals("operator", again.updatedBy)
        val doc = collection.find(Document("tenantId", tenantId)).first()
        assertEquals(createdAt, doc.getDate("createdAt"))
        assertEquals(1L, collection.countDocuments(Document("tenantId", tenantId)))
        assertTrue(
            doc.getList("flows", Document::class.java).single().keys.containsAll(flow("servicos", step("menu", "Qual serviço?")).keys),
            "everything a document written by hand has, and the settings added since",
        )
    }

    @Test
    fun `a company has one flows document`(): Unit = runBlocking {
        val tenantId = ObjectId()
        collection.insertOne(Document("tenantId", tenantId))

        assertFailsWith<MongoWriteException> { collection.insertOne(Document("tenantId", tenantId)) }
    }

    @Test
    fun `start gates are saved by channel and read back, and a document without any has none`(): Unit = runBlocking {
        val tenantId = ObjectId()
        val repository = ChatFlowRepository(mongo, tenantId)
        collection.insertOne(Document("tenantId", tenantId).append("flows", emptyList<Document>()))
        assertEquals(emptyMap(), repository.find()!!.startGates)

        val gates = mapOf(
            Platform.WHATSAPP to StartGate(enabled = true, words = listOf("Orçamento", "Quero marcar")),
            Platform.INSTAGRAM to StartGate(enabled = false, words = listOf("Oi")),
        )
        val saved = repository.save(ChatFlows(startGates = gates), "ana@clinica.pt")

        assertEquals(gates, saved.startGates)
        assertEquals(gates, repository.find()!!.startGates)
        val stored = collection.find(org.bson.Document("tenantId", tenantId)).first().getList("startGates", Document::class.java)
        assertEquals(listOf("INSTAGRAM", "WHATSAPP"), stored.map { it.getString("channel") }, "stored in channel order")
    }

    @Test
    fun `a start gate of an unknown channel is ignored when reading`(): Unit = runBlocking {
        val tenantId = ObjectId()
        collection.insertOne(
            Document("tenantId", tenantId).append(
                "startGates",
                listOf(Document("channel", "TELEGRAM").append("words", listOf("oi")), Document("channel", "WHATSAPP").append("words", listOf("oi"))),
            ),
        )

        assertEquals(mapOf(Platform.WHATSAPP to StartGate(true, listOf("oi"))), ChatFlowRepository(mongo, tenantId).find()!!.startGates)
    }

    @Test
    fun `a flow saved with every kind of step reads back exactly`(): Unit = runBlocking {
        val tenantId = ObjectId()
        val repo = ChatFlowRepository(mongo, tenantId)
        val flow = ChatFlow(
            id = "marcar", name = "Marcar", channels = setOf(Platform.WHATSAPP, Platform.WEB), keywords = listOf("marcar"),
            aiReads = true, afterMisses = AfterMisses.LET_AI, handoverText = "Já lhe respondemos.", retries = 2, startAt = BoardPoint(0, 48),
            steps = listOf(
                FlowStep("menu", "Que serviço?", listOf(FlowButton("Unhas", "name", listOf("manicure", "gel")), FlowButton("Outro", "person")), show = OptionsDisplay.NUMBERED, listLabel = "Escolher", retryText = "Não percebi.", at = BoardPoint(320, -40)),
                FlowStep("name", "Nome?", kind = StepKind.ASK, key = "name", expect = Expect.NAME, next = "open", at = BoardPoint(680, 0)),
                FlowStep("open", kind = StepKind.CONDITION, check = Check.HOURS, hours = listOf(HoursWindow(1, "09:00", "18:00"), HoursWindow(6, "10:00", "13:00")), whenTrue = "book", whenFalse = "late"),
                FlowStep("book", "Marcado.", kind = StepKind.ACTION, action = ActionKind.CREATE_BOOKING, serviceId = "svc-1", key = "service", next = "known", failNext = "person"),
                FlowStep("known", kind = StepKind.CONDITION, check = Check.ANSWER, answerKey = "name", answerIs = "Ana", whenTrue = "late", whenFalse = "late"),
                FlowStep("late", "Fora de horas."),
                FlowStep("person", "Já vamos.", kind = StepKind.HANDOFF),
            ),
        )

        val saved = repo.saveFlows(listOf(flow), "ana@clinica.pt")
        assertEquals(listOf(flow), saved.flows)
        assertEquals(listOf(flow), repo.find()!!.flows)
    }

    @Test
    fun `a step saved before steps had kinds is a plain message with its buttons`(): Unit = runBlocking {
        val tenantId = ObjectId()
        collection.insertOne(Document("tenantId", tenantId).append("flows", listOf(flow("servicos", step("menu", "Qual serviço?", "Unhas" to "contacto"), step("contacto", "Fale connosco")))))

        val read = ChatFlowRepository(mongo, tenantId).find()!!.flows.single()
        assertEquals(FlowStep("menu", "Qual serviço?", listOf(FlowButton("Unhas", "contacto")), kind = StepKind.MESSAGE, show = OptionsDisplay.AUTO), read.steps.first())
        assertEquals(false, read.aiReads)
        assertEquals(AfterMisses.LET_AI, read.afterMisses, "a reply it couldn't read always went to the AI")
        assertEquals(0, read.retries, "and at once")
        assertNull(read.handoverText)
        assertNull(read.startAt, "a flow nobody arranged is laid out by the board")
        assertTrue(read.steps.all { it.at == null })
    }

    @Test
    fun `places on the board read back whatever number type they were stored as, and half a place is none`(): Unit = runBlocking {
        val tenantId = ObjectId()
        val placed = step("menu", "Qual serviço?", "Unhas" to "contacto").append("at", Document("x", 320.0).append("y", -40L))
        val half = step("contacto", "Fale connosco").append("at", Document("x", 12))
        collection.insertOne(Document("tenantId", tenantId).append("flows", listOf(flow("servicos", placed, half).append("startAt", Document("x", 0).append("y", 48)))))

        val read = ChatFlowRepository(mongo, tenantId).find()!!.flows.single()
        assertEquals(BoardPoint(0, 48), read.startAt)
        assertEquals(listOf(BoardPoint(320, -40), null), read.steps.map { it.at })
    }

    @Test
    fun `the flows and the channel settings are saved apart, each leaving the other as it was`(): Unit = runBlocking {
        val tenantId = ObjectId()
        val repo = ChatFlowRepository(mongo, tenantId)
        val menu = ChatFlow("servicos", "Menu", channels = setOf(Platform.INSTAGRAM), keywords = listOf("Olá"), steps = listOf(FlowStep("menu", "Qual serviço?")))
        val gates = mapOf(Platform.WHATSAPP to StartGate(true, listOf("oi")))

        repo.saveChannels(setOf(Platform.WHATSAPP), gates, "ana@clinica.pt")
        val afterFlows = repo.saveFlows(listOf(menu), "rui@clinica.pt")
        assertEquals(setOf(Platform.WHATSAPP), afterFlows.teamOnlyChannels, "saving flows leaves the AI switch")
        assertEquals(gates, afterFlows.startGates, "and the start gates")
        assertEquals("rui@clinica.pt", afterFlows.updatedBy)

        val afterChannels = repo.saveChannels(emptySet(), emptyMap(), "ana@clinica.pt")
        assertEquals(listOf(menu), afterChannels.flows, "saving channels leaves the flows")
        assertEquals(emptySet(), afterChannels.teamOnlyChannels)
        assertEquals(1L, collection.countDocuments(Document("tenantId", tenantId)))
    }

    @Test
    fun `a customer's place in a flow is kept per chat, replaced by the next message, and cleared at the end`(): Unit = runBlocking {
        val tenantId = ObjectId()
        val sessions = FlowSessionRepository(mongo, tenantId)
        val chat = ObjectId()
        val other = ObjectId()
        val at = Instant.parse("2026-11-17T10:00:00Z")

        assertNull(sessions.find(chat))
        sessions.save(chat, FlowSession("f", "name", mapOf("service" to "Unhas"), 1, at))
        sessions.save(other, FlowSession("g", "menu", updatedAt = at))
        assertEquals(FlowSession("f", "name", mapOf("service" to "Unhas"), 1, at), sessions.find(chat))

        sessions.save(chat, FlowSession("f", "phone", mapOf("service" to "Unhas", "name" to "Ana"), 0, at))
        assertEquals("phone", sessions.find(chat)?.stepId)
        assertEquals(1L, mongo.database.getCollection<Document>("chat_flow_sessions").countDocuments(Document("tenantId", tenantId).append("conversationId", chat)))
        assertEquals("g", sessions.find(other)?.flowId, "another chat's place is its own")

        sessions.clear(chat)
        assertNull(sessions.find(chat))
        assertNotNull(sessions.find(other))
        assertNull(FlowSessionRepository(mongo, ObjectId()).find(other), "another company never sees it")
    }

    @Test
    fun `a session is stale after twelve idle hours`() {
        val at = Instant.parse("2026-11-17T10:00:00Z")
        val session = FlowSession("f", "menu", updatedAt = at)
        assertEquals(false, session.expired(Instant.parse("2026-11-17T21:59:59Z")))
        assertEquals(true, session.expired(Instant.parse("2026-11-17T22:00:01Z")))
    }
}
