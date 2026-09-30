package com.rfm.edubot.agents.store

import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.rfm.edubot.agents.model.AgentSettings
import com.rfm.edubot.agents.model.CompanyAgentSettings
import com.rfm.edubot.agents.model.PlatformAgentLimits
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.types.ObjectId

/**
 * Per-company agent settings (`agent_settings`, one document per company keyed by its tenant id):
 * the defaults the company edits, and the limits only the backoffice sets.
 */
class AgentSettingsRepository(mongo: MongoModule, private val clock: () -> Instant = SystemClock::now) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    suspend fun get(tenantId: ObjectId): AgentSettings {
        val doc = collection.find(Filters.eq("_id", tenantId)).firstOrNull() ?: return AgentSettings(tenantId)
        return AgentSettings(
            tenantId = tenantId,
            company = AgentJson.fromDocument(CompanyAgentSettings.serializer(), doc.get("company", Document::class.java), CompanyAgentSettings()),
            platform = AgentJson.fromDocument(PlatformAgentLimits.serializer(), doc.get("platform", Document::class.java), PlatformAgentLimits()),
            updatedAt = doc.instant("updatedAt"),
        )
    }

    suspend fun saveCompany(tenantId: ObjectId, company: CompanyAgentSettings): AgentSettings {
        upsert(tenantId, "company", AgentJson.toDocument(CompanyAgentSettings.serializer(), company))
        return get(tenantId)
    }

    suspend fun savePlatform(tenantId: ObjectId, platform: PlatformAgentLimits): AgentSettings {
        upsert(tenantId, "platform", AgentJson.toDocument(PlatformAgentLimits.serializer(), platform))
        return get(tenantId)
    }

    private suspend fun upsert(tenantId: ObjectId, field: String, value: Document) {
        collection.updateOne(
            Filters.eq("_id", tenantId),
            Updates.combine(Updates.set(field, value), Updates.set("updatedAt", clock().toDate())),
            UpdateOptions().upsert(true),
        )
    }

    companion object {
        const val COLLECTION = "agent_settings"
    }
}
