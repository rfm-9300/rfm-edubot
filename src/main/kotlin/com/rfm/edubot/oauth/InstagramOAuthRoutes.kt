package com.rfm.edubot.oauth

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.tenant.ChannelBindingService
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.Platform
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("InstagramOAuthRoutes")

private const val RESULT_PAGE = "/backoffice/"

/**
 * Instagram-Login onboarding endpoints (docs/plan-instagram-oauth-onboarding.md §6.1):
 *  - GET /admin/api/tenants/{slug}/instagram/connect  (admin-jwt) -> { authorizeUrl }
 *  - GET /admin/api/instagram/callback                (state-authed, NOT jwt) -> writes binding, redirects
 */
fun Route.instagramOAuthRoutes(
    config: AppConfig.InstagramConfig,
    oauthState: OAuthState,
    oauthClient: InstagramOAuthClient,
    bindingService: ChannelBindingService,
    tenantRepository: TenantRepository,
) {
    authenticate("admin-jwt") {
        get("/admin/api/tenants/{slug}/instagram/connect") {
            if (!config.oauthEnabled) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Instagram OAuth not configured"))
                return@get
            }
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            val state = oauthState.mint(slug)
            call.respond(mapOf("authorizeUrl" to authorizeUrl(config, state)))
        }
    }

    // Unauthenticated: Meta redirects the browser here with no JWT. The signed `state` is the auth.
    get("/admin/api/instagram/callback") {
        val params = call.request.queryParameters

        params["error"]?.let { error ->
            log.warn("Instagram OAuth denied: error={} reason={}", error, params["error_reason"])
            return@get call.respondRedirect(resultUrl("error", reason = error))
        }

        val code = params["code"]
        val state = params["state"]
        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            return@get call.respondRedirect(resultUrl("error", reason = "missing_params"))
        }

        val slug = oauthState.verify(state)
        if (slug == null) {
            log.warn("Instagram OAuth: invalid or replayed state")
            return@get call.respondRedirect(resultUrl("error", reason = "invalid_state"))
        }

        val result = oauthClient.exchange(code)
        if (result == null) {
            return@get call.respondRedirect(resultUrl("error", reason = "exchange_failed", tenant = slug))
        }

        val updated = bindingService.upsert(slug, ChannelBinding(Platform.INSTAGRAM, result.igId, result.accessToken))
        if (updated == null) {
            return@get call.respondRedirect(resultUrl("error", reason = "tenant_not_found", tenant = slug))
        }

        log.info("Instagram channel connected via OAuth: tenant={} igId={}", slug, result.igId)
        call.respondRedirect(resultUrl("connected", tenant = slug))
    }
}

private fun authorizeUrl(config: AppConfig.InstagramConfig, state: String): String {
    val scope = "instagram_business_basic,instagram_business_manage_messages"
    return "https://www.instagram.com/oauth/authorize" +
        "?client_id=${config.appId.encodeURLParameter()}" +
        "&redirect_uri=${config.redirectUri.encodeURLParameter()}" +
        "&response_type=code" +
        "&scope=${scope.encodeURLParameter()}" +
        "&state=${state.encodeURLParameter()}"
}

private fun resultUrl(status: String, reason: String? = null, tenant: String? = null): String =
    buildString {
        append(RESULT_PAGE).append("?ig=").append(status.encodeURLParameter())
        if (reason != null) append("&reason=").append(reason.encodeURLParameter())
        if (tenant != null) append("&tenant=").append(tenant.encodeURLParameter())
    }
