package com.rfm.edubot.dashboard

import at.favre.lib.crypto.bcrypt.BCrypt
import com.rfm.edubot.admin.FirebaseIdTokenVerifier
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserStatus
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.TenantRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Decides which dashboard user a verified Google account signs in as: the user it is linked to, or
 * else the user with the same (verified) email, whose account then gets linked automatically.
 */
internal class DashboardGoogleSignIn(
    private val users: DashboardUserRepository,
    private val tenants: TenantRepository,
    private val employees: EmployeeLookup = noEmployeeLookup,
) {

    sealed interface Outcome {
        data class SignedIn(val user: DashboardUser, val linkedNow: Boolean) : Outcome
        data class Refused(val reason: String) : Outcome
    }

    suspend fun signIn(uid: String, email: String, now: Instant): Outcome {
        val linked = users.findByGoogleUid(uid)
        val user = linked ?: users.findByEmail(email) ?: return Outcome.Refused(NO_ACCOUNT)
        // Once a user links a Google account, only that account signs them in.
        if (linked == null && user.googleUid != null) return Outcome.Refused(OTHER_GOOGLE_ACCOUNT)
        signInCompany(user, tenants, employees) ?: return Outcome.Refused(ACCOUNT_INACTIVE)
        if (linked == null || linked.googleEmail != email) {
            if (users.linkGoogle(user.id, uid, email) != DashboardUserRepository.LinkResult.LINKED) return Outcome.Refused(OTHER_GOOGLE_ACCOUNT)
        }
        users.markLogin(user.id, now)
        return Outcome.SignedIn(user.copy(googleUid = uid, googleEmail = email), linkedNow = linked == null)
    }

    companion object {
        const val NO_ACCOUNT = "no_account"
        const val OTHER_GOOGLE_ACCOUNT = "other_google_account"
        const val ACCOUNT_INACTIVE = "account_inactive"
    }
}

/** Password rules for self-service changes; BCrypt only reads the first 72 bytes. */
internal fun newPasswordProblem(password: String): String? = when {
    password.length < MIN_PASSWORD_LENGTH -> "password_too_short"
    password.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES -> "password_too_long"
    else -> null
}

private const val MIN_PASSWORD_LENGTH = 8
private const val MAX_PASSWORD_BYTES = 72

/** A Google sign-in used to confirm an account change must be this recent. */
private const val RECENT_SIGN_IN_SECONDS = 5 * 60L

/**
 * Tenant dashboard sign-in, with a password or with Google, and each user's own sign-in settings: link
 * or unlink a Google account, change their password, or turn the password off to sign in with Google only.
 * An open session alone can't change how you sign in: each change needs the current password, or a
 * fresh sign-in to the linked Google account (to turn the password off, or to set one again).
 */
