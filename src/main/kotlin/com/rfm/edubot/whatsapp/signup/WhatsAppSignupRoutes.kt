package com.rfm.edubot.whatsapp.signup

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.ChannelBindingService
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("WhatsAppSignupRoutes")

fun Route.whatsAppSignupRoutes(
    configProvider: () -> AppConfig.WhatsAppConfig,
    signupClient: WhatsAppSignupClient,
    bindingService: ChannelBindingService,
    tenantRepository: TenantRepository,
) {
    authenticate("admin-jwt") {
        get("/admin/api/whatsapp/embedded-signup/config") {
            call.respond(embeddedSignupConfig(configProvider()))
        }

        post("/admin/api/tenants/{slug}/whatsapp/connect") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
            connectWhatsAppForTenant(
                call = call,
                slug = slug,
                config = configProvider(),
                signupClient = signupClient,
                bindingService = bindingService,
            )
        }
    }

    authenticate("dashboard") {
        // Tenant-scoped: slug comes from the JWT so a client can only connect their own tenant.
        get("/app/api/whatsapp/embedded-signup/config") {
            call.respond(embeddedSignupConfig(configProvider()))
        }

        post("/app/api/whatsapp/connect") {
            val tenantId = call.principal<JWTPrincipal>()?.payload?.getClaim("tenantId")?.asString()
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val tenant = tenantRepository.findById(ObjectId(tenantId))
                ?: return@post call.respond(HttpStatusCode.NotFound)
            connectWhatsAppForTenant(
                call = call,
                slug = tenant.slug,
                config = configProvider(),
                signupClient = signupClient,
                bindingService = bindingService,
            )
        }
    }
}

private fun embeddedSignupConfig(config: AppConfig.WhatsAppConfig): EmbeddedSignupConfigResponse {
    val embedded = config.embeddedSignup
    if (!embedded.enabled) return EmbeddedSignupConfigResponse(enabled = false)
    return EmbeddedSignupConfigResponse(
        enabled = true,
        appId = embedded.appId,
        configId = embedded.configId,
        graphVersion = config.apiVersion,
    )
}

private suspend fun connectWhatsAppForTenant(
    call: ApplicationCall,
    slug: String,
    config: AppConfig.WhatsAppConfig,
    signupClient: WhatsAppSignupClient,
    bindingService: ChannelBindingService,
) {
    if (!config.embeddedSignup.enabled) {
        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "WhatsApp Embedded Signup not configured"))
        return
    }
    val request = call.receive<WhatsAppConnectRequest>()
    if (request.code.isBlank() || request.wabaId.isBlank() || request.phoneNumberId.isBlank()) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "code, wabaId and phoneNumberId are required"))
        return
    }

    val result = try {
        signupClient.connect(
            code = request.code.trim(),
            wabaId = request.wabaId.trim(),
            phoneNumberId = request.phoneNumberId.trim(),
        )
    } catch (e: SignupException) {
        log.warn(
            "WhatsApp Embedded Signup failed: tenant={} reason={} status={} graphCode={} graphSubcode={} traceId={}",
            slug,
            e.reason,
            e.statusCode,
            e.graphError?.code,
            e.graphError?.subcode,
            e.graphError?.traceId,
        )
        call.respond(HttpStatusCode.BadGateway, mapOf("error" to e.reason))
        return
    }

    val displayName = listOfNotNull(result.verifiedName, result.displayPhoneNumber).joinToString(" · ").takeIf { it.isNotBlank() }
    val updated = bindingService.upsert(
        slug,
        ChannelBinding(
            platform = Platform.WHATSAPP,
            externalId = result.phoneNumberId,
            accessToken = result.accessToken,
            displayName = displayName,
            wabaId = result.wabaId,
            tokenObtainedAt = SystemClock.now(),
            source = "embedded_signup",
        ),
    ) ?: run {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    log.info("WhatsApp channel connected via Embedded Signup: tenant={} wabaId={} phoneNumberId={}", slug, result.wabaId, result.phoneNumberId)
    call.respond(updated.dto())
}

@Serializable
private data class EmbeddedSignupConfigResponse(
    val enabled: Boolean,
    val appId: String? = null,
    val configId: String? = null,
    val graphVersion: String? = null,
)

@Serializable
private data class WhatsAppConnectRequest(
    val code: String = "",
    val wabaId: String = "",
    val phoneNumberId: String = "",
)

@Serializable
private data class TenantDto(
    val id: String,
    val slug: String,
    val name: String,
    val phoneNumberId: String,
    val openrouterModel: String? = null,
    val enabledModules: List<String>? = null,
    val rateLimitPerHour: Int,
    val rateLimitPerDay: Int,
    val status: String,
    val channels: List<ChannelBindingDto>,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
private data class ChannelBindingDto(
    val platform: String,
    val externalId: String,
    val hasAccessToken: Boolean,
    val displayName: String? = null,
    val wabaId: String? = null,
    val source: String? = null,
)

private fun Tenant.dto() = TenantDto(
    id = id.toHexString(),
    slug = slug,
    name = name,
    phoneNumberId = phoneNumberId,
    openrouterModel = openrouterModel,
    enabledModules = enabledModules,
    rateLimitPerHour = rateLimitPerHour,
    rateLimitPerDay = rateLimitPerDay,
    status = status.name,
    channels = channels.map { ChannelBindingDto(it.platform.name, it.externalId, it.accessToken.isNotBlank(), it.displayName, it.wabaId, it.source) },
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)
