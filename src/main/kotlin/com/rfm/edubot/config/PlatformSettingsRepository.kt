package com.rfm.edubot.config

import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.Instant
import org.bson.Document
import java.util.Date

data class PlatformSettingsDocument(
    val overrides: Map<String, String>,
    val updatedAt: Instant?,
)

class PlatformSettingsRepository(mongoModule: MongoModule) {
    private val collection = mongoModule.database.getCollection<Document>("platform_settings")

    suspend fun load(): PlatformSettingsDocument {
        val doc = collection.find(Filters.eq("_id", DOC_ID)).firstOrNull() ?: return PlatformSettingsDocument(emptyMap(), null)
        val raw = doc.get("overrides")
        val overrides = when (raw) {
            is Document -> raw.mapValues { it.value?.toString().orEmpty() }.filterValues { it.isNotBlank() }
            else -> emptyMap()
        }
        val updatedAt = doc.getDate("updatedAt")?.let { Instant.fromEpochMilliseconds(it.time) }
        return PlatformSettingsDocument(overrides, updatedAt)
    }

    suspend fun save(overrides: Map<String, String>): PlatformSettingsDocument {
        val now = SystemClock.now()
        val sanitized = overrides
            .filterKeys { PlatformSettingKey.fromKey(it) != null }
            .mapValues { it.value.trim() }
            .filterValues { it.isNotBlank() }
        val overridesDoc = Document()
        sanitized.forEach { (key, value) -> overridesDoc[key] = value }
        val doc = Document("_id", DOC_ID)
            .append("overrides", overridesDoc)
            .append("updatedAt", Date(now.toEpochMilliseconds()))
        collection.replaceOne(Filters.eq("_id", DOC_ID), doc, ReplaceOptions().upsert(true))
        return PlatformSettingsDocument(sanitized, now)
    }

    companion object {
        const val DOC_ID = "global"
    }
}
