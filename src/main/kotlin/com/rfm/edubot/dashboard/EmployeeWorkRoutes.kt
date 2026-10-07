package com.rfm.edubot.dashboard

import at.favre.lib.crypto.bcrypt.BCrypt
import com.mongodb.ErrorCategory
import com.mongodb.MongoServerException
import com.rfm.edubot.admin.ClientServiceDto
import com.rfm.edubot.admin.CreateClientRequest
import com.rfm.edubot.admin.CreateLineItemRequest
import com.rfm.edubot.admin.LineItemDto
import com.rfm.edubot.admin.dto
import com.rfm.edubot.admin.serviceItemsError
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.ServiceSubmissionRepository
import com.rfm.edubot.crm.StandardItem
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.crm.SubmissionChange
import com.rfm.edubot.crm.SubmissionContent
import com.rfm.edubot.crm.content
import com.rfm.edubot.crm.isBookable
import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.crm.model.ServiceSubmission
import com.rfm.edubot.crm.model.ServiceSubmissionStatus
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.dashboard.model.DashboardUserStatus
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.integrations.email.EmailAddresses
import com.rfm.edubot.notifications.NotificationAudience
import com.rfm.edubot.notifications.NotificationKinds
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory

/**
 * Employees registering the services they did, and the team approving them into Serviços rows.
 *
 *  - `/app/api/portal/…`, an employee's own sign-in only: the clients and catalog to pick from, and their
 *    submissions, which they can still change or withdraw while nobody decided on them.
 *  - `/app/api/crm/service-submissions`, the rest of the team: approve one as sent or with changes, which
 *    saves a Serviços row done by that employee, or reject it with a reason.
 *  - `/app/api/crm/employees/{id}/access`: an employee's sign-in, which only admins give, change or remove.
 */
