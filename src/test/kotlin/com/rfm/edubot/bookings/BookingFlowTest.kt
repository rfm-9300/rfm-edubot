package com.rfm.edubot.bookings

import com.rfm.edubot.admin.StandardItemRequest
import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.bookings.model.AvailabilityRule
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.bookings.model.BookingStatus
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.StandardItem
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class BookingFlowTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "bookings"))
            mongoModule.initialize()
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
    fun `legacy booking services move into the catalog and existing rows follow them`() = runBlocking<Unit> {
        val tenant = tenant()
        val items = StandardItemRepository(mongoModule, tenant.id)
        items.create(StandardItem("srv-corte", "service", "Salão", "Corte de cabelo", "un", 18.0))
        val corte = ObjectId()
        val barba = ObjectId()
        val booking = ObjectId()
        val row = ObjectId()
        insert(
            "bookings.services",
            Document("_id", corte).append("tenantId", tenant.id).append("name", "corte de cabelo").append("durationMinutes", 45).append("active", true),
            Document("_id", barba).append("tenantId", tenant.id).append("name", "Barba").append("durationMinutes", 20).append("active", false),
        )
        insert(
            "bookings.appointments",
            Document("_id", booking).append("tenantId", tenant.id).append("serviceId", corte).append("contactName", "Ana").append("contactPhone", "+351911111111")
                .append("startAt", date(Instant.parse("2026-08-10T09:00:00Z"))).append("endAt", date(Instant.parse("2026-08-10T09:45:00Z")))
                .append("status", "CONFIRMED").append("source", "DASHBOARD").append("createdAt", date(Instant.parse("2026-08-01T09:00:00Z")))
                .append("updatedAt", date(Instant.parse("2026-08-01T09:00:00Z"))),
        )
        insert(
            "crm.client_services",
            Document("_id", row).append("tenantId", tenant.id).append("clientId", ObjectId()).append("name", "Barba").append("bookingServiceId", barba)
                .append("unitPriceCents", 800L).append("totalCents", 800L).append("status", "OPEN")
                .append("createdAt", date(Instant.parse("2026-08-01T09:00:00Z"))).append("updatedAt", date(Instant.parse("2026-08-01T09:00:00Z"))),
        )

        val before = BookingRepository(mongoModule, tenant.id).findById(booking)!!
        assertEquals("corte de cabelo", before.serviceName, "legacy rows still show their service before the migration runs")

        assertEquals(2, BookingCatalogMigration(mongoModule).run())

        val merged = items.findById("srv-corte")!!
        assertEquals(45, merged.durationMinutes, "same-named catalog service picks up the booking duration")
        assertTrue(merged.bookable)
        assertEquals(18.0, merged.defaultUnitPriceEur, "the catalog price is kept")
        val barbaItem = items.search().single { it.description == "Barba" }
        assertEquals(20, barbaItem.durationMinutes)
        assertEquals(false, barbaItem.bookable, "an inactive booking service stays off")
        assertEquals("Marcações", barbaItem.category)

        val after = BookingRepository(mongoModule, tenant.id).findById(booking)!!
        assertEquals("srv-corte", after.serviceId)
        assertEquals("corte de cabelo", after.serviceName)
        assertEquals(barbaItem.id, ClientServiceRepository(mongoModule, tenant.id).findById(row)!!.catalogItemId)

        assertEquals(0, BookingCatalogMigration(mongoModule).run(), "second start finds nothing to move")
        assertEquals(listOf("Barba", "Consulta", "Corte de cabelo"), items.search().map { it.description }.sorted(), "no duplicate catalog rows")
    }

    @Test
    fun `a booking links to the client with the same phone, or creates one`() = runBlocking<Unit> {
        val tenant = tenant()
        val deps = deps(tenant)
        val clients = ClientRepository(mongoModule, tenant.id)
        val existing = clients.create("Maria Silva", "+351 912 345 678")

        val first = deps.scheduler.create(newBooking(day(1), 10, contactName = "Maria", contactPhone = "912345678"))
        assertEquals(existing.id, first.clientId)

        val second = deps.scheduler.create(newBooking(day(1), 14, contactName = "João Costa", contactPhone = "+351 933 000 111"))
        val created = assertNotNull(second.clientId?.let { clients.findById(it) })
        assertEquals("João Costa", created.name)

        val third = deps.scheduler.create(newBooking(day(2), 10, contactName = "Walk-in", contactPhone = "n/a"))
        assertNull(third.clientId, "no client for a contact without a usable phone")
    }

    @Test
    fun `completing a booking bills it once in Servicos and undoing it cancels the row`() = runBlocking<Unit> {
        val tenant = tenant()
        val deps = deps(tenant)
        val booking = deps.scheduler.create(newBooking(day(1), 10, contactName = "Rita", contactPhone = "+351 966 555 444"))
        assertEquals(35_00, booking.priceCents, "price comes from the catalog")

        val done = deps.scheduler.setStatus(booking.id, BookingStatus.COMPLETED)
        val rows = ClientServiceRepository(mongoModule, tenant.id)
        val row = assertNotNull(done.clientServiceId?.let { rows.findById(it) })
        assertEquals(ClientServiceStatus.OPEN, row.status)
        assertEquals(35_00, row.unitPriceCents)
        assertEquals(booking.id, row.bookingId)
        assertEquals(booking.clientId, row.clientId)
        assertEquals("Consulta", row.name)
        assertEquals(day(1), row.performedAt)

        deps.scheduler.setStatus(booking.id, BookingStatus.CONFIRMED)
        assertEquals(ClientServiceStatus.CANCELLED, rows.findById(row.id)!!.status)

        val again = deps.scheduler.setStatus(booking.id, BookingStatus.COMPLETED)
        assertEquals(row.id, again.clientServiceId, "re-completing reopens the same row")
        assertEquals(ClientServiceStatus.OPEN, rows.findById(row.id)!!.status)
        assertEquals(1, rows.list(clientId = booking.clientId).size)
    }

    @Test
    fun `a booking stored without a price bills at the catalog price`() = runBlocking<Unit> {
        val tenant = tenant()
        val id = ObjectId()
        val start = at(day(1), 10)
        val created = Instant.parse("2026-08-01T09:00:00Z")
        insert(
            "bookings.appointments",
            Document("_id", id).append("tenantId", tenant.id).append("catalogItemId", "srv-consulta").append("serviceName", "Consulta")
                .append("contactName", "Legacy").append("contactPhone", "+351 917 000 000")
                .append("startAt", date(start)).append("endAt", date(start.plus(60, DateTimeUnit.MINUTE)))
                .append("status", "CONFIRMED").append("source", "WHATSAPP")
                .append("createdAt", date(created)).append("updatedAt", date(created)),
        )

        val done = deps(tenant).scheduler.setStatus(id, BookingStatus.COMPLETED)

        val row = assertNotNull(done.clientServiceId?.let { ClientServiceRepository(mongoModule, tenant.id).findById(it) })
        assertEquals(35_00, row.unitPriceCents)
        assertEquals("sessão", row.unit)
    }

    @Test
    fun `status changes respect the calendar`() = runBlocking<Unit> {
        val tenant = tenant()
        val deps = deps(tenant)
        val first = deps.scheduler.create(newBooking(day(1), 10, contactName = "A", contactPhone = "+351 910 000 001"))
        deps.scheduler.setStatus(first.id, BookingStatus.CANCELLED)
        val second = deps.scheduler.create(newBooking(day(1), 10, contactName = "B", contactPhone = "+351 910 000 002"))

        val edited = deps.scheduler.update(first.id, BookingChange(notes = "called to apologise"))
        assertEquals("called to apologise", edited.notes, "a cancelled booking can still be edited")

        assertFailsWith<BookingConflictException> { deps.scheduler.setStatus(first.id, BookingStatus.CONFIRMED) }

        deps.scheduler.setStatus(second.id, BookingStatus.NO_SHOW)
        assertEquals(BookingStatus.CONFIRMED, deps.scheduler.setStatus(first.id, BookingStatus.CONFIRMED).status, "a no-show frees the slot")

        val moved = deps.scheduler.update(first.id, BookingChange(startAt = at(day(1), 11)))
        assertEquals(60, (moved.endAt - moved.startAt).inWholeMinutes.toInt(), "rescheduling keeps the booking's length")
        val cleared = deps.scheduler.update(first.id, BookingChange(notes = ""))
        assertNull(cleared.notes)
    }

    @Test
    fun `customers can only book open future times`() = runBlocking<Unit> {
        val tenant = tenant()
        val deps = deps(tenant)
        val sunday = generateSequence(day(1)) { it.plus(DatePeriod(days = 1)) }.first { it.dayOfWeek.ordinal == 6 }
        val outside = assertFailsWith<BookingRuleException> {
            deps.scheduler.create(newBooking(sunday, 10, contactName = "C", contactPhone = "+351 910 000 003", source = BookingSource.WHATSAPP))
        }
        assertEquals(BookingScheduler.OUTSIDE_HOURS, outside.code)
        val past = assertFailsWith<BookingRuleException> {
            deps.scheduler.create(newBooking(LocalDate(2020, 1, 6), 10, contactName = "C", contactPhone = "+351 910 000 003", source = BookingSource.WHATSAPP))
        }
        assertEquals(BookingScheduler.IN_PAST, past.code)
        val staff = deps.scheduler.create(newBooking(sunday, 10, contactName = "C", contactPhone = "+351 910 000 003"))
        assertEquals(BookingSource.DASHBOARD, staff.source, "staff can book outside opening hours")
    }

    @Test
    fun `the WhatsApp tool books for the customer in the conversation`() = runBlocking<Unit> {
        val tenant = tenant()
        val tools = deps(tenant).tools()
        val start = "${nextWeekday()}T10:00"
        val result = tools.execute(
            ToolCall("1", "create_booking", buildJsonObject { put("service_id", "srv-consulta"); put("start_at", start) }),
            BookingCallContext(BookingSource.WHATSAPP, customerName = "Inês", customerPhone = "+351 915 000 000"),
        )
        assertEquals("true", result["created"]?.jsonPrimitive?.content, result.toString())
        assertEquals("Inês", result["contact_name"]?.jsonPrimitive?.content)
        assertEquals("Consulta", result["service_name"]?.jsonPrimitive?.content)
        val saved = BookingRepository(mongoModule, tenant.id).list().single()
        assertEquals(BookingSource.WHATSAPP, saved.source)
        assertEquals(BookingStatus.CONFIRMED, saved.status)
        assertNotNull(saved.clientId)

        val clash = tools.execute(
            ToolCall("2", "create_booking", buildJsonObject { put("service_id", "srv-consulta"); put("start_at", start) }),
            BookingCallContext(BookingSource.WHATSAPP, customerName = "Rui", customerPhone = "+351 916 000 000"),
        )
        assertEquals(BookingScheduler.CONFLICT, clash["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `catalog edits that omit booking fields keep them`() {
        val existing = StandardItem("srv-x", "service", "Cat", "Massagem", "un", 40.0, durationMinutes = 50, bookable = true)
        val request = StandardItemRequest("srv-x", "service", "Cat", "Massagem relaxante", "un", 45.0)
        val next = request.toStandardItem("srv-x", existing)
        assertEquals(50, next.durationMinutes)
        assertTrue(next.bookable)
        assertEquals("Massagem relaxante", next.description)
        val off = StandardItemRequest("srv-x", "service", "Cat", "Massagem", "un", 45.0, bookable = false).toStandardItem("srv-x", existing)
        assertEquals(false, off.bookable)
        assertEquals(50, off.durationMinutes, "switching booking off keeps the duration for later")
    }

    private suspend fun tenant(): Tenant {
        val now = Instant.parse("2026-08-01T00:00:00Z")
        val tenant = Tenant(
            slug = "bookings-${ObjectId().toHexString()}",
            name = "Bookings test",
            channels = emptyList(),
            timezone = zoneId,
            enabledModules = listOf(DashboardModules.OVERVIEW, DashboardModules.CLIENTS, DashboardModules.BOOKINGS, DashboardModules.CATALOG),
            createdAt = now,
            updatedAt = now,
        )
        StandardItemRepository(mongoModule, tenant.id).create(
            StandardItem("srv-consulta", "service", "Clínica", "Consulta", "sessão", 35.0, durationMinutes = 60, bookable = true)
        )
        AvailabilityRepository(mongoModule, tenant.id).replaceAll(
            (1..5).map { AvailabilityRule(tenantId = tenant.id, dayOfWeek = it, startLocal = "09:00", endLocal = "18:00") }
        )
        return tenant
    }

    private fun deps(tenant: Tenant) = bookingDeps(mongoModule, tenant, BookingSource.DASHBOARD)

    /** The [n]th weekday from tomorrow, so customer-facing checks never hit the past. */
    private fun day(n: Int): LocalDate =
        generateSequence(today().plus(DatePeriod(days = 1))) { it.plus(DatePeriod(days = 1)) }
            .filter { it.dayOfWeek.ordinal < 5 }
            .elementAt(n - 1)

    private fun nextWeekday(): LocalDate = day(1)

    private fun today(): LocalDate = kotlinx.datetime.Clock.System.now().toLocalDateTime(zone).date

    private fun at(date: LocalDate, hour: Int): Instant = LocalDateTime(date, LocalTime(hour, 0)).toInstant(zone)

    private fun newBooking(
        date: LocalDate,
        hour: Int,
        contactName: String,
        contactPhone: String,
        source: BookingSource = BookingSource.DASHBOARD,
    ) = NewBooking(
        serviceId = "srv-consulta",
        startAt = at(date, hour),
        contactName = contactName,
        contactPhone = contactPhone,
        status = BookingStatus.CONFIRMED,
        source = source,
    )

    private suspend fun insert(collection: String, vararg docs: Document) {
        mongoModule.database.getCollection<Document>(collection).insertMany(docs.toList())
    }

    private fun date(at: Instant) = Date(at.toEpochMilliseconds())
}
