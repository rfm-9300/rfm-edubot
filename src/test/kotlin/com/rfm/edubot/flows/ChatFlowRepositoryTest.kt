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
        assertEquals(flow("servicos", step("menu", "Qual serviço?")).keys, doc.getList("flows", Document::class.java).single().keys, "the same shape as a document written by hand")
    }

    @Test
    fun `a company has one flows document`(): Unit = runBlocking {
        val tenantId = ObjectId()
        collection.insertOne(Document("tenantId", tenantId))

        assertFailsWith<MongoWriteException> { collection.insertOne(Document("tenantId", tenantId)) }
    }
}
