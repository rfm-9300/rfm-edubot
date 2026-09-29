package com.rfm.edubot.dashboard

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.Payload
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantCompanies
import com.rfm.edubot.tenant.model.TenantStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.text.Normalizer
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Adds companies to a tenant, up to its first company's `maxCompanies`, which only the backoffice sets.
 * A new company starts empty, with the first company's modules and limits. The check-then-insert runs
 * under a per-tenant lock, which assumes a single app instance (like the booking scheduler).
 */
internal class CompanyService(private val tenants: TenantRepository) {

    sealed interface Created {
        data class Ok(val company: Tenant) : Created
        data object InvalidName : Created
        data object LimitReached : Created
    }

    private val locks = ConcurrentHashMap<ObjectId, Mutex>()

    suspend fun create(current: Tenant, name: String, now: Instant): Created {
        val cleanName = name.trim().takeIf { it.isNotEmpty() && it.length <= TenantCompanies.MAX_NAME_LENGTH }
            ?: return Created.InvalidName
        val primaryId = current.primaryTenantId
        return locks.computeIfAbsent(primaryId) { Mutex() }.withLock {
            val companies = tenants.findCompanies(primaryId)
            val primary = companies.firstOrNull { it.id == primaryId }
            if (primary == null || companies.size >= primary.maxCompanies) return@withLock Created.LimitReached
            val company = Tenant(
                slug = freeSlug(companySlug(primary.slug, cleanName)),
                name = cleanName,
                channels = emptyList(),
                locale = primary.locale,
                timezone = primary.timezone,
                openrouterModel = primary.openrouterModel,
                enabledModules = primary.enabledModules,
                rateLimitPerHour = primary.rateLimitPerHour,
                rateLimitPerDay = primary.rateLimitPerDay,
                monthlyTokenBudget = primary.monthlyTokenBudget,
                parentTenantId = primary.id,
                createdAt = now,
                updatedAt = now,
            )
            Created.Ok(tenants.create(company))
        }
    }

    private suspend fun freeSlug(base: String): String {
        var slug = base
        var n = 1
        while (tenants.findBySlug(slug) != null) slug = "$base-${++n}"
        return slug
    }
}

/**
 * The companies of the signed-in tenant. Anyone signed in can switch between the companies they may
 * open; Settings lists them and lets administrators add one.
 */
fun Route.dashboardCompanyRoutes(tenantRepository: TenantRepository, runtimeConfig: RuntimeConfig) {
    val companies = CompanyService(tenantRepository)

    authenticate("dashboard") {
        route("/app/api/companies") {
            get {
                val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.SETTINGS) }
                    ?: return@get call.respond(HttpStatusCode.Forbidden)
                val all = tenantRepository.findCompanies(ctx.tenant.primaryTenantId)
                call.respond(
                    CompaniesDto(
                        current = ctx.tenant.id.toHexString(),
                        limit = all.companyLimit(ctx.tenant),
                        canManage = ctx.canManageCompanies(),
                        companies = all.map { it.companyDto() },
                    ),
                )
            }

            post {
                val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.SETTINGS) }
                    ?: return@post call.respond(HttpStatusCode.Forbidden)
                if (!ctx.canManageCompanies()) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val request = call.receive<CreateCompanyRequest>()
                when (val result = companies.create(ctx.tenant, request.name, SystemClock.now())) {
                    is CompanyService.Created.Ok -> call.respond(HttpStatusCode.Created, result.company.companyDto())
                    CompanyService.Created.InvalidName -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name_required"))
                    CompanyService.Created.LimitReached -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "company_limit"))
                }
            }

            // The new token keeps this session's expiry, so switching can't extend a session.
            post("/{id}/switch") {
                val ctx = call.dashboardContext() ?: return@post
                val session = call.principal<JWTPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val expiresAt = session.expiresAt ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val target = call.parameters["id"]?.toObjectIdOrNull()?.let { tenantRepository.findById(it) }
                    ?.takeIf { it.primaryTenantId == ctx.tenant.primaryTenantId && it.status != TenantStatus.DELETED }
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                if (!DashboardAccessPolicy.allows(target, ctx.user, ctx.principalType)) {
                    return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "company_inactive"))
                }
                call.respond(SwitchCompanyResponse(token = switchedDashboardToken(runtimeConfig.get().admin, session.payload, target, expiresAt)))
            }
        }
    }
}

/** The first company's limit. A tenant always holds at least the company it signed in to. */
internal fun List<Tenant>.companyLimit(current: Tenant): Int =
    firstOrNull { it.id == current.primaryTenantId }?.maxCompanies ?: 1

/** Adding companies is for the tenant's administrators, and for operators opening its dashboard. */
private fun DashboardContext.canManageCompanies(): Boolean =
    principalType == DashboardAccessPolicy.OPERATOR_IMPERSONATION || user?.role == DashboardUserRole.TENANT_ADMIN

/** [session] moved to [company]: same subject, role and principal type, until [expiresAt]. */
private fun switchedDashboardToken(config: AppConfig.AdminConfig, session: Payload, company: Tenant, expiresAt: Date): String = JWT.create()
    .withIssuer(config.jwtIssuer)
    .withSubject(session.subject)
    .withClaim("tenantId", company.id.toHexString())
    .withClaim("role", session.getClaim("role").asString())
    .withClaim("typ", session.getClaim("typ").asString())
    .withExpiresAt(expiresAt)
    .sign(Algorithm.HMAC256(config.jwtSecret))

private fun companySlug(primarySlug: String, name: String): String {
    val part = Normalizer.normalize(name, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(40)
        .trim('-')
    return "$primarySlug-${part.ifEmpty { "company" }}"
}

private fun Tenant.companyDto() = CompanyDto(id.toHexString(), name, slug, status.name, primary = parentTenantId == null)

@Serializable private data class CreateCompanyRequest(val name: String = "")
@Serializable private data class SwitchCompanyResponse(val token: String)
@Serializable private data class CompaniesDto(val current: String, val limit: Int, val canManage: Boolean, val companies: List<CompanyDto>)
@Serializable private data class CompanyDto(val id: String, val name: String, val slug: String, val status: String, val primary: Boolean)
