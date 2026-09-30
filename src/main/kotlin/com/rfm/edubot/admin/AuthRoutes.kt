package com.rfm.edubot.admin

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.JWTVerifier
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.dashboard.attachDashboardContext
import com.rfm.edubot.dashboard.resolveDashboardContext
import com.rfm.edubot.tenant.TenantRepository
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.Date

fun Application.configureAdminAuth(runtime: RuntimeConfig, tenants: TenantRepository, dashboardUsers: DashboardUserRepository) {
    val dynamicVerifier = object : JWTVerifier {
        override fun verify(token: String): DecodedJWT = verifierFor(runtime.get().admin).verify(token)
        override fun verify(jwt: DecodedJWT): DecodedJWT = verifierFor(runtime.get().admin).verify(jwt)
    }
    install(Authentication) {
        jwt("admin-jwt") {
            verifier(dynamicVerifier)
            validate { credential ->
                // A Google session ends as soon as its email leaves the allowlist, not when the token expires.
                val email = credential.payload.getClaim("email").asString()
                when {
                    credential.payload.subject != "admin" -> null
                    email != null && email.lowercase() !in runtime.get().admin.googleSignIn.allowedEmails -> null
                    else -> JWTPrincipal(credential.payload)
                }
            }
        }
        jwt("dashboard") {
            verifier(dynamicVerifier)
            validate { credential ->
                val context = resolveDashboardContext(credential.payload, tenants, dashboardUsers) ?: return@validate null
                attachDashboardContext(context)
                JWTPrincipal(credential.payload)
            }
        }
    }
}

fun Route.authRoutes(runtime: RuntimeConfig, googleVerifier: FirebaseIdTokenVerifier = FirebaseIdTokenVerifier()) {
    get("/admin/auth/config") {
        val admin = runtime.get().admin
        val google = admin.googleSignIn.takeIf { it.enabled }?.let {
            GoogleWebConfig(apiKey = it.webApiKey, authDomain = it.authDomain, projectId = it.firebaseProjectId, appId = it.appId)
        }
        call.respond(AuthConfigResponse(passwordEnabled = admin.passwordLoginEnabled, google = google))
    }
    post("/admin/auth/login") {
        val config = runtime.get().admin
        if (!config.passwordLoginEnabled) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "password_login_disabled"))
            return@post
        }
        val request = call.receive<LoginRequest>()
        val verified = BCrypt.verifyer().verify(request.password.toCharArray(), config.adminPasswordHash).verified
        if (!verified) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid credentials"))
            return@post
        }
        call.respond(adminSession(config, email = null))
    }
    post("/admin/auth/google") {
        val config = runtime.get().admin
        val google = config.googleSignIn.takeIf { it.enabled }
            ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "google_sign_in_disabled"))
        val request = call.receive<GoogleLoginRequest>()
        when (val result = withContext(Dispatchers.IO) { googleVerifier.verify(request.idToken, google) }) {
            is FirebaseIdTokenVerifier.Result.Allowed -> {
                authLog.info("Backoffice sign-in with Google: {}", result.email)
                call.respond(adminSession(config, result.email))
            }
            is FirebaseIdTokenVerifier.Result.Rejected -> {
                authLog.warn("Backoffice Google sign-in refused: reason={}, email={}", result.reason, result.email)
                val status = if (result.reason == FirebaseIdTokenVerifier.INVALID_TOKEN) HttpStatusCode.Unauthorized else HttpStatusCode.Forbidden
                call.respond(status, mapOf("error" to result.reason))
            }
        }
    }
}

private val authLog = LoggerFactory.getLogger("AdminAuth")

private fun adminSession(config: AppConfig.AdminConfig, email: String?): LoginResponse {
    val expiresAtMillis = Clock.System.now().toEpochMilliseconds() + config.jwtExpiryHours * 60L * 60L * 1000L
    val builder = JWT.create()
        .withIssuer(config.jwtIssuer)
        .withSubject("admin")
        .withExpiresAt(Date(expiresAtMillis))
    email?.let { builder.withClaim("email", it) }
    return LoginResponse(token = builder.sign(Algorithm.HMAC256(config.jwtSecret)), expiresAt = Date(expiresAtMillis).toInstant().toString(), email = email)
}

private fun verifierFor(config: AppConfig.AdminConfig): JWTVerifier =
    JWT.require(Algorithm.HMAC256(config.jwtSecret))
        .withIssuer(config.jwtIssuer)
        .build()

@Serializable
private data class LoginRequest(val password: String)

@Serializable
private data class GoogleLoginRequest(val idToken: String)

@Serializable
private data class LoginResponse(val token: String, val expiresAt: String, val email: String? = null)

@Serializable
private data class GoogleWebConfig(val apiKey: String, val authDomain: String, val projectId: String, val appId: String)

@Serializable
private data class AuthConfigResponse(val passwordEnabled: Boolean, val google: GoogleWebConfig? = null)
