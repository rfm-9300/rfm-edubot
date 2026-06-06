package com.rfm.edubot.tenant

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.util.Date

class TenantRepository(mongoModule: MongoModule) {
    private val collection = mongoModule.database.getCollection<Document>("tenants")

    suspend fun findByPhoneNumberId(phoneNumberId: String): Tenant? =
        collection.find(Filters.eq("phoneNumberId", phoneNumberId)).firstOrNull()?.toTenant()

    suspend fun findBySlug(slug: String): Tenant? =
        collection.find(Filters.eq("slug", slug)).firstOrNull()?.toTenant()

    suspend fun findById(id: ObjectId): Tenant? =
        collection.find(Filters.eq("_id", id)).firstOrNull()?.toTenant()

    suspend fun findAll(): List<Tenant> =
        collection.find().toList().map { it.toTenant() }

    suspend fun create(tenant: Tenant): Tenant {
        collection.insertOne(tenant.toDocument())
        return tenant
    }

    suspend fun update(slug: String, update: Bson): Tenant? =
        collection.findOneAndUpdate(
            Filters.eq("slug", slug),
            update,
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toTenant()

    suspend fun setStatus(slug: String, status: TenantStatus, updatedAt: Instant): Tenant? =
        update(slug, Updates.combine(Updates.set("status", status.name), Updates.set("updatedAt", updatedAt.toDate())))

    private fun Document.toTenant() = Tenant(
        id = getObjectId("_id"),
        slug = getString("slug"),
        name = getString("name"),
        phoneNumberId = getString("phoneNumberId"),
        agentType = getString("agentType") ?: "CRM_V1",
        openrouterModel = getString("openrouterModel"),
        rateLimitPerHour = getInteger("rateLimitPerHour") ?: 30,
        rateLimitPerDay = getInteger("rateLimitPerDay") ?: 200,
        status = TenantStatus.valueOf(getString("status") ?: TenantStatus.ACTIVE.name),
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
    )

    private fun Tenant.toDocument() = Document("_id", id)
        .append("slug", slug)
        .append("name", name)
        .append("phoneNumberId", phoneNumberId)
        .append("agentType", agentType)
        .append("openrouterModel", openrouterModel)
        .append("rateLimitPerHour", rateLimitPerHour)
        .append("rateLimitPerDay", rateLimitPerDay)
        .append("status", status.name)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())
}

private fun Document.getInstant(field: String): Instant = Instant.fromEpochMilliseconds(getDate(field).time)

private fun Instant.toDate(): Date = Date(toEpochMilliseconds())
