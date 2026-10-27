package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.crm.ClientServiceDelete
import com.rfm.edubot.crm.EmployeeAssignment
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.InvoiceService
import com.rfm.edubot.crm.QuoteInvoicing
import com.rfm.edubot.crm.ServiceDays
import com.rfm.edubot.crm.api.CreateClientServiceRequest
import com.rfm.edubot.crm.api.InvoiceClientServicesRequest
import com.rfm.edubot.crm.api.UpdateClientServiceRequest
import com.rfm.edubot.crm.api.dto
import com.rfm.edubot.crm.api.dtos
import com.rfm.edubot.crm.api.serviceItemsError
import com.rfm.edubot.crm.eurToCents
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.requireModules
import com.rfm.edubot.http.ErrorBody
import com.rfm.edubot.http.dateValue
import com.rfm.edubot.http.idValue
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import org.bson.types.ObjectId

/** `/crm/services`: Serviços, the work done for clients, and invoicing it (which needs invoices too). */
internal object ServiceRoutes : DashboardFeature(DashboardModules.SERVICES) {
    override fun Route.routes(deps: DashboardDeps) {
        route("/crm") {
            get("/services") {
                val crm = deps.crm(call)
                val clientId = call.request.queryParameters["clientId"]?.takeIf { it.isNotBlank() }?.let { runCatching { ObjectId(it) }.getOrNull() }
                val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let { runCatching { ClientServiceStatus.valueOf(it.uppercase()) }.getOrNull() }
                val quoteId = call.request.queryParameters["quoteId"]?.takeIf { it.isNotBlank() }?.let { idValue(it, "quoteId") }
                val employeeId = call.request.queryParameters["employeeId"]?.takeIf { it.isNotBlank() }?.let { idValue(it, "employeeId") }
                val days = call.serviceDays(crm.tenant) ?: return@get
                val rows = if (quoteId != null) crm.clientServices.findByQuoteId(quoteId) else crm.clientServices.list(clientId, status, employeeId, days.range)
                call.respond(rows.dtos(crm.clients, crm.teamDirectory()))
            }
            get("/services/{id}") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
                val service = crm.clientServices.findById(id) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(service.dto(crm.clients.findById(service.clientId), crm.doneBy(service.employeeId)))
            }
            post("/services") {
                val crm = deps.crm(call)
                val request = call.receive<CreateClientServiceRequest>()
                val clientId = runCatching { ObjectId(request.clientId) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client required"))
                val client = crm.clients.findById(clientId) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
                if (request.name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name required"))
                serviceItemsError(request.items)?.let { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
                val employee = when (val picked = crm.assignableEmployee(request.employeeId)) {
                    EmployeePick.Invalid -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "employee_not_found"))
                    is EmployeePick.Picked -> picked.employee
                }
                val service = crm.clientServices.create(
                    clientId = clientId,
                    name = request.name,
                    notes = request.notes,
                    quantity = request.quantity,
                    unit = request.unit,
                    unitPriceCents = eurToCents(request.unitPriceEur),
                    bookingServiceId = request.bookingServiceId?.takeIf { it.isNotBlank() }?.let { runCatching { ObjectId(it) }.getOrNull() },
                    catalogItemId = request.catalogItemId,
                    performedAt = request.performedAt?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    items = request.items.map { it.toLineItem() },
                    employeeId = employee?.id,
                )
                call.respond(HttpStatusCode.Created, service.dto(client, employee))
            }
            requireModules(DashboardModules.INVOICES) {
                post("/services/invoice") {
                    val crm = deps.crm(call)
                    val request = call.receive<InvoiceClientServicesRequest>()
                    val clientId = runCatching { ObjectId(request.clientId) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client required"))
                    val dueDate = runCatching { LocalDate.parse(request.dueDate) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "due date required"))
                    val ids = request.serviceIds.mapNotNull { runCatching { ObjectId(it) }.getOrNull() }
                    when (val outcome = crm.invoiceService.invoiceClientServices(clientId, ids, dueDate)) {
                        is InvoiceService.ServicesInvoiced.Refused -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to outcome.reason))
                        InvoiceService.ServicesInvoiced.ClientNotFound -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
                        is InvoiceService.ServicesInvoiced.Invoiced -> call.respond(HttpStatusCode.Created, outcome.invoice.dto(outcome.client))
                    }
                }
            }
            patch("/services/{id}") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val request = call.receive<UpdateClientServiceRequest>()
                serviceItemsError(request.items)?.let { return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
                val status = request.status?.takeIf { it.isNotBlank() }?.let { runCatching { ClientServiceStatus.valueOf(it.uppercase()) }.getOrNull() }
                // Resending who already does the work keeps them, even once they are inactive.
                val reassigned = request.employeeId?.takeIf { it != crm.clientServices.findById(id)?.employeeId?.toHexString() }
                val assignment = when (val picked = reassigned?.let { crm.assignableEmployee(it) }) {
                    null -> null
                    EmployeePick.Invalid -> return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "employee_not_found"))
                    is EmployeePick.Picked -> EmployeeAssignment(picked.employee?.id)
                }
                if (status == ClientServiceStatus.OPEN) {
                    val existing = crm.clientServices.findById(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
                    val quote = existing.quoteId?.takeIf { existing.status == ClientServiceStatus.CANCELLED }?.let { crm.quotes.findById(it) }
                    if (quote != null && QuoteInvoicing.billedBy(quote, crm.invoices, crm.clientServices) != null) {
                        return@patch call.respond(HttpStatusCode.Conflict, mapOf("error" to "quote_billed"))
                    }
                }
                val service = crm.clientServices.update(
                    id = id,
                    name = request.name,
                    notes = request.notes,
                    quantity = request.quantity,
                    unit = request.unit,
                    unitPriceCents = request.unitPriceEur?.let { eurToCents(it) },
                    performedAt = request.performedAt?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    status = status,
                    items = request.items?.takeIf { it.isNotEmpty() }?.map { it.toLineItem() },
                    employee = assignment,
                ) ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(service.dto(crm.clients.findById(service.clientId), crm.doneBy(service.employeeId)))
            }
            delete("/services/{id}") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
                when (crm.clientServices.delete(id)) {
                    ClientServiceDelete.NotFound -> call.respond(HttpStatusCode.NotFound)
                    ClientServiceDelete.Invoiced -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "invoiced services cannot be deleted"))
                    ClientServiceDelete.Removed -> call.respond(mapOf("deleted" to true))
                }
            }
        }
    }
}

