package com.rfm.edubot.dashboard.settings

import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.OverviewHomeLayout
import com.rfm.edubot.dashboard.moduleContext
import com.rfm.edubot.dashboard.meDto
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.TenantLocales
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

/** The Settings page: the website widget, disconnecting a channel, the dashboard's language and Home's cards. */
internal object SettingsRoutes : DashboardFeature(DashboardModules.SETTINGS) {
    override fun Route.routes(deps: DashboardDeps) {
        get("/web-widget") {
            val ctx = call.moduleContext()
            call.respond(ctx.tenant.binding(Platform.WEB).toWebWidgetDto())
        }
        // Self-serve website-widget onboarding: create the WEB binding on first call (minting a
        // public key) and update its origin allow-list on later calls — the key is preserved so a
        // tenant's embedded snippet never breaks. ChannelBindingService re-indexes the registry and
        // evicts the pipeline so the new channel is live without a restart.
        post("/web-widget") {
            val ctx = call.moduleContext()
            val request = runCatching { call.receive<WebWidgetRequest>() }.getOrDefault(WebWidgetRequest())
            val existing = ctx.tenant.binding(Platform.WEB)
            val publicKey = existing?.externalId ?: ("web_" + ObjectId().toHexString())
            val origins = request.allowedOrigins.map { it.trim() }.filter { it.isNotBlank() }
            val updated = deps.channelBindings.upsert(
                ctx.tenant.slug,
                ChannelBinding(
                    platform = Platform.WEB,
                    externalId = publicKey,
                    displayName = existing?.displayName ?: "Website",
                    source = existing?.source ?: "dashboard",
                    allowedOrigins = origins,
                ),
            ) ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(updated.binding(Platform.WEB).toWebWidgetDto())
        }
        // Self-serve disconnect: only removes the binding for the tenant's own slug (never an
        // arbitrary one from the URL), mirroring the admin-only delete in TenantAdminRoutes but
        // scoped to the authenticated tenant. Instagram's Meta-side deauthorize webhook
        // (InstagramMetaCallbacks) converges on the same ChannelBindingService.remove call.
        delete("/channels/{platform}/{externalId}") {
            val ctx = call.moduleContext()
            val platform = call.parameters["platform"]?.uppercase()?.let { runCatching { Platform.valueOf(it) }.getOrNull() }
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid platform"))
            val externalId = call.parameters["externalId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val updated = deps.channelBindings.remove(ctx.tenant.slug, platform, externalId)
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            call.respond(updated.meDto())
        }
        // Tenant-selectable UI language. Persisted on the tenant so it becomes the default for every
        // dashboard session; the browser keeps a per-session override (localStorage.uiLocale).
        post("/settings/locale") {
            val ctx = call.moduleContext()
            val request = call.receive<LocaleRequest>()
            if (request.locale !in TenantLocales.SUPPORTED) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unsupported locale"))
            }
            val updated = deps.tenants.setLocale(ctx.tenant.slug, request.locale, SystemClock.now())
                ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(mapOf("locale" to updated.locale))
        }
        get("/settings/overview") {
            val ctx = call.moduleContext()
            call.respond(OverviewHomeLayout.dto(ctx.tenant))
        }
        put("/settings/overview") {
            val ctx = call.moduleContext()
            val request = call.receive<OverviewLayoutRequest>()
            val hidden = OverviewHomeLayout.sanitize(request.hidden)
            val updated = deps.tenants.setOverviewHiddenCards(ctx.tenant.slug, hidden, SystemClock.now())
                ?: return@put call.respond(HttpStatusCode.NotFound)
            call.respond(OverviewHomeLayout.dto(updated))
        }
    }
}

@Serializable private data class WebWidgetRequest(val allowedOrigins: List<String> = emptyList())
@Serializable private data class LocaleRequest(val locale: String)
@Serializable private data class OverviewLayoutRequest(val hidden: List<String> = emptyList())
@Serializable private data class WebWidgetDto(val publicKey: String? = null, val allowedOrigins: List<String> = emptyList())

private fun ChannelBinding?.toWebWidgetDto() = WebWidgetDto(publicKey = this?.externalId, allowedOrigins = this?.allowedOrigins ?: emptyList())
