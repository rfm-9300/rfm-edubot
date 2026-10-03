package com.rfm.edubot.crm

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.crm.model.ServiceSubmissionStatus
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class ServiceSubmissionRepositoryTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "service-submissions"))
            mongoModule.initialize()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }
    }

    private val employee = ObjectId()
    private val day = LocalDate(2026, 9, 28)

    private fun content(client: ObjectId = ObjectId(), hours: Double = 3.0) = SubmissionContent(
        clientId = client,
        name = " Pintura da sala ",
        notes = " ",
        items = listOf(lineItem("Pintura interior", quantity = hours, unitPriceEur = 18.5, unit = "h"), lineItem("Tinta", unitPriceEur = 24.9)),
        performedAt = day,
    )

    @Test
    fun `an employee changes or withdraws their own submission only while it is pending`() = runBlocking {
        val submissions = ServiceSubmissionRepository(mongoModule, ObjectId())
        val created = submissions.create(employee, content())
        assertEquals("Pintura da sala", created.name)
        assertNull(created.notes)
        assertEquals(5550L + 2490L, created.totalCents)
        assertEquals(ServiceSubmissionStatus.PENDING, created.status)
        assertEquals(created, submissions.findById(created.id))

        assertEquals(SubmissionChange.NotFound, submissions.update(created.id, ObjectId(), content()), "someone else's")
        val changed = assertIs<SubmissionChange.Done>(submissions.update(created.id, employee, content(hours = 2.0))).submission
        assertEquals(3700L + 2490L, changed.totalCents)

        assertNotNull(submissions.reject(created.id, " Faltam as horas de ontem ", "chefe@acme.test"))
        assertEquals(SubmissionChange.NotPending, submissions.update(created.id, employee, content()))
        assertEquals(SubmissionChange.NotPending, submissions.withdraw(created.id, employee))
        val rejected = submissions.findById(created.id)!!
        assertEquals(ServiceSubmissionStatus.REJECTED, rejected.status)
        assertEquals("Faltam as horas de ontem", rejected.rejectionReason)
        assertEquals("chefe@acme.test", rejected.reviewedBy)

        val other = submissions.create(employee, content())
        assertIs<SubmissionChange.Done>(submissions.withdraw(other.id, employee))
        assertNull(submissions.findById(other.id))
        assertEquals(listOf(created.id), submissions.list(employeeId = employee).map { it.id })
    }

    @Test
    fun `approving claims the submission once, keeping what was approved`() = runBlocking {
        val submissions = ServiceSubmissionRepository(mongoModule, ObjectId())
        val sent = submissions.create(employee, content())

        val outcomes = (1..5).map { async { submissions.approve(sent, sent.content(), ObjectId(), "chefe@acme.test") } }.awaitAll()
        val approved = outcomes.filterNotNull().single()
        assertEquals(ServiceSubmissionStatus.APPROVED, approved.status)
        assertNotNull(approved.serviceId)
        assertFalse(approved.adjusted)
        assertNull(submissions.reject(sent.id, null, "chefe@acme.test"), "decided once")

        val second = submissions.create(employee, content())
        val serviceId = ObjectId()
        val adjusted = submissions.approve(second, second.content().copy(items = content(hours = 2.5).items), serviceId, "chefe@acme.test")!!
        assertTrue(adjusted.adjusted)
        assertEquals(4625L + 2490L, adjusted.totalCents)
        assertEquals(setOf(sent.id, second.id), submissions.list(status = ServiceSubmissionStatus.APPROVED).map { it.id }.toSet())

        submissions.reopen(second, serviceId)
        val reopened = submissions.findById(second.id)!!
        assertEquals(ServiceSubmissionStatus.PENDING, reopened.status)
        assertNull(reopened.serviceId)
        assertEquals(second.totalCents, reopened.totalCents)
        assertFalse(reopened.adjusted)
    }

    @Test
    fun `submissions stay inside their company`() = runBlocking {
        val mine = ServiceSubmissionRepository(mongoModule, ObjectId())
        val theirs = ServiceSubmissionRepository(mongoModule, ObjectId())
        val created = mine.create(employee, content())
        assertNull(theirs.findById(created.id))
        assertEquals(SubmissionChange.NotFound, theirs.withdraw(created.id, employee))
        assertNull(theirs.approve(created, created.content(), ObjectId(), "x"))
        assertEquals(emptyList(), theirs.list(employeeId = employee))
    }
}
