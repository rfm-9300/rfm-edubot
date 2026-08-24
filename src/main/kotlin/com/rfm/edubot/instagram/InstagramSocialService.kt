package com.rfm.edubot.instagram

import com.rfm.edubot.instagram.model.InstagramComment
import com.rfm.edubot.instagram.model.InstagramMedia
import com.rfm.edubot.oauth.InstagramOAuthScopes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.webhook.dto.InstagramCommentValue
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory

class InstagramSocialService(
    private val mongo: MongoModule,
    private val httpClient: HttpClient,
    private val graphVersion: () -> String = { "v21.0" },
) {
    private val log = LoggerFactory.getLogger("InstagramSocialService")

    suspend fun ingestComment(tenant: Tenant, accountId: String, comment: InstagramCommentValue, receivedAt: Instant) {
        val commentId = comment.id.takeIf { it.isNotBlank() } ?: return
        val mediaId = comment.media?.id?.takeIf { it.isNotBlank() } ?: return
        val media = InstagramMediaRepository(mongo, tenant.id).upsertStub(mediaId)
        val fromId = comment.from?.id
        InstagramCommentRepository(mongo, tenant.id).upsert(
            InstagramComment(
                tenantId = tenant.id,
                commentId = commentId,
                mediaId = media.mediaId,
                text = comment.text.orEmpty(),
                fromId = fromId,
                fromUsername = comment.from?.username,
                fromAccount = fromId != null && fromId == accountId,
                createdAt = receivedAt,
                updatedAt = receivedAt,
            ),
        )
        log.info("Stored Instagram comment: tenant={} mediaId={} commentId={}", tenant.slug, mediaId, commentId)
    }

    suspend fun summary(tenant: Tenant, refresh: Boolean): InstagramSummaryDto {
        val binding = tenant.binding(Platform.INSTAGRAM)
        if (binding == null) {
            return InstagramSummaryDto(
                connected = false,
                commentsEnabled = false,
                needsReconnect = false,
                unrepliedCount = 0,
                comments = emptyList(),
                media = emptyList(),
            )
        }
        var graphDenied = false
        if (refresh && binding.accessToken.isNotBlank()) {
            when (val result = graph(binding).listMedia()) {
                is InstagramGraphResult.Ok -> persistMedia(tenant, result.value)
                InstagramGraphResult.PermissionDenied -> graphDenied = true
                is InstagramGraphResult.Failed -> log.warn("Instagram media refresh failed: {}", result.message)
            }
        }
        val mediaRepo = InstagramMediaRepository(mongo, tenant.id)
        val commentRepo = InstagramCommentRepository(mongo, tenant.id)
        val media = mediaRepo.listRecent()
        val unrepliedByMedia = commentRepo.countUnrepliedByMedia(media.map { it.mediaId })
        val mediaById = media.associateBy { it.mediaId }
        val comments = commentRepo.listUnreplied().ifEmpty { commentRepo.listRecent(40) }
        val commentsEnabled = InstagramOAuthScopes.hasComments(binding.grantedScopes)
        return InstagramSummaryDto(
            connected = true,
            commentsEnabled = commentsEnabled,
            needsReconnect = !commentsEnabled || graphDenied,
            username = binding.displayName,
            unrepliedCount = commentRepo.countUnreplied(),
            comments = comments.map { item ->
                val post = mediaById[item.mediaId]
                item.dto(post?.caption, post?.permalink, post?.thumbnailUrl ?: post?.mediaUrl)
            },
            media = media.map { it.dto(unrepliedByMedia[it.mediaId] ?: 0) },
        )
    }

    suspend fun mediaDetail(tenant: Tenant, mediaId: String, refresh: Boolean): Pair<HttpStatusCode, Any> {
        val binding = tenant.binding(Platform.INSTAGRAM) ?: return HttpStatusCode.NotFound to mapOf("error" to "instagram not connected")
        if (refresh && binding.accessToken.isNotBlank()) {
            when (val result = graph(binding).listComments(mediaId)) {
                is InstagramGraphResult.Ok -> persistComments(tenant, mediaId, binding.externalId, result.value)
                InstagramGraphResult.PermissionDenied -> { /* surfaced via summary reconnect */ }
                is InstagramGraphResult.Failed -> log.warn("Instagram comment refresh failed: {}", result.message)
            }
        }
        val media = InstagramMediaRepository(mongo, tenant.id).findByMediaId(mediaId)
            ?: return HttpStatusCode.NotFound to mapOf("error" to "media not found")
        val comments = InstagramCommentRepository(mongo, tenant.id).listByMedia(mediaId)
        val unreplied = comments.count { it.needsReply }
        return HttpStatusCode.OK to InstagramMediaDetailDto(
            media = media.dto(unreplied),
            comments = comments.map { it.dto(media.caption, media.permalink, media.thumbnailUrl ?: media.mediaUrl) },
        )
    }

    suspend fun reply(tenant: Tenant, commentId: String, message: String): Pair<HttpStatusCode, Any> {
        val text = message.trim()
        if (text.isBlank()) return HttpStatusCode.BadRequest to mapOf("error" to "message is required")
        val binding = tenant.binding(Platform.INSTAGRAM) ?: return HttpStatusCode.BadRequest to mapOf("error" to "instagram not connected")
        if (binding.accessToken.isBlank()) return HttpStatusCode.BadRequest to mapOf("error" to "instagram not connected")
        val comments = InstagramCommentRepository(mongo, tenant.id)
        val existing = comments.findByCommentId(commentId) ?: return HttpStatusCode.NotFound to mapOf("error" to "comment not found")
        return when (val result = graph(binding).replyToComment(commentId, text)) {
            is InstagramGraphResult.Ok -> {
                comments.markReplied(commentId, result.value)
                val now = SystemClock.now()
                comments.upsert(
                    InstagramComment(
                        tenantId = tenant.id,
                        commentId = result.value,
                        mediaId = existing.mediaId,
                        text = text,
                        fromId = binding.externalId,
                        fromUsername = binding.displayName,
                        fromAccount = true,
                        parentCommentId = commentId,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                val updated = comments.findByCommentId(commentId) ?: existing
                HttpStatusCode.OK to updated.dto()
            }
            InstagramGraphResult.PermissionDenied -> HttpStatusCode.Forbidden to mapOf("error" to "reconnect_required")
            is InstagramGraphResult.Failed -> HttpStatusCode.BadGateway to mapOf("error" to result.message)
        }
    }

    private fun graph(binding: com.rfm.edubot.tenant.model.ChannelBinding) =
        InstagramClient(binding.accessToken, binding.externalId, graphVersion(), httpClient)

    private suspend fun persistMedia(tenant: Tenant, items: List<InstagramGraphMedia>) {
        val repo = InstagramMediaRepository(mongo, tenant.id)
        val now = SystemClock.now()
        for (item in items) {
            repo.upsert(
                InstagramMedia(
                    tenantId = tenant.id,
                    mediaId = item.id,
                    caption = item.caption,
                    mediaType = item.mediaType,
                    mediaUrl = item.mediaUrl,
                    thumbnailUrl = item.thumbnailUrl,
                    permalink = item.permalink,
                    publishedAt = parseGraphTime(item.timestamp),
                    commentsCount = item.commentsCount,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    private suspend fun persistComments(
        tenant: Tenant,
        mediaId: String,
        accountId: String,
        items: List<InstagramGraphComment>,
    ) {
        val repo = InstagramCommentRepository(mongo, tenant.id)
        val now = SystemClock.now()
        for (item in items) {
            repo.upsert(
                InstagramComment(
                    tenantId = tenant.id,
                    commentId = item.id,
                    mediaId = mediaId,
                    text = item.text,
                    fromId = item.fromId,
                    fromUsername = item.fromUsername,
                    fromAccount = item.fromId != null && item.fromId == accountId,
                    createdAt = parseGraphTime(item.timestamp) ?: now,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun parseGraphTime(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { Instant.parse(value.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")) }.getOrNull()
    }
}
