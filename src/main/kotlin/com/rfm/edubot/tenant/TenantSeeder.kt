package com.rfm.edubot.tenant

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Tenant
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document
import org.slf4j.LoggerFactory

class TenantSeeder(
    private val mongo: MongoModule,
    private val repo: TenantRepository,
    private val appConfig: AppConfig,
) {
    private val log = LoggerFactory.getLogger("TenantSeeder")

    suspend fun run(): Tenant {
        val existing = repo.findAll().firstOrNull()
        val tenant = existing ?: createDefaultTenant()
        backfillMissingTenantId(tenant)
        return tenant
    }

    private suspend fun createDefaultTenant(): Tenant {
        val now = SystemClock.now()
        val tenant = Tenant(
            slug = "default",
            name = "Default Tenant",
            phoneNumberId = appConfig.whatsapp.phoneNumberId,
            rateLimitPerHour = appConfig.rateLimit.perUserPerHour,
            rateLimitPerDay = appConfig.rateLimit.perUserPerDay,
            createdAt = now,
            updatedAt = now,
        )
        repo.create(tenant)
        log.info("Seeded default tenant: id={}, phoneNumberId={}", tenant.id, tenant.phoneNumberId)
        return tenant
    }

    private suspend fun backfillMissingTenantId(tenant: Tenant) {
        val collections = listOf(
            "users",
            "conversations",
            "messages",
            "webhook_events",
            "crm.clients",
            "crm.quotes",
            "crm.invoices",
            "crm.sequences",
            "crm.standard_items",
        )
        for (name in collections) {
            val result = mongo.database.getCollection<Document>(name).updateMany(
                Filters.exists("tenantId", false),
                Updates.set("tenantId", tenant.id),
            )
            if (result.modifiedCount > 0) {
                log.info("Backfilled tenantId for collection={} count={}", name, result.modifiedCount)
            }
        }
    }
}
