package com.rfm.edubot.dashboard

import com.mongodb.ErrorCategory
import com.mongodb.MongoServerException
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

    suspend fun findByGoogleUid(uid: String): DashboardUser? =
        collection.find(Filters.eq("googleUid", uid)).firstOrNull()?.toDashboardUser()

    /** The sign-in of an employee record, if they have one. */
    suspend fun findByEmployee(employeeId: ObjectId): DashboardUser? =
        collection.find(Filters.eq("employeeId", employeeId)).firstOrNull()?.toDashboardUser()

    /** Throws a duplicate-key error when another user has [email]. */
    suspend fun setEmail(id: ObjectId, email: String): DashboardUser? =
        collection.findOneAndUpdate(
            Filters.eq("_id", id),
            Updates.set("email", email.trim().lowercase()),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toDashboardUser()

    suspend fun deleteByEmployee(employeeId: ObjectId): Boolean =
        collection.deleteOne(Filters.eq("employeeId", employeeId)).deletedCount > 0

    enum class LinkResult { LINKED, TAKEN, NOT_FOUND }

    /** Attaches a Google account to [id]. A Google account belongs to one user at most (unique index). */
    suspend fun linkGoogle(id: ObjectId, uid: String, email: String): LinkResult {
        findByGoogleUid(uid)?.let { if (it.id != id) return LinkResult.TAKEN }
        return try {
            val updated = collection.updateOne(
                Filters.eq("_id", id),
                Updates.combine(Updates.set("googleUid", uid), Updates.set("googleEmail", email.trim().lowercase())),
            )
            if (updated.matchedCount == 0L) LinkResult.NOT_FOUND else LinkResult.LINKED
        } catch (e: MongoServerException) {
            if (ErrorCategory.fromErrorCode(e.code) != ErrorCategory.DUPLICATE_KEY) throw e
            LinkResult.TAKEN
        }
    }

    suspend fun unlinkGoogle(id: ObjectId): DashboardUser? =
        collection.findOneAndUpdate(
            Filters.eq("_id", id),
            Updates.combine(Updates.unset("googleUid"), Updates.unset("googleEmail")),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toDashboardUser()

    /** A null [hash] turns password sign-in off for the user. */
    suspend fun setPasswordHash(id: ObjectId, hash: String?): DashboardUser? =
        collection.findOneAndUpdate(
            Filters.eq("_id", id),
            if (hash == null) Updates.unset("passwordHash") else Updates.set("passwordHash", hash),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toDashboardUser()

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
        googleUid = getString("googleUid"),
        googleEmail = getString("googleEmail"),
        employeeId = get("employeeId", ObjectId::class.java),
        employeeTenantId = get("employeeTenantId", ObjectId::class.java),
    )

    private fun DashboardUser.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("email", email.trim().lowercase())
        .append("role", role.name)
        .append("status", status.name)
        .append("createdAt", createdAt.toDate())
        .append("lastLoginAt", lastLoginAt?.toDate())
        .apply {
            passwordHash?.let { append("passwordHash", it) }
            googleUid?.let { append("googleUid", it) }
            googleEmail?.let { append("googleEmail", it) }
            employeeId?.let { append("employeeId", it) }
            employeeTenantId?.let { append("employeeTenantId", it) }
        }
}

private fun Document.getInstant(field: String): Instant = Instant.fromEpochMilliseconds(getDate(field).time)

private fun Instant.toDate(): Date = Date(toEpochMilliseconds())
