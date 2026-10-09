package com.rfm.edubot.ai

import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.bson.Document
import org.bson.types.ObjectId
import java.util.Date

/** What spent the tokens, kept per month in `tenant_usage.bySource`. */
object UsageSources {
    const val PIPELINE = "pipeline"
    const val ASSISTANT = "assistant"
    const val AGENTS = "agents"
    /** Writing the persona's instructions from its sources. */
    const val PERSONA = "persona"
    /** Reading received emails for the Email page's suggestions. */
    const val EMAIL = "email"
}

/**
 * Tracks OpenRouter token usage per tenant per calendar month (UTC). Backs the hard monthly
 * budget (Tenant.monthlyTokenBudget): MessagePipeline stops calling the LLM for a tenant once
 * this month's usage crosses it, and agent AI steps fail instead of starting, so OpenRouter spend
 * stays bounded per tenant. The total counts every source; `bySource` splits it for the dashboard.
 */
class TenantUsageRepository(
    mongoModule: MongoModule,
    private val tenantId: ObjectId,
    private val clock: () -> Instant = SystemClock::now,
) {
    private val collection = mongoModule.database.getCollection<Document>("tenant_usage")

    suspend fun tokensUsedThisMonth(): Long = (thisMonth()?.get("tokensUsed") as? Number)?.toLong() ?: 0L

    suspend fun tokensBySourceThisMonth(): Map<String, Long> {
        val bySource = thisMonth()?.get("bySource") as? Document ?: return emptyMap()
        return bySource.mapNotNull { (source, tokens) -> (tokens as? Number)?.let { source to it.toLong() } }.toMap()
    }

    suspend fun recordUsage(tokens: Long, source: String = UsageSources.PIPELINE) {
        if (tokens <= 0) return
        val period = periodOf(clock())
        collection.updateOne(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("period", period)),
            Updates.combine(
                Updates.inc("tokensUsed", tokens),
                Updates.inc("bySource.$source", tokens),
                Updates.inc("messageCount", 1L),
                Updates.setOnInsert("tenantId", tenantId),
                Updates.setOnInsert("period", period),
                Updates.set("updatedAt", Date()),
            ),
            UpdateOptions().upsert(true),
        )
    }

    private suspend fun thisMonth(): Document? =
        collection.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("period", periodOf(clock())))).firstOrNull()

    companion object {
        fun currentPeriod(): String = periodOf(SystemClock.now())

        fun periodOf(instant: Instant): String {
            val date = instant.toLocalDateTime(TimeZone.UTC)
            return "%04d-%02d".format(date.year, date.monthNumber)
        }
    }
}
