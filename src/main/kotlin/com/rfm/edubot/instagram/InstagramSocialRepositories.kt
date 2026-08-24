package com.rfm.edubot.instagram

import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.rfm.edubot.instagram.model.InstagramComment
import com.rfm.edubot.instagram.model.InstagramMedia
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.util.Date

class InstagramMediaRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("instagram.media")

    suspend fun findByMediaId(mediaId: String): InstagramMedia? =
        collection.find(scoped(Filters.eq("mediaId", mediaId))).firstOrNull()?.toMedia()

    suspend fun listRecent(limit: Int = 40): List<InstagramMedia> =
        collection.find(Filters.eq("tenantId", tenantId))
            .sort(Document("publishedAt", -1).append("updatedAt", -1))
            .limit(limit)
            .toList()
            .map { it.toMedia() }

    suspend fun upsert(media: InstagramMedia): InstagramMedia {
        val now = SystemClock.now()
        collection.updateOne(
            scoped(Filters.eq("mediaId", media.mediaId)),
            Updates.combine(
                Updates.setOnInsert("_id", media.id),
                Updates.setOnInsert("tenantId", tenantId),
                Updates.setOnInsert("mediaId", media.mediaId),
                Updates.setOnInsert("createdAt", media.createdAt.toDate()),
                Updates.set("updatedAt", now.toDate()),
                Updates.set("caption", media.caption),
                Updates.set("mediaType", media.mediaType),
                Updates.set("mediaUrl", media.mediaUrl),
                Updates.set("thumbnailUrl", media.thumbnailUrl),
                Updates.set("permalink", media.permalink),
                Updates.set("publishedAt", media.publishedAt?.toDate()),
                Updates.set("commentsCount", media.commentsCount),
            ),
            UpdateOptions().upsert(true),
        )
        return findByMediaId(media.mediaId) ?: media.copy(updatedAt = now)
    }

    suspend fun upsertStub(mediaId: String): InstagramMedia {
        val existing = findByMediaId(mediaId)
        if (existing != null) return existing
        val now = SystemClock.now()
        return upsert(
            InstagramMedia(
                tenantId = tenantId,
                mediaId = mediaId,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}

class InstagramCommentRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("instagram.comments")

    suspend fun findByCommentId(commentId: String): InstagramComment? =
        collection.find(scoped(Filters.eq("commentId", commentId))).firstOrNull()?.toComment()

    suspend fun listByMedia(mediaId: String): List<InstagramComment> =
        collection.find(scoped(Filters.eq("mediaId", mediaId)))
            .sort(Document("createdAt", 1))
            .toList()
            .map { it.toComment() }

    suspend fun listRecent(limit: Int = 80): List<InstagramComment> =
        collection.find(Filters.eq("tenantId", tenantId))
            .sort(Document("createdAt", -1))
            .limit(limit)
            .toList()
            .map { it.toComment() }

    suspend fun listUnreplied(limit: Int = 50): List<InstagramComment> =
        collection.find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("fromAccount", false),
                Filters.eq("hidden", false),
                Filters.eq("parentCommentId", null),
                Filters.eq("repliedAt", null),
            ),
        )
            .sort(Document("createdAt", -1))
            .limit(limit)
            .toList()
            .map { it.toComment() }

    suspend fun countUnreplied(): Int =
        collection.countDocuments(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("fromAccount", false),
                Filters.eq("hidden", false),
                Filters.eq("parentCommentId", null),
                Filters.eq("repliedAt", null),
            ),
        ).toInt()

    suspend fun countUnrepliedByMedia(mediaIds: Collection<String>): Map<String, Int> {
        if (mediaIds.isEmpty()) return emptyMap()
        return collection.find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.`in`("mediaId", mediaIds),
                Filters.eq("fromAccount", false),
                Filters.eq("hidden", false),
                Filters.eq("parentCommentId", null),
                Filters.eq("repliedAt", null),
            ),
        ).toList().groupingBy { it.getString("mediaId") }.eachCount()
    }

    suspend fun upsert(comment: InstagramComment): InstagramComment {
        val now = SystemClock.now()
        collection.updateOne(
            scoped(Filters.eq("commentId", comment.commentId)),
            Updates.combine(
                Updates.setOnInsert("_id", comment.id),
                Updates.setOnInsert("tenantId", tenantId),
                Updates.setOnInsert("commentId", comment.commentId),
                Updates.setOnInsert("createdAt", comment.createdAt.toDate()),
                Updates.set("updatedAt", now.toDate()),
                Updates.set("mediaId", comment.mediaId),
                Updates.set("text", comment.text),
                Updates.set("fromId", comment.fromId),
                Updates.set("fromUsername", comment.fromUsername),
                Updates.set("fromAccount", comment.fromAccount),
                Updates.set("parentCommentId", comment.parentCommentId),
                Updates.set("hidden", comment.hidden),
            ),
            UpdateOptions().upsert(true),
        )
        return findByCommentId(comment.commentId) ?: comment.copy(updatedAt = now)
    }

    suspend fun markReplied(commentId: String, replyId: String?): InstagramComment? {
        val now = SystemClock.now()
        collection.updateOne(
            scoped(Filters.eq("commentId", commentId)),
            Updates.combine(
                Updates.set("repliedAt", now.toDate()),
                Updates.set("replyId", replyId),
                Updates.set("updatedAt", now.toDate()),
            ),
        )
        return findByCommentId(commentId)
    }

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}

private fun Document.toMedia() = InstagramMedia(
    id = getObjectId("_id"),
    tenantId = getObjectId("tenantId"),
    mediaId = getString("mediaId") ?: "",
    caption = getString("caption"),
    mediaType = getString("mediaType"),
    mediaUrl = getString("mediaUrl"),
    thumbnailUrl = getString("thumbnailUrl"),
    permalink = getString("permalink"),
    publishedAt = getInstantOrNull("publishedAt"),
    commentsCount = getInteger("commentsCount"),
    createdAt = getInstant("createdAt"),
    updatedAt = getInstant("updatedAt"),
)

private fun Document.toComment() = InstagramComment(
    id = getObjectId("_id"),
    tenantId = getObjectId("tenantId"),
    commentId = getString("commentId") ?: "",
    mediaId = getString("mediaId") ?: "",
    text = getString("text") ?: "",
    fromId = getString("fromId"),
    fromUsername = getString("fromUsername"),
    fromAccount = getBoolean("fromAccount") ?: false,
    parentCommentId = getString("parentCommentId"),
    replyId = getString("replyId"),
    repliedAt = getInstantOrNull("repliedAt"),
    hidden = getBoolean("hidden") ?: false,
    createdAt = getInstant("createdAt"),
    updatedAt = getInstant("updatedAt"),
)

private fun Document.getInstant(field: String): Instant = Instant.fromEpochMilliseconds(getDate(field).time)

private fun Document.getInstantOrNull(field: String): Instant? =
    getDate(field)?.let { Instant.fromEpochMilliseconds(it.time) }

private fun Instant.toDate(): Date = Date(toEpochMilliseconds())
