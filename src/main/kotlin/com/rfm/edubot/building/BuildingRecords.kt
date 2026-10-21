package com.rfm.edubot.building

import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.InvoiceService
import com.rfm.edubot.crm.PaymentRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.QuoteService
import com.rfm.edubot.crm.SupplierRepository
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.timesheets.ShiftRepository
import com.rfm.edubot.timesheets.WorkSiteRepository
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId

/**
 * One company's Building records and the CRM and time clock records they point at, which its services write and
 * its views read.
 */
class BuildingRecords(mongo: MongoModule, val tenantId: ObjectId) {
    val projects by lazy { ProjectRepository(mongo, tenantId) }
    val logs by lazy { SiteLogRepository(mongo, tenantId) }
    val photos by lazy { SitePhotoRepository(mongo, tenantId) }
    val issues by lazy { SiteIssueRepository(mongo, tenantId) }
    val changes by lazy { ChangeOrderRepository(mongo, tenantId) }
    val measurements by lazy { MeasurementRepository(mongo, tenantId) }
    val clients by lazy { ClientRepository(mongo, tenantId) }
    val quotes by lazy { QuoteRepository(mongo, tenantId) }
    val invoices by lazy { InvoiceRepository(mongo, tenantId) }
    val clientServices by lazy { ClientServiceRepository(mongo, tenantId) }
    val employees by lazy { EmployeeRepository(mongo, tenantId) }
    val suppliers by lazy { SupplierRepository(mongo, tenantId) }
    val payments by lazy { PaymentRepository(mongo, tenantId) }
    val sites by lazy { WorkSiteRepository(mongo, tenantId) }
    val shifts by lazy { ShiftRepository(mongo, tenantId) }
    val events by lazy { BuildingEvents(DomainEventLog(mongo), tenantId) }
    val notifications by lazy { NotificationRepository(mongo) }

    /** The CRM's own writes: a project's quote turns accepted, and a measurement's invoice is an invoice like the others. */
    val quoteService: QuoteService get() = QuoteService(quotes, invoices, clientServices, clients)
    val invoiceService: InvoiceService get() = InvoiceService(invoices, clientServices, clients)
}

/**
 * Who makes a write and when: [who] is the person's email (or "operator" for the platform's), [now] is stored
 * to the millisecond, as MongoDB keeps it, and [today] is the company's day.
 */
class Stamp(val who: String, now: Instant, val zone: TimeZone) {
    val now: Instant = Instant.fromEpochMilliseconds(now.toEpochMilliseconds())
    val today: LocalDate = this.now.toLocalDateTime(zone).date

    fun day(at: Instant): LocalDate = at.toLocalDateTime(zone).date
}

/** What a Building write did: the record, or why not, as a stable code the dashboard translates. */
sealed interface Outcome<out T> {
    data class Done<T>(val value: T) : Outcome<T>

    /**
     * The request can't be done: invalid ([conflict] false), or the record's state or another write stands in the
     * way ([conflict] true). [detail] is what it is about: a field, a measured line's key, the record already there.
     */
    data class Refused(val error: String, val conflict: Boolean = false, val detail: String? = null) : Outcome<Nothing>

    data object NotFound : Outcome<Nothing>
}

internal fun invalid(error: String, detail: String? = null) = Outcome.Refused(error, conflict = false, detail = detail)

internal fun conflict(error: String, detail: String? = null) = Outcome.Refused(error, conflict = true, detail = detail)
