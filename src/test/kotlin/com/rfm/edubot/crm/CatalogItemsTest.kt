package com.rfm.edubot.crm

import com.mongodb.MongoServerException
import com.rfm.edubot.admin.StandardItemRequest
import com.rfm.edubot.bookings.BookableServiceRepository
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Catalog titles and codes: numbering, uniqueness, the description fallback and the startup backfill. */
@Testcontainers
class CatalogItemsTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "catalog_items"))
            mongoModule.initialize()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }
    }

    private val tenantId = ObjectId()

    private suspend fun StandardItemRepository.add(title: String, type: String = "service", code: String? = null, description: String = "") =
        create(StandardItem(freeId(title, type), type, "Pintura", description, "m2", 10.0, title = title, code = code))

    /** An item as releases before titles and codes saved it. */
    private suspend fun insertLegacy(id: String, type: String, description: String, tenant: ObjectId = tenantId) {
        mongoModule.database.getCollection<Document>("crm.standard_items").insertOne(
            Document("id", id).append("tenantId", tenant).append("type", type).append("category", "Limpeza")
                .append("description", description).append("unit", "un").append("defaultUnitPriceEur", 20.0),
        )
    }

    private suspend fun stored(id: String, tenant: ObjectId = tenantId): Document =
        mongoModule.database.getCollection<Document>("crm.standard_items")
            .find(Document("tenantId", tenant).append("id", id)).first()

    @Test
    fun `new items are numbered per type, skipping codes typed by hand`() = runBlocking<Unit> {
        val items = StandardItemRepository(mongoModule, tenantId)
        assertEquals("SRV-001", items.add("Pintura interior").code)
        assertEquals("MAT-001", items.add("Tinta acrílica", type = "material").code)
        assertEquals("SRV-002", items.add("Lavagem de fachada", code = "SRV-002").code)
        assertEquals("SRV-003", items.add("Impermeabilização").code, "a number typed by hand is not handed out again")
        assertEquals("PINT-01", items.add("Pintura exterior", code = "PINT-01").code)
        assertEquals("SRV-001", StandardItemRepository(mongoModule, ObjectId()).add("Pintura interior").code, "each tenant numbers its own catalog")
    }

    @Test
    fun `two items of one tenant can't share a code, and search finds an item by its code`() = runBlocking<Unit> {
        val items = StandardItemRepository(mongoModule, tenantId)
        val first = items.add("Pintura interior")
        assertFailsWith<MongoServerException> { items.add("Outra pintura", code = first.code) }
        val second = items.add("Lavagem")
        assertFailsWith<MongoServerException> { items.update(second.id, second.copy(code = first.code)) }

        assertEquals("LAV-1", items.update(second.id, second.copy(code = "LAV-1"))?.code)
        assertEquals(second.id, items.findByCode("LAV-1")?.id)
        assertEquals(listOf(second.id), items.search("lav-1").map { it.id })
        assertEquals("LAV-1", items.update(second.id, second.copy(code = null, title = "Lavagem exterior"))?.code, "a write without a code keeps it")
    }

    @Test
    fun `an item without a description stores its title there, and older items read their description as title`() = runBlocking<Unit> {
        val items = StandardItemRepository(mongoModule, tenantId)
        val plain = items.add("Pintura interior")
        assertEquals("Pintura interior", stored(plain.id).getString("description"), "readers that predate titles still get a name")
        assertEquals("", items.findById(plain.id)?.details())
        val detailed = items.add("Pintura exterior", description = "Duas demãos, tinta acrílica")
        assertEquals("Duas demãos, tinta acrílica", items.findById(detailed.id)?.details())

        insertLegacy("srv-escritorio", "service", "Limpeza de escritório")
        val legacy = items.findById("srv-escritorio")!!
        assertEquals("Limpeza de escritório", legacy.title)
        assertNull(legacy.code)
    }

    @Test
    fun `the backfill numbers older items in creation order once and leaves their descriptions alone`() = runBlocking<Unit> {
        val items = StandardItemRepository(mongoModule, tenantId)
        val other = ObjectId()
        insertLegacy("srv-a", "service", "Limpeza")
        insertLegacy("mat-b", "material", "Detergente")
        insertLegacy("srv-c", "servico", "Engomar")
        insertLegacy("srv-a", "service", "Jardinagem", tenant = other)

        CatalogItemBackfill(mongoModule).run()

        assertEquals(listOf("SRV-001", "MAT-001", "SRV-002"), listOf("srv-a", "mat-b", "srv-c").map { stored(it).getString("code") })
        assertEquals("Limpeza", stored("srv-a").getString("title"))
        assertEquals("Limpeza", stored("srv-a").getString("description"), "the previous release still reads the description")
        assertEquals("SRV-001", stored("srv-a", other).getString("code"))
        assertEquals(0, CatalogItemBackfill(mongoModule).run(), "a second start finds nothing to fill")
        assertEquals("SRV-003", items.add("Passar a ferro").code, "new items continue the numbering")
    }

    @Test
    fun `renaming a bookable service keeps its own description`() = runBlocking<Unit> {
        val items = StandardItemRepository(mongoModule, tenantId)
        val services = BookableServiceRepository(mongoModule, tenantId)
        val detailed = items.add("Massagem", description = "Com óleos essenciais")
        val plain = items.add("Corte")

        services.update(detailed.id, "Massagem relaxante", null, null, null)
        services.update(plain.id, "Corte de cabelo", null, null, null)

        assertEquals("Massagem relaxante", items.findById(detailed.id)?.title)
        assertEquals("Com óleos essenciais", items.findById(detailed.id)?.details())
        assertEquals("Corte de cabelo", stored(plain.id).getString("description"))
        assertEquals("Corte de cabelo", services.findById(plain.id)?.name)
    }

    @Test
    fun `a request without a title uses the description, and an omitted code keeps the stored one`() {
        val older = StandardItemRequest(type = "service", category = "Bem-estar", description = "Massagem", unit = "un", defaultUnitPriceEur = 40.0)
        assertEquals("Massagem", older.toStandardItem("srv-x").title)

        val existing = older.toStandardItem("srv-x").copy(code = "SRV-007")
        val edit = StandardItemRequest(type = "service", category = "Bem-estar", unit = "un", defaultUnitPriceEur = 45.0, title = " Massagem relaxante ")
        val next = edit.toStandardItem("srv-x", existing)
        assertEquals("Massagem relaxante", next.title)
        assertEquals("Massagem relaxante", next.description)
        assertEquals("SRV-007", next.code)
        assertEquals("MAS-1", edit.copy(code = " MAS-1 ").toStandardItem("srv-x", existing).code)

        assertEquals("title_required", StandardItemRequest(type = "service", category = "Bem-estar", unit = "un", defaultUnitPriceEur = 1.0).error())
        assertEquals("code_too_long", edit.copy(code = "X".repeat(StandardItemRequest.MAX_CODE + 1)).error())
        assertNull(edit.error())
    }
}
