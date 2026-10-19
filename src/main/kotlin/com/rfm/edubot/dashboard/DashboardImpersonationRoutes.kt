package com.rfm.edubot.dashboard

import at.favre.lib.crypto.bcrypt.BCrypt
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.dashboard.model.DashboardUserStatus
import com.rfm.edubot.http.idParam
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.TenantRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** The backoffice's side of a company's dashboard: its users, and opening it as an operator. */
fun Route.dashboardImpersonationRoute(
    tenantRepository: TenantRepository,
    dashboardUsers: DashboardUserRepository,
    runtimeConfig: RuntimeConfig,
) {
    // Users belong to the tenant's first company, whichever of its companies the operator opened.
    authenticate("admin-jwt") {
        get("/admin/api/tenants/{slug}/dashboard-users") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(dashboardUsers.listByTenant(tenant.primaryTenantId).map { it.dto() })
        }
        post("/admin/api/tenants/{slug}/dashboard-users") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
            val request = call.receive<DashboardUserCreateRequest>()
            if (request.email.isBlank() || request.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "email and password are required"))
                return@post
            }
            // An employee's sign-in belongs to their employee record, so it is only given from that record in /app.
            val role = DashboardUserRole.entries.firstOrNull { it.name == request.role && it != DashboardUserRole.TENANT_EMPLOYEE }
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_role"))
            val now = SystemClock.now()
            val user = DashboardUser(
                tenantId = tenant.primaryTenantId,
                email = request.email.trim().lowercase(),
                passwordHash = BCrypt.withDefaults().hashToString(12, request.password.toCharArray()),
                role = role,
                createdAt = now,
            )
            call.respond(HttpStatusCode.Created, dashboardUsers.create(user).dto())
        }
        post("/admin/api/tenants/{slug}/dashboard-users/{id}/disable") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
            val user = dashboardUsers.setStatus(call.idParam("id"), tenant.primaryTenantId, DashboardUserStatus.DISABLED)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(user.dto())
        }
        post("/admin/api/tenants/{slug}/dashboard-users/{id}/activate") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
            val user = dashboardUsers.setStatus(call.idParam("id"), tenant.primaryTenantId, DashboardUserStatus.ACTIVE)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(user.dto())
        }
        post("/admin/api/tenants/{slug}/impersonate") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(DashboardLoginResponse(token = dashboardToken(runtimeConfig.get().admin, tenant, DashboardAccessPolicy.OPERATOR_IMPERSONATION, 1)))
        }
    }
}

@Serializable private data class DashboardLoginResponse(val token: String)
@Serializable private data class DashboardUserCreateRequest(val email: String, val password: String, val role: String = "TENANT_ADMIN")
