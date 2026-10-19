package com.rfm.edubot.admin

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.adminRoutes() {
    // The old tenant CRM at /admin is retired. Clients use /app; you configure
    // tenants in /backoffice. Shared CSS/JS/catalogs stay under /admin/{asset}.
    get("/admin") { call.respondRedirect("/backoffice/") }
    get("/admin/") { call.respondRedirect("/backoffice/") }
    get("/admin/{asset}") {
        val asset = call.parameters["asset"] ?: return@get call.respond(HttpStatusCode.NotFound)
        val contentType = when (asset.substringAfterLast('.', "")) {
            "js" -> ContentType.Application.JavaScript
            "css" -> ContentType.Text.CSS
            else -> ContentType.Application.OctetStream
        }
        val bytes = this::class.java.classLoader.getResource("admin/$asset")?.readBytes()
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondBytes(bytes, contentType)
    }
}
