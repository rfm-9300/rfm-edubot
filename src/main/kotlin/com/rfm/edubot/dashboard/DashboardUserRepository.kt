package com.rfm.edubot.dashboard

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.dashboard.model.DashboardUserStatus
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.types.ObjectId
import java.util.Date

class DashboardUserRepository(mongoModule: MongoModule) {
    private val collection = mongoModule.database.getCollection<Document>("dashboard_users")

    suspend fun findByEmail(email: String): DashboardUser? =
        collection.find(Filters.eq("email", email.trim().lowercase())).firstOrNull()?.toDashboardUser()

    suspend fun findById(id: ObjectId): DashboardUser? =
        collection.find(Filters.eq("_id", id)).firstOrNull()?.toDashboardUser()

    suspend fun listByTenant(tenantId: ObjectId): List<DashboardUser> =
        collection.find(Filters.eq("tenantId", tenantId)).sort(Document("email", 1)).toList().map { it.toDashboardUser() }

    suspend fun create(user: DashboardUser): DashboardUser {
        collection.insertOne(user.toDocument())
        return user
    }

    suspend fun setStatus(id: ObjectId, tenantId: ObjectId, status: DashboardUserStatus): DashboardUser? =
        collection.findOneAndUpdate(
            Filters.and(Filters.eq("_id", id), Filters.eq("tenantId", tenantId)),
            Updates.set("status", status.name),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toDashboardUser()

    suspend fun markLogin(id: ObjectId, at: Instant) {
        collection.updateOne(Filters.eq("_id", id), Updates.set("lastLoginAt", at.toDate()))
    }

    private fun Document.toDashboardUser() = DashboardUser(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        email = getString("email"),
        passwordHash = getString("passwordHash"),
        role = DashboardUserRole.valueOf(getString("role") ?: DashboardUserRole.TENANT_ADMIN.name),
        status = DashboardUserStatus.valueOf(getString("status") ?: DashboardUserStatus.ACTIVE.name),
        createdAt = getInstant("createdAt"),
        lastLoginAt = getDate("lastLoginAt")?.let { Instant.fromEpochMilliseconds(it.time) },
    )

    private fun DashboardUser.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("email", email.trim().lowercase())
        .append("passwordHash", passwordHash)
        .append("role", role.name)
        .append("status", status.name)
        .append("createdAt", createdAt.toDate())
        .append("lastLoginAt", lastLoginAt?.toDate())
}

private fun Document.getInstant(field: String): Instant = Instant.fromEpochMilliseconds(getDate(field).time)

private fun Instant.toDate(): Date = Date(toEpochMilliseconds())
