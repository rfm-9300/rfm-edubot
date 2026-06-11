package com.rfm.edubot.oauth

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.tenant.ChannelBindingService
import com.rfm.edubot.tenant.TenantRegistry
import com.rfm.edubot.tenant.model.Platform
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("InstagramMetaCallbacks")

/**
 * Meta App Review-required account-removal callbacks for the Instagram product
 * (configured under Instagram product settings → "Deauthorize" / "Data Deletion Request" URLs):
 *
 *  - POST /admin/api/instagram/deauthorize    — user removed the app from their Instagram settings.
 *  - POST /admin/api/instagram/data-deletion  — user requested deletion of the data we hold.
 *
 * Both receive a form-encoded `signed_request` signed with the **Instagram app secret** ([SignedRequest]).
 * The `user_id` is the Instagram account that authorized us, which is exactly the
 * `ChannelBinding.externalId` we stored during OAuth — so removal is: look the tenant up by that id
 * and evict the binding (drops the now-invalid token + stops routing DMs to that account).
 *
 * Data-deletion additionally returns the JSON `{ url, confirmation_code }` Meta requires so the user
 * can track the request; `url` points at the public /data-deletion status page.
 */
fun Route.instagramMetaCallbacks(
    config: AppConfig.InstagramConfig,
    tenantRegistry: TenantRegistry,
    bindingService: ChannelBindingService,
) {
    val statusBaseUrl = config.redirectUri.substringBefore("/admin/api/instagram/callback")

    post("/admin/api/instagram/deauthorize") {
        val payload = verify(call.receiveParameters()["signed_request"], config.appSecret)
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid signed_request"))
        payload.user_id?.let { evictInstagram(it, tenantRegistry, bindingService) }
        // Meta only needs a 200 here.
        call.respond(HttpStatusCode.OK)
    }

    post("/admin/api/instagram/data-deletion") {
        val payload = verify(call.receiveParameters()["signed_request"], config.appSecret)
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid signed_request"))
        val igId = payload.user_id
        if (igId != null) evictInstagram(igId, tenantRegistry, bindingService)
        val code = "ig-${igId ?: "unknown"}"
        call.respond(
            mapOf(
                "url" to "$statusBaseUrl/data-deletion?code=${code.encodeURLParameter()}",
                "confirmation_code" to code,
            ),
        )
    }
}

private fun verify(signedRequest: String?, appSecret: String): SignedRequest.Payload? {
    if (signedRequest.isNullOrBlank()) return null
    val payload = SignedRequest.parse(signedRequest, appSecret)
    if (payload == null) log.warn("Instagram callback: signed_request failed verification")
    return payload
}

private suspend fun evictInstagram(
    igId: String,
    registry: TenantRegistry,
    bindingService: ChannelBindingService,
) {
    val tenant = registry.byExternalId(Platform.INSTAGRAM, igId)
    if (tenant == null) {
        log.info("Instagram removal callback: no binding for igId={} (already gone)", igId)
        return
    }
    bindingService.remove(tenant.slug, Platform.INSTAGRAM, igId)
    log.info("Instagram removal callback: evicted binding tenant={} igId={}", tenant.slug, igId)
}
