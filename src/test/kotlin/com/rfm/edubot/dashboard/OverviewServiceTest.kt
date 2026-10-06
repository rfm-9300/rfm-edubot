package com.rfm.edubot.dashboard

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Tenant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Testcontainers
class OverviewServiceTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "overview"))
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }
    }

    private val zoneId = "Europe/Lisbon"
    private val zone = TimeZone.of(zoneId)

    @Test
    fun `extended overview adds trends, agenda, feed and top clients`() = runBlocking {
        val seed = seed()
        val overview = OverviewService(mongoModule).build(seed.tenant, extended = true)

        assertEquals(OverviewMath.trendMonthKeys(seed.window.today), overview.cashFlow.map { it.month })
        val thisMonth = overview.cashFlow.last()
        assertEquals(120_00, thisMonth.inCents, "paid by paidAt plus the legacy updatedAt fallback")
        assertEquals(40_00, thisMonth.outCents)
        assertEquals(overview.cash?.collectedThisMonthCents, thisMonth.inCents, "chart and KPI use the same paid rule")
        assertEquals(50_00, overview.cashFlow[4].inCents)
        assertEquals(40_00L, overview.cashFlow.sumOf { it.outCents })

        assertEquals(14, overview.activity.size)
        assertEquals(3, overview.activity.last().count)
        assertEquals(2, overview.activity[10].count)
        assertEquals(5, overview.activity.sumOf { it.count })

        assertEquals(listOf(seed.bookingToday.toHexString()), overview.agenda.map { it.id })
        assertEquals("Limpeza", overview.agenda.single().service)

        assertEquals(listOf("Hotel Miradouro", "Café Central"), overview.topClients.map { it.name })
        assertEquals(170_00, overview.topClients[0].billedCents)
        assertEquals(100_00, overview.topClients[0].paidCents)
        assertEquals(2, overview.topClients[0].invoiceCount)
        assertEquals(100_00, overview.topClients[1].billedCents)

        assertTrue(OverviewMath.RECENT_BOOKING_CREATED in overview.recent.map { it.kind })
        val accepted = overview.recent.first { it.kind == OverviewMath.RECENT_QUOTE_ACCEPTED }
        assertEquals("Hotel Miradouro", accepted.name)
        assertEquals(200_00, accepted.amountCents)
        assertTrue(overview.recent.size <= OverviewMath.RECENT_LIMIT)
        assertTrue(overview.recent.zipWithNext().all { (a, b) -> Instant.parse(a.at) >= Instant.parse(b.at) }, "newest first")
        assertTrue(overview.recent.none { it.id == seed.cancelledInvoice.toHexString() })
        assertTrue(overview.recent.none { it.id == seed.cancelledBooking.toHexString() })
        val payment = overview.recent.firstOrNull { it.kind == OverviewMath.RECENT_PAYMENT_PAID }
        assertEquals("Tintas Lusas", payment?.name)
    }

    @Test
    fun `classic overview skips the extended queries`() = runBlocking {
        val overview = OverviewService(mongoModule).build(seed().tenant)
        assertTrue(overview.cashFlow.isEmpty())
        assertTrue(overview.activity.isEmpty())
        assertTrue(overview.agenda.isEmpty())
        assertTrue(overview.recent.isEmpty())
        assertTrue(overview.topClients.isEmpty())
    }

    @Test
    fun `hidden cards drop their extended blocks and feed events`() = runBlocking {
        val seed = seed(hidden = listOf(OverviewHomeLayout.FINANCEIRO, OverviewHomeLayout.CALENDAR))
        val overview = OverviewService(mongoModule).build(seed.tenant, extended = true)
        assertTrue(overview.cashFlow.isEmpty())
        assertTrue(overview.agenda.isEmpty())
        assertEquals(14, overview.activity.size)
        val kinds = overview.recent.map { it.kind }.toSet()
        assertTrue(kinds.none { it in setOf(OverviewMath.RECENT_INVOICE_PAID, OverviewMath.RECENT_INVOICE_ISSUED, OverviewMath.RECENT_PAYMENT_PAID) })
        assertTrue(OverviewMath.RECENT_BOOKING_CREATED !in kinds)
        assertTrue(OverviewMath.RECENT_QUOTE_ACCEPTED in kinds)
    }

    @Test
    fun `installments count as owed, overdue and collected part by part`() = runBlocking<Unit> {
        val now = SystemClock.now()
        val window = OverviewMath.window(now, zoneId)
        val tenant = Tenant(
            slug = "overview-${ObjectId().toHexString()}", name = "Installments", channels = emptyList(), timezone = zoneId,
            enabledModules = listOf(DashboardModules.OVERVIEW, DashboardModules.CLIENTS, DashboardModules.INVOICES),
            createdAt = now, updatedAt = now,
        )
        val t = tenant.id
        val client = ObjectId()
        val split = ObjectId()
        val receivedAt = window.monthStart + 1.minutes
        val overdueDay = window.today.minus(DatePeriod(days = 3)).toString()
        insert("crm.clients", doc(t, client).append("number", "CLT-001").append("name", "Casa Martins").append("createdAt", date(now - 5.days)))
        insert(
            "crm.invoices",
            invoice(t, client, "FAT-010", "PENDING", 400_00, created = now - 2.days, id = split)
                .append("dueDate", overdueDay)
                .append(
                    "installments",
                    listOf(
                        Document("amountCents", 200_00L).append("dueDate", window.monthStart.toString().take(10)).append("paidAt", date(receivedAt)),
                        Document("amountCents", 200_00L).append("dueDate", overdueDay),
                    ),
                ),
            invoice(t, client, "FAT-011", "PENDING", 100_00, created = now - 1.days),
        )

        val overview = OverviewService(mongoModule).build(tenant, extended = true)
        val cash = overview.cash!!
        assertEquals(300_00, cash.outstandingCents, "the installment received is no longer owed")
        assertEquals(200_00, cash.overdueCents, "only the part past its date is overdue")
        assertEquals(1, cash.overdueCount)
        assertEquals(200_00, cash.collectedThisMonthCents)
        assertEquals(200_00, overview.cashFlow.last().inCents)
        assertEquals(200_00, cash.topOverdue.single().amountCents)
        assertEquals(200_00, overview.topClients.single().paidCents)
        assertEquals(200_00, overview.attention.single { it.kind == OverviewMath.KIND_OVERDUE_INVOICE }.amountCents)
    }

    private data class Seed(
        val tenant: Tenant,
        val window: OverviewMath.Window,
        val bookingToday: ObjectId,
        val cancelledBooking: ObjectId,
        val cancelledInvoice: ObjectId,
    )

    private suspend fun seed(hidden: List<String> = emptyList()): Seed {
        val now = SystemClock.now()
        val window = OverviewMath.window(now, zoneId)
        val tenant = Tenant(
            slug = "overview-${ObjectId().toHexString()}",
            name = "Overview test",
            channels = emptyList(),
            timezone = zoneId,
            enabledModules = listOf(
                DashboardModules.OVERVIEW, DashboardModules.CLIENTS, DashboardModules.QUOTES, DashboardModules.INVOICES,
                DashboardModules.PAYMENTS, DashboardModules.SUPPLIERS, DashboardModules.BOOKINGS, DashboardModules.CONVERSATIONS,
            ),
            overviewHiddenCards = hidden,
            createdAt = now,
            updatedAt = now,
        )
        val t = tenant.id
        val hotel = ObjectId()
        val cafe = ObjectId()
        val supplier = ObjectId()
        val service = ObjectId()
        val bookingToday = ObjectId()
        val cancelledBooking = ObjectId()
        val cancelledInvoice = ObjectId()
        val lastMonthStart = window.lastMonthStart

        insert(
            "crm.clients",
            doc(t, hotel).append("number", "CLT-001").append("name", "Hotel Miradouro").append("createdAt", date(now - 2.days)),
            doc(t, cafe).append("number", "CLT-002").append("name", "Café Central").append("createdAt", date(now - 40.days)),
        )
        insert("crm.suppliers", doc(t, supplier).append("number", "FOR-001").append("name", "Tintas Lusas").append("createdAt", date(now - 90.days)))
        insert(
            "crm.invoices",
            invoice(t, hotel, "FAT-001", "PAID", 100_00, created = window.monthStart - 5.days, paidAt = window.monthStart + 1.minutes),
            invoice(t, cafe, "FAT-002", "PAID", 50_00, created = lastMonthStart - 3.days, paidAt = lastMonthStart + 1.days),
            invoice(t, hotel, "FAT-003", "PENDING", 70_00, created = now - 1.days),
            invoice(t, cafe, "FAT-004", "CANCELLED", 999_00, created = now - 3.days, id = cancelledInvoice),
            invoice(t, cafe, "FAT-005", "PAID", 20_00, created = window.monthStart - 40.days, legacyPaidAt = window.monthStart + 2.minutes),
            invoice(t, cafe, "FAT-006", "PAID", 30_00, created = window.monthStart - 200.days, paidAt = window.monthStart - 200.days),
        )
        insert(
            "crm.payments",
            doc(t, ObjectId()).append("number", "PAG-001").append("supplierId", supplier).append("status", "PAID").append("totalCents", 40_00L)
                .append("dueDate", window.today.toString()).append("paidAt", date(window.monthStart + 3.minutes))
                .append("createdAt", date(window.monthStart - 2.days)).append("updatedAt", date(window.monthStart + 3.minutes)),
        )
        insert(
            "crm.quotes",
            doc(t, ObjectId()).append("number", "ORC-001").append("clientId", hotel).append("status", "ACEITO").append("totalCents", 200_00L)
                .append("createdAt", date(now - 6.days)).append("updatedAt", date(now - 2.hours)),
        )
        insert("bookings.services", doc(t, service).append("name", "Limpeza").append("durationMinutes", 90).append("active", true).append("createdAt", date(now)).append("updatedAt", date(now)))
        insert(
            "bookings.appointments",
            booking(t, service, "Ana", start = window.todayStart + 15.hours, status = "CONFIRMED", created = now - 30.minutes, id = bookingToday),
            booking(t, service, "Bruno", start = window.todayStart + 9.hours, status = "CANCELLED", created = now - 10.minutes, id = cancelledBooking),
            booking(t, service, "Carla", start = window.tomorrowStart + 10.hours, status = "PENDING", created = now - 5.days),
        )
        val threeDaysAgo = window.today.minus(DatePeriod(days = 3)).atStartOfDayIn(zone) + 12.hours
        insert(
            "messages",
            *(listOf(window.todayStart + 1.minutes, window.todayStart + 2.minutes, window.todayStart + 3.minutes, threeDaysAgo, threeDaysAgo + 1.minutes) +
                window.today.minus(DatePeriod(days = 20)).atStartOfDayIn(zone))
                .map { at -> doc(t, ObjectId()).append("role", "USER").append("createdAt", date(at)) }
                .toTypedArray(),
        )
        assertEquals(window.today.plus(DatePeriod(days = 1)).atStartOfDayIn(zone), window.tomorrowStart)
        return Seed(tenant, window, bookingToday, cancelledBooking, cancelledInvoice)
    }

    private fun doc(tenantId: ObjectId, id: ObjectId) = Document("_id", id).append("tenantId", tenantId)

    private fun invoice(
        tenantId: ObjectId,
        clientId: ObjectId,
        number: String,
        status: String,
        cents: Long,
        created: Instant,
        paidAt: Instant? = null,
        legacyPaidAt: Instant? = null,
        id: ObjectId = ObjectId(),
    ): Document {
        val d = doc(tenantId, id).append("number", number).append("clientId", clientId).append("status", status)
            .append("totalCents", cents).append("dueDate", "2099-01-01").append("createdAt", date(created))
        if (legacyPaidAt != null) return d.append("updatedAt", date(legacyPaidAt))
        return d.append("paidAt", paidAt?.let(::date)).append("updatedAt", date(paidAt ?: created))
    }

    private fun booking(tenantId: ObjectId, serviceId: ObjectId, name: String, start: Instant, status: String, created: Instant, id: ObjectId = ObjectId()) =
        doc(tenantId, id).append("serviceId", serviceId).append("contactName", name).append("contactPhone", "+351900000000")
            .append("startAt", date(start)).append("endAt", date(start + 1.hours)).append("status", status).append("source", "DASHBOARD")
            .append("createdAt", date(created)).append("updatedAt", date(created))

    private suspend fun insert(collection: String, vararg docs: Document) {
        mongoModule.database.getCollection<Document>(collection).insertMany(docs.toList())
    }

    private fun date(at: Instant) = Date(at.toEpochMilliseconds())
}
