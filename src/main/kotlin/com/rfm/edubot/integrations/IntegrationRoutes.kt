package com.rfm.edubot.integrations

import com.rfm.edubot.dashboard.DashboardAccessPolicy
import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.dashboard.dashboardContext
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.dashboard.requireModule
import com.rfm.edubot.dashboard.toObjectIdOrNull
import com.rfm.edubot.integrations.email.EmailAddresses
import com.rfm.edubot.integrations.email.EmailSendResult
import com.rfm.edubot.integrations.email.EmailService
import com.rfm.edubot.integrations.email.httpStatus
import com.rfm.edubot.integrations.google.GoogleIntegration
import com.rfm.edubot.integrations.google.GoogleScopes
import com.rfm.edubot.oauth.OAuthState
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.TenantStatus
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("IntegrationRoutes")

/**
 * A company's connected accounts (docs/plan-agents-automations.md, "Google / Gmail integration"), under the
 * settings module:
 *  - GET    /app/api/integrations                 the connections, and whether Google can be connected
 *  - GET    /app/api/integrations/google/connect  { authorizeUrl } for a company admin signed in as themselves; ?account= reconnects one
 *  - PATCH  /app/api/integrations/{id}            sender name, reply-to, signature, default sender
 *  - POST   /app/api/integrations/{id}/test-email a test email to the admin asking (an operator: to the account itself)
 *  - DELETE /app/api/integrations/{id}            delete the tokens and the kept mail, revoking the grant when no company still uses it
 *  - GET    /integrations/google/callback         public: Google redirects the browser here; the signed state is the auth
 */
