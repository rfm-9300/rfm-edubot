package com.rfm.edubot.crm

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Testcontainers
class ClientRepositoryTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "clients"))
            mongoModule.initialize()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }
    }

    private fun repository() = ClientRepository(mongoModule, ObjectId())

    @Test
    fun `details are stored, kept when omitted and cleared with a blank string`() = runBlocking {
        val clients = repository()
        val created = clients.create(
            name = "Ana Ribeiro",
            phone = "+351 911 222 333",
            address = "Rua das Flores 12",
            email = " ana@example.pt ",
            taxId = "245678901",
            notes = "Alérgica a amoníaco.\nPrefere tardes.",
        )
        assertEquals("ana@example.pt", created.email)

        val kept = clients.update(created.id, "Ana Ribeiro", "+351 911 222 333", "Rua das Flores 12")!!
        assertEquals("ana@example.pt", kept.email)
        assertEquals("245678901", kept.taxId)
        assertEquals("Alérgica a amoníaco.\nPrefere tardes.", kept.notes)

        val cleared = clients.update(created.id, "Ana Ribeiro", "+351 911 222 333", null, email = "", taxId = " ", notes = "")!!
        assertNull(cleared.email)
        assertNull(cleared.taxId)
        assertNull(cleared.notes)
        assertNull(cleared.address)
        assertEquals(cleared, clients.findById(created.id))
    }

    @Test
    fun `postal code, city and contact person are stored, kept when omitted, cleared with a blank string and searchable`() = runBlocking {
        val clients = repository()
        val created = clients.create(
            name = "Hotel Miradouro",
            phone = "+351 213 000 111",
            address = "Rua do Castelo 3",
            taxId = "509876543",
            postalCode = " 1100-129 ",
            city = "Lisboa",
            contactPerson = "Marta Reis",
        )
        assertEquals("1100-129", created.postalCode)

        val kept = clients.update(created.id, "Hotel Miradouro", "+351 213 000 111", "Rua do Castelo 3")!!
        assertEquals("1100-129", kept.postalCode)
        assertEquals("Lisboa", kept.city)
        assertEquals("Marta Reis", kept.contactPerson)
        assertEquals(listOf(created.id), clients.search("marta").map { it.id })
        assertEquals(listOf(created.id), clients.search("1100-129").map { it.id })
        assertEquals(listOf(created.id), clients.search("lisboa").map { it.id })

        val cleared = clients.update(
            created.id, "Hotel Miradouro", "+351 213 000 111", "Rua do Castelo 3",
            postalCode = "", city = " ", contactPerson = "",
        )!!
        assertNull(cleared.postalCode)
        assertNull(cleared.city)
        assertNull(cleared.contactPerson)
        assertEquals(cleared, clients.findById(created.id))
    }

    @Test
    fun `search finds clients by email, tax number and phone digits typed without spaces`() = runBlocking {
        val clients = repository()
        val ana = clients.create("Ana Ribeiro", "+351 911 222 333", email = "ana@example.pt", taxId = "245678901")
        clients.create("Bruno Esteves", "+351 922 333 444")

        assertEquals(listOf(ana.id), clients.search("ana@example").map { it.id })
        assertEquals(listOf(ana.id), clients.search("245678901").map { it.id })
        assertEquals(listOf(ana.id), clients.search("911222333").map { it.id })
        assertEquals(listOf(ana.id), clients.search("911 222").map { it.id })
        assertEquals(2, clients.search("").size)
    }

    @Test
    fun `custom values are stored by type, changed one by one, cleared with null and searchable`() = runBlocking {
        val clients = repository()
        val created = clients.create(
            "Ana Ribeiro", "+351 911 222 333",
            customFields = mapOf(
                "cf_pet00001" to JsonPrimitive("Rex"),
                "cf_vis00001" to JsonPrimitive(3.0),
                "cf_new00001" to JsonPrimitive(true),
                "bad.key" to JsonPrimitive("dropped"),
            ),
        )
        val expected = mapOf("cf_pet00001" to JsonPrimitive("Rex"), "cf_vis00001" to JsonPrimitive(3.0), "cf_new00001" to JsonPrimitive(true))
        assertEquals(expected, created.customFields)
        assertEquals(expected, clients.findById(created.id)!!.customFields)

        val changed = clients.update(
            created.id, "Ana Ribeiro", "+351 911 222 333", null,
            customFieldChanges = mapOf("cf_vis00001" to JsonPrimitive(4.5), "cf_new00001" to null, "cf_siz00001" to JsonPrimitive("Large")),
        )!!
        assertEquals(
            mapOf("cf_pet00001" to JsonPrimitive("Rex"), "cf_vis00001" to JsonPrimitive(4.5), "cf_siz00001" to JsonPrimitive("Large")),
            changed.customFields,
        )
        assertEquals(changed.customFields, clients.update(created.id, "Ana Ribeiro", "+351 911 222 333", null)!!.customFields)

        clients.create("Bruno Esteves", "+351 922 333 444", customFields = mapOf("cf_pet00001" to JsonPrimitive("Bobby")))
        assertEquals(emptyList(), clients.search("rex").map { it.id })
        assertEquals(listOf(created.id), clients.search("rex", customKeys = listOf("cf_pet00001")).map { it.id })
        assertEquals(listOf(created.id), clients.search("larg", customKeys = listOf("cf_pet00001", "cf_siz00001", "not a key")).map { it.id })
    }

    @Test
    fun `the directory can list more than twenty clients`() = runBlocking {
        val clients = repository()
        repeat(25) { clients.create("Cliente $it", "+351 900 000 ${it.toString().padStart(3, '0')}") }

        assertEquals(20, clients.search("").size)
        assertEquals(25, clients.search("", limit = 2000).size)
    }
}
