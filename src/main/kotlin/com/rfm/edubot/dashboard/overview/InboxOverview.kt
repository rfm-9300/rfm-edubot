package com.rfm.edubot.dashboard.overview

import com.mongodb.client.model.Filters
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.dashboard.OverviewDayCountDto
import com.rfm.edubot.dashboard.OverviewInboxDto
import com.rfm.edubot.dashboard.OverviewMath
import com.rfm.edubot.dashboard.OverviewSocialDto
import com.rfm.edubot.instagram.InstagramCommentRepository
import com.rfm.edubot.oauth.InstagramOAuthScopes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import java.util.Date
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.bson.types.ObjectId

/** The Inbox and Social cards: chats waiting for the team, message activity and unanswered Instagram comments. */
internal class InboxOverview(mongo: MongoModule) : OverviewQueries(mongo) {
    suspend fun inbox(
        tenantId: ObjectId,
        window: OverviewMath.Window,
        conversations: Long,
        contacts: Long,
        messagesToday: Long,
        waiting: Int,
    ): OverviewInboxDto {
        val tenantFilter = Filters.eq("tenantId", tenantId)
        val messagesThisWeek = coll("messages").countDocuments(
            Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.weekStart.toEpochMilliseconds())), Filters.lt("createdAt", Date(window.nextWeekStart.toEpochMilliseconds()))),
        )
        val messagesLastWeek = coll("messages").countDocuments(
            Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.lastWeekStart.toEpochMilliseconds())), Filters.lt("createdAt", Date(window.weekStart.toEpochMilliseconds()))),
        )
        val newContactsThisWeek = coll("users").countDocuments(
            Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.weekStart.toEpochMilliseconds()))),
        )
        val autoReplyPaused = coll("conversations").countDocuments(
            Filters.and(tenantFilter, Filters.eq("autoReplyEnabled", false)),
        )
        return OverviewInboxDto(
            waiting = waiting,
            conversations = conversations,
            messagesToday = messagesToday,
            messagesThisWeek = messagesThisWeek,
            messagesLastWeek = messagesLastWeek,
            contacts = contacts,
            newContactsThisWeek = newContactsThisWeek,
            autoReplyPaused = autoReplyPaused.toInt(),
        )
    }

    suspend fun activity(tenantId: ObjectId, window: OverviewMath.Window): List<OverviewDayCountDto> {
        val keys = OverviewMath.trendDayKeys(window.today)
        val from = OverviewMath.daysBackStart(window.today, window.zone, keys.size)
        val range = Document("\$gte", Date(from.toEpochMilliseconds())).append("\$lt", Date(window.tomorrowStart.toEpochMilliseconds()))
        val localDay = Document("\$dateToString", Document("format", "%Y-%m-%d").append("date", "\$createdAt").append("timezone", window.zone.id))
        val pipeline = listOf(
            Document("\$match", Document("tenantId", tenantId).append("createdAt", range)),
            Document("\$group", Document("_id", localDay).append("count", Document("\$sum", 1))),
        )
        val byDay = coll("messages").aggregate<Document>(pipeline).toList()
            .associate { (it.getString("_id") ?: "") to it.get("count").asLong() }
        return OverviewMath.dayCounts(keys, byDay)
    }

    data class WaitingConversation(val id: String, val detail: String, val at: String?)

    suspend fun waitingConversations(tenantId: ObjectId): List<WaitingConversation> {
        val conversations = ConversationRepository(mongo, tenantId).list(limit = 80)
        if (conversations.isEmpty()) return emptyList()
        val last = MessageRepository(mongo, tenantId).lastByConversationIds(conversations.map { it.id })
        val displayNames = com.rfm.edubot.conversation.UserRepository(mongo, tenantId)
            .displayNamesByIds(conversations.map { it.userId })
        return conversations.mapNotNull { convo ->
            val message = last[convo.id] ?: return@mapNotNull null
            if (!convo.needsTeamReply(message)) return@mapNotNull null
            val name = displayNames[convo.userId]?.takeIf { it.isNotBlank() } ?: convo.waId
            val preview = when (val content = message.content) {
                is MessageContent.Text -> content.body.trim()
                else -> ""
            }.take(80)
            WaitingConversation(
                id = convo.id.toHexString(),
                detail = listOf(name, preview).filter { it.isNotBlank() }.joinToString(" · "),
                at = convo.lastMessageAt.toString(),
            )
        }
    }

    suspend fun social(tenant: Tenant): OverviewSocialDto {
        val binding = tenant.binding(Platform.INSTAGRAM)
        val unreplied = InstagramCommentRepository(mongo, tenant.id).countUnreplied()
        return OverviewSocialDto(
            unreplied = unreplied,
            connected = binding != null,
            commentsEnabled = binding != null && InstagramOAuthScopes.hasComments(binding.grantedScopes),
        )
    }
}