fun Route.integrationRoutes(
    google: GoogleIntegration,
    email: EmailService,
    oauthState: OAuthState,
    tenants: TenantRepository,
    users: DashboardUserRepository,
    clock: () -> Instant = SystemClock::now,
) {
    val connections = google.connections

    authenticate("dashboard") {
        route("/app/api/integrations") {
            get {
                val ctx = call.integrationsContext() ?: return@get
                val today = email.today(ctx.tenant)
                val limit = email.dailyLimit(ctx.tenant)
                call.respond(
                    IntegrationsDto(
                        google = GoogleAvailabilityDto(configured = google.configured, canConnect = ctx.canConnect()),
                        connections = connections.list(ctx.tenant.id).map { it.dto(today, limit) },
                        canManage = ctx.canManageIntegrations(),
                    ),
                )
            }

            // The operator would consent with their own Google account, so only the company's admins connect.
            get("/google/connect") {
                val ctx = call.integrationsContext() ?: return@get
                if (ctx.principalType == DashboardAccessPolicy.OPERATOR_IMPERSONATION) {
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "operator_not_allowed"))
                }
                val user = ctx.user?.takeIf { ctx.canConnect() }
                    ?: return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                if (!google.configured) {
                    return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "google_not_configured"))
                }
                val state = oauthState.mint(
                    ctx.tenant.slug,
                    origin = OAuthState.ORIGIN_DASHBOARD,
                    purpose = OAuthState.PURPOSE_GOOGLE,
                    tenantId = ctx.tenant.id.toHexString(),
                    userId = user.id.toHexString(),
                )
                val reconnecting = call.request.queryParameters["account"]?.let(EmailAddresses::normalize)
                    ?.takeIf { account -> connections.list(ctx.tenant.id).any { it.accountEmail == account } }
                call.respond(mapOf("authorizeUrl" to google.oauth.authorizeUrl(state, loginHint = reconnecting)))
            }

            patch("/{id}") {
                val ctx = call.integrationsContext() ?: return@patch
                if (!ctx.canManageIntegrations()) return@patch call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val connection = call.connectionParam(connections, ctx) ?: return@patch
                val request = runCatching { call.receive<UpdateConnectionRequest>() }.getOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                val settings = when (val update = request.applyTo(connection.settings)) {
                    is SettingsUpdate.Invalid -> return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to update.error))
                    is SettingsUpdate.Ok -> update.settings
                }
                var updated: IntegrationConnection? = connection
                if (settings != connection.settings) updated = connections.updateSettings(ctx.tenant.id, connection.id, settings)
                if (request.isDefault == true && !connection.isDefault) updated = connections.makeDefault(ctx.tenant.id, connection.id)
                updated?.let { call.respond(it.dto(email.today(ctx.tenant), email.dailyLimit(ctx.tenant))) } ?: call.respond(HttpStatusCode.NotFound)
            }

            post("/{id}/test-email") {
                val ctx = call.integrationsContext() ?: return@post
                if (!ctx.canManageIntegrations()) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val connection = call.connectionParam(connections, ctx) ?: return@post
                // Never a third party: the button can't be used to write to anyone else.
                val to = ctx.user?.email ?: connection.accountEmail
                when (val sent = email.sendTest(ctx.tenant, connection, to)) {
                    is EmailSendResult.Sent -> call.respond(TestEmailDto(to = to, messageId = sent.messageId))
                    is EmailSendResult.Failed -> call.respond(sent.httpStatus(), mapOf("error" to sent.key))
                }
            }

            delete("/{id}") {
                val ctx = call.integrationsContext() ?: return@delete
                if (!ctx.canManageIntegrations()) return@delete call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val connection = call.connectionParam(connections, ctx) ?: return@delete
                connections.delete(ctx.tenant.id, connection.id) ?: return@delete call.respond(HttpStatusCode.NotFound)
                email.forget(connection)
                if (connections.countForAccount(connection.provider, connection.accountEmail) == 0L) {
                    val token = (connection.refreshToken ?: connection.accessToken)?.let { google.cipher?.open(it) }
                    val revoked = token != null && google.oauth.revoke(token)
                    log.info("Google account disconnected: tenant={} connection={} revoked={}", ctx.tenant.slug, connection.id, revoked)
                } else {
                    log.info("Google account disconnected, grant kept for another company: tenant={} connection={}", ctx.tenant.slug, connection.id)
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    get("/integrations/google/callback") {
        val params = call.request.queryParameters
        val state = params["state"]
        val verified = state?.takeIf { it.isNotBlank() }?.let { oauthState.verify(it) }?.takeIf { it.purpose == OAuthState.PURPOSE_GOOGLE }

        params["error"]?.let { error ->
            log.info("Google consent not given: error={}", error)
            return@get call.respondRedirect(googleResult("error", error.takeIf { REASON.matches(it) } ?: "consent_failed"))
        }
        val code = params["code"]
        if (code.isNullOrBlank() || state.isNullOrBlank()) return@get call.respondRedirect(googleResult("error", "missing_params"))
        if (verified == null) {
            log.warn("Google OAuth: invalid, replayed or foreign state")
            return@get call.respondRedirect(googleResult("error", "invalid_state"))
        }

        val tenant = verified.tenantId?.toObjectIdOrNull()?.let { tenants.findById(it) }?.takeIf { it.status != TenantStatus.DELETED }
            ?: return@get call.respondRedirect(googleResult("error", "tenant_not_found"))
        // The admin may have been demoted or disabled while Google's consent screen was open.
        val user = verified.userId?.toObjectIdOrNull()?.let { users.findById(it) }
            ?.takeIf { it.role == DashboardUserRole.TENANT_ADMIN && DashboardAccessPolicy.allows(tenant, it, DashboardAccessPolicy.TENANT_USER) }
            ?: return@get call.respondRedirect(googleResult("error", "not_allowed"))
        val cipher = google.cipher
        if (!google.configured || cipher == null) return@get call.respondRedirect(googleResult("error", "not_configured"))

        val grant = google.oauth.exchange(code) ?: return@get call.respondRedirect(googleResult("error", "exchange_failed"))
        val email = grant.email?.takeIf { grant.emailVerified }?.let(EmailAddresses::normalize)

        suspend fun refuse(reason: String) {
            // A revoke ends the account's whole grant, so it only happens when no company relies on that account.
            if (email != null && connections.countForAccount(IntegrationProviders.GOOGLE, email) == 0L) google.oauth.revoke(grant.accessToken)
            log.info("Google account not connected: tenant={} reason={}", tenant.slug, reason)
            call.respondRedirect(googleResult("error", reason))
        }
        if (!GoogleScopes.canSend(grant.scopes)) return@get refuse("missing_scope")
        if (email == null) return@get refuse("no_email")
        val refreshToken = grant.refreshToken ?: return@get refuse("no_refresh_token")

        val connection = connections.connect(
            tenantId = tenant.id,
            provider = IntegrationProviders.GOOGLE,
            accountEmail = email,
            scopes = grant.scopes,
            accessToken = cipher.seal(grant.accessToken),
            refreshToken = cipher.seal(refreshToken),
            accessTokenExpiresAt = clock() + grant.expiresInSeconds.seconds,
            connectedByUserId = user.id.toHexString(),
            connectedByEmail = user.email,
        )
        log.info("Google account connected: tenant={} connection={}", tenant.slug, connection.id)
        call.respondRedirect(googleResult("connected"))
    }
}

private val REASON = Regex("^[a-z_]{1,40}$")

private const val MAX_SENDER_NAME = 80
private const val MAX_SIGNATURE = 2000

private fun googleResult(status: String, reason: String? = null): String = buildString {
    append("/app/?google=").append(status.encodeURLParameter())
    if (reason != null) append("&reason=").append(reason.encodeURLParameter())
}

private sealed interface SettingsUpdate {
    data class Ok(val settings: EmailSettings) : SettingsUpdate
    data class Invalid(val error: String) : SettingsUpdate
}

private fun UpdateConnectionRequest.applyTo(current: EmailSettings): SettingsUpdate {
    val name = senderName?.trim()
    if (name != null && (name.length > MAX_SENDER_NAME || name.any { it.isISOControl() })) return SettingsUpdate.Invalid("invalid_sender_name")
    val reply = replyTo?.trim()
    val replyAddress = reply?.takeIf { it.isNotEmpty() }?.let { EmailAddresses.normalize(it) ?: return SettingsUpdate.Invalid("invalid_reply_to") }
    val sign = signature?.trim()
    if (sign != null && sign.length > MAX_SIGNATURE) return SettingsUpdate.Invalid("signature_too_long")
    return SettingsUpdate.Ok(
        EmailSettings(
            senderName = if (name == null) current.senderName else name.ifEmpty { null },
            replyTo = if (reply == null) current.replyTo else replyAddress,
            signature = if (sign == null) current.signature else sign.ifEmpty { null },
        ),
    )
}

private fun DashboardContext.canManageIntegrations(): Boolean =
    principalType == DashboardAccessPolicy.OPERATOR_IMPERSONATION || user?.role == DashboardUserRole.TENANT_ADMIN

private fun DashboardContext.canConnect(): Boolean =
    principalType == DashboardAccessPolicy.TENANT_USER && user?.role == DashboardUserRole.TENANT_ADMIN

private suspend fun ApplicationCall.integrationsContext(): DashboardContext? {
    val ctx = dashboardContext() ?: run {
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    if (!ctx.requireModule(DashboardModules.SETTINGS)) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "module_disabled"))
        return null
    }
    return ctx
}

private suspend fun ApplicationCall.connectionParam(connections: IntegrationConnectionRepository, ctx: DashboardContext): IntegrationConnection? {
    val id = parameters["id"]?.toObjectIdOrNull() ?: run {
        respond(HttpStatusCode.BadRequest)
        return null
    }
    return connections.find(ctx.tenant.id, id) ?: run {
        respond(HttpStatusCode.NotFound)
        return null
    }
}
