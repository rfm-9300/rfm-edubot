package com.rfm.edubot.dashboard

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.tenant.model.Tenant
import java.util.Date

/** A signed-in [user]'s dashboard token, for the company that sign-in opens. */
internal fun dashboardToken(config: AppConfig.AdminConfig, user: DashboardUser, typ: String, expiryHours: Int): String = JWT.create()
    .withIssuer(config.jwtIssuer)
    .withSubject(user.id.toHexString())
    .withClaim("tenantId", user.homeTenantId.toHexString())
    .withClaim("role", user.role.name)
    .withClaim("typ", typ)
    .withExpiresAt(Date(System.currentTimeMillis() + expiryHours * 60L * 60L * 1000L))
    .sign(Algorithm.HMAC256(config.jwtSecret))

/** An operator's token for opening [tenant]'s dashboard from the backoffice. */
internal fun dashboardToken(config: AppConfig.AdminConfig, tenant: Tenant, typ: String, expiryHours: Int): String = JWT.create()
    .withIssuer(config.jwtIssuer)
    .withSubject("operator")
    .withClaim("tenantId", tenant.id.toHexString())
    .withClaim("role", "PLATFORM_ADMIN")
    .withClaim("typ", typ)
    .withExpiresAt(Date(System.currentTimeMillis() + expiryHours * 60L * 60L * 1000L))
    .sign(Algorithm.HMAC256(config.jwtSecret))
