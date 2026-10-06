package com.rfm.edubot.crm

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.crm.model.PaymentStatus
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
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Linking payments (expenses) to the client they were for, cancelling and deleting them. */
@Testcontainers
class PaymentRepositoryTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "payment_repository"))
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

    private suspend fun PaymentRepository.bill(description: String, clientId: ObjectId? = null) = create(
        supplierId = ObjectId(),
        employeeId = null,
        items = listOf(lineItem(description, unitPriceEur = 80.0)),
        dueDate = LocalDate(2026, 10, 1),
        notes = null,
        clientId = clientId,
    )

    @Test
    fun `a payment can be linked to a client, listed by client and unlinked`() = runBlocking<Unit> {
        val payments = PaymentRepository(mongoModule, tenantId)
        val client = ObjectId()
        val linked = payments.bill("Tinta para a obra", clientId = client)
        val other = payments.bill("Material de escritório")

        assertEquals(client, payments.findById(linked.id)?.clientId)
        assertEquals(listOf(linked.id), payments.list(clientId = client).map { it.id })
        assertEquals(setOf(linked.id, other.id), payments.list().map { it.id }.toSet())

        assertEquals(client, payments.setClient(other.id, client)?.clientId)
        assertEquals(setOf(linked.id, other.id), payments.list(clientId = client).map { it.id }.toSet())

        assertNull(payments.setClient(linked.id, null)?.clientId)
        assertEquals(listOf(other.id), payments.list(clientId = client).map { it.id })
    }

    @Test
    fun `another tenant can't relink a payment`() = runBlocking<Unit> {
        val mine = PaymentRepository(mongoModule, tenantId).bill("Andaimes")

        assertNull(PaymentRepository(mongoModule, ObjectId()).setClient(mine.id, ObjectId()))
        assertNull(PaymentRepository(mongoModule, tenantId).findById(mine.id)?.clientId)
    }

    @Test
    fun `only an unpaid payment can be cancelled, and a cancelled one is never marked paid`() = runBlocking<Unit> {
        val payments = PaymentRepository(mongoModule, tenantId)
        val open = payments.bill("Tinta")
        assertEquals(PaymentStatus.CANCELLED, assertIs<PaymentCancel.Done>(payments.cancel(open.id)).payment.status)
        assertEquals("payment_cancelled", assertIs<PaymentCancel.Refused>(payments.cancel(open.id)).reason)
        assertEquals(PaymentStatus.CANCELLED, payments.markPaid(open.id)?.status)

        val paid = payments.bill("Andaimes")
        val paidAt = payments.markPaid(paid.id)?.paidAt
        assertEquals(paidAt, payments.markPaid(paid.id)?.paidAt, "marking paid again changes nothing")
        assertEquals("payment_paid", assertIs<PaymentCancel.Refused>(payments.cancel(paid.id)).reason)
        assertIs<PaymentCancel.NotFound>(payments.cancel(ObjectId()))
        assertIs<PaymentCancel.NotFound>(PaymentRepository(mongoModule, ObjectId()).cancel(paid.id), "another tenant's payment")
    }

    @Test
    fun `deleting removes a payment of any status, only for its own tenant`() = runBlocking<Unit> {
        val payments = PaymentRepository(mongoModule, tenantId)
        val paid = payments.bill("Tinta")
        payments.markPaid(paid.id)

        assertNull(PaymentRepository(mongoModule, ObjectId()).delete(paid.id))
        assertEquals(paid.id, payments.delete(paid.id)?.id)
        assertNull(payments.findById(paid.id))
        assertNull(payments.delete(paid.id))
    }
}
