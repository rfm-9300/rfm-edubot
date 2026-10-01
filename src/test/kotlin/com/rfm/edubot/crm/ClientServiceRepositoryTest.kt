package com.rfm.edubot.crm

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.crm.model.LineItem
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

@Testcontainers
class ClientServiceRepositoryTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "client-services"))
            mongoModule.initialize()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }
    }

    private fun repository() = ClientServiceRepository(mongoModule, ObjectId())

    @Test
    fun `a service with several lines totals their sum and keeps them through status changes`() = runBlocking {
        val services = repository()
        val lines = listOf(
            lineItem("Limpeza doméstica", quantity = 3.0, unitPriceEur = 12.5, unit = "h"),
            lineItem("Limpeza de vidros", unitPriceEur = 20.0),
            lineItem("Produtos", quantity = 2.0, unitPriceEur = 4.35),
        )
        val created = services.create(
            clientId = ObjectId(),
            name = "Limpeza completa",
            notes = null,
            quantity = 7.0,
            unit = "ignored",
            unitPriceCents = 1,
            bookingServiceId = null,
            catalogItemId = "srv-limpeza-domestica",
            performedAt = null,
            items = lines,
        )
        assertEquals(3750L + 2000L + 870L, created.totalCents)
        assertEquals(1.0, created.quantity)
        assertEquals("", created.unit)
        assertEquals(created.totalCents, created.unitPriceCents)

        // Status changes resend the row's single-line fields; they must not replace what the lines add up to.
        val cancelled = services.update(created.id, created.name, null, 1.0, "", 999, null, ClientServiceStatus.CANCELLED)!!
        assertEquals(lines, cancelled.items)
        assertEquals(created.totalCents, cancelled.totalCents)
        assertEquals(cancelled, services.findById(created.id))

        val reopened = services.update(
            created.id, null, null, null, null, null, null, ClientServiceStatus.OPEN,
            items = listOf(lineItem("Engomadoria", quantity = 2.0, unitPriceEur = 15.0, unit = "h")),
        )!!
        assertEquals(ClientServiceStatus.OPEN, reopened.status)
        assertEquals(2.0, reopened.quantity)
        assertEquals("h", reopened.unit)
        assertEquals(1500L, reopened.unitPriceCents)
        assertEquals(3000L, reopened.totalCents)
    }

    @Test
    fun `rows without lines keep billing from their own fields`() = runBlocking {
        val services = repository()
        val created = services.create(ObjectId(), "Pintura", null, 2.0, "m2", 1250, null, null, null)
        assertEquals(emptyList<LineItem>(), created.items)
        assertEquals(2500L, created.totalCents)

        val updated = services.update(created.id, null, null, 4.0, null, null, null, null)!!
        assertEquals(5000L, updated.totalCents)
        assertEquals(listOf(LineItem("Pintura", 4.0, "m2", 1250, 5000)), updated.lines())
    }
}
