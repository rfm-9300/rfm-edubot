package com.rfm.edubot.crm

import com.mongodb.ErrorCategory
import com.mongodb.MongoServerException
import com.mongodb.client.model.Accumulators
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.bson.Document
import org.bson.types.ObjectId
import java.text.Normalizer
import java.util.Date

/** A catalog category as the dashboard lists it: its name and how many of the company's items use it. */
@Serializable
data class CatalogCategory(val name: String, val itemCount: Int)

/** What a category change came to. [error] is the stable code the dashboard translates. */
sealed interface CategoryOutcome {
    data class Saved(val category: CatalogCategory) : CategoryOutcome
    data object Deleted : CategoryOutcome
    data class Refused(val error: String, val itemCount: Int = 0) : CategoryOutcome
    data object NotFound : CategoryOutcome
}

private val spaces = Regex("\\s+")
private val marks = Regex("\\p{M}+")

/** The name as stored: trimmed, inner runs of spaces collapsed to one. */
fun cleanCategoryName(name: String): String = name.trim().replace(spaces, " ")

/** What makes two names the same category: "Limpeza", "limpeza " and "LIMPEZA" are one, so are "Inspeção" and "inspecao". */
fun categoryKey(name: String): String =
    Normalizer.normalize(cleanCategoryName(name), Normalizer.Form.NFD).replace(marks, "").lowercase()

/**
 * The categories of one company's catalog (`crm.catalog_categories`). An item keeps its category as plain text
 * (`StandardItem.category`), so the mobile app, the backoffice and the AI keep working and any of them may save
 * an item under a category nobody created: the list is the names created here **plus** the ones items use,
 * so a catalog that predates categories needs no migration. Names are compared by [categoryKey].
 */
class CatalogCategoryRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val categories = mongoModule.database.getCollection<Document>("crm.catalog_categories")
    private val items = mongoModule.database.getCollection<Document>("crm.standard_items")

    /** One category as found in the data: the stored name (or the most used spelling) and every spelling items use. */
    private class Entry(val key: String, var name: String, var stored: Boolean) {
        val spellings = mutableMapOf<String, Int>()
        val count get() = spellings.values.sum()
    }

    suspend fun list(): List<CatalogCategory> = entries().map { CatalogCategory(it.name, it.count) }

    suspend fun create(name: String): CategoryOutcome {
        val clean = cleanCategoryName(name)
        invalid(clean)?.let { return CategoryOutcome.Refused(it) }
        if (entries().any { it.key == categoryKey(clean) }) return CategoryOutcome.Refused("category_exists")
        try {
            categories.insertOne(Document("tenantId", tenantId).append("name", clean).append("key", categoryKey(clean)).append("createdAt", Date()))
        } catch (e: MongoServerException) {
            if (ErrorCategory.fromErrorCode(e.code) != ErrorCategory.DUPLICATE_KEY) throw e
            return CategoryOutcome.Refused("category_exists")
        }
        return CategoryOutcome.Saved(CatalogCategory(clean, 0))
    }

    /**
     * Gives the category a new name and moves its items to it, whatever way they spell it. A name another
     * category already has is refused; changing only the case or the accents of its own is fine.
     */
    suspend fun rename(from: String, to: String): CategoryOutcome {
        val clean = cleanCategoryName(to)
        invalid(clean)?.let { return CategoryOutcome.Refused(it) }
        val all = entries()
        val current = all.firstOrNull { it.key == categoryKey(from) } ?: return CategoryOutcome.NotFound
        val key = categoryKey(clean)
        if (key != current.key && all.any { it.key == key }) return CategoryOutcome.Refused("category_exists")
        try {
            if (current.spellings.isNotEmpty()) {
                items.updateMany(
                    Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`("category", current.spellings.keys.toList())),
                    Updates.set("category", clean),
                )
            }
            categories.updateOne(
                Document("tenantId", tenantId).append("key", current.key),
                Updates.combine(Updates.set("name", clean), Updates.set("key", key), Updates.setOnInsert("createdAt", Date())),
                UpdateOptions().upsert(true),
            )
        } catch (e: MongoServerException) {
            if (ErrorCategory.fromErrorCode(e.code) != ErrorCategory.DUPLICATE_KEY) throw e
            return CategoryOutcome.Refused("category_exists")
        }
        return CategoryOutcome.Saved(CatalogCategory(clean, current.count))
    }

    /** Only a category no item uses goes; the items of one in use would be left with a name that isn't listed. */
    suspend fun delete(name: String): CategoryOutcome {
        val current = entries().firstOrNull { it.key == categoryKey(name) } ?: return CategoryOutcome.NotFound
        if (current.count > 0) return CategoryOutcome.Refused("category_in_use", current.count)
        categories.deleteOne(Document("tenantId", tenantId).append("key", current.key))
        return CategoryOutcome.Deleted
    }

    private fun invalid(clean: String): String? = when {
        clean.isEmpty() -> "name_required"
        clean.length > MAX_NAME -> "name_too_long"
        else -> null
    }

    private suspend fun entries(): List<Entry> {
        val byKey = linkedMapOf<String, Entry>()
        categories.find(Filters.eq("tenantId", tenantId)).toList().forEach { doc ->
            val name = doc.getString("name").orEmpty()
            val key = categoryKey(name)
            if (key.isNotEmpty()) byKey.putIfAbsent(key, Entry(key, name, stored = true))
        }
        val used = items.aggregate(
            listOf(Aggregates.match(Filters.eq("tenantId", tenantId)), Aggregates.group("\$category", Accumulators.sum("n", 1))),
        ).toList()
        for (row in used) {
            val spelling = row.getString("_id") ?: continue
            val key = categoryKey(spelling)
            if (key.isEmpty()) continue
            val entry = byKey.getOrPut(key) { Entry(key, spelling, stored = false) }
            entry.spellings.merge(spelling, (row["n"] as? Number)?.toInt() ?: 0, Int::plus)
        }
        // A category only items name is called after the spelling most of them use.
        byKey.values.filter { !it.stored }.forEach { entry ->
            entry.name = entry.spellings.maxByOrNull { it.value }?.key ?: entry.name
        }
        return byKey.values.sortedWith(compareBy({ it.key }, { it.name }))
    }

    companion object {
        const val MAX_NAME = 60
    }
}
