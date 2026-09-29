package com.rfm.edubot.crm

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.persistence.MongoModule
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Deleting, archiving and restoring clients, suppliers and employees. */
@Testcontainers
class DirectoryRecordsTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "directory_records"))
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

    /** A bare document pointing at [id]; quotes, invoices and payments need a number unique per tenant. */
    private suspend fun refer(collection: String, field: String, id: ObjectId) {
        mongoModule.database.getCollection<Document>(collection)
            .insertOne(Document("tenantId", tenantId).append(field, id).append("number", "REF-${ObjectId().toHexString()}"))
    }

    @Test
    fun `a client with no documents is deleted, one with documents is archived and restored`() = runBlocking<Unit> {
        val clients = ClientRepository(mongoModule, tenantId)
        val unused = clients.create("Ana Ribeiro", "+351 911 000 001")
        val busy = clients.create("Bruno Esteves", "+351 911 000 002")
        refer("crm.invoices", "clientId", busy.id)

        assertEquals(DirectoryDelete.DELETED, clients.delete(unused.id))
        assertNull(clients.findById(unused.id))
        assertEquals(DirectoryDelete.NOT_FOUND, clients.delete(unused.id))
        assertEquals(DirectoryDelete.IN_USE, clients.delete(busy.id))

        val archivedAt = assertNotNull(clients.setArchived(busy.id, archived = true)?.archivedAt)
        assertEquals(emptyList(), clients.search("").map { it.id })
        assertEquals(listOf(busy.id), clients.search("", archived = true).map { it.id })
        assertEquals(emptyList(), clients.search("Bruno").map { it.id }, "pickers and the bot's search skip archived clients")
        assertEquals(busy.id, clients.findByPhone("911000002")?.id, "an archived client still owns its phone")
        assertEquals(archivedAt, clients.setArchived(busy.id, archived = true)?.archivedAt, "archiving twice keeps the first date")

        assertNull(clients.setArchived(busy.id, archived = false)?.archivedAt)
        assertEquals(listOf(busy.id), clients.search("").map { it.id })
    }

    @Test
    fun `quotes, Servicos rows and bookings also keep a client from being deleted`() = runBlocking<Unit> {
        val clients = ClientRepository(mongoModule, tenantId)
        listOf("crm.quotes", "crm.client_services", "bookings.appointments").forEachIndexed { i, collection ->
            val client = clients.create("Cliente $i", "+351 912 000 00$i")
            refer(collection, "clientId", client.id)
            assertEquals(DirectoryDelete.IN_USE, clients.delete(client.id), collection)
        }
    }

    @Test
    fun `suppliers and employees with payments are archived, the others deleted`() = runBlocking<Unit> {
        val suppliers = SupplierRepository(mongoModule, tenantId)
        val employees = EmployeeRepository(mongoModule, tenantId)
        val paidSupplier = suppliers.create("Tintas Lda", "+351 210 000 001")
        val newSupplier = suppliers.create("Madeiras SA", "+351 210 000 002")
        val paidEmployee = employees.create("Carla Mendes", "+351 930 000 001")
        val newEmployee = employees.create("Duarte Lopes", "+351 930 000 002")
        refer("crm.payments", "supplierId", paidSupplier.id)
        refer("crm.payments", "employeeId", paidEmployee.id)

        assertEquals(DirectoryDelete.IN_USE, suppliers.delete(paidSupplier.id))
        assertEquals(DirectoryDelete.DELETED, suppliers.delete(newSupplier.id))
        assertEquals(DirectoryDelete.IN_USE, employees.delete(paidEmployee.id))
        assertEquals(DirectoryDelete.DELETED, employees.delete(newEmployee.id))

        suppliers.setArchived(paidSupplier.id, archived = true)
        employees.setArchived(paidEmployee.id, archived = true)
        assertEquals(emptyList(), suppliers.search("").map { it.id })
        assertEquals(listOf(paidSupplier.id), suppliers.search("", archived = true).map { it.id })
        assertEquals(emptyList(), employees.search("").map { it.id })
        assertEquals(listOf(paidEmployee.id), employees.search("", archived = true).map { it.id })
        assertNotNull(employees.findById(paidEmployee.id)?.archivedAt, "payments still find their archived payee")
    }

    @Test
    fun `another tenant's records can't be deleted or archived`() = runBlocking<Unit> {
        val mine = ClientRepository(mongoModule, tenantId).create("Eva Martins", "+351 913 000 001")
        val theirs = ClientRepository(mongoModule, ObjectId())

        assertEquals(DirectoryDelete.NOT_FOUND, theirs.delete(mine.id))
        assertNull(theirs.setArchived(mine.id, archived = true))
        assertNull(ClientRepository(mongoModule, tenantId).findById(mine.id)?.archivedAt)
    }
}
