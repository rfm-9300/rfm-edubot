package com.rfm.edubot.dashboard

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.settings.logoFile
import com.rfm.edubot.dashboard.settings.respondLogo
import com.rfm.edubot.instagram.InstagramSocialService
import com.rfm.edubot.oauth.InstagramOAuthScopes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.persona.PersonaCompiler
import com.rfm.edubot.tenant.ChannelBindingService
import com.rfm.edubot.tenant.TenantPipelineFactory
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

fun Route.dashboardStaticRoutes() {
    get("/app") { call.respondRedirect("/app/") }
    get("/app/") {
        val html = this::class.java.classLoader.getResource("app/index.html")?.readText()
        call.respondText(html ?: "Dashboard UI missing", ContentType.Text.Html)
    }
    get("/app/{asset}") {
        val asset = call.parameters["asset"] ?: return@get call.respond(HttpStatusCode.NotFound)
        val contentType = when (asset.substringAfterLast('.', "")) {
            "js" -> ContentType.Application.JavaScript
            "css" -> ContentType.Text.CSS
            else -> ContentType.Application.OctetStream
        }
        val bytes = this::class.java.classLoader.getResource("app/$asset")?.readBytes()
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondBytes(bytes, contentType)
    }
}

/** `/app/api`: the session, then every area in [DashboardFeatures], each behind its module gate. */
internal fun Route.dashboardRoutes(
    mongo: MongoModule,
    tenantRepository: TenantRepository,
    dashboardUsers: DashboardUserRepository,
    pipelineFactory: TenantPipelineFactory,
    personaCompiler: PersonaCompiler,
    aiClient: AiClient,
    runtimeConfig: RuntimeConfig,
    channelBindingService: ChannelBindingService,
    instagramSocial: InstagramSocialService,
    assistantExtension: AssistantExtension? = null,
) {
    val deps = DashboardDeps(
        mongo = mongo,
        tenants = tenantRepository,
        dashboardUsers = dashboardUsers,
        pipelineFactory = pipelineFactory,
        personaCompiler = personaCompiler,
        aiClient = aiClient,
        runtimeConfig = runtimeConfig,
        channelBindings = channelBindingService,
        instagramSocial = instagramSocial,
        assistantExtension = assistantExtension,
    )
    authenticate("dashboard") {
        route("/app/api") {
            openToEverySession("who is signed in, to which company, and which pages that session has") {
                get("/me") {
                    val ctx = call.dashboardContext() ?: return@get
                    val companies = tenantRepository.findCompanies(ctx.tenant.primaryTenantId)
                    val employee = ctx.employee
                    call.respond(
                        MeDto(
                            tenant = ctx.tenant.meDto().let { if (employee != null) it.copy(channels = emptyList()) else it },
                            user = ctx.user?.dto(),
                            modules = if (employee != null) EmployeePortal.pages(ctx.tenant) else DashboardModules.effectiveFor(ctx.tenant),
                            principalType = ctx.principalType,
                            companies = companies
                                .filter { DashboardAccessPolicy.allows(it, ctx.user, ctx.principalType) }
                                .map { CompanyMeDto(it.id.toHexString(), it.name, it.slug, primary = it.parentTenantId == null) },
                            companyLimit = if (employee != null) 1 else companies.companyLimit(ctx.tenant),
                            employee = employee?.let { EmployeeMeDto(it.id.toHexString(), it.number, it.name) },
                        ),
                    )
                }
            }
            openToEverySession("the company's logo, which every sign-in of the company shows in the sidebar; only Settings changes it") {
                get("/me/logo") {
                    val ctx = call.dashboardContext() ?: return@get
                    call.respondLogo(ctx.tenant.documentTemplate)
                }
            }
            installDashboardFeatures(deps)
        }
    }
}

@Serializable private data class MeDto(
    val tenant: TenantMeDto,
    val user: DashboardUserDto?,
    val modules: List<String>,
    val principalType: String,
    /** The tenant's companies this session can switch to, the current one included. */
    val companies: List<CompanyMeDto>,
    val companyLimit: Int,
    /** Set for an employee's sign-in, whose [modules] are [EmployeePortal.pages]. */
    val employee: EmployeeMeDto? = null,
)
@Serializable private data class EmployeeMeDto(val id: String, val number: String, val name: String)
@Serializable private data class CompanyMeDto(val id: String, val name: String, val slug: String, val primary: Boolean)
@Serializable internal data class TenantMeDto(
    val id: String,
    val slug: String,
    val name: String,
    val locale: String,
    val timezone: String,
    val channels: List<ChannelMeDto> = emptyList(),
    /** Whether `GET /app/api/me/logo` has an image to show. */
    val hasLogo: Boolean = false,
)
@Serializable internal data class ChannelMeDto(
    val platform: String,
    val externalId: String,
    val displayName: String? = null,
    val commentsEnabled: Boolean = false,
)
@Serializable internal data class DashboardUserDto(val id: String, val email: String, val role: String, val status: String)

/** The company as `/me` and Settings show it, with its channels. */
internal fun Tenant.meDto() = TenantMeDto(
    id.toHexString(),
    slug,
    name,
    locale,
    timezone,
    channels.map {
        ChannelMeDto(
            it.platform.name,
            it.externalId,
            it.displayName,
            commentsEnabled = it.platform == Platform.INSTAGRAM && InstagramOAuthScopes.hasComments(it.grantedScopes),
        )
    },
    hasLogo = documentTemplate.logoFile() != null,
)

internal fun DashboardUser.dto() = DashboardUserDto(id.toHexString(), email, role.name, status.name)
