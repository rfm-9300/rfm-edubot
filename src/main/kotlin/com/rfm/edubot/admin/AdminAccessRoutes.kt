package com.rfm.edubot.admin

import com.rfm.edubot.config.RuntimeConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** The email of the signed-in operator; null for a password sign-in. */
internal fun ApplicationCall.adminEmail(): String? =
    principal<JWTPrincipal>()?.payload?.getClaim("email")?.asString()

fun Route.adminAccessRoutes(access: AdminAccess, runtime: RuntimeConfig) {
    authenticate("admin-jwt") {
        route("/admin/api/admins") {
            get {
                call.respond(
                    AdminListDto(
                        googleReady = runtime.get().admin.googleSignIn.webConfigured,
                        me = call.adminEmail(),
                        admins = access.list().map { it.dto() },
                    ),
                )
            }
            post {
                val request = call.receive<AddAdminRequest>()
                try {
                    call.respond(HttpStatusCode.Created, access.add(request.email, call.adminEmail()).dto())
                } catch (e: AdminAccess.Refused) {
                    val status = if (e.error == "invalid_email") HttpStatusCode.BadRequest else HttpStatusCode.Conflict
                    call.respond(status, mapOf("error" to e.error))
                }
            }
            delete("/{email}") {
                val email = call.parameters["email"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                try {
                    access.remove(email, call.adminEmail())
                    call.respond(mapOf("removed" to true))
                } catch (e: AdminAccess.Refused) {
                    val status = if (e.error == "not_found") HttpStatusCode.NotFound else HttpStatusCode.Conflict
                    call.respond(status, mapOf("error" to e.error))
                }
            }
        }
    }
}

@Serializable
private data class AddAdminRequest(val email: String)

@Serializable
private data class AdminListDto(
    /** False while the Firebase web config is missing: nobody can sign in with Google yet. */
    val googleReady: Boolean,
    val me: String?,
    val admins: List<AdminEntryDto>,
)

@Serializable
private data class AdminEntryDto(
    val email: String,
    /** "env" (ADMIN_EMAILS, can't be removed here) or "backoffice". */
    val source: String,
    val addedBy: String?,
    val addedAt: String?,
)

private fun AdminAccess.Entry.dto() = AdminEntryDto(
    email = email,
    source = if (fromEnv) "env" else "backoffice",
    addedBy = addedBy,
    addedAt = addedAt?.toString(),
)
