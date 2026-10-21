package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.crm.PaymentCancel
import com.rfm.edubot.crm.api.CreatePaymentRequest
import com.rfm.edubot.crm.api.dto
import com.rfm.edubot.crm.api.dtos
import com.rfm.edubot.crm.model.Payment
import com.rfm.edubot.crm.model.PaymentStatus
import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.moduleContext
import com.rfm.edubot.dashboard.requireModule
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
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

/** `/crm/payments`: bills the company pays to suppliers and employees, optionally for one of its clients and projects. */
internal object PaymentRoutes : DashboardFeature(DashboardModules.PAYMENTS) {
    override fun Route.routes(deps: DashboardDeps) {
        route("/crm") {
            get("/payments") {
                val crm = deps.crm(call)
                val supplierId = call.request.queryParameters["supplierId"]?.takeIf { it.isNotBlank() }?.let { runCatching { ObjectId(it) }.getOrNull() }
                val employeeId = call.request.queryParameters["employeeId"]?.takeIf { it.isNotBlank() }?.let { runCatching { ObjectId(it) }.getOrNull() }
                val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let { runCatching { PaymentStatus.valueOf(it.uppercase()) }.getOrNull() }
                val clientId = call.request.queryParameters["clientId"]?.takeIf { it.isNotBlank() }?.let { runCatching { ObjectId(it) }.getOrNull() }
                val payments = crm.payments.list(supplierId, employeeId, status, clientId)
                call.respond(payments.dtos(crm.suppliers, crm.employees, crm.clients, crm.projectLabels(payments.mapNotNull { it.projectId })))
            }
            post("/payments") {
                val ctx = call.moduleContext()
                val crm = deps.crm(call)
                val request = call.receive<CreatePaymentRequest>()
                if (request.items.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "at least one item is required"))
                val supplierRaw = request.supplierId?.takeIf { it.isNotBlank() }
                val employeeRaw = request.employeeId?.takeIf { it.isNotBlank() }
                if ((supplierRaw == null) == (employeeRaw == null)) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "choose a supplier or an employee"))
                }
                val dueDate = runCatching { LocalDate.parse(request.dueDate) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "due date required"))
                val clientId = request.clientId?.takeIf { it.isNotBlank() }?.let { call.paymentClient(ctx, crm, it) ?: return@post }
                val projectId = request.projectId?.takeIf { it.isNotBlank() }?.let { call.paymentProject(ctx, crm, it) ?: return@post }
                val items = request.items.map { it.toLineItem() }
                val payment = if (employeeRaw != null) {
                    if (!ctx.requireModule(DashboardModules.EMPLOYEES)) return@post call.respond(HttpStatusCode.Forbidden)
                    val employeeId = runCatching { ObjectId(employeeRaw) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "employee required"))
                    if (crm.employees.findById(employeeId) == null) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "employee not found"))
                    crm.payments.create(supplierId = null, employeeId = employeeId, items = items, dueDate = dueDate, notes = request.notes, clientId = clientId, projectId = projectId)
                } else {
                    val supplierId = runCatching { ObjectId(supplierRaw) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "supplier required"))
                    if (crm.suppliers.findById(supplierId) == null) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "supplier not found"))
                    crm.payments.create(supplierId = supplierId, employeeId = null, items = items, dueDate = dueDate, notes = request.notes, clientId = clientId, projectId = projectId)
                }
                call.respond(HttpStatusCode.Created, crm.paymentDto(payment))
            }
            get("/payments/{id}") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
                val payment = crm.payments.findById(id) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(crm.paymentDto(payment))
            }
            patch("/payments/{id}/paid") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val payment = crm.payments.markPaid(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(crm.paymentDto(payment))
            }
            patch("/payments/{id}/client") {
                val ctx = call.moduleContext()
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val request = call.receive<PaymentClientRequest>()
                val clientId = request.clientId?.takeIf { it.isNotBlank() }?.let { call.paymentClient(ctx, crm, it) ?: return@patch }
                val payment = crm.payments.setClient(id, clientId) ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(crm.paymentDto(payment))
            }
            patch("/payments/{id}/project") {
                val ctx = call.moduleContext()
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val request = call.receive<PaymentProjectRequest>()
                val projectId = request.projectId?.takeIf { it.isNotBlank() }?.let { call.paymentProject(ctx, crm, it) ?: return@patch }
                val payment = crm.payments.setProject(id, projectId) ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(crm.paymentDto(payment))
            }
            post("/payments/{id}/cancel") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                when (val cancel = crm.payments.cancel(id)) {
                    PaymentCancel.NotFound -> call.respond(HttpStatusCode.NotFound)
                    is PaymentCancel.Refused -> call.respond(HttpStatusCode.Conflict, mapOf("error" to cancel.reason))
                    is PaymentCancel.Done -> call.respond(crm.paymentDto(cancel.payment))
                }
            }
            delete("/payments/{id}") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
                crm.payments.delete(id) ?: return@delete call.respond(HttpStatusCode.NotFound)
                call.respond(mapOf("deleted" to true))
            }
        }
    }
}

/**
 * The tenant's client [raw] names, for linking a payment to it. Answers 403 without the clients
 * module and 400 for an unknown client, returning null after responding.
 */
private suspend fun ApplicationCall.paymentClient(ctx: DashboardContext, crm: TenantCrm, raw: String): ObjectId? {
    if (!ctx.requireModule(DashboardModules.CLIENTS)) {
        respond(HttpStatusCode.Forbidden)
        return null
    }
    val id = runCatching { ObjectId(raw) }.getOrNull()?.takeIf { crm.clients.findById(it) != null }
    if (id == null) respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
    return id
}

/**
 * The tenant's construction project [raw] names, for a payment that was for it. Answers 403 without the projects
 * module and 400 for an unknown project, returning null after responding.
 */
private suspend fun ApplicationCall.paymentProject(ctx: DashboardContext, crm: TenantCrm, raw: String): ObjectId? {
    if (!ctx.requireModule(DashboardModules.PROJECTS)) {
        respond(HttpStatusCode.Forbidden)
        return null
    }
    val id = runCatching { ObjectId(raw) }.getOrNull()?.takeIf { crm.projects.findById(it) != null }
    if (id == null) respond(HttpStatusCode.BadRequest, mapOf("error" to "project not found"))
    return id
}

private suspend fun TenantCrm.projectLabels(ids: List<ObjectId>): Map<ObjectId, String> =
    projects.byIds(ids).associate { it.id to "${it.number} · ${it.name}" }

private suspend fun TenantCrm.paymentDto(payment: Payment) = payment.dto(
    supplier = payment.supplierId?.let { suppliers.findById(it) },
    employee = payment.employeeId?.let { employees.findById(it) },
    client = payment.clientId?.let { clients.findById(it) },
    projectLabel = payment.projectId?.let { projects.findById(it) }?.let { "${it.number} · ${it.name}" },
)

/** A blank or missing `clientId` unlinks the payment from its client. */
@Serializable
private data class PaymentClientRequest(val clientId: String? = null)

/** A blank or missing `projectId` unlinks the payment from its project. */
@Serializable
private data class PaymentProjectRequest(val projectId: String? = null)
