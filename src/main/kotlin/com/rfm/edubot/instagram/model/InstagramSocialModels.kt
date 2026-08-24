package com.rfm.edubot.instagram.model

import kotlinx.datetime.Instant
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

data class InstagramMedia(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val mediaId: String,
    val caption: String? = null,
    val mediaType: String? = null,
    val mediaUrl: String? = null,
    val thumbnailUrl: String? = null,
    val permalink: String? = null,
    val publishedAt: Instant? = null,
    val commentsCount: Int? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class InstagramComment(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val commentId: String,
    val mediaId: String,
    val text: String,
    val fromId: String? = null,
    val fromUsername: String? = null,
    val fromAccount: Boolean = false,
    val parentCommentId: String? = null,
    val replyId: String? = null,
    val repliedAt: Instant? = null,
    val hidden: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val needsReply: Boolean get() = !fromAccount && repliedAt == null && parentCommentId == null && !hidden
}