fun Route.dashboardAccountRoutes(
    tenantRepository: TenantRepository,
    dashboardUsers: DashboardUserRepository,
    runtimeConfig: RuntimeConfig,
    googleVerifier: FirebaseIdTokenVerifier = FirebaseIdTokenVerifier(),
    employees: EmployeeLookup = noEmployeeLookup,
) {
    val googleSignIn = DashboardGoogleSignIn(dashboardUsers, tenantRepository, employees)
    fun googleConfig(): AppConfig.GoogleSignInConfig? = runtimeConfig.get().admin.googleSignIn.takeIf { it.webConfigured }

    get("/app/auth/config") {
        call.respond(DashboardAuthConfig(google = googleConfig()?.webConfig()))
    }

    post("/app/auth/login") {
        val request = call.receive<DashboardLoginRequest>()
        val user = dashboardUsers.findByEmail(request.email)
        // A Google-only account (no password) gets the same answer as a wrong password, so the
        // endpoint doesn't reveal which emails exist or how they sign in.
        if (user == null || user.status != DashboardUserStatus.ACTIVE || !user.passwordMatches(request.password)) {
            return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid credentials"))
        }
        signInCompany(user, tenantRepository, employees)
            ?: return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "account inactive"))
        dashboardUsers.markLogin(user.id, SystemClock.now())
        val admin = runtimeConfig.get().admin
        call.respond(DashboardTokenResponse(token = dashboardToken(admin, user, DashboardAccessPolicy.TENANT_USER, admin.jwtExpiryHours)))
    }

    post("/app/auth/google") {
        val google = googleConfig() ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "google_sign_in_disabled"))
        val request = call.receive<GoogleIdTokenRequest>()
        val identity = when (val result = withContext(Dispatchers.IO) { googleVerifier.identify(request.idToken, google) }) {
            is FirebaseIdTokenVerifier.Result.Allowed -> result
            is FirebaseIdTokenVerifier.Result.Rejected -> {
                accountLog.warn("Dashboard Google sign-in refused: reason={}, email={}", result.reason, result.email)
                return@post call.respondRejected(result)
            }
        }
        when (val outcome = googleSignIn.signIn(identity.uid, identity.email, SystemClock.now())) {
            is DashboardGoogleSignIn.Outcome.SignedIn -> {
                accountLog.info("Dashboard sign-in with Google: {} (linked now: {})", identity.email, outcome.linkedNow)
                val admin = runtimeConfig.get().admin
                call.respond(DashboardTokenResponse(token = dashboardToken(admin, outcome.user, DashboardAccessPolicy.TENANT_USER, admin.jwtExpiryHours)))
            }
            is DashboardGoogleSignIn.Outcome.Refused -> {
                accountLog.warn("Dashboard Google sign-in refused: reason={}, email={}", outcome.reason, identity.email)
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to outcome.reason))
            }
        }
    }

    authenticate("dashboard") {
        route("/app/api/account") {
            get {
                val user = call.accountUser() ?: return@get
                call.respond(user.accountDto(googleConfig() != null))
            }

            post("/google/link") {
                val user = call.accountUser() ?: return@post
                val google = googleConfig() ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "google_sign_in_disabled"))
                val request = call.receive<LinkGoogleRequest>()
                if (user.googleUid != null) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "already_linked"))
                if (!user.passwordMatches(request.currentPassword)) return@post call.respondWrongPassword()
                val identity = when (val result = withContext(Dispatchers.IO) { googleVerifier.identify(request.idToken, google, RECENT_SIGN_IN_SECONDS) }) {
                    is FirebaseIdTokenVerifier.Result.Allowed -> result
                    is FirebaseIdTokenVerifier.Result.Rejected -> return@post call.respondRejected(result)
                }
                when (dashboardUsers.linkGoogle(user.id, identity.uid, identity.email)) {
                    DashboardUserRepository.LinkResult.LINKED -> {
                        accountLog.info("Dashboard user {} linked Google account {}", user.email, identity.email)
                        call.respondAccount(dashboardUsers, user, googleAvailable = true)
                    }
                    DashboardUserRepository.LinkResult.TAKEN -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "google_in_use"))
                    DashboardUserRepository.LinkResult.NOT_FOUND -> call.respond(HttpStatusCode.NotFound)
                }
            }

            post("/google/unlink") {
                val user = call.accountUser() ?: return@post
                val request = call.receive<CurrentPasswordRequest>()
                if (user.googleUid != null) {
                    // Without a password, unlinking would leave no way to sign in.
                    if (user.passwordHash == null) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "password_required"))
                    if (!user.passwordMatches(request.currentPassword)) return@post call.respondWrongPassword()
                    dashboardUsers.unlinkGoogle(user.id)
                    accountLog.info("Dashboard user {} unlinked their Google account", user.email)
                }
                call.respondAccount(dashboardUsers, user, googleConfig() != null)
            }

            post("/password") {
                val user = call.accountUser() ?: return@post
                val request = call.receive<SetPasswordRequest>()
                newPasswordProblem(request.newPassword)?.let { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
                if (user.passwordHash != null) {
                    if (!user.passwordMatches(request.currentPassword)) return@post call.respondWrongPassword()
                } else if (!call.confirmLinkedGoogle(user, request.idToken, googleConfig(), googleVerifier)) {
                    return@post
                }
                val hash = withContext(Dispatchers.Default) { BCrypt.withDefaults().hashToString(12, request.newPassword.toCharArray()) }
                dashboardUsers.setPasswordHash(user.id, hash)
                accountLog.info("Dashboard user {} {} their password", user.email, if (user.passwordHash == null) "set" else "changed")
                call.respondAccount(dashboardUsers, user, googleConfig() != null)
            }

            // Confirmed with a fresh sign-in to the linked Google account, which proves that account
            // still works before it becomes the only way in.
            post("/password/disable") {
                val user = call.accountUser() ?: return@post
                val request = call.receive<GoogleConfirmRequest>()
                if (user.passwordHash != null) {
                    if (user.googleUid == null) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "google_required"))
                    if (!call.confirmLinkedGoogle(user, request.idToken, googleConfig(), googleVerifier)) return@post
                    dashboardUsers.setPasswordHash(user.id, null)
                    accountLog.info("Dashboard user {} switched to Google-only sign-in", user.email)
                }
                call.respondAccount(dashboardUsers, user, googleConfig() != null)
            }
        }
    }
}

