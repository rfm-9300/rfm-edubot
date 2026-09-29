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
)

enum class DashboardUserRole { TENANT_ADMIN, TENANT_MEMBER }

enum class DashboardUserStatus { ACTIVE, DISABLED }
