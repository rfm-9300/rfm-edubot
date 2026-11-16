package com.rfm.edubot.timesheets

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.rfm.edubot.crm.getInstant
import com.rfm.edubot.crm.toDate
import com.rfm.edubot.crm.toInstantValue
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.timesheets.model.Excuse
import com.rfm.edubot.timesheets.model.PlannedShift
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

/** The shifts a company planned for its employees. */
class PlannedShiftRepository(mongo: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    /** Planned shifts on company-local days [from] to [to], both included, in start order. */
    suspend fun list(from: LocalDate, to: LocalDate, employeeId: ObjectId? = null): List<PlannedShift> {
        val filters = listOfNotNull(
            Filters.eq("tenantId", tenantId),
            Filters.gte("day", from.toString()),
            Filters.lte("day", to.toString()),
            employeeId?.let { Filters.eq("employeeId", it) },
        )
        return collection.find(Filters.and(filters)).sort(Document("startAt", 1)).limit(MAX_LIST).toList().map { it.toPlan() }
    }

    suspend fun findById(id: ObjectId): PlannedShift? = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toPlan()

    suspend fun byIds(ids: Collection<ObjectId>): List<PlannedShift> =
        if (ids.isEmpty()) emptyList() else collection.find(scoped(Filters.`in`("_id", ids.distinct()))).toList().map { it.toPlan() }

    /** [employeeId]'s planned shifts overlapping [startAt]–[endAt], other than [except]. */
    suspend fun overlapping(employeeId: ObjectId, startAt: Instant, endAt: Instant, except: ObjectId? = null): List<PlannedShift> {
        val filters = listOfNotNull(
            Filters.eq("employeeId", employeeId),
            Filters.lt("startAt", endAt.toDate()),
            Filters.gt("endAt", startAt.toDate()),
            except?.let { Filters.ne("_id", it) },
        )
        return collection.find(scoped(Filters.and(filters))).toList().map { it.toPlan() }
    }

    suspend fun insert(plan: PlannedShift) {
        collection.insertOne(plan.toDocument())
    }

    suspend fun replace(plan: PlannedShift): PlannedShift? =
        plan.takeIf { collection.replaceOne(scoped(Filters.eq("_id", plan.id)), plan.toDocument()).matchedCount > 0 }

    suspend fun delete(id: ObjectId): Boolean = collection.deleteOne(scoped(Filters.eq("_id", id))).deletedCount > 0

    /** Records the first shift that attended [id]; a later one doesn't replace it. */
    suspend fun setMatched(id: ObjectId, shiftId: ObjectId) {
        collection.updateOne(scoped(Filters.and(Filters.eq("_id", id), Filters.eq("matchedShiftId", null))), Updates.set("matchedShiftId", shiftId))
    }

    /** Whether any planned shift names [activityId] in its job. */
    suspend fun usesActivity(activityId: ObjectId): Boolean =
        collection.find(scoped(Filters.eq("job.activityId", activityId))).limit(1).firstOrNull() != null

    /** Claims the no-show alert for [id]: true for the one caller that sets it. */
    suspend fun claimAlert(id: ObjectId, at: Instant): Boolean =
        collection.updateOne(scoped(Filters.and(Filters.eq("_id", id), Filters.eq("alertedAt", null))), Updates.set("alertedAt", at.toDate())).modifiedCount > 0

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    companion object {
        const val COLLECTION = "timesheets.planned"
        private const val MAX_LIST = 5000

        /**
         * Every company's planned shifts that started between [from] and [to] with nobody matched, no alert sent and
         * no excuse: the ones the no-show watch looks at.
         */
        suspend fun unattended(mongo: MongoModule, from: Instant, to: Instant): List<PlannedShift> =
            mongo.database.getCollection<Document>(COLLECTION).find(
                Filters.and(
                    Filters.gte("startAt", from.toDate()),
                    Filters.lte("startAt", to.toDate()),
                    Filters.eq("matchedShiftId", null),
                    Filters.eq("alertedAt", null),
                    Filters.eq("excuse", null),
                ),
            ).sort(Document("startAt", 1)).limit(MAX_LIST).toList().map { it.toPlan() }
    }
}

private fun PlannedShift.toDocument() = Document("_id", id)
    .append("tenantId", tenantId)
    .append("employeeId", employeeId)
    .append("day", day.toString())
    .append("startAt", startAt.toDate())
    .append("endAt", endAt.toDate())
    .append("siteId", siteId)
    .append("siteName", siteName)
    .append("job", job?.toDocument())
    .append("note", note)
    .append("excuse", excuse?.let { Document("reason", it.reason).append("by", it.by).append("at", it.at.toDate()) })
    .append("matchedShiftId", matchedShiftId)
    .append("alertedAt", alertedAt?.toDate())
    .append("createdBy", createdBy)
    .append("createdAt", createdAt.toDate())
    .append("updatedAt", updatedAt.toDate())

private fun Document.toPlan() = PlannedShift(
    id = getObjectId("_id"),
    tenantId = getObjectId("tenantId"),
    employeeId = getObjectId("employeeId"),
    day = LocalDate.parse(getString("day")),
    startAt = getInstant("startAt"),
    endAt = getInstant("endAt"),
    siteId = get("siteId", ObjectId::class.java),
    siteName = getString("siteName"),
    job = get("job", Document::class.java)?.toJob(),
    note = getString("note"),
    excuse = get("excuse", Document::class.java)?.let { Excuse(it.getString("reason").orEmpty(), it.getString("by").orEmpty(), it.getInstant("at")) },
    matchedShiftId = get("matchedShiftId", ObjectId::class.java),
    alertedAt = getDate("alertedAt")?.toInstantValue(),
    createdBy = getString("createdBy").orEmpty(),
    createdAt = getInstant("createdAt"),
    updatedAt = getInstant("updatedAt"),
)
