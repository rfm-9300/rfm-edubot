package com.rfm.edubot.dashboard.crm

import com.mongodb.ErrorCategory
import com.mongodb.MongoServerException
import com.rfm.edubot.building.MeasurementRepository
import com.rfm.edubot.building.ProjectRepository
import com.rfm.edubot.crm.ClientDirectory
import com.rfm.edubot.crm.ClientFields
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.DirectoryDelete
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.InvoiceService
import com.rfm.edubot.crm.InvoicedWork
import com.rfm.edubot.crm.PaymentRepository
import com.rfm.edubot.crm.PdfGenerator
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.QuoteService
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.crm.SupplierRepository
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.moduleContext
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.DocumentTemplate
import com.rfm.edubot.tenant.model.Tenant
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

/** One company's CRM records, and the services that change them, for one request. */
internal class TenantCrm(mongo: MongoModule, val tenant: Tenant) {
    val clients by lazy { ClientRepository(mongo, tenant.id) }
    val quotes by lazy { QuoteRepository(mongo, tenant.id) }
    val invoices by lazy { InvoiceRepository(mongo, tenant.id) }
    val suppliers by lazy { SupplierRepository(mongo, tenant.id) }
    val employees by lazy { EmployeeRepository(mongo, tenant.id) }
    val payments by lazy { PaymentRepository(mongo, tenant.id) }
    val standardItems by lazy { StandardItemRepository(mongo, tenant.id) }
    val clientServices by lazy { ClientServiceRepository(mongo, tenant.id) }
    val projects by lazy { ProjectRepository(mongo, tenant.id) }
    val measurements by lazy { MeasurementRepository(mongo, tenant.id) }

    val clientDirectory: ClientDirectory get() = ClientDirectory(clients, ClientFields.of(tenant))
    val quoteService: QuoteService get() = QuoteService(quotes, invoices, clientServices, clients)
    val invoiceService: InvoiceService get() = InvoiceService(
        invoices, clientServices, clients,
        listOf(InvoicedWork { measurements.releaseInvoice(it) }, InvoicedWork { projects.releaseRetentionInvoice(it) }),
    )

    val pdfGenerator: PdfGenerator get() = PdfGenerator()
    val documentTemplate: DocumentTemplate get() = tenant.documentTemplate.withCompanyFallback(tenant.name)
}

/** The CRM of the company the module gate let through. */
internal fun DashboardDeps.crm(call: ApplicationCall): TenantCrm = TenantCrm(mongo, call.moduleContext().tenant)

@Serializable internal data class ArchiveStateDto(val archivedAt: String?)

/**
 * Removing a client, supplier or employee. DELETE only works when no document refers to the record
 * (409 `in_use` otherwise, so the dashboard can offer to archive it); archive hides it from lists and
 * pickers, restore brings it back.
 */
internal fun Route.removableDirectory(
    path: String,
    deps: DashboardDeps,
    remove: suspend TenantCrm.(ObjectId) -> DirectoryDelete,
    archive: suspend TenantCrm.(ObjectId, Boolean) -> ArchiveStateDto?,
) {
    delete("/$path/{id}") {
        val crm = deps.crm(call)
        val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
        when (crm.remove(id)) {
            DirectoryDelete.NOT_FOUND -> call.respond(HttpStatusCode.NotFound)
            DirectoryDelete.IN_USE -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "in_use"))
            DirectoryDelete.DELETED -> call.respond(mapOf("deleted" to true))
        }
    }
    for ((action, archived) in listOf("archive" to true, "restore" to false)) {
        post("/$path/{id}/$action") {
            val crm = deps.crm(call)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            call.respond(crm.archive(id, archived) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
    }
}

internal class Written<T>(val value: T)

/**
 * Runs a supplier or employee write. Phone is unique per tenant in each of those collections, so a
 * duplicate-key clash answers 409 `phone_taken` and returns null instead of surfacing as a 500.
 */
internal suspend fun <T> ApplicationCall.uniquePhone(write: suspend () -> T): Written<T>? =
    try {
        Written(write())
    } catch (e: MongoServerException) {
        // Inserts fail with MongoWriteException, findOneAndUpdate with MongoCommandException; both carry the code.
        if (ErrorCategory.fromErrorCode(e.code) != ErrorCategory.DUPLICATE_KEY) throw e
        respond(HttpStatusCode.Conflict, mapOf("error" to "phone_taken"))
        null
    }
