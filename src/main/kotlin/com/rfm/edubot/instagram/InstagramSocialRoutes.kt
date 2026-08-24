package com.rfm.edubot.instagram

import com.rfm.edubot.tenant.model.Tenant
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

data class InstagramSocialDeps(
    val tenant: Tenant,
    val service: InstagramSocialService,
)

fun Route.installInstagramSocialRoutes(resolve: suspend ApplicationCall.() -> InstagramSocialDeps?) {
    route("/instagram") {
        get {
            val deps = call.resolve() ?: return@get
            val refresh = call.request.queryParameters["refresh"] == "1"
            call.respond(deps.service.summary(deps.tenant, refresh))
        }
        get("/media/{mediaId}") {
            val deps = call.resolve() ?: return@get
            val mediaId = call.parameters["mediaId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val refresh = call.request.queryParameters["refresh"] != "0"
            val (status, body) = deps.service.mediaDetail(deps.tenant, mediaId, refresh)
            call.respond(status, body)
        }
        post("/comments/{commentId}/replies") {
            val deps = call.resolve() ?: return@post
            val commentId = call.parameters["commentId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<InstagramReplyRequest>()
            val (status, body) = deps.service.reply(deps.tenant, commentId, request.message)
            call.respond(status, body)
        }
    }
}
