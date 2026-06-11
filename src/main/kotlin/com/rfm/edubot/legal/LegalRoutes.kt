package com.rfm.edubot.legal

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Public, unauthenticated legal pages required by Meta App Review:
 *  - GET /privacy        — Privacy Policy URL (App Settings → Basic).
 *  - GET /data-deletion  — Data Deletion Instructions URL, and the status page the
 *                          data-deletion callback ([com.rfm.edubot.oauth.instagramMetaCallbacks])
 *                          points users to.
 *
 * Static HTML lives in `resources/legal/`. Served raw (no auth, no JWT) so Meta's crawler and any
 * end user can load them.
 */
fun Route.legalRoutes() {
    get("/privacy") {
        val html = this::class.java.classLoader.getResource("legal/privacy.html")?.readText()
        call.respondText(html ?: "Privacy policy missing", ContentType.Text.Html)
    }
    get("/data-deletion") {
        val html = this::class.java.classLoader.getResource("legal/data-deletion.html")?.readText()
        call.respondText(html ?: "Data deletion page missing", ContentType.Text.Html)
    }
}
