package com.rfm.edubot.instagram

import com.rfm.edubot.instagram.model.InstagramComment
import com.rfm.edubot.instagram.model.InstagramMedia
import kotlinx.serialization.Serializable

@Serializable
data class InstagramSummaryDto(
    val connected: Boolean,
    val commentsEnabled: Boolean,
    val needsReconnect: Boolean,
    val username: String? = null,
    val unrepliedCount: Int,
    val comments: List<InstagramCommentDto>,
    val media: List<InstagramMediaDto>,
)

@Serializable
data class InstagramMediaDto(
    val id: String,
    val caption: String? = null,
    val mediaType: String? = null,
    val thumbnailUrl: String? = null,
    val permalink: String? = null,
    val publishedAt: String? = null,
    val commentsCount: Int? = null,
    val unrepliedCount: Int = 0,
)

@Serializable
data class InstagramCommentDto(
    val id: String,
    val mediaId: String,
    val text: String,
    val fromUsername: String? = null,
    val fromId: String? = null,
    val fromAccount: Boolean = false,
    val needsReply: Boolean = false,
    val createdAt: String,
    val caption: String? = null,
    val permalink: String? = null,
    val thumbnailUrl: String? = null,
)

@Serializable
data class InstagramMediaDetailDto(
    val media: InstagramMediaDto,
    val comments: List<InstagramCommentDto>,
)

@Serializable
data class InstagramReplyRequest(
    val message: String,
)

fun InstagramMedia.dto(unrepliedCount: Int = 0) = InstagramMediaDto(
    id = mediaId,
    caption = caption,
    mediaType = mediaType,
    thumbnailUrl = thumbnailUrl ?: mediaUrl,
    permalink = permalink,
    publishedAt = publishedAt?.toString(),
    commentsCount = commentsCount,
    unrepliedCount = unrepliedCount,
)

fun InstagramComment.dto(
    caption: String? = null,
    permalink: String? = null,
    thumbnailUrl: String? = null,
) = InstagramCommentDto(
    id = commentId,
    mediaId = mediaId,
    text = text,
    fromUsername = fromUsername,
    fromId = fromId,
    fromAccount = fromAccount,
    needsReply = needsReply,
    createdAt = createdAt.toString(),
    caption = caption,
    permalink = permalink,
    thumbnailUrl = thumbnailUrl,
)
