package com.rfm.edubot.notifications

import com.rfm.edubot.agents.NotificationsDto
import com.rfm.edubot.agents.actorId
import com.rfm.edubot.agents.canManageAgents
import com.rfm.edubot.agents.dto
import com.rfm.edubot.dashboard.dashboardContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.bson.types.ObjectId

/** The top bar's notifications, for every dashboard user whatever modules the company has. */
fun Route.notificationRoutes(notifications: NotificationRepository) {
    authenticate("dashboard") {
        route("/app/api/notifications") {
            get {
                val ctx = call.dashboardContext() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val reader = ctx.actorId()
                val admin = ctx.canManageAgents()
                call.respond(
                    NotificationsDto(
                        items = notifications.listFor(ctx.tenant.id, reader, admin, limit = 50).map { it.dto(reader) },
                        unread = notifications.unreadCount(ctx.tenant.id, reader, admin),
                    ),
                )
            }
            post("/{id}/read") {
                val ctx = call.dashboardContext() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                if (!notifications.markRead(ctx.tenant.id, id, ctx.actorId(), ctx.canManageAgents())) return@post call.respond(HttpStatusCode.NotFound)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/read-all") {
                val ctx = call.dashboardContext() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                notifications.markAllRead(ctx.tenant.id, ctx.actorId(), ctx.canManageAgents())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