private val accountLog = LoggerFactory.getLogger("DashboardAuth")

/** Only a signed-in tenant user has an account here; an operator opening the dashboard does not. */
private suspend fun ApplicationCall.accountUser(): DashboardUser? {
    val context = dashboardContext() ?: return null
    val user = context.user
    if (user == null || context.principalType != DashboardAccessPolicy.TENANT_USER) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "no_user_account"))
        return null
    }
    return user
}

private fun DashboardUser.passwordMatches(candidate: String?): Boolean {
    val hash = passwordHash ?: return false
    if (candidate.isNullOrEmpty()) return false
    return runCatching { BCrypt.verifyer().verify(candidate.toCharArray(), hash).verified }.getOrDefault(false)
}

private suspend fun ApplicationCall.respondWrongPassword() =
    respond(HttpStatusCode.Forbidden, mapOf("error" to "wrong_password"))

/** A recent Google sign-in to the account linked to [user]; otherwise responds with the reason and returns false. */
private suspend fun ApplicationCall.confirmLinkedGoogle(
    user: DashboardUser,
    idToken: String?,
    google: AppConfig.GoogleSignInConfig?,
    verifier: FirebaseIdTokenVerifier,
): Boolean {
    if (google == null) {
        respond(HttpStatusCode.NotFound, mapOf("error" to "google_sign_in_disabled"))
        return false
    }
    if (idToken.isNullOrBlank()) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "google_confirmation_required"))
        return false
    }
    return when (val result = withContext(Dispatchers.IO) { verifier.identify(idToken, google, RECENT_SIGN_IN_SECONDS) }) {
        is FirebaseIdTokenVerifier.Result.Rejected -> {
            respondRejected(result)
            false
        }
        is FirebaseIdTokenVerifier.Result.Allowed -> {
            if (result.uid != user.googleUid) respond(HttpStatusCode.Forbidden, mapOf("error" to "other_google_account"))
            result.uid == user.googleUid
        }
    }
}

private suspend fun ApplicationCall.respondRejected(result: FirebaseIdTokenVerifier.Result.Rejected) {
    val status = if (result.reason == FirebaseIdTokenVerifier.INVALID_TOKEN) HttpStatusCode.Unauthorized else HttpStatusCode.Forbidden
    respond(status, mapOf("error" to result.reason))
}

private suspend fun ApplicationCall.respondAccount(users: DashboardUserRepository, user: DashboardUser, googleAvailable: Boolean) {
    val fresh = users.findById(user.id) ?: return respond(HttpStatusCode.NotFound)
    respond(fresh.accountDto(googleAvailable))
}

private fun DashboardUser.accountDto(googleAvailable: Boolean) = AccountDto(
    email = email,
    role = role.name,
    passwordEnabled = passwordHash != null,
    googleEmail = googleEmail?.takeIf { googleUid != null },
    googleAvailable = googleAvailable,
)

private fun AppConfig.GoogleSignInConfig.webConfig() =
    DashboardGoogleWebConfig(apiKey = webApiKey, authDomain = authDomain, projectId = firebaseProjectId, appId = appId)

@Serializable private data class DashboardGoogleWebConfig(val apiKey: String, val authDomain: String, val projectId: String, val appId: String)
@Serializable private data class DashboardAuthConfig(val google: DashboardGoogleWebConfig?)
@Serializable private data class DashboardLoginRequest(val email: String, val password: String)
@Serializable private data class GoogleIdTokenRequest(val idToken: String)
@Serializable private data class DashboardTokenResponse(val token: String)
@Serializable private data class LinkGoogleRequest(val idToken: String, val currentPassword: String? = null)
@Serializable private data class CurrentPasswordRequest(val currentPassword: String? = null)
@Serializable private data class GoogleConfirmRequest(val idToken: String? = null)
@Serializable private data class SetPasswordRequest(val newPassword: String, val currentPassword: String? = null, val idToken: String? = null)
@Serializable private data class AccountDto(
    val email: String,
    val role: String,
    val passwordEnabled: Boolean,
    val googleEmail: String?,
    val googleAvailable: Boolean,
)
