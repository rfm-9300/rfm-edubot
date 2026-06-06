package com.rfm.edubot.admin

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.rfm.edubot.config.AppConfig
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import java.util.Date

fun Application.configureAdminAuth(config: AppConfig.AdminConfig) {
    val algorithm = Algorithm.HMAC256(config.jwtSecret)
    install(Authentication) {
        jwt("admin-jwt") {
            verifier(
                JWT.require(algorithm)
                    .withIssuer(config.jwtIssuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.subject == "admin") JWTPrincipal(credential.payload) else null
            }
        }
        jwt("dashboard") {
            verifier(
                JWT.require(algorithm)
                    .withIssuer(config.jwtIssuer)
                    .build()
            )
            validate { credential ->
                val typ = credential.payload.getClaim("typ").asString()
                val tenantId = credential.payload.getClaim("tenantId").asString()
                if ((typ == "tenant" || typ == "operator-imp") && !tenantId.isNullOrBlank()) JWTPrincipal(credential.payload) else null
            }
        }
    }
}

fun Route.authRoutes(config: AppConfig.AdminConfig) {
    post("/admin/auth/login") {
        val request = call.receive<LoginRequest>()
        val verified = BCrypt.verifyer().verify(request.password.toCharArray(), config.adminPasswordHash).verified
        if (!verified) {
            call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "invalid credentials"))
            return@post
        }

        val expiresAtMillis = Clock.System.now().toEpochMilliseconds() + config.jwtExpiryHours * 60L * 60L * 1000L
        val token = JWT.create()
            .withIssuer(config.jwtIssuer)
            .withSubject("admin")
            .withExpiresAt(Date(expiresAtMillis))
            .sign(Algorithm.HMAC256(config.jwtSecret))
        call.respond(LoginResponse(token = token, expiresAt = Date(expiresAtMillis).toInstant().toString()))
    }
}

@Serializable
private data class LoginRequest(val password: String)

@Serializable
private data class LoginResponse(val token: String, val expiresAt: String)
