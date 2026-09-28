package com.rfm.edubot.dashboard

import com.auth0.jwt.interfaces.Payload
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserStatus
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import org.bson.types.ObjectId

internal data class DashboardContext(val tenant: Tenant, val user: DashboardUser?, val principalType: String)

internal object DashboardAccessPolicy {
    const val TENANT_USER = "tenant"
    const val OPERATOR_IMPERSONATION = "operator-imp"

    fun allows(tenant: Tenant, user: DashboardUser?, principalType: String): Boolean = when (principalType) {
        TENANT_USER -> tenant.status == TenantStatus.ACTIVE &&
            user != null && user.tenantId == tenant.id && user.status == DashboardUserStatus.ACTIVE
        // Platform operators can still open a suspended tenant for support.
        OPERATOR_IMPERSONATION -> tenant.status != TenantStatus.DELETED
        else -> false
    }
}

private val DashboardContextKey = AttributeKey<DashboardContext>("DashboardContext")

/**
 * Loads the tenant and user behind a dashboard token and applies [DashboardAccessPolicy]. Called
 * from the `dashboard` JWT validator, so it guards every route under `authenticate("dashboard")`,
 * including the ones that read token claims directly instead of calling [dashboardContext].
 */
internal suspend fun resolveDashboardContext(
    payload: Payload,
    tenants: TenantRepository,
    users: DashboardUserRepository,
): DashboardContext? {
    val principalType = payload.getClaim("typ").asString() ?: return null
    val tenantId = payload.getClaim("tenantId").asString()?.toObjectIdOrNull() ?: return null
    val tenant = tenants.findById(tenantId) ?: return null
    val user = if (principalType == DashboardAccessPolicy.TENANT_USER) {
        payload.subject?.toObjectIdOrNull()?.let { users.findById(it) }
    } else {
        null
    }
    return DashboardContext(tenant, user, principalType).takeIf { DashboardAccessPolicy.allows(tenant, user, principalType) }
}

internal fun ApplicationCall.attachDashboardContext(context: DashboardContext) {
    attributes.put(DashboardContextKey, context)
}

/** The tenant and user the `dashboard` JWT validator authorized for this call. */
internal fun ApplicationCall.dashboardContext(): DashboardContext? = attributes.getOrNull(DashboardContextKey)

internal fun DashboardContext.requireModule(id: String): Boolean = id in DashboardModules.effectiveFor(tenant)

private fun String.toObjectIdOrNull(): ObjectId? = runCatching { ObjectId(this) }.getOrNull()
