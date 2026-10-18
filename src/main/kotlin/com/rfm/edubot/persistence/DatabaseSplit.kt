package com.rfm.edubot.persistence

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.Instant
import org.bson.Document
import org.slf4j.LoggerFactory
import java.util.Date

/**
 * Until October 2026 one database held everything. The platform's own collections and the runtime
 * state now live in [MongoModule.platform] and [MongoModule.runtime], and this carries them over.
 *
 * The first start with three databases copies each moved collection that has documents into its new
 * database, unless that already has some. The originals stay in the data database, because a release
 * from before the split (the one a failed deploy rolls back to) reads and writes only them. So every
 * start compares the platform originals with the hashes recorded at the last copy and copies again
 * any that changed, which carries over what such a release wrote. An emptied or dropped original is
 * never copied. Runtime state is copied once only: it is disposable.
 */
internal class DatabaseSplit(private val mongo: MongoModule, private val clock: () -> Instant = SystemClock::now) {
    private val log = LoggerFactory.getLogger("DatabaseSplit")
    private val markers = mongo.platform.getCollection<Document>(MARKERS)

    suspend fun run() {
        val marker = markers.find(Filters.eq("_id", MARKER_ID)).firstOrNull()
        val hashes = hashes(MongoModule.PLATFORM_COLLECTIONS)
        if (marker == null) {
            MongoModule.PLATFORM_COLLECTIONS.forEach { copyIfNew(it, mongo.platform) }
            MongoModule.RUNTIME_COLLECTIONS.forEach { copyIfNew(it, mongo.runtime) }
        } else {
            val recorded = marker.get("sourceHashes", Document::class.java) ?: Document()
            if (hashes == recorded.mapValues { it.value.toString() }) return
            for ((name, hash) in hashes) {
                if (hash == recorded.getString(name) || !hasDocuments(mongo.database, name)) continue
                log.warn(
                    "{}.{} changed after it was copied to {}: a release from before the split wrote to it. Copying it again.",
                    mongo.database.name, name, mongo.platform.name,
                )
                copy(name, mongo.platform)
            }
        }
        val now = Date(clock().toEpochMilliseconds())
        markers.replaceOne(
            Filters.eq("_id", MARKER_ID),
            Document("_id", MARKER_ID)
                .append("splitAt", marker?.getDate("splitAt") ?: now)
                .append("updatedAt", now)
                .append("sourceHashes", Document(hashes)),
            ReplaceOptions().upsert(true),
        )
    }

    private suspend fun copyIfNew(name: String, target: MongoDatabase) {
        if (!hasDocuments(mongo.database, name)) return
        if (hasDocuments(target, name)) {
            log.warn("{}.{} already has documents, so {}.{} was not copied over it", target.name, name, mongo.database.name, name)
            return
        }
        copy(name, target)
    }

    /** Replaces the target collection with the original in one step, keeping the target's indexes. */
    private suspend fun copy(name: String, target: MongoDatabase) {
        mongo.database.getCollection<Document>(name).aggregate(listOf(Aggregates.out(target.name, name))).toCollection()
        val count = target.getCollection<Document>(name).countDocuments()
        log.info("Copied {}.{} to {}.{} ({} documents)", mongo.database.name, name, target.name, name, count)
    }

    private suspend fun hasDocuments(db: MongoDatabase, name: String): Boolean =
        db.getCollection<Document>(name).find().limit(1).firstOrNull() != null

    /** The data database's MD5 of each named collection that exists there; content only, not indexes. */
    private suspend fun hashes(names: List<String>): Map<String, String> {
        val result = mongo.database.runCommand(Document("dbHash", 1).append("collections", names))
        val collections = result.get("collections", Document::class.java) ?: return emptyMap()
        return collections.mapValues { it.value.toString() }
    }

    companion object {
        const val MARKERS = "migrations"
        const val MARKER_ID = "database-split"
    }
}