private class DaysParam(val range: ServiceDays?)

private const val MAX_RANGE_DAYS = 366

/**
 * `from` and `to` (`yyyy-MM-dd`, both or neither) narrow the list to those days, at most a year, as an employee's
 * report prints them. Null after answering 400 `invalid_range` for a range backwards, half given or too long.
 */
private suspend fun ApplicationCall.serviceDays(tenant: Tenant): DaysParam? {
    val params = request.queryParameters
    val from = params["from"]?.takeIf { it.isNotBlank() }?.let { dateValue(it, "from") }
    val to = params["to"]?.takeIf { it.isNotBlank() }?.let { dateValue(it, "to") }
    if (from == null && to == null) return DaysParam(null)
    if (from == null || to == null || to < from || from.daysUntil(to) >= MAX_RANGE_DAYS) {
        respond(HttpStatusCode.BadRequest, ErrorBody("invalid_range"))
        return null
    }
    return DaysParam(ServiceDays(from, to, TimeZone.of(TenantTimeZones.normalize(tenant.timezone))))
}

/** The employees directory, when the company has that module; rows then name who does the work. */
private fun TenantCrm.teamDirectory(): EmployeeRepository? =
    employees.takeIf { DashboardModules.EMPLOYEES in DashboardModules.effectiveFor(tenant) }

private suspend fun TenantCrm.doneBy(employeeId: ObjectId?): Employee? =
    employeeId?.let { id -> teamDirectory()?.findById(id) }

private sealed interface EmployeePick {
    /** [employee] null: nobody, from a blank id. */
    data class Picked(val employee: Employee?) : EmployeePick
    data object Invalid : EmployeePick
}

/** A blank [id] picks nobody; otherwise it must be an active employee of this company. */
private suspend fun TenantCrm.assignableEmployee(id: String?): EmployeePick {
    if (id.isNullOrBlank()) return EmployeePick.Picked(null)
    val employee = runCatching { ObjectId(id) }.getOrNull()?.let { teamDirectory()?.findById(it) }
    return if (employee == null || employee.archivedAt != null) EmployeePick.Invalid else EmployeePick.Picked(employee)
}