fun Route.employeeWorkRoutes(mongo: MongoModule, dashboardUsers: DashboardUserRepository, notifications: NotificationRepository) {
    authenticate("dashboard") {
        route("/app/api/portal") {
            get("/clients") {
                val session = call.portalSession() ?: return@get
                val clients = ClientRepository(mongo, session.tenant.id).search("", limit = PORTAL_CLIENT_LIMIT)
                call.respond(clients.sortedBy { it.name.lowercase() }.map { PortalClientDto(it.id.toHexString(), it.number, it.name) })
            }
            get("/catalog") {
                val session = call.portalSession() ?: return@get
                call.respond(portalCatalog(mongo, session.tenant))
            }
            get("/services") {
                val session = call.portalSession() ?: return@get
                val rows = ServiceSubmissionRepository(mongo, session.tenant.id).list(employeeId = session.employee.id)
                call.respond(SubmissionNames.load(mongo, session.tenant.id, rows).dtos(rows))
            }
            post("/services") {
                val session = call.portalSession() ?: return@post
                val request = call.receiveSubmission() ?: return@post
                val content = call.parsed(request, mongo, session.tenant, latest = session.tenant.today()) ?: return@post
                val submission = ServiceSubmissionRepository(mongo, session.tenant.id).create(session.employee.id, content)
                val names = SubmissionNames.load(mongo, session.tenant.id, listOf(submission))
                runCatching {
                    notifications.notify(
                        tenantId = session.tenant.id,
                        kind = NotificationKinds.SERVICE_SUBMITTED,
                        audience = NotificationAudience.ALL,
                        params = mapOf("employee" to session.employee.name, "service" to submission.name, "client" to names.client(submission)),
                        link = DashboardModules.EMPLOYEES,
                        subject = SubjectRef.of(SubjectTypes.EMPLOYEE, session.employee.id),
                        ref = "submission:${submission.id.toHexString()}",
                    )
                }.onFailure { log.warn("Could not notify the team of submission {}: {}", submission.id, it.message) }
                call.respond(HttpStatusCode.Created, names.dto(submission))
            }
            patch("/services/{id}") {
                val session = call.portalSession() ?: return@patch
                val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@patch call.respond(HttpStatusCode.NotFound)
                val request = call.receiveSubmission() ?: return@patch
                val content = call.parsed(request, mongo, session.tenant, latest = session.tenant.today()) ?: return@patch
                when (val change = ServiceSubmissionRepository(mongo, session.tenant.id).update(id, session.employee.id, content)) {
                    is SubmissionChange.Done -> call.respond(SubmissionNames.load(mongo, session.tenant.id, listOf(change.submission)).dto(change.submission))
                    SubmissionChange.NotFound -> call.respond(HttpStatusCode.NotFound)
                    SubmissionChange.NotPending -> call.respondNotPending()
                }
            }
            delete("/services/{id}") {
                val session = call.portalSession() ?: return@delete
                val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@delete call.respond(HttpStatusCode.NotFound)
                when (ServiceSubmissionRepository(mongo, session.tenant.id).withdraw(id, session.employee.id)) {
                    is SubmissionChange.Done -> call.respond(mapOf("deleted" to true))
                    SubmissionChange.NotFound -> call.respond(HttpStatusCode.NotFound)
                    SubmissionChange.NotPending -> call.respondNotPending()
                }
            }
        }

        route("/app/api/crm") {
            get("/service-submissions") {
                val ctx = call.reviewContext() ?: return@get
                val params = call.request.queryParameters
                val employeeId = params["employeeId"]?.takeIf { it.isNotBlank() }?.let { it.toObjectIdOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest) }
                val status = params["status"]?.takeIf { it.isNotBlank() }?.let { raw ->
                    ServiceSubmissionStatus.entries.firstOrNull { it.name == raw.uppercase() } ?: return@get call.respond(HttpStatusCode.BadRequest)
                }
                val rows = ServiceSubmissionRepository(mongo, ctx.tenant.id).list(employeeId, status)
                call.respond(SubmissionNames.load(mongo, ctx.tenant.id, rows).dtos(rows))
            }
            get("/service-submissions/{id}") {
                val ctx = call.reviewContext() ?: return@get
                val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)
                val submission = ServiceSubmissionRepository(mongo, ctx.tenant.id).findById(id) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(SubmissionNames.load(mongo, ctx.tenant.id, listOf(submission)).dto(submission))
            }
            // Claimed before the service is saved, so approving twice (two people, a double click) saves one.
            post("/service-submissions/{id}/approve") {
                val ctx = call.reviewContext() ?: return@post
                val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@post call.respond(HttpStatusCode.NotFound)
                val request = runCatching { call.receive<ApproveSubmissionRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                val submissions = ServiceSubmissionRepository(mongo, ctx.tenant.id)
                val submission = submissions.findById(id) ?: return@post call.respond(HttpStatusCode.NotFound)
                if (submission.status != ServiceSubmissionStatus.PENDING) return@post call.respondNotPending()
                val content = request.changes?.let { call.parsed(it, mongo, ctx.tenant, latest = null) ?: return@post } ?: submission.content()
                val serviceId = ObjectId()
                val approved = submissions.approve(submission, content, serviceId, ctx.reviewer()) ?: return@post call.respondNotPending()
                val service = try {
                    ClientServiceRepository(mongo, ctx.tenant.id).create(
                        clientId = approved.clientId,
                        name = approved.name,
                        notes = approved.notes,
                        quantity = 1.0,
                        unit = "",
                        unitPriceCents = 0,
                        bookingServiceId = null,
                        catalogItemId = approved.catalogItemId,
                        performedAt = approved.performedAt,
                        items = approved.items,
                        employeeId = approved.employeeId,
                        id = serviceId,
                    )
                } catch (e: Exception) {
                    submissions.reopen(submission, serviceId)
                    throw e
                }
                val names = SubmissionNames.load(mongo, ctx.tenant.id, listOf(approved))
                call.respond(ApprovedSubmissionDto(names.dto(approved), service.dto(ClientRepository(mongo, ctx.tenant.id).findById(service.clientId))))
            }
            post("/service-submissions/{id}/reject") {
                val ctx = call.reviewContext() ?: return@post
                val id = call.parameters["id"]?.toObjectIdOrNull() ?: return@post call.respond(HttpStatusCode.NotFound)
                val request = runCatching { call.receive<RejectSubmissionRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                if ((request.reason?.trim()?.length ?: 0) > ServiceSubmissionRepository.MAX_REASON) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "reason_too_long"))
                }
                val submissions = ServiceSubmissionRepository(mongo, ctx.tenant.id)
                val rejected = submissions.reject(id, request.reason, ctx.reviewer())
                    ?: return@post if (submissions.findById(id) == null) call.respond(HttpStatusCode.NotFound) else call.respondNotPending()
                call.respond(SubmissionNames.load(mongo, ctx.tenant.id, listOf(rejected)).dto(rejected))
            }

            get("/employees/{id}/access") {
                val (ctx, employee) = call.accessTarget(mongo) ?: return@get
                call.respond(dashboardUsers.findByEmployee(employee.id).accessDto(ctx.canManageAccess()))
            }
            post("/employees/{id}/access") {
                val (ctx, employee) = call.accessTarget(mongo, manage = true) ?: return@post
                if (employee.archivedAt != null) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "employee_archived"))
                val request = runCatching { call.receive<GiveAccessRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                val email = EmailAddresses.normalize(request.email) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_email"))
                newPasswordProblem(request.password)?.let { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
                if (dashboardUsers.findByEmployee(employee.id) != null) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "already_has_access"))
                val user = DashboardUser(
                    tenantId = ctx.tenant.primaryTenantId,
                    email = email,
                    passwordHash = hashPassword(request.password),
                    role = DashboardUserRole.TENANT_EMPLOYEE,
                    createdAt = SystemClock.now(),
                    employeeId = employee.id,
                    employeeTenantId = ctx.tenant.id,
                )
                val created = call.uniqueEmail { dashboardUsers.create(user) } ?: return@post
                log.info("Employee {} of tenant {} can now sign in as {}", employee.number, ctx.tenant.slug, email)
                call.respond(HttpStatusCode.Created, created.accessDto(canManage = true))
            }
            patch("/employees/{id}/access") {
                val (ctx, employee) = call.accessTarget(mongo, manage = true) ?: return@patch
                val request = runCatching { call.receive<ChangeAccessRequest>() }.getOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                val user = dashboardUsers.findByEmployee(employee.id) ?: return@patch call.respond(HttpStatusCode.NotFound, mapOf("error" to "no_access"))
                val email = request.email?.let { EmailAddresses.normalize(it) ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_email")) }
                val password = request.password?.takeIf { it.isNotEmpty() }
                password?.let { newPasswordProblem(it)?.let { problem -> return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to problem)) } }
                if (email != null && email != user.email) {
                    val changed = call.uniqueEmail { dashboardUsers.setEmail(user.id, email) != null } ?: return@patch
                    if (!changed) return@patch call.respond(HttpStatusCode.NotFound, mapOf("error" to "no_access"))
                }
                password?.let { dashboardUsers.setPasswordHash(user.id, hashPassword(it)) }
                request.active?.let { active ->
                    dashboardUsers.setStatus(user.id, user.tenantId, if (active) DashboardUserStatus.ACTIVE else DashboardUserStatus.DISABLED)
                }
                call.respond(dashboardUsers.findById(user.id).accessDto(canManage = true))
            }
            delete("/employees/{id}/access") {
                val (_, employee) = call.accessTarget(mongo, manage = true) ?: return@delete
                dashboardUsers.deleteByEmployee(employee.id)
                call.respond(EmployeeAccessDto(canManage = true))
            }
        }
    }
}

