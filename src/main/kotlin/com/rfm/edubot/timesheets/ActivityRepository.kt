package com.rfm.edubot.timesheets

import com.mongodb.client.model.Filters
import com.rfm.edubot.crm.getInstant
import com.rfm.edubot.crm.toDate
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.timesheets.model.Activity
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

/** A company's activities (task codes) that time is logged against. */
class ActivityRepository(mongo: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    /** By name; [activeOnly] leaves out the ones taken out of use. */
    suspend fun list(activeOnly: Boolean = false): List<Activity> {
        val filter = if (activeOnly) scoped(Filters.eq("active", true)) else Filters.eq("tenantId", tenantId)
        return collection.find(filter).sort(Document("name", 1)).limit(MAX_ACTIVITIES).toList().map { it.toActivity() }
    }

    suspend fun findById(id: ObjectId): Activity? = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toActivity()

    suspend fun count(): Long = collection.countDocuments(Filters.eq("tenantId", tenantId))

    suspend fun create(activity: Activity): Activity {
        collection.insertOne(activity.toDocument())
        return activity
    }

    suspend fun replace(activity: Activity): Activity? =
        activity.takeIf { collection.replaceOne(scoped(Filters.eq("_id", activity.id)), activity.toDocument()).matchedCount > 0 }

    suspend fun delete(id: ObjectId): Boolean = collection.deleteOne(scoped(Filters.eq("_id", id))).deletedCount > 0

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    private fun Activity.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("name", name)
        .append("code", code)
        .append("billable", billable)
        .append("active", active)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())

    private fun Document.toActivity() = Activity(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        name = getString("name").orEmpty(),
        code = getString("code"),
        billable = getBoolean("billable") ?: true,
        active = getBoolean("active") ?: true,
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
    )

    companion object {
        const val COLLECTION = "timesheets.activities"
        const val MAX_ACTIVITIES = 300
        const val MAX_NAME = 80
        const val MAX_CODE = 20
    }
}
