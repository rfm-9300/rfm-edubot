package com.rfm.edubot.crm

import com.rfm.edubot.admin.CreateSupplierRequest
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.runBlocking
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A supplier's free-text type. */
@Testcontainers
class SupplierRepositoryTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "suppliers"))
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

    @Test
    fun `a supplier's type is searchable, kept when a write omits it and cleared by an empty string`() = runBlocking<Unit> {
        val suppliers = SupplierRepository(mongoModule, tenantId)
        val tintas = suppliers.create("Tintas Norte", "+351 220 100 001", type = " Materiais ")
        suppliers.create("Andaimes & Cia", "+351 220 100 002")
        assertEquals("Materiais", tintas.type)
        assertEquals(listOf(tintas.id), suppliers.search("materiais").map { it.id })

        assertEquals("Materiais", suppliers.update(tintas.id, "Tintas Norte, Lda.", "+351 220 100 001", null)?.type)
        assertEquals("Equipamento", suppliers.update(tintas.id, "Tintas Norte, Lda.", "+351 220 100 001", null, type = "Equipamento")?.type)
        assertNull(suppliers.update(tintas.id, "Tintas Norte, Lda.", "+351 220 100 001", null, type = "")?.type)
        assertNull(suppliers.findById(tintas.id)?.type)
    }

    @Test
    fun `a type longer than the form allows is refused`() {
        assertEquals("type_too_long", CreateSupplierRequest("Tintas", "+351 220 100 001", type = "x".repeat(CreateSupplierRequest.MAX_TYPE + 1)).detailsError())
        assertNull(CreateSupplierRequest("Tintas", "+351 220 100 001", type = "Materiais").detailsError())
    }
}
