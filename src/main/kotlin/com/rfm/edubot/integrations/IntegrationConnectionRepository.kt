package com.rfm.edubot.integrations

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.rfm.edubot.agents.store.instant
import com.rfm.edubot.agents.store.toDate
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

class IntegrationConnectionRepository(mongo: MongoModule, private val clock: () -> Instant = SystemClock::now) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    /**
     * Connects [accountEmail], or reconnects it with fresh tokens and scopes while keeping its settings.
     * A company's first account becomes its default sender.
     */
    suspend fun connect(
        tenantId: ObjectId,
        provider: String,
        accountEmail: String,
        scopes: List<String>,
        accessToken: String,
        refreshToken: String,
        accessTokenExpiresAt: Instant,
        connectedByUserId: String?,
        connectedByEmail: String?,
    ): IntegrationConnection {
        val email = accountEmail.trim().lowercase()
        val now = clock().toDate()
        val key = Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("provider", provider), Filters.eq("accountEmail", email))
        val first = collection.countDocuments(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("provider", provider))) == 0L
        val update = Updates.combine(
            Updates.set("scopes", scopes),
            Updates.set("status", ConnectionStatus.ACTIVE.name),
            Updates.set("accessToken", accessToken),
            Updates.set("refreshToken", refreshToken),
            Updates.set("accessTokenExpiresAt", accessTokenExpiresAt.toDate()),
            Updates.set("connectedByUserId", connectedByUserId),
            Updates.set("connectedByEmail", connectedByEmail),
            Updates.unset("lastError"),
            Updates.set("updatedAt", now),
            Updates.setOnInsert("isDefault", first),
            Updates.setOnInsert("settings", Document()),
            Updates.setOnInsert("createdAt", now),
        )
        try {
            collection.updateOne(key, update, UpdateOptions().upsert(true))
        } catch (e: MongoWriteException) {
            // Two consents for the same account raced to insert it; the second one updates what the first wrote.
            if (e.error.category != ErrorCategory.DUPLICATE_KEY) throw e
            collection.updateOne(key, update)
        }
        return collection.find(key).firstOrNull()!!.toConnection()
    }

    suspend fun list(tenantId: ObjectId, provider: String? = null): List<IntegrationConnection> =
        collection.find(scope(tenantId, provider)).sort(Document("createdAt", 1)).toList().map { it.toConnection() }

    suspend fun find(tenantId: ObjectId, id: ObjectId): IntegrationConnection? =
        collection.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", id))).firstOrNull()?.toConnection()

    suspend fun findById(id: ObjectId): IntegrationConnection? =
        collection.find(Filters.eq("_id", id)).firstOrNull()?.toConnection()

    /** The account email goes out from when none is named: the one marked default, else the oldest. */
    suspend fun defaultFor(tenantId: ObjectId, provider: String): IntegrationConnection? {
        val all = list(tenantId, provider)
        return all.firstOrNull { it.isDefault } ?: all.firstOrNull()
    }

    /** Connections to [accountEmail] across every company: Google revokes a grant for all of them at once. */
    suspend fun countForAccount(provider: String, accountEmail: String): Long =
        collection.countDocuments(Filters.and(Filters.eq("provider", provider), Filters.eq("accountEmail", accountEmail.trim().lowercase())))

    suspend fun needingReconnect(tenantId: ObjectId): List<IntegrationConnection> =
        collection.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("status", ConnectionStatus.NEEDS_RECONNECT.name)))
            .sort(Document("createdAt", 1)).toList().map { it.toConnection() }

    suspend fun updateSettings(tenantId: ObjectId, id: ObjectId, settings: EmailSettings): IntegrationConnection? {
        collection.updateOne(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", id)),
            Updates.combine(Updates.set("settings", settings.toDocument()), Updates.set("updatedAt", clock().toDate())),
        )
        return find(tenantId, id)
    }

    suspend fun makeDefault(tenantId: ObjectId, id: ObjectId): IntegrationConnection? {
        val target = find(tenantId, id) ?: return null
        collection.updateMany(
            Filters.and(scope(tenantId, target.provider), Filters.ne("_id", id)),
            Updates.set("isDefault", false),
        )
        collection.updateOne(Filters.eq("_id", id), Updates.combine(Updates.set("isDefault", true), Updates.set("updatedAt", clock().toDate())))
        return find(tenantId, id)
    }

    /** Removes the account; when it was the default, the oldest remaining one takes over. */
    suspend fun delete(tenantId: ObjectId, id: ObjectId): IntegrationConnection? {
        val removed = collection.findOneAndDelete(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", id)))?.toConnection()
            ?: return null
        if (removed.isDefault) {
            list(tenantId, removed.provider).firstOrNull()?.let { next ->
                collection.updateOne(Filters.eq("_id", next.id), Updates.set("isDefault", true))
            }
        }
        return removed
    }

    suspend fun saveAccessToken(id: ObjectId, accessToken: String, expiresAt: Instant, refreshToken: String? = null) {
        val updates = mutableListOf(
            Updates.set("accessToken", accessToken),
            Updates.set("accessTokenExpiresAt", expiresAt.toDate()),
            Updates.set("updatedAt", clock().toDate()),
        )
        if (refreshToken != null) updates += Updates.set("refreshToken", refreshToken)
        collection.updateOne(Filters.eq("_id", id), Updates.combine(updates))
    }

    /** Gmail refused the stored access token before it was due to expire: the next use refreshes it. */
    suspend fun expireAccessToken(id: ObjectId) {
        collection.updateOne(Filters.eq("_id", id), Updates.set("accessTokenExpiresAt", Instant.fromEpochMilliseconds(0).toDate()))
    }

    /**
     * Takes one send from the account's allowance for [day]; false once [cap] were used. The counter
     * starts over on a new day; two first sends of a day race safely, the loser counts on the winner's.
     */
    suspend fun claimSend(id: ObjectId, day: String, cap: Int): Boolean {
        if (cap <= 0) return false
        suspend fun sameDay() = collection.updateOne(
            Filters.and(Filters.eq("_id", id), Filters.eq("dailySends.day", day), Filters.lt("dailySends.count", cap)),
            Updates.inc("dailySends.count", 1),
        ).modifiedCount > 0
        if (sameDay()) return true
        val started = collection.updateOne(
            Filters.and(Filters.eq("_id", id), Filters.ne("dailySends.day", day)),
            Updates.set("dailySends", Document("day", day).append("count", 1)),
        ).modifiedCount > 0
        return started || sameDay()
    }

    /** Gives back a send claimed with [claimSend] that didn't go out. */
    suspend fun releaseSend(id: ObjectId, day: String) {
        collection.updateOne(
            Filters.and(Filters.eq("_id", id), Filters.eq("dailySends.day", day), Filters.gt("dailySends.count", 0)),
            Updates.inc("dailySends.count", -1),
        )
    }

    /** False when the connection wasn't active any more, so whoever flips it first is the one who tells people. */
    suspend fun markNeedsReconnect(id: ObjectId, reason: String): Boolean =
        collection.updateOne(
            Filters.and(Filters.eq("_id", id), Filters.eq("status", ConnectionStatus.ACTIVE.name)),
            Updates.combine(
                Updates.set("status", ConnectionStatus.NEEDS_RECONNECT.name),
                Updates.set("lastError", reason.take(200)),
                Updates.unset("accessToken"),
                Updates.unset("accessTokenExpiresAt"),
                Updates.set("updatedAt", clock().toDate()),
            ),
        ).modifiedCount > 0

    private fun scope(tenantId: ObjectId, provider: String?): Bson =
        if (provider == null) Filters.eq("tenantId", tenantId) else Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("provider", provider))

    private fun EmailSettings.toDocument() = Document()
        .append("senderName", senderName)
        .append("replyTo", replyTo)
        .append("signature", signature)

    private fun Document.toConnection(): IntegrationConnection {
        val settings = get("settings", Document::class.java)
        val sends = get("dailySends", Document::class.java)
        return IntegrationConnection(
            id = getObjectId("_id"),
            tenantId = getObjectId("tenantId"),
            provider = getString("provider").orEmpty(),
            accountEmail = getString("accountEmail").orEmpty(),
            scopes = getList("scopes", String::class.java).orEmpty(),
            status = runCatching { ConnectionStatus.valueOf(getString("status")) }.getOrDefault(ConnectionStatus.NEEDS_RECONNECT),
            accessToken = getString("accessToken"),
            refreshToken = getString("refreshToken"),
            accessTokenExpiresAt = instant("accessTokenExpiresAt"),
            connectedByUserId = getString("connectedByUserId"),
            connectedByEmail = getString("connectedByEmail"),
            settings = EmailSettings(
                senderName = settings?.getString("senderName"),
                replyTo = settings?.getString("replyTo"),
                signature = settings?.getString("signature"),
            ),
            isDefault = getBoolean("isDefault", false),
            lastError = getString("lastError"),
            dailySends = sends?.getString("day")?.let { DailySends(it, sends.getInteger("count") ?: 0) },
            createdAt = instant("createdAt") ?: Instant.fromEpochMilliseconds(0),
            updatedAt = instant("updatedAt") ?: Instant.fromEpochMilliseconds(0),
        )
    }

    companion object {
        const val COLLECTION = "integration_connections"
    }
}
