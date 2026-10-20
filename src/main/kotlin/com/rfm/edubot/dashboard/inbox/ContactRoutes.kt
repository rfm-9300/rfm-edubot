package com.rfm.edubot.dashboard.inbox

import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.UserStatus
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.moduleContext
import com.rfm.edubot.http.enumValue
import com.rfm.edubot.http.idParam
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch

/** `/contacts`: the people who wrote to the company on its channels, and blocking them. */
internal object ContactRoutes : DashboardFeature(DashboardModules.CONTACTS) {
    override fun Route.routes(deps: DashboardDeps) {
        get("/contacts") {
            val ctx = call.moduleContext()
            call.respond(UserRepository(deps.mongo, ctx.tenant.id).list(call.request.queryParameters["q"]).map { it.dto() })
        }
        patch("/contacts/{id}/status") {
            val ctx = call.moduleContext()
            val request = call.receive<ContactStatusRequest>()
            val id = call.idParam("id")
            val user = UserRepository(deps.mongo, ctx.tenant.id).setStatus(id, enumValue<UserStatus>(request.status, "status"))
                ?: return@patch call.respond(HttpStatusCode.NotFound)
            call.respond(user.dto())
        }
    }
}
