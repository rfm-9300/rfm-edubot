package com.rfm.edubot.crm

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
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
class EmployeeRepositoryTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "employees"))
            mongoModule.initialize()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }
    }

    private fun repository() = EmployeeRepository(mongoModule, ObjectId())

    @Test
    fun `profile details are stored, kept when omitted and cleared with a blank string`() = runBlocking {
        val employees = repository()
        val created = employees.create(
            name = "Ana Costa",
            phone = "+351 912 345 678",
            role = "Pintora",
            birthDate = "1990-03-12",
            address = " Rua das Flores 12 ",
            taxId = "245678901",
        )
        assertEquals(LocalDate(1990, 3, 12), created.birthDate)
        assertEquals("Rua das Flores 12", created.address)

        val kept = employees.update(created.id, "Ana Costa", "+351 912 345 678", "Pintora")!!
        assertEquals(LocalDate(1990, 3, 12), kept.birthDate)
        assertEquals("Rua das Flores 12", kept.address)
        assertEquals("245678901", kept.taxId)
        assertEquals(listOf(created.id), employees.search("245678901").map { it.id })

        val cleared = employees.update(created.id, "Ana Costa", "+351 912 345 678", null, birthDate = "", address = "", taxId = " ")!!
        assertNull(cleared.birthDate)
        assertNull(cleared.address)
        assertNull(cleared.taxId)
        assertNull(cleared.role)
        assertEquals(cleared, employees.findById(created.id))
    }

    @Test
    fun `an email is stored trimmed, found by search, kept when omitted and cleared with a blank string`() = runBlocking {
        val employees = repository()
        val created = employees.create(name = "Rita Dias", phone = "+351 913 000 111", email = " rita@exemplo.pt ")
        assertEquals("rita@exemplo.pt", created.email)
        assertEquals(listOf(created.id), employees.search("rita@exemplo").map { it.id })
        assertEquals("rita@exemplo.pt", employees.update(created.id, "Rita Dias", "+351 913 000 111", null)!!.email)
        assertEquals("rita@novo.pt", employees.update(created.id, "Rita Dias", "+351 913 000 111", null, email = "rita@novo.pt")!!.email)
        assertNull(employees.update(created.id, "Rita Dias", "+351 913 000 111", null, email = " ")!!.email)
        assertNull(employees.create(name = "Sem Email", phone = "+351 913 000 222").email)
    }
}
