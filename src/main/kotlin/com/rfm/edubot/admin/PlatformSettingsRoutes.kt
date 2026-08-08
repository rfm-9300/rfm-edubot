package com.rfm.edubot.admin

import com.rfm.edubot.config.PlatformSettingsService
import com.rfm.edubot.config.PlatformSettingsUpdateRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.platformSettingsRoutes(service: PlatformSettingsService) {
    authenticate("admin-jwt") {
        route("/admin/api/platform-settings") {
            get {
                call.respond(service.list())
            }
            put {
                try {
                    val request = call.receive<PlatformSettingsUpdateRequest>()
                    call.respond(service.update(request))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid request")))
                }
            }
            post("/reload") {
                call.respond(service.reload())
            }
            get("/reveal") {
                val key = call.request.queryParameters["key"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "key is required"))
                try {
                    call.respond(service.reveal(key))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid key")))
                }
            }
        }
    }
}
