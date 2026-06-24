package com.rfm.edubot.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * Serves the embeddable website chat widget assets from the classpath (`resources/widget/`).
 * Tenants embed it with: <script src="https://HOST/widget/widget.js" data-key="PUBLIC_KEY" defer></script>
 * `/widget/demo` is a self-contained test page used for local verification.
 */
fun Routing.widgetRoutes() {
    get("/widget/{asset}") {
        val asset = call.parameters["asset"] ?: return@get call.respond(HttpStatusCode.NotFound)
        val contentType = when (asset.substringAfterLast('.', "")) {
            "js" -> ContentType.Application.JavaScript
            "css" -> ContentType.Text.CSS
            "html" -> ContentType.Text.Html
            else -> ContentType.Application.OctetStream
        }
        val bytes = this::class.java.classLoader.getResource("widget/$asset")?.readBytes()
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondBytes(bytes, contentType)
    }
}
