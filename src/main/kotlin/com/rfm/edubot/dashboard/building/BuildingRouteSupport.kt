package com.rfm.edubot.dashboard.building

import com.rfm.edubot.building.BuildingDocument
import com.rfm.edubot.building.BuildingDocuments
import com.rfm.edubot.building.BuildingRecords
import com.rfm.edubot.building.ChangeOrderService
import com.rfm.edubot.building.MeasurementService
import com.rfm.edubot.building.Outcome
import com.rfm.edubot.building.PhotoStore
import com.rfm.edubot.building.ProjectService
import com.rfm.edubot.building.SiteDiaryService
import com.rfm.edubot.building.SiteIssueService
import com.rfm.edubot.building.SitePhotoService
import com.rfm.edubot.building.Stamp
import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.moduleContext
import com.rfm.edubot.http.ErrorBody
import com.rfm.edubot.http.enumValue
import com.rfm.edubot.http.idValue
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.DocumentTemplate
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.timesheets.zone
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

/** One company's Building records and services for one request, stamped with who is asking and when. */
internal class TenantBuilding(mongo: MongoModule, val ctx: DashboardContext, storagePath: () -> String) {
    val tenant: Tenant = ctx.tenant
    val records = BuildingRecords(mongo, tenant.id)
    val stamp = Stamp(ctx.user?.email ?: OPERATOR, SystemClock.now(), tenant.zone())
    val store = PhotoStore(storagePath)

    val projects: ProjectService get() = ProjectService(records, store)
    val photos: SitePhotoService get() = SitePhotoService(records, store)
    val diary: SiteDiaryService get() = SiteDiaryService(records)
    val issues: SiteIssueService get() = SiteIssueService(records)
    val changes: ChangeOrderService get() = ChangeOrderService(records)
    val measurements: MeasurementService get() = MeasurementService(records)
    val documents: BuildingDocuments get() = BuildingDocuments(records, store)
    val template: DocumentTemplate get() = tenant.documentTemplate.withCompanyFallback(tenant.name)

    private companion object {
        /** Who a write is stamped with when the platform's operator opened the company. */
        const val OPERATOR = "operator"
    }
}

/** The Building of the company the module gate let through. Photos live on the PDF volume, beside its logo. */
internal fun DashboardDeps.building(call: ApplicationCall): TenantBuilding =
    TenantBuilding(mongo, call.moduleContext()) { runtimeConfig.get().pdfStoragePath }

@Serializable internal data class DeletedDto(val deleted: Boolean)

/** [outcome]'s record as [view] shows it, with [status]; or 404, or the refusal as 400 or 409. */
internal suspend inline fun <T, reified R : Any> ApplicationCall.respondOutcome(
    outcome: Outcome<T>,
    status: HttpStatusCode = HttpStatusCode.OK,
    view: (T) -> R,
) {
    when (outcome) {
        is Outcome.Done -> respond(status, view(outcome.value))
        is Outcome.Refused -> respondRefused(outcome)
        Outcome.NotFound -> respond(HttpStatusCode.NotFound)
    }
}

internal suspend fun ApplicationCall.respondRefused(refused: Outcome.Refused) =
    respond(if (refused.conflict) HttpStatusCode.Conflict else HttpStatusCode.BadRequest, ErrorBody(refused.error, refused.detail))

/** [document] to open in the browser, under its file name. */
internal suspend fun ApplicationCall.respondPdf(document: BuildingDocument) {
    response.header(HttpHeaders.ContentDisposition, ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, document.fileName).toString())
    respondBytes(document.bytes, ContentType.Application.Pdf)
}

/** `?projectId=`: the records of one project. */
internal fun ApplicationCall.projectFilter(): ObjectId? =
    request.queryParameters["projectId"]?.takeIf { it.isNotBlank() }?.let { idValue(it, "projectId") }

/** `?status=A,B`: records in any of these statuses; all of them without one. */
internal inline fun <reified E : Enum<E>> ApplicationCall.statusFilter(): List<E> =
    request.queryParameters["status"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { enumValue<E>(it.uppercase(), "status") }.orEmpty()

/** A body's id of the project a new record is for; blank or missing is the service's `project_not_found`. */
internal fun projectOf(raw: String?): ObjectId? = raw?.takeIf { it.isNotBlank() }?.let { idValue(it, "projectId") }
