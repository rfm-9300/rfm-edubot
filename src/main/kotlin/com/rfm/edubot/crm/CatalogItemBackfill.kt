package com.rfm.edubot.crm

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.slf4j.LoggerFactory

/**
 * Catalog items saved before titles and codes existed get both once: the description becomes the title,
 * and each item takes the next free code for its type, in creation order. A code typed before codes had
 * to be three letters, a dash and digits is replaced the same way. Only those fields are written, so the
 * previous release still reads every item.
 */
class CatalogItemBackfill(private val mongo: MongoModule) {
    private val log = LoggerFactory.getLogger("CatalogItemBackfill")
    private val items = mongo.database.getCollection<Document>("crm.standard_items")

    /** Returns how many items it changed. */
    suspend fun run(): Int {
        val pending = items.find(Filters.or(Filters.eq("title", null), Filters.not(Filters.regex("code", CATALOG_CODE_PATTERN))))
            .sort(Document("_id", 1))
            .toList()
        var changed = 0
        for ((tenantId, docs) in pending.groupBy { it.getObjectId("tenantId") }) {
            if (tenantId == null) continue
            val repository = StandardItemRepository(mongo, tenantId)
            for (doc in docs) {
                val updates = buildList {
                    if (doc.getString("title").isNullOrBlank()) add(Updates.set("title", doc.getString("description").orEmpty()))
                    val code = doc.getString("code")
                    if (!isCatalogCode(code)) {
                        val next = repository.freeCode(catalogCodePrefix(doc.getString("type").orEmpty()))
                        if (!code.isNullOrBlank()) log.info("Catalog item {} of tenant {}: code {} became {}", doc.getString("id"), tenantId, code, next)
                        add(Updates.set("code", next))
                    }
                }
                if (updates.isEmpty()) continue
                items.updateOne(Filters.eq("_id", doc["_id"]), Updates.combine(updates))
                changed += 1
            }
        }
        if (changed > 0) log.info("Gave {} catalog items a title or a code", changed)
        return changed
    }
}
