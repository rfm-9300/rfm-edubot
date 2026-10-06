package com.rfm.edubot.dashboard

import com.auth0.jwt.interfaces.Payload
import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.dashboard.model.DashboardUserStatus
import com.rfm.edubot.events.Actor
import com.rfm.edubot.events.ActorType
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import org.bson.types.ObjectId

/** [employee] is set for an employee's sign-in: the record it registers services as. */
internal data class DashboardContext(val tenant: Tenant, val user: DashboardUser?, val principalType: String, val employee: Employee? = null)

/** An employee record of a company, by id. */
typealias EmployeeLookup = suspend (tenantId: ObjectId, employeeId: ObjectId) -> Employee?

/** Finds no one, so employees' sign-ins are refused wherever the real lookup isn't passed. */
internal val noEmployeeLookup: EmployeeLookup = { _, _ -> null }

internal object DashboardAccessPolicy {
    const val TENANT_USER = "tenant"
    const val OPERATOR_IMPERSONATION = "operator-imp"

    fun allows(tenant: Tenant, user: DashboardUser?, principalType: String): Boolean = when (principalType) {
        TENANT_USER -> tenant.status == TenantStatus.ACTIVE &&
            user != null && user.tenantId == tenant.primaryTenantId && user.status == DashboardUserStatus.ACTIVE &&
            (!user.isEmployee || EmployeePortal.opens(tenant, user))
        // Platform operators can still open a suspended tenant for support.
        OPERATOR_IMPERSONATION -> tenant.status != TenantStatus.DELETED
        else -> false
    }
}

/**
 * What an employee's sign-in reaches: the services they register (`/app/api/portal/…`), their account and
 * `/me`, in the one company their employee record is in. Never a company module.
 */
internal object EmployeePortal {
    /** The only page an employee's session has, in place of the company's modules. */
    const val MODULE = "my-services"

    /** Employees register work as Serviços rows, so the company needs both modules. */
    fun isAvailable(tenant: Tenant): Boolean =
        DashboardModules.effectiveFor(tenant).let { DashboardModules.EMPLOYEES in it && DashboardModules.SERVICES in it }

    fun opens(tenant: Tenant, user: DashboardUser): Boolean =
        user.employeeId != null && user.employeeTenantId == tenant.id && isAvailable(tenant)

    /**
     * Checked by the `dashboard` validator before any route runs, so an employee's token can't reach a route
     * that forgets its module check. Dot segments are refused: they would step out of these prefixes if
     * routing ever resolved them.
     */
    fun allowsPath(path: String): Boolean {
        if (path.split('/').any { it == "." || it == ".." } || path.contains("%2e", ignoreCase = true)) return false
        return path == "/app/api/me" || path == "/app/api/account" || path.startsWith("/app/api/account/") || path.startsWith("/app/api/portal/")
    }

    /** The employee [user] signs in as, while that record exists in [tenant] and isn't archived. */
    suspend fun employeeOf(user: DashboardUser, tenant: Tenant, employees: EmployeeLookup): Employee? =
        user.employeeId?.let { employees(tenant.id, it) }?.takeIf { it.archivedAt == null }
}

private val DashboardContextKey = AttributeKey<DashboardContext>("DashboardContext")

/**
 * Loads the tenant and user behind a dashboard token and applies [DashboardAccessPolicy]. Called
 * from the `dashboard` JWT validator, so it guards every route under `authenticate("dashboard")`,
 * including the ones that read token claims directly instead of calling [dashboardContext].
 * An employee's token also needs their employee record, so archiving or deleting it ends the session.
 */
internal suspend fun resolveDashboardContext(
    payload: Payload,
    tenants: TenantRepository,
    users: DashboardUserRepository,
    employees: EmployeeLookup = noEmployeeLookup,
): DashboardContext? {
    val principalType = payload.getClaim("typ").asString() ?: return null
    val tenantId = payload.getClaim("tenantId").asString()?.toObjectIdOrNull() ?: return null
    val tenant = tenants.findById(tenantId) ?: return null
    val user = if (principalType == DashboardAccessPolicy.TENANT_USER) {
        payload.subject?.toObjectIdOrNull()?.let { users.findById(it) }
    } else {
        null
    }
    if (!DashboardAccessPolicy.allows(tenant, user, principalType)) return null
    if (user == null || !user.isEmployee) return DashboardContext(tenant, user, principalType)
    val employee = EmployeePortal.employeeOf(user, tenant, employees) ?: return null
    return DashboardContext(tenant, user, principalType, employee)
}

/**
 * The company a password or Google sign-in opens for [user], or null when it may not sign in: the
 * tenant's first company, or an employee's own company while their record is there and not archived.
 */
internal suspend fun signInCompany(user: DashboardUser, tenants: TenantRepository, employees: EmployeeLookup): Tenant? {
    val tenant = tenants.findById(user.homeTenantId) ?: return null
    if (!DashboardAccessPolicy.allows(tenant, user, DashboardAccessPolicy.TENANT_USER)) return null
    if (user.isEmployee && EmployeePortal.employeeOf(user, tenant, employees) == null) return null
    return tenant
}

internal fun ApplicationCall.attachDashboardContext(context: DashboardContext) {
    attributes.put(DashboardContextKey, context)
}

/** The tenant and user the `dashboard` JWT validator authorized for this call. */
internal fun ApplicationCall.dashboardContext(): DashboardContext? = attributes.getOrNull(DashboardContextKey)

/** An employee's sign-in has none of the company's modules, only [EmployeePortal]'s pages. */
internal fun DashboardContext.requireModule(id: String): Boolean = user?.isEmployee != true && id in DashboardModules.effectiveFor(tenant)

/** The company's administrators, and operators opening its dashboard, change how it works for the whole team. */
internal fun DashboardContext.isAdmin(): Boolean =
    principalType == DashboardAccessPolicy.OPERATOR_IMPERSONATION || user?.role == DashboardUserRole.TENANT_ADMIN

/** Who a change made through this dashboard session is attributed to. */
internal fun DashboardContext.actor(): Actor =
    user?.let { Actor(ActorType.USER, it.id.toHexString(), it.email) } ?: Actor(ActorType.OPERATOR, name = "operator")

internal fun String.toObjectIdOrNull(): ObjectId? = runCatching { ObjectId(this) }.getOrNull()