private val log = LoggerFactory.getLogger("EmployeeWork")

private const val PORTAL_CLIENT_LIMIT = 2000

private suspend fun ApplicationCall.portalSession(): PortalSession? = portalSession(EmployeePortal.MY_SERVICES)

/** The team reviewing employees' submissions: the employees and services modules, never an employee. */
private suspend fun ApplicationCall.reviewContext(): DashboardContext? {
    val ctx = dashboardContext()?.takeIf { it.requireModule(DashboardModules.EMPLOYEES) && it.requireModule(DashboardModules.SERVICES) }
    if (ctx == null) respond(HttpStatusCode.Forbidden)
    return ctx
}

/** The team managing employees' sign-ins: whenever employees have a page to sign in to (services or the time clock). */
private suspend fun ApplicationCall.accessContext(): DashboardContext? {
    val ctx = dashboardContext()?.takeIf { it.requireModule(DashboardModules.EMPLOYEES) && EmployeePortal.isAvailable(it.tenant) }
    if (ctx == null) respond(HttpStatusCode.Forbidden)
    return ctx
}

/** Giving and changing employees' sign-ins is for admins (and operators opening the dashboard). */
private fun DashboardContext.canManageAccess(): Boolean =
    principalType == DashboardAccessPolicy.OPERATOR_IMPERSONATION || user?.role == DashboardUserRole.TENANT_ADMIN

private fun DashboardContext.reviewer(): String = user?.email ?: "operator"

