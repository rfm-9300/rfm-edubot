package com.rfm.edubot.persona

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.types.ObjectId
import java.util.Date

class PersonaRepository(mongo: MongoModule) {
    private val collection = mongo.database.getCollection<Document>("tenant_persona")
    private val sources = mongo.database.getCollection<Document>("persona_sources")

    suspend fun findByTenant(tenantId: ObjectId): TenantPersona? =
        collection.find(Filters.eq("tenantId", tenantId)).firstOrNull()?.toPersona()

    /** Direct write of the compiled file (manual edit or compiler output). Bumps version. */
    suspend fun upsertCompiled(tenantId: ObjectId, instructions: String): TenantPersona {
        val now = SystemClock.now()
        val status = if (instructions.isBlank()) PersonaStatus.EMPTY else PersonaStatus.READY
        return collection.findOneAndUpdate(
            Filters.eq("tenantId", tenantId),
            Updates.combine(
                Updates.setOnInsert("_id", ObjectId()),
                Updates.set("compiledInstructions", instructions),
                Updates.inc("version", 1),
                Updates.set("tokenEstimate", estimateTokens(instructions)),
                Updates.set("status", status.name),
                Updates.set("updatedAt", now.toDate()),
            ),
            FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER),
        )!!.toPersona()
    }

    /** Flip status without touching the compiled file — used while a compile is in flight or on failure. */
    suspend fun setStatus(tenantId: ObjectId, status: PersonaStatus): TenantPersona =
        collection.findOneAndUpdate(
            Filters.eq("tenantId", tenantId),
            Updates.combine(
                Updates.setOnInsert("_id", ObjectId()),
                Updates.set("status", status.name),
                Updates.set("updatedAt", SystemClock.now().toDate()),
            ),
            FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER),
        )!!.toPersona()

    // --- Sources (the gradual sync feed) ---

    suspend fun addSource(tenantId: ObjectId, kind: SourceKind, content: String, label: String): PersonaSource {
        val source = PersonaSource(tenantId = tenantId, kind = kind, content = content, label = label, createdAt = SystemClock.now())
        sources.insertOne(source.toDocument())
        return source
    }

    suspend fun listSources(tenantId: ObjectId): List<PersonaSource> =
        sources.find(Filters.eq("tenantId", tenantId)).sort(Sorts.descending("createdAt")).map { it.toSource() }.toList()

    suspend fun pendingSources(tenantId: ObjectId): List<PersonaSource> =
        sources.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("compiledIntoVersion", null)))
            .sort(Sorts.ascending("createdAt")).map { it.toSource() }.toList()

    suspend fun allSources(tenantId: ObjectId): List<PersonaSource> =
        sources.find(Filters.eq("tenantId", tenantId)).sort(Sorts.ascending("createdAt")).map { it.toSource() }.toList()

    suspend fun deleteSource(tenantId: ObjectId, sourceId: ObjectId): Boolean =
        sources.deleteOne(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", sourceId))).deletedCount > 0

    /** Stamp the given sources as folded into [version] so they are not re-synthesised next time. */
    suspend fun markSourcesCompiled(tenantId: ObjectId, sourceIds: List<ObjectId>, version: Int) {
        if (sourceIds.isEmpty()) return
        sources.updateMany(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`("_id", sourceIds)),
            Updates.set("compiledIntoVersion", version),
        )
    }
}

private fun estimateTokens(text: String): Int = if (text.isBlank()) 0 else (text.length + 3) / 4

private fun Document.toPersona() = TenantPersona(
    id = getObjectId("_id"),
    tenantId = getObjectId("tenantId"),
    compiledInstructions = getString("compiledInstructions").orEmpty(),
    version = getInteger("version") ?: 0,
    tokenEstimate = getInteger("tokenEstimate") ?: 0,
    status = PersonaStatus.valueOf(getString("status") ?: PersonaStatus.EMPTY.name),
    updatedAt = getDate("updatedAt")?.toKxInstant() ?: SystemClock.now(),
)

private fun PersonaSource.toDocument() = Document("_id", id)
    .append("tenantId", tenantId)
    .append("kind", kind.name)
    .append("content", content)
    .append("label", label)
    .append("compiledIntoVersion", compiledIntoVersion)
    .append("createdAt", createdAt.toDate())

private fun Document.toSource() = PersonaSource(
    id = getObjectId("_id"),
    tenantId = getObjectId("tenantId"),
    kind = SourceKind.valueOf(getString("kind") ?: SourceKind.TEXT_NOTE.name),
    content = getString("content").orEmpty(),
    label = getString("label").orEmpty(),
    compiledIntoVersion = getInteger("compiledIntoVersion"),
    createdAt = getDate("createdAt")?.toKxInstant() ?: SystemClock.now(),
)

private fun Instant.toDate(): Date = Date(toEpochMilliseconds())
private fun Date.toKxInstant(): Instant = Instant.fromEpochMilliseconds(toInstant().toEpochMilli())
