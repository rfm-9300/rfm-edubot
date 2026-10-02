package com.rfm.edubot.dashboard.model

import kotlinx.datetime.Instant
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

data class DashboardUser(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val email: String,
    /** Null once the user chose to sign in with Google only. */
    val passwordHash: String?,
    val role: DashboardUserRole = DashboardUserRole.TENANT_ADMIN,
    val status: DashboardUserStatus = DashboardUserStatus.ACTIVE,
    val createdAt: Instant,
    val lastLoginAt: Instant? = null,
    /** Firebase uid of the linked Google account (stable even if its email changes); unique across users. */
    val googleUid: String? = null,
    val googleEmail: String? = null,
    /** An employee's sign-in ([DashboardUserRole.TENANT_EMPLOYEE]): the employee record it signs in as, unique across users. */
    val employeeId: ObjectId? = null,
    /** The company that employee record is in. [tenantId] stays the first company, like every user's. */
    val employeeTenantId: ObjectId? = null,
) {
    val isEmployee: Boolean get() = role == DashboardUserRole.TENANT_EMPLOYEE

    /** The company a sign-in opens: the first company, or an employee's own company. */
    val homeTenantId: ObjectId get() = employeeTenantId ?: tenantId
}

/** [TENANT_EMPLOYEE] is a team member's own sign-in from their employee record: it only registers their services. */
enum class DashboardUserRole { TENANT_ADMIN, TENANT_MEMBER, TENANT_EMPLOYEE }

enum class DashboardUserStatus { ACTIVE, DISABLED }
