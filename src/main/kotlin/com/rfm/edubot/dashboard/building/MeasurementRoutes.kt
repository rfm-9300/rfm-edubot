package com.rfm.edubot.dashboard.building

import com.rfm.edubot.building.api.MeasurementInvoiceRequest
import com.rfm.edubot.building.api.MeasurementInvoicedDto
import com.rfm.edubot.building.api.MeasurementRequest
import com.rfm.edubot.building.api.MeasurementUpdateRequest
import com.rfm.edubot.building.api.RetentionInvoicedDto
import com.rfm.edubot.building.api.measurementDtos
import com.rfm.edubot.building.api.projectDtos
import com.rfm.edubot.building.model.MeasurementStatus
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.requireModules
import com.rfm.edubot.http.dateValue
import com.rfm.edubot.http.idParam
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * `/building/measurements`: a project's measurements, from draft to approved to invoiced. Invoicing one needs
 * invoices too, as does invoicing a finished project's retention (`/building/projects/{id}/retention/invoice`).
 */
internal object MeasurementRoutes : DashboardFeature(DashboardModules.MEASUREMENTS) {
    override fun Route.routes(deps: DashboardDeps) {
        route("/building") {
            get("/measurements") {
                val b = deps.building(call)
                call.respond(b.records.measurementDtos(b.records.measurements.list(call.projectFilter(), call.statusFilter<MeasurementStatus>())))
            }
            post("/measurements") {
                val b = deps.building(call)
                val request = call.receive<MeasurementRequest>()
                val periodEnd = request.periodEnd?.takeIf { it.isNotBlank() }?.let { dateValue(it, "periodEnd") }
                call.respondOutcome(b.measurements.create(projectOf(request.projectId), periodEnd, request.notes, b.stamp), HttpStatusCode.Created) {
                    b.records.measurementDtos(listOf(it)).single()
                }
            }
            get("/measurements/{id}") {
                val b = deps.building(call)
                val measurement = b.records.measurements.findById(call.idParam("id")) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(b.records.measurementDtos(listOf(measurement)).single())
            }
            patch("/measurements/{id}") {
                val b = deps.building(call)
                val id = call.idParam("id")
                val request = call.receive<MeasurementUpdateRequest>()
                val periodEnd = request.periodEnd?.takeIf { it.isNotBlank() }?.let { dateValue(it, "periodEnd") }
                val toDate = request.lines.orEmpty().associate { it.key to it.toDateQty }
                call.respondOutcome(b.measurements.update(id, periodEnd, request.notes, toDate, b.stamp)) { b.records.measurementDtos(listOf(it)).single() }
            }
            get("/measurements/{id}/pdf") {
                val b = deps.building(call)
                val document = b.documents.measurement(call.idParam("id"), b.template, b.tenant.locale, b.stamp) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondPdf(document)
            }
            post("/measurements/{id}/approve") {
                val b = deps.building(call)
                call.respondOutcome(b.measurements.approve(call.idParam("id"), b.stamp)) { b.records.measurementDtos(listOf(it)).single() }
            }
            post("/measurements/{id}/reopen") {
                val b = deps.building(call)
                call.respondOutcome(b.measurements.reopen(call.idParam("id"), b.stamp)) { b.records.measurementDtos(listOf(it)).single() }
            }
            requireModules(DashboardModules.INVOICES) {
                post("/measurements/{id}/invoice") {
                    val b = deps.building(call)
                    val id = call.idParam("id")
                    val dueDate = dateValue(call.receive<MeasurementInvoiceRequest>().dueDate.trim(), "dueDate")
                    call.respondOutcome(b.measurements.invoice(id, dueDate, b.tenant.locale, b.stamp), HttpStatusCode.Created) { done ->
                        MeasurementInvoicedDto(b.records.measurementDtos(listOf(done.measurement)).single(), done.invoice.id.toHexString(), done.invoice.number)
                    }
                }
                post("/projects/{id}/retention/invoice") {
                    val b = deps.building(call)
                    val id = call.idParam("id")
                    val dueDate = dateValue(call.receive<MeasurementInvoiceRequest>().dueDate.trim(), "dueDate")
                    call.respondOutcome(b.measurements.invoiceRetention(id, dueDate, b.tenant.locale, b.stamp), HttpStatusCode.Created) { done ->
                        RetentionInvoicedDto(b.records.projectDtos(listOf(done.project), b.stamp.today, detail = true).single(), done.invoice.id.toHexString(), done.invoice.number)
                    }
                }
            }
            delete("/measurements/{id}") {
                val b = deps.building(call)
                call.respondOutcome(b.measurements.delete(call.idParam("id"))) { DeletedDto(true) }
            }
        }
    }
}