/** The employee in the path, for the team; [manage] also needs [canManageAccess]. */
private suspend fun ApplicationCall.accessTarget(mongo: MongoModule, manage: Boolean = false): Pair<DashboardContext, Employee>? {
    val ctx = accessContext() ?: return null
    if (manage && !ctx.canManageAccess()) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
        return null
    }
    val employee = parameters["id"]?.toObjectIdOrNull()?.let { EmployeeRepository(mongo, ctx.tenant.id).findById(it) }
    if (employee == null) {
        respond(HttpStatusCode.NotFound)
        return null
    }
    return ctx to employee
}

private suspend fun ApplicationCall.receiveSubmission(): SubmissionRequest? =
    runCatching { receive<SubmissionRequest>() }.getOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
        null
    }

/** [request] as content to store, or null after answering 400 with its stable error code. */
private suspend fun ApplicationCall.parsed(request: SubmissionRequest, mongo: MongoModule, tenant: Tenant, latest: LocalDate?): SubmissionContent? =
    when (val parsed = request.parse(ClientRepository(mongo, tenant.id), latest)) {
        is ParsedSubmission.Ok -> parsed.content
        is ParsedSubmission.Invalid -> {
            respond(HttpStatusCode.BadRequest, mapOf("error" to parsed.error))
            null
        }
    }

private suspend fun ApplicationCall.respondNotPending() = respond(HttpStatusCode.Conflict, mapOf("error" to "not_pending"))

/** Email is unique across dashboard users: a clash answers 409 `email_taken` and returns null. */
private suspend fun <T> ApplicationCall.uniqueEmail(write: suspend () -> T?): T? = try {
    write()
} catch (e: MongoServerException) {
    if (ErrorCategory.fromErrorCode(e.code) != ErrorCategory.DUPLICATE_KEY) throw e
    respond(HttpStatusCode.Conflict, mapOf("error" to "email_taken"))
    null
}

private suspend fun hashPassword(password: String): String =
    withContext(Dispatchers.Default) { BCrypt.withDefaults().hashToString(12, password.toCharArray()) }

private fun Tenant.today(): LocalDate = SystemClock.now().toLocalDateTime(TimeZone.of(TenantTimeZones.normalize(timezone))).date

/** The company's catalog to pick lines from, or only its bookable services when the catalog module is off. */
private suspend fun portalCatalog(mongo: MongoModule, tenant: Tenant): List<StandardItem> {
    val modules = DashboardModules.effectiveFor(tenant)
    val items = StandardItemRepository(mongo, tenant.id)
    return when {
        DashboardModules.CATALOG in modules -> items.search()
        DashboardModules.BOOKINGS in modules -> items.search().filter { it.isBookable() }
        else -> emptyList()
    }
}

/** Client and employee names for submission rows, each looked up once. */
private class SubmissionNames(private val clients: Map<ObjectId, String>, private val employees: Map<ObjectId, String>) {
    fun client(s: ServiceSubmission): String = clients[s.clientId].orEmpty()

    fun dto(s: ServiceSubmission) = ServiceSubmissionDto(
        id = s.id.toHexString(),
        employeeId = s.employeeId.toHexString(),
        employeeName = employees[s.employeeId].orEmpty(),
        clientId = s.clientId.toHexString(),
        clientName = client(s),
        name = s.name,
        notes = s.notes,
        items = s.items.map { LineItemDto(it.description, it.quantity, it.unit, it.unitPriceCents / 100.0) },
        totalEur = s.totalCents / 100.0,
        catalogItemId = s.catalogItemId,
        performedAt = s.performedAt.toString(),
        status = s.status.name,
        serviceId = s.serviceId?.toHexString(),
        reviewedBy = s.reviewedBy,
        reviewedAt = s.reviewedAt?.toString(),
        rejectionReason = s.rejectionReason,
        adjusted = s.adjusted,
        createdAt = s.createdAt.toString(),
        updatedAt = s.updatedAt.toString(),
    )

    fun dtos(rows: List<ServiceSubmission>) = rows.map(::dto)

    companion object {
        suspend fun load(mongo: MongoModule, tenantId: ObjectId, rows: List<ServiceSubmission>): SubmissionNames {
            val clients = ClientRepository(mongo, tenantId)
            val employees = EmployeeRepository(mongo, tenantId)
            return SubmissionNames(
                clients = rows.map { it.clientId }.distinct().mapNotNull { id -> clients.findById(id)?.let { id to it.name } }.toMap(),
                employees = rows.map { it.employeeId }.distinct().mapNotNull { id -> employees.findById(id)?.let { id to it.name } }.toMap(),
            )
        }
    }
}

