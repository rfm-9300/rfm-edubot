package com.rfm.edubot.ai

import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.bson.Document
import org.bson.types.ObjectId
import java.util.Date

/**
 * Tracks OpenRouter token usage per tenant per calendar month (UTC). Backs the hard monthly
 * budget (Tenant.monthlyTokenBudget): MessagePipeline stops calling the LLM for a tenant once
 * this month's usage crosses it, instead of leaving OpenRouter spend unbounded per tenant.
 */
class TenantUsageRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("tenant_usage")

    suspend fun tokensUsedThisMonth(): Long {
        val doc = collection.find(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("period", currentPeriod()))
        ).firstOrNull()
        return doc?.getLong("tokensUsed") ?: 0L
    }

    suspend fun recordUsage(tokens: Long) {
        if (tokens <= 0) return
        collection.updateOne(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("period", currentPeriod())),
            Updates.combine(
                Updates.inc("tokensUsed", tokens),
                Updates.inc("messageCount", 1L),
                Updates.setOnInsert("tenantId", tenantId),
                Updates.setOnInsert("period", currentPeriod()),
                Updates.set("updatedAt", Date()),
            ),
            UpdateOptions().upsert(true),
        )
    }

    companion object {
        fun currentPeriod(): String {
            val now = SystemClock.now().toLocalDateTime(TimeZone.UTC)
            return "%04d-%02d".format(now.year, now.monthNumber)
        }
    }
}
