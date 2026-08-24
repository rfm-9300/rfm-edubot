package com.rfm.edubot.oauth

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.tenant.ChannelBindingService
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.shared.SystemClock
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("InstagramOAuthRoutes")

/**
 * Instagram-Login onboarding endpoints (docs/plan-instagram-oauth-onboarding.md §6.1):
 *  - GET /admin/api/tenants/{slug}/instagram/connect  (admin-jwt) -> { authorizeUrl }
 *  - GET /app/api/instagram/connect                   (dashboard) -> { authorizeUrl } for own tenant
 *  - GET /admin/api/instagram/callback                (state-authed, NOT jwt) -> writes binding, redirects
 *
 * The signed `state` carries the originating surface (backoffice vs tenant dashboard) so the
 * callback can land the popup back on the page that opened it.
 */
fun Route.instagramOAuthRoutes(
    configProvider: () -> AppConfig.InstagramConfig,
    oauthState: OAuthState,
    oauthClient: InstagramOAuthClient,
    bindingService: ChannelBindingService,
    tenantRepository: TenantRepository,
) {
    authenticate("admin-jwt") {
        get("/admin/api/tenants/{slug}/instagram/connect") {
            val config = configProvider()
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

    authenticate("dashboard") {
        // Tenant-scoped: the slug comes from the JWT, so a client can only ever link their own tenant.
        get("/app/api/instagram/connect") {
            val config = configProvider()
            if (!config.oauthEnabled) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Instagram OAuth not configured"))
                return@get
            }
            val tenantId = call.principal<JWTPrincipal>()?.payload?.getClaim("tenantId")?.asString()
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val tenant = tenantRepository.findById(ObjectId(tenantId))
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val state = oauthState.mint(tenant.slug, origin = OAuthState.ORIGIN_DASHBOARD)
            call.respond(mapOf("authorizeUrl" to authorizeUrl(config, state)))
        }
    }

    // Unauthenticated: Meta redirects the browser here with no JWT. The signed `state` is the auth.
    get("/admin/api/instagram/callback") {
        val params = call.request.queryParameters
        val state = params["state"]
        val verified = state?.takeIf { it.isNotBlank() }?.let { oauthState.verify(it) }
        val origin = verified?.origin ?: OAuthState.ORIGIN_BACKOFFICE

        params["error"]?.let { error ->
            log.warn("Instagram OAuth denied: error={} reason={}", error, params["error_reason"])
            return@get call.respondRedirect(resultUrl(origin, "error", reason = error))
        }

        val code = params["code"]
        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            return@get call.respondRedirect(resultUrl(origin, "error", reason = "missing_params"))
        }

        if (verified == null) {
            log.warn("Instagram OAuth: invalid or replayed state")
            return@get call.respondRedirect(resultUrl(origin, "error", reason = "invalid_state"))
        }
        val slug = verified.slug

        val result = oauthClient.exchange(code)
        if (result == null) {
            return@get call.respondRedirect(resultUrl(origin, "error", reason = "exchange_failed", tenant = slug))
        }

        val updated = bindingService.upsert(
            slug,
            ChannelBinding(
                Platform.INSTAGRAM,
                result.igId,
                result.accessToken,
                displayName = result.username,
                tokenObtainedAt = SystemClock.now(),
                source = "oauth",
                grantedScopes = InstagramOAuthScopes.login,
            ),
        )
        if (updated == null) {
            return@get call.respondRedirect(resultUrl(origin, "error", reason = "tenant_not_found", tenant = slug))
        }

        log.info("Instagram channel connected via OAuth: tenant={} igId={} origin={}", slug, result.igId, origin)
        call.respondRedirect(resultUrl(origin, "connected", tenant = slug))
    }
}

private fun authorizeUrl(config: AppConfig.InstagramConfig, state: String): String {
    val scope = InstagramOAuthScopes.authorizeParam
    return "https://www.instagram.com/oauth/authorize" +
        "?client_id=${config.appId.encodeURLParameter()}" +
        "&redirect_uri=${config.redirectUri.encodeURLParameter()}" +
        "&response_type=code" +
        "&scope=${scope.encodeURLParameter()}" +
        "&state=${state.encodeURLParameter()}"
}

private fun resultUrl(origin: String, status: String, reason: String? = null, tenant: String? = null): String =
    buildString {
        append(if (origin == OAuthState.ORIGIN_DASHBOARD) "/app/" else "/backoffice/")
        append("?ig=").append(status.encodeURLParameter())
        if (reason != null) append("&reason=").append(reason.encodeURLParameter())
        if (tenant != null) append("&tenant=").append(tenant.encodeURLParameter())
    }
