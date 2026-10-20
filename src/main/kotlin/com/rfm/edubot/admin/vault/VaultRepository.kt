package com.rfm.edubot.admin.vault

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.types.ObjectId
import java.util.Date

/** How the browser turns the master password into the key that unwraps the vault key. */
data class VaultKdf(val name: String, val hash: String, val iterations: Int, val salt: String)

/** What AES-GCM sealed in the browser: a 12-byte [iv] and the ciphertext with its tag, both base64. */
data class Sealed(val iv: String, val data: String)

data class VaultMeta(
    val kdf: VaultKdf,
    /** The random vault key, sealed with the key the master password derives. */
    val wrappedKey: Sealed,
    /** Base64 SHA-256 of the token the browser derives from the vault key: it proves the caller unlocked the vault. */
    val authHash: String,
    val createdAt: Instant,
    val createdBy: String?,
    val updatedAt: Instant,
    val updatedBy: String?,
)

/** One saved login. Everything about it but who wrote it and when is inside [sealed]. */
data class VaultEntry(
    val id: ObjectId = ObjectId(),
    val sealed: Sealed,
    val createdAt: Instant,
    val createdBy: String?,
    val updatedAt: Instant,
    val updatedBy: String?,
)

/** The team's password vault, in the platform database: one meta document and the sealed entries. */
class VaultRepository(mongo: MongoModule) {
    private val meta = mongo.platform.getCollection<Document>(META)
    private val entries = mongo.platform.getCollection<Document>(ENTRIES)

    suspend fun meta(): VaultMeta? = meta.find(Filters.eq("_id", TEAM)).firstOrNull()?.toMeta()

    /** False when the vault already exists. */
    suspend fun create(vault: VaultMeta): Boolean = try {
        meta.insertOne(
            Document("_id", TEAM)
                .append("kdf", vault.kdf.toDocument())
                .append("wrappedKey", vault.wrappedKey.toDocument())
                .append("authHash", vault.authHash)
                .append("createdAt", vault.createdAt.date())
                .append("createdBy", vault.createdBy)
                .append("updatedAt", vault.updatedAt.date())
                .append("updatedBy", vault.updatedBy),
        )
        true
    } catch (e: MongoWriteException) {
        if (e.error.category != ErrorCategory.DUPLICATE_KEY) throw e
        false
    }

    suspend fun setKey(kdf: VaultKdf, wrappedKey: Sealed, now: Instant, by: String?): VaultMeta? =
        meta.findOneAndUpdate(
            Filters.eq("_id", TEAM),
            Updates.combine(
                Updates.set("kdf", kdf.toDocument()),
                Updates.set("wrappedKey", wrappedKey.toDocument()),
                Updates.set("updatedAt", now.date()),
                Updates.set("updatedBy", by),
            ),
            AFTER,
        )?.toMeta()

    suspend fun entries(): List<VaultEntry> = entries.find().sort(Sorts.ascending("createdAt")).toList().map { it.toEntry() }

    suspend fun count(): Long = entries.countDocuments()

    suspend fun findEntry(id: ObjectId): VaultEntry? = entries.find(Filters.eq("_id", id)).firstOrNull()?.toEntry()

    suspend fun insert(entry: VaultEntry) {
        entries.insertOne(
            Document("_id", entry.id)
                .append("sealed", entry.sealed.toDocument())
                .append("createdAt", entry.createdAt.date())
                .append("createdBy", entry.createdBy)
                .append("updatedAt", entry.updatedAt.date())
                .append("updatedBy", entry.updatedBy),
        )
    }

    /** Null when the entry is gone or, with [expectedUpdatedAt], someone saved it since. */
    suspend fun update(id: ObjectId, sealed: Sealed, expectedUpdatedAt: Instant?, now: Instant, by: String?): VaultEntry? {
        val filter = listOfNotNull(Filters.eq("_id", id), expectedUpdatedAt?.let { Filters.eq("updatedAt", it.date()) })
        return entries.findOneAndUpdate(
            Filters.and(filter),
            Updates.combine(Updates.set("sealed", sealed.toDocument()), Updates.set("updatedAt", now.date()), Updates.set("updatedBy", by)),
            AFTER,
        )?.toEntry()
    }

    suspend fun delete(id: ObjectId): Boolean = entries.deleteOne(Filters.eq("_id", id)).deletedCount > 0

    private fun Instant.date() = Date(toEpochMilliseconds())

    private fun Date.instant() = Instant.fromEpochMilliseconds(time)

    private fun VaultKdf.toDocument() = Document("name", name).append("hash", hash).append("iterations", iterations).append("salt", salt)

    private fun Sealed.toDocument() = Document("iv", iv).append("data", data)

    private fun Document.toSealed() = Sealed(getString("iv"), getString("data"))

    private fun Document.toMeta(): VaultMeta {
        val kdf = get("kdf", Document::class.java)
        return VaultMeta(
            kdf = VaultKdf(kdf.getString("name"), kdf.getString("hash"), kdf.getInteger("iterations"), kdf.getString("salt")),
            wrappedKey = get("wrappedKey", Document::class.java).toSealed(),
            authHash = getString("authHash"),
            createdAt = getDate("createdAt").instant(),
            createdBy = getString("createdBy"),
            updatedAt = getDate("updatedAt").instant(),
            updatedBy = getString("updatedBy"),
        )
    }

    private fun Document.toEntry() = VaultEntry(
        id = getObjectId("_id"),
        sealed = get("sealed", Document::class.java).toSealed(),
        createdAt = getDate("createdAt").instant(),
        createdBy = getString("createdBy"),
        updatedAt = getDate("updatedAt").instant(),
        updatedBy = getString("updatedBy"),
    )

    companion object {
        const val META = "vault.meta"
        const val ENTRIES = "vault.entries"
        private const val TEAM = "team"
        private val AFTER = FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
    }
}