/** What an employee sends, and the changes an approver may send with Approve. */
@Serializable
internal data class SubmissionRequest(
    val clientId: String = "",
    /** Left empty, the service is named after its lines ("Pintura + Limpeza"), as in the Serviços form. */
    val name: String? = null,
    val notes: String? = null,
    /** `yyyy-MM-dd`, the day the work was done. */
    val performedAt: String = "",
    val items: List<CreateLineItemRequest> = emptyList(),
    val catalogItemId: String? = null,
) {
    /**
     * The content to store, or the first problem as a stable error code. [latest] is the last day the work
     * may be dated: today in the company's timezone for an employee, none for the team, as for any service.
     */
    suspend fun parse(clients: ClientRepository, latest: LocalDate?): ParsedSubmission {
        val client = clientId.trim().toObjectIdOrNull() ?: return ParsedSubmission.Invalid("client_required")
        if (clients.findById(client) == null) return ParsedSubmission.Invalid("client_not_found")
        val day = runCatching { LocalDate.parse(performedAt.trim()) }.getOrNull() ?: return ParsedSubmission.Invalid("date_required")
        if (latest != null && day > latest) return ParsedSubmission.Invalid("date_in_future")
        if (items.isEmpty()) return ParsedSubmission.Invalid("items_required")
        serviceItemsError(items)?.let { return ParsedSubmission.Invalid(it) }
        if ((notes?.trim()?.length ?: 0) > CreateClientRequest.MAX_NOTES) return ParsedSubmission.Invalid("notes_too_long")
        val lines = items.map { it.copy(description = it.description.trim(), unit = it.unit.trim()).toLineItem() }
        val title = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: lines.joinToString(" + ") { it.description.substringBefore(" - ").trim() }.take(ServiceSubmissionRepository.MAX_NAME)
        return ParsedSubmission.Ok(SubmissionContent(client, title, notes, lines, day, catalogItemId))
    }
}

internal sealed interface ParsedSubmission {
    data class Ok(val content: SubmissionContent) : ParsedSubmission
    data class Invalid(val error: String) : ParsedSubmission
}

/** Without [changes] the submission is approved as the employee sent it. */
@Serializable private data class ApproveSubmissionRequest(val changes: SubmissionRequest? = null)
@Serializable private data class RejectSubmissionRequest(val reason: String? = null)
@Serializable private data class GiveAccessRequest(val email: String = "", val password: String = "")
/** Omitted fields keep what is stored; an empty password keeps the current one. */
@Serializable private data class ChangeAccessRequest(val email: String? = null, val password: String? = null, val active: Boolean? = null)

@Serializable private data class PortalClientDto(val id: String, val number: String, val name: String)

@Serializable
internal data class ServiceSubmissionDto(
    val id: String,
    val employeeId: String,
    val employeeName: String,
    val clientId: String,
    val clientName: String,
    val name: String,
    val notes: String? = null,
    val items: List<LineItemDto>,
    val totalEur: Double,
    val catalogItemId: String? = null,
    val performedAt: String,
    val status: String,
    val serviceId: String? = null,
    val reviewedBy: String? = null,
    val reviewedAt: String? = null,
    val rejectionReason: String? = null,
    val adjusted: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable private data class ApprovedSubmissionDto(val submission: ServiceSubmissionDto, val service: ClientServiceDto)

/** An employee's sign-in as the team sees it: no [email] means they have none. */
@Serializable
private data class EmployeeAccessDto(
    val email: String? = null,
    val active: Boolean = false,
    val lastLoginAt: String? = null,
    val googleEmail: String? = null,
    val passwordEnabled: Boolean = false,
    val canManage: Boolean = false,
)

private fun DashboardUser?.accessDto(canManage: Boolean) = EmployeeAccessDto(
    email = this?.email,
    active = this?.status == DashboardUserStatus.ACTIVE,
    lastLoginAt = this?.lastLoginAt?.toString(),
    googleEmail = this?.googleEmail?.takeIf { googleUid != null },
    passwordEnabled = this?.passwordHash != null,
    canManage = canManage,
)
