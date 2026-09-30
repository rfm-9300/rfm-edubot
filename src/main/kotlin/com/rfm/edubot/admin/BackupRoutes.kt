package com.rfm.edubot.admin

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val backupLog = LoggerFactory.getLogger("Backups")

fun Route.backupRoutes(backups: BackupControl) {
    authenticate("admin-jwt") {
        route("/admin/api/backups") {
            get {
                call.respond(withContext(Dispatchers.IO) { backups.status() })
            }
            post {
                val by = call.adminEmail()
                try {
                    val status = withContext(Dispatchers.IO) { backups.request(by) }
                    backupLog.info("Backup requested from the backoffice by {}", by ?: "password sign-in")
                    call.respond(HttpStatusCode.Accepted, status)
                } catch (e: BackupControl.Unavailable) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "backups_unavailable"))
                } catch (e: BackupControl.Busy) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "backup_in_progress"))
                }
            }
        }
    }
}
