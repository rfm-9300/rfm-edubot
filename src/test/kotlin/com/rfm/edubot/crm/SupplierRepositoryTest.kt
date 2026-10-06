package com.rfm.edubot.crm

import com.rfm.edubot.admin.CreateSupplierRequest
import com.rfm.edubot.admin.SupplierServiceRequest
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.crm.model.SupplierService
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

/** A supplier's free-text type and usual services. */
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

    @Test
    fun `usual services are kept when a write omits them, replaced when sent and found by search`() = runBlocking<Unit> {
        val suppliers = SupplierRepository(mongoModule, tenantId)
        val eletricista = suppliers.create(
            "Luz & Cia", "+351 220 100 003",
            services = listOf(SupplierService("Instalação elétrica", "h", 25_00), SupplierService("Certificação", "")),
        )
        val stored = suppliers.findById(eletricista.id)!!
        assertEquals(listOf("Instalação elétrica", "Certificação"), stored.services.map { it.description })
        assertEquals(listOf(25_00L, null), stored.services.map { it.unitPriceCents }, "a service without a price keeps none")
        assertEquals(listOf(eletricista.id), suppliers.search("certifica").map { it.id })

        assertEquals(2, suppliers.update(eletricista.id, "Luz & Cia", "+351 220 100 003", null)?.services?.size)
        val replaced = suppliers.update(eletricista.id, "Luz & Cia", "+351 220 100 003", null, services = listOf(SupplierService("Quadro elétrico", "un", 180_00)))
        assertEquals(listOf("Quadro elétrico"), replaced?.services?.map { it.description })
        assertEquals(emptyList(), suppliers.update(eletricista.id, "Luz & Cia", "+351 220 100 003", null, services = emptyList())?.services)
    }

    @Test
    fun `usual services the form can't hold are refused, and prices become cents`() {
        fun request(vararg services: SupplierServiceRequest) = CreateSupplierRequest("Luz", "+351 220 100 003", services = services.toList())
        assertNull(request(SupplierServiceRequest(" Instalação ", " h ", 4.35)).detailsError())
        assertEquals(listOf(SupplierService("Instalação", "h", 435)), request(SupplierServiceRequest(" Instalação ", " h ", 4.35)).supplierServices())
        assertNull(CreateSupplierRequest("Luz", "+351 220 100 003").supplierServices(), "no list keeps the stored services")

        assertEquals("service_description_required", request(SupplierServiceRequest(" ")).detailsError())
        assertEquals("service_price_invalid", request(SupplierServiceRequest("Instalação", unitPriceEur = -1.0)).detailsError())
        assertEquals("service_description_too_long", request(SupplierServiceRequest("x".repeat(CreateSupplierRequest.MAX_SERVICE_DESCRIPTION + 1))).detailsError())
        assertEquals("service_unit_too_long", request(SupplierServiceRequest("Instalação", "x".repeat(CreateSupplierRequest.MAX_SERVICE_UNIT + 1))).detailsError())
        val tooMany = (0..CreateSupplierRequest.MAX_SERVICES).map { SupplierServiceRequest("Serviço $it") }
        assertEquals("too_many_services", request(*tooMany.toTypedArray()).detailsError())
    }
}
