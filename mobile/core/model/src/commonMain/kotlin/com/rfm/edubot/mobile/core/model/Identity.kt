package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Session(val token: String)

/** `GET /app/api/me`. [user] is null while an operator is impersonating the tenant. */
@Serializable
data class DashboardIdentity(
    val tenant: Tenant,
    val user: DashboardUser? = null,
    val modules: List<String> = emptyList(),
    val principalType: String = PRINCIPAL_TENANT,
    val companies: List<Company> = emptyList(),
    val companyLimit: Int = 1,
    /** Set for an employee's own sign-in, whose [modules] are only their own pages. */
    val employee: EmployeeIdentity? = null,
) {
    val isOperator: Boolean get() = principalType != PRINCIPAL_TENANT

    val isAdmin: Boolean get() = isOperator || user?.role == ROLE_ADMIN

    /** Everything else answers 401 to this session, so the app must not call it. */
    val isEmployee: Boolean get() = employee != null

    /** Whether the account can hold more than the company it is signed in to. */
    val canSwitchCompany: Boolean get() = companies.size > 1

    companion object {
        const val PRINCIPAL_TENANT = "tenant"
        const val ROLE_ADMIN = "TENANT_ADMIN"
    }
}

@Serializable
data class Tenant(
    val id: String,
    val slug: String,
    val name: String,
    val locale: String,
    /** IANA zone the backend renders its own dates in; the app formats instants with it too. */
    val timezone: String = "Europe/Lisbon",
    val channels: List<ChannelAsset> = emptyList(),
)

@Serializable
data class ChannelAsset(
    val platform: String,
    val externalId: String,
    val displayName: String? = null,
    val commentsEnabled: Boolean = false,
) {
    companion object {
        const val WHATSAPP = "WHATSAPP"
        const val INSTAGRAM = "INSTAGRAM"
        const val WEB = "WEB"
    }
}

/** The employee record an employee's sign-in clocks in and registers services as. */
@Serializable
data class EmployeeIdentity(val id: String, val number: String, val name: String)

@Serializable
data class DashboardUser(
    val id: String,
    val email: String,
    val role: String,
    val status: String,
)

@Serializable
data class Company(
    val id: String,
    val name: String,
    val slug: String,
    val primary: Boolean = false,
)

@Serializable
data class SwitchedCompany(val token: String)

/** `GET /app/api/account`. */
@Serializable
data class Account(
    val email: String,
    val role: String,
    val passwordEnabled: Boolean = false,
    val googleEmail: String? = null,
    val googleAvailable: Boolean = false,
)
