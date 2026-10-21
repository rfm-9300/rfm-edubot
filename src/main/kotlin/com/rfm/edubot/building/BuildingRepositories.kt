package com.rfm.edubot.building

import com.mongodb.ErrorCategory
import com.mongodb.MongoServerException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.building.model.ChangeApproval
import com.rfm.edubot.building.model.ChangeOrder
import com.rfm.edubot.building.model.ChangeOrderKind
import com.rfm.edubot.building.model.ChangeOrderStatus
import com.rfm.edubot.building.model.ContractLine
import com.rfm.edubot.building.model.DecidedVia
import com.rfm.edubot.building.model.IssueEvent
import com.rfm.edubot.building.model.IssueKind
import com.rfm.edubot.building.model.IssuePriority
import com.rfm.edubot.building.model.IssueStatus
import com.rfm.edubot.building.model.Measurement
import com.rfm.edubot.building.model.MeasurementLine
import com.rfm.edubot.building.model.MeasurementStatus
import com.rfm.edubot.building.model.PhaseProgressChange
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.ProjectPhase
import com.rfm.edubot.building.model.ProjectShare
import com.rfm.edubot.building.model.ProjectStatus
import com.rfm.edubot.building.model.RetentionRelease
import com.rfm.edubot.building.model.SiteIssue
import com.rfm.edubot.building.model.SiteLogEntry
import com.rfm.edubot.building.model.SitePhoto
import com.rfm.edubot.building.model.Weather
import com.rfm.edubot.crm.SequenceRepository
import com.rfm.edubot.crm.getInstant
import com.rfm.edubot.crm.getLongValue
import com.rfm.edubot.crm.toDate
import com.rfm.edubot.crm.toDocument
import com.rfm.edubot.crm.toInstantValue
import com.rfm.edubot.crm.toLineItem
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

/** A write to a project: the project after it, or a stable error code. */
sealed interface ProjectChange {
    data class Ok(val project: Project) : ProjectChange
    data class Refused(val error: String) : ProjectChange
}

class ProjectRepository(mongo: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)
    private val sequences = SequenceRepository(mongo, tenantId)

    suspend fun nextNumber(): String = "OB-${sequences.next("project_number").toString().padStart(3, '0')}"

    suspend fun list(statuses: Collection<ProjectStatus> = emptyList()): List<Project> {
        val filters = listOfNotNull(
            Filters.eq("tenantId", tenantId),
            statuses.takeIf { it.isNotEmpty() }?.let { s -> Filters.`in`("status", s.map { it.name }) },
        )
        return collection.find(Filters.and(filters)).sort(Document("updatedAt", -1)).limit(MAX_LIST).toList().map { it.toProject() }
    }

    suspend fun findById(id: ObjectId): Project? = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toProject()

    suspend fun byIds(ids: Collection<ObjectId>): List<Project> =
        if (ids.isEmpty()) emptyList() else collection.find(scoped(Filters.`in`("_id", ids.distinct()))).toList().map { it.toProject() }

    suspend fun forQuote(quoteId: ObjectId): List<Project> = collection.find(scoped(Filters.eq("quoteId", quoteId))).toList().map { it.toProject() }

    suspend fun count(): Long = collection.countDocuments(Filters.eq("tenantId", tenantId))

    suspend fun insert(project: Project) {
        collection.insertOne(project.toDocument())
    }

    /**
     * Applies [change] to project [id] and stores the result only over the version it was computed from, reading
     * again when someone else wrote in between. Null when there is no such project.
     */
    suspend fun update(id: ObjectId, change: suspend (Project) -> ProjectChange): ProjectChange? {
        repeat(RETRIES) {
            val current = findById(id) ?: return null
            when (val outcome = change(current)) {
                is ProjectChange.Refused -> return outcome
                is ProjectChange.Ok -> {
                    if (outcome.project == current) return outcome
                    val next = outcome.project.copy(version = current.version + 1)
                    val stored = collection.replaceOne(scoped(Filters.and(Filters.eq("_id", id), Filters.eq("version", current.version))), next.toDocument())
                    if (stored.matchedCount > 0) return ProjectChange.Ok(next)
                }
            }
        }
        return ProjectChange.Refused("conflict")
    }

    suspend fun delete(id: ObjectId): Boolean = collection.deleteOne(scoped(Filters.eq("_id", id))).deletedCount > 0

    /** Only the watch writes it, and without a new version: nobody edits it, so it can't overwrite someone's edit. */
    suspend fun setWatchedHealth(id: ObjectId, health: String) {
        collection.updateOne(scoped(Filters.eq("_id", id)), Updates.set("watchedHealth", health))
    }

    /**
     * A cancelled or deleted invoice gives back the retention release it billed, so the amount is held again. A new
     * version, so an edit read before can't write the release back.
     */
    suspend fun releaseRetentionInvoice(invoiceId: ObjectId): Int =
        collection.updateMany(
            scoped(Filters.eq("retentionReleases.invoiceId", invoiceId)),
            Updates.combine(Updates.pull("retentionReleases", Document("invoiceId", invoiceId)), Updates.inc("version", 1)),
        ).modifiedCount.toInt()

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    companion object {
        const val COLLECTION = "building.projects"
        const val MAX_LIST = 500
        private const val RETRIES = 3

        /** The project a client's link opens, whatever its company; only while the link is on. */
        suspend fun byShareToken(mongo: MongoModule, token: String): Project? {
            if (token.isBlank() || token.length > 100) return null
            return mongo.database.getCollection<Document>(COLLECTION)
                .find(Filters.and(Filters.eq("share.token", token), Filters.eq("share.enabled", true)))
                .firstOrNull()?.toProject()
        }
    }
}

class SiteLogRepository(mongo: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    /** Entries of days from [from] to [to], both included, newest first. */
    suspend fun list(projectId: ObjectId?, from: LocalDate?, to: LocalDate?, limit: Int = MAX_LIST): List<SiteLogEntry> {
        val filters = listOfNotNull(
            Filters.eq("tenantId", tenantId),
            projectId?.let { Filters.eq("projectId", it) },
            from?.let { Filters.gte("day", it.toString()) },
            to?.let { Filters.lte("day", it.toString()) },
        )
        return collection.find(Filters.and(filters)).sort(Document("day", -1).append("createdAt", -1)).limit(limit).toList().map { it.toEntry() }
    }

    suspend fun findById(id: ObjectId): SiteLogEntry? = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toEntry()

    suspend fun findByDay(projectId: ObjectId, day: LocalDate): SiteLogEntry? =
        collection.find(scoped(Filters.and(Filters.eq("projectId", projectId), Filters.eq("day", day.toString())))).firstOrNull()?.toEntry()

    /** False when the project already has an entry that day (the unique index decides). */
    suspend fun insert(entry: SiteLogEntry): Boolean = try {
        collection.insertOne(entry.toDocument())
        true
    } catch (e: MongoServerException) {
        if (ErrorCategory.fromErrorCode(e.code) != ErrorCategory.DUPLICATE_KEY) throw e
        false
    }

    /** False when another entry of the project already has the new day. */
    suspend fun replace(entry: SiteLogEntry): Boolean = try {
        collection.replaceOne(scoped(Filters.eq("_id", entry.id)), entry.toDocument()).matchedCount > 0
    } catch (e: MongoServerException) {
        if (ErrorCategory.fromErrorCode(e.code) != ErrorCategory.DUPLICATE_KEY) throw e
        false
    }

    suspend fun delete(id: ObjectId): Boolean = collection.deleteOne(scoped(Filters.eq("_id", id))).deletedCount > 0

    suspend fun deleteForProject(projectId: ObjectId) {
        collection.deleteMany(scoped(Filters.eq("projectId", projectId)))
    }

    /** The latest diary day of each of [projectIds] that has one. */
    suspend fun lastDays(projectIds: Collection<ObjectId>): Map<ObjectId, LocalDate> {
        if (projectIds.isEmpty()) return emptyMap()
        return collection.aggregate<Document>(
            listOf(
                Document("\$match", Document("tenantId", tenantId).append("projectId", Document("\$in", projectIds.distinct()))),
                Document("\$group", Document("_id", "\$projectId").append("day", Document("\$max", "\$day"))),
            ),
        ).toList().mapNotNull { doc -> doc.getString("day")?.let { doc.getObjectId("_id") to LocalDate.parse(it) } }.toMap()
    }

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    companion object {
        const val COLLECTION = "building.site_logs"
        const val MAX_LIST = 400
    }
}

class SitePhotoRepository(mongo: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    /** Newest first. */
    suspend fun list(projectId: ObjectId, sharedOnly: Boolean = false, limit: Int = MAX_LIST): List<SitePhoto> {
        val filters = listOfNotNull(
            Filters.eq("tenantId", tenantId),
            Filters.eq("projectId", projectId),
            Filters.eq("shared", true).takeIf { sharedOnly },
        )
        return collection.find(Filters.and(filters)).sort(Document("day", -1).append("createdAt", -1)).limit(limit).toList().map { it.toPhoto() }
    }

    suspend fun forLogs(logIds: Collection<ObjectId>): List<SitePhoto> =
        if (logIds.isEmpty()) emptyList() else collection.find(scoped(Filters.`in`("logId", logIds.distinct()))).sort(Document("createdAt", 1)).toList().map { it.toPhoto() }

    suspend fun forIssue(issueId: ObjectId): List<SitePhoto> =
        collection.find(scoped(Filters.eq("issueId", issueId))).sort(Document("createdAt", 1)).toList().map { it.toPhoto() }

    suspend fun findById(id: ObjectId): SitePhoto? = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toPhoto()

    suspend fun count(projectId: ObjectId): Long = collection.countDocuments(scoped(Filters.eq("projectId", projectId)))

    suspend fun insert(photo: SitePhoto) {
        collection.insertOne(photo.toDocument())
    }

    suspend fun update(id: ObjectId, caption: String?, shared: Boolean?): SitePhoto? {
        val ops = listOfNotNull(
            caption?.let { Updates.set("caption", it.ifBlank { null }) },
            shared?.let { Updates.set("shared", it) },
        )
        if (ops.isEmpty()) return findById(id)
        return collection.findOneAndUpdate(scoped(Filters.eq("_id", id)), Updates.combine(ops), FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER))?.toPhoto()
    }

    suspend fun delete(id: ObjectId): SitePhoto? = collection.findOneAndDelete(scoped(Filters.eq("_id", id)))?.toPhoto()

    /** A deleted diary day or issue leaves its photos in the project's gallery. */
    suspend fun detach(field: String, id: ObjectId) {
        collection.updateMany(scoped(Filters.eq(field, id)), Updates.set(field, null))
    }

    suspend fun deleteForProject(projectId: ObjectId): List<SitePhoto> {
        val photos = collection.find(scoped(Filters.eq("projectId", projectId))).toList().map { it.toPhoto() }
        collection.deleteMany(scoped(Filters.eq("projectId", projectId)))
        return photos
    }

    /** How many photos each of [projectIds] has. */
    suspend fun counts(projectIds: Collection<ObjectId>): Map<ObjectId, Int> {
        if (projectIds.isEmpty()) return emptyMap()
        return collection.aggregate<Document>(
            listOf(
                Document("\$match", Document("tenantId", tenantId).append("projectId", Document("\$in", projectIds.distinct()))),
                Document("\$group", Document("_id", "\$projectId").append("n", Document("\$sum", 1))),
            ),
        ).toList().associate { it.getObjectId("_id") to (it.get("n") as Number).toInt() }
    }

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    companion object {
        const val COLLECTION = "building.photos"
        const val MAX_LIST = 500
        const val MAX_PER_PROJECT = 3000
    }
}

class SiteIssueRepository(mongo: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)
    private val sequences = SequenceRepository(mongo, tenantId)

    suspend fun nextNumber(): String = "OC-${sequences.next("issue_number").toString().padStart(3, '0')}"

    suspend fun list(projectId: ObjectId? = null, statuses: Collection<IssueStatus> = emptyList()): List<SiteIssue> {
        val filters = listOfNotNull(
            Filters.eq("tenantId", tenantId),
            projectId?.let { Filters.eq("projectId", it) },
            statuses.takeIf { it.isNotEmpty() }?.let { s -> Filters.`in`("status", s.map { it.name }) },
        )
        return collection.find(Filters.and(filters)).sort(Document("createdAt", -1)).limit(MAX_LIST).toList().map { it.toIssue() }
    }

    /** The issues of [projectIds] in [statuses] (all when empty), however many. */
    suspend fun forProjects(projectIds: Collection<ObjectId>, statuses: Collection<IssueStatus> = emptyList()): List<SiteIssue> {
        if (projectIds.isEmpty()) return emptyList()
        val filters = listOfNotNull(
            Filters.`in`("projectId", projectIds.distinct()),
            statuses.takeIf { it.isNotEmpty() }?.let { s -> Filters.`in`("status", s.map { it.name }) },
        )
        return collection.find(scoped(Filters.and(filters))).toList().map { it.toIssue() }
    }

    suspend fun findById(id: ObjectId): SiteIssue? = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toIssue()

    suspend fun insert(issue: SiteIssue) {
        collection.insertOne(issue.toDocument())
    }

    suspend fun replace(issue: SiteIssue): SiteIssue? =
        issue.takeIf { collection.replaceOne(scoped(Filters.eq("_id", issue.id)), issue.toDocument()).matchedCount > 0 }

    suspend fun delete(id: ObjectId): Boolean = collection.deleteOne(scoped(Filters.eq("_id", id))).deletedCount > 0

    suspend fun deleteForProject(projectId: ObjectId) {
        collection.deleteMany(scoped(Filters.eq("projectId", projectId)))
    }

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    companion object {
        const val COLLECTION = "building.issues"
        const val MAX_LIST = 1000
    }
}

class ChangeOrderRepository(mongo: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)
    private val sequences = SequenceRepository(mongo, tenantId)

    suspend fun nextNumber(): String = "TM-${sequences.next("change_order_number").toString().padStart(3, '0')}"

    suspend fun list(projectId: ObjectId? = null, statuses: Collection<ChangeOrderStatus> = emptyList()): List<ChangeOrder> {
        val filters = listOfNotNull(
            Filters.eq("tenantId", tenantId),
            projectId?.let { Filters.eq("projectId", it) },
            statuses.takeIf { it.isNotEmpty() }?.let { s -> Filters.`in`("status", s.map { it.name }) },
        )
        return collection.find(Filters.and(filters)).sort(Document("createdAt", -1)).limit(MAX_LIST).toList().map { it.toChange() }
    }

    /** Every change of [projectIds], however many. */
    suspend fun forProjects(projectIds: Collection<ObjectId>): List<ChangeOrder> =
        if (projectIds.isEmpty()) emptyList() else collection.find(scoped(Filters.`in`("projectId", projectIds.distinct()))).toList().map { it.toChange() }

    suspend fun findById(id: ObjectId): ChangeOrder? = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toChange()

    suspend fun insert(change: ChangeOrder) {
        collection.insertOne(change.toDocument())
    }

    suspend fun replace(change: ChangeOrder): ChangeOrder? =
        change.takeIf { collection.replaceOne(scoped(Filters.eq("_id", change.id)), change.toDocument()).matchedCount > 0 }

    /** Stores [next] only while the stored change still has [expected]'s status and update time. */
    suspend fun replace(expected: ChangeOrder, next: ChangeOrder): ChangeOrder? {
        val guard = Filters.and(Filters.eq("_id", expected.id), Filters.eq("status", expected.status.name), Filters.eq("updatedAt", expected.updatedAt.toDate()))
        return next.takeIf { collection.replaceOne(scoped(guard), next.toDocument()).matchedCount > 0 }
    }

    suspend fun delete(id: ObjectId): Boolean = collection.deleteOne(scoped(Filters.eq("_id", id))).deletedCount > 0

    suspend fun deleteForProject(projectId: ObjectId) {
        collection.deleteMany(scoped(Filters.eq("projectId", projectId)))
    }

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    companion object {
        const val COLLECTION = "building.change_orders"
        const val MAX_LIST = 1000

        /** The change a client's approval link opens, whatever its company. */
        suspend fun byApprovalToken(mongo: MongoModule, token: String): ChangeOrder? {
            if (token.isBlank() || token.length > 100) return null
            return mongo.database.getCollection<Document>(COLLECTION).find(Filters.eq("approval.token", token)).firstOrNull()?.toChange()
        }
    }
}

class MeasurementRepository(mongo: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    /** Newest first. */
    suspend fun list(projectId: ObjectId? = null, statuses: Collection<MeasurementStatus> = emptyList()): List<Measurement> {
        val filters = listOfNotNull(
            Filters.eq("tenantId", tenantId),
            projectId?.let { Filters.eq("projectId", it) },
            statuses.takeIf { it.isNotEmpty() }?.let { s -> Filters.`in`("status", s.map { it.name }) },
        )
        return collection.find(Filters.and(filters)).sort(Document("projectId", 1).append("number", -1)).limit(MAX_LIST).toList().map { it.toMeasurement() }
    }

    suspend fun findById(id: ObjectId): Measurement? = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toMeasurement()

    /** Every measurement of [projectIds], however many. */
    suspend fun forProjects(projectIds: Collection<ObjectId>): List<Measurement> =
        if (projectIds.isEmpty()) emptyList() else collection.find(scoped(Filters.`in`("projectId", projectIds.distinct()))).toList().map { it.toMeasurement() }

    suspend fun latest(projectId: ObjectId): Measurement? =
        collection.find(scoped(Filters.eq("projectId", projectId))).sort(Document("number", -1)).limit(1).firstOrNull()?.toMeasurement()

    /** The number of the latest measurement of each of [projectIds] that has one. */
    suspend fun latestNumbers(projectIds: Collection<ObjectId>): Map<ObjectId, Int> {
        if (projectIds.isEmpty()) return emptyMap()
        return collection.aggregate<Document>(
            listOf(
                Document("\$match", Document("tenantId", tenantId).append("projectId", Document("\$in", projectIds.distinct()))),
                Document("\$group", Document("_id", "\$projectId").append("number", Document("\$max", "\$number"))),
            ),
        ).toList().associate { it.getObjectId("_id") to (it.get("number") as Number).toInt() }
    }

    suspend fun count(projectId: ObjectId): Long = collection.countDocuments(scoped(Filters.eq("projectId", projectId)))

    /** Stores [measurement] as the project's next number; null when another one took that number first. */
    suspend fun insert(measurement: Measurement): Measurement? = try {
        collection.insertOne(measurement.toDocument())
        measurement
    } catch (e: MongoServerException) {
        if (ErrorCategory.fromErrorCode(e.code) != ErrorCategory.DUPLICATE_KEY) throw e
        null
    }

    /** Stores [next] only while the stored one still has [expected]'s status and update time. */
    suspend fun replace(expected: Measurement, next: Measurement): Measurement? {
        val guard = Filters.and(Filters.eq("_id", expected.id), Filters.eq("status", expected.status.name), Filters.eq("updatedAt", expected.updatedAt.toDate()))
        return next.takeIf { collection.replaceOne(scoped(guard), next.toDocument()).matchedCount > 0 }
    }

    suspend fun delete(id: ObjectId): Boolean = collection.deleteOne(scoped(Filters.eq("_id", id))).deletedCount > 0

    /** A cancelled or deleted invoice frees the measurement it billed, which can then be invoiced again. */
    suspend fun releaseInvoice(invoiceId: ObjectId): Int =
        collection.updateMany(
            scoped(Filters.and(Filters.eq("invoiceId", invoiceId), Filters.eq("status", MeasurementStatus.INVOICED.name))),
            Updates.combine(
                Updates.set("status", MeasurementStatus.APPROVED.name),
                Updates.set("invoiceId", null),
                Updates.set("invoicedAt", null),
            ),
        ).modifiedCount.toInt()

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    companion object {
        const val COLLECTION = "building.measurements"
        const val MAX_LIST = 1000
    }
}

private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
    value?.let { raw -> enumValues<E>().firstOrNull { it.name == raw } } ?: default

private inline fun <reified E : Enum<E>> enumOrNull(value: String?): E? =
    value?.let { raw -> enumValues<E>().firstOrNull { it.name == raw } }

private fun Document.day(field: String): LocalDate? = getString(field)?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private fun Document.instantOrNull(field: String): Instant? = getDate(field)?.toInstantValue()

private fun Document.docs(field: String): List<Document> = getList(field, Document::class.java).orEmpty()

private fun Document.int(field: String): Int? = (get(field) as? Number)?.toInt()

private fun Document.double(field: String): Double? = (get(field) as? Number)?.toDouble()

private fun Document.objectId(field: String): ObjectId? = get(field, ObjectId::class.java)

private fun Project.toDocument(): Document = Document("_id", id)
    .append("tenantId", tenantId)
    .append("number", number)
    .append("name", name)
    .append("status", status.name)
    .append("clientId", clientId)
    .append("quoteId", quoteId)
    .append("managerId", managerId)
    .append("address", address)
    .append("description", description)
    .append("plannedStart", plannedStart?.toString())
    .append("plannedEnd", plannedEnd?.toString())
    .append("actualStart", actualStart?.toString())
    .append("actualEnd", actualEnd?.toString())
    .append(
        "contract",
        contract.map {
            Document("id", it.id).append("description", it.description).append("quantity", it.quantity).append("unit", it.unit)
                .append("unitPriceCents", it.unitPriceCents).append("totalCents", it.totalCents)
        },
    )
    .append("retentionPct", retentionPct)
    .append(
        "retentionReleases",
        retentionReleases.map {
            Document("id", it.id).append("invoiceId", it.invoiceId).append("amountCents", it.amountCents)
                .append("releasedBy", it.releasedBy).append("releasedAt", it.releasedAt.toDate())
        },
    )
    .append("manualProgressPct", manualProgressPct)
    .append(
        "phases",
        phases.map {
            Document("id", it.id).append("name", it.name)
                .append("plannedStart", it.plannedStart?.toString()).append("plannedEnd", it.plannedEnd?.toString())
                .append("actualStart", it.actualStart?.toString()).append("actualEnd", it.actualEnd?.toString())
                .append("progressPct", it.progressPct).append("weight", it.weight)
        },
    )
    .append("share", share?.let { Document("token", it.token).append("enabled", it.enabled).append("createdAt", it.createdAt.toDate()) })
    .append("watchedHealth", watchedHealth)
    .append("version", version)
    .append("createdAt", createdAt.toDate())
    .append("updatedAt", updatedAt.toDate())

private fun Document.toProject(): Project {
    val share = get("share", Document::class.java)
    return Project(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        number = getString("number").orEmpty(),
        name = getString("name").orEmpty(),
        status = enumOr(getString("status"), ProjectStatus.PLANNING),
        clientId = objectId("clientId"),
        quoteId = objectId("quoteId"),
        managerId = objectId("managerId"),
        address = getString("address"),
        description = getString("description"),
        plannedStart = day("plannedStart"),
        plannedEnd = day("plannedEnd"),
        actualStart = day("actualStart"),
        actualEnd = day("actualEnd"),
        contract = docs("contract").map {
            ContractLine(
                id = it.getString("id").orEmpty(),
                description = it.getString("description").orEmpty(),
                quantity = it.double("quantity") ?: 0.0,
                unit = it.getString("unit").orEmpty(),
                unitPriceCents = it.getLongValue("unitPriceCents"),
                totalCents = it.getLongValue("totalCents"),
            )
        },
        retentionPct = double("retentionPct") ?: 0.0,
        retentionReleases = docs("retentionReleases").map {
            RetentionRelease(
                id = it.getString("id").orEmpty(),
                invoiceId = it.objectId("invoiceId"),
                amountCents = it.getLongValue("amountCents"),
                releasedBy = it.getString("releasedBy").orEmpty(),
                releasedAt = it.getInstant("releasedAt"),
            )
        },
        manualProgressPct = int("manualProgressPct") ?: 0,
        phases = docs("phases").map {
            ProjectPhase(
                id = it.getString("id").orEmpty(),
                name = it.getString("name").orEmpty(),
                plannedStart = it.day("plannedStart"),
                plannedEnd = it.day("plannedEnd"),
                actualStart = it.day("actualStart"),
                actualEnd = it.day("actualEnd"),
                progressPct = it.int("progressPct") ?: 0,
                weight = it.int("weight"),
            )
        },
        share = share?.let { ProjectShare(it.getString("token").orEmpty(), it.getBoolean("enabled") ?: false, it.getInstant("createdAt")) },
        watchedHealth = getString("watchedHealth"),
        version = (get("version") as? Number)?.toLong() ?: 0,
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
    )
}

private fun SiteLogEntry.toDocument(): Document = Document("_id", id)
    .append("tenantId", tenantId)
    .append("projectId", projectId)
    .append("day", day.toString())
    .append("weather", weather?.name)
    .append("temperatureC", temperatureC)
    .append("workers", workers)
    .append("workDone", workDone)
    .append("equipment", equipment)
    .append("materials", materials)
    .append("visitors", visitors)
    .append("incidents", incidents)
    .append("lostHours", lostHours)
    .append("shared", shared)
    .append("progress", progress.map { Document("phaseId", it.phaseId).append("phaseName", it.phaseName).append("fromPct", it.fromPct).append("toPct", it.toPct) })
    .append("createdBy", createdBy)
    .append("updatedBy", updatedBy)
    .append("createdAt", createdAt.toDate())
    .append("updatedAt", updatedAt.toDate())

private fun Document.toEntry() = SiteLogEntry(
    id = getObjectId("_id"),
    tenantId = getObjectId("tenantId"),
    projectId = getObjectId("projectId"),
    day = LocalDate.parse(getString("day")),
    weather = enumOrNull<Weather>(getString("weather")),
    temperatureC = int("temperatureC"),
    workers = int("workers"),
    workDone = getString("workDone").orEmpty(),
    equipment = getString("equipment"),
    materials = getString("materials"),
    visitors = getString("visitors"),
    incidents = getString("incidents"),
    lostHours = double("lostHours"),
    shared = getBoolean("shared") ?: false,
    progress = docs("progress").map {
        PhaseProgressChange(it.getString("phaseId").orEmpty(), it.getString("phaseName").orEmpty(), it.int("fromPct") ?: 0, it.int("toPct") ?: 0)
    },
    createdBy = getString("createdBy").orEmpty(),
    updatedBy = getString("updatedBy").orEmpty(),
    createdAt = getInstant("createdAt"),
    updatedAt = getInstant("updatedAt"),
)

private fun SitePhoto.toDocument(): Document = Document("_id", id)
    .append("tenantId", tenantId)
    .append("projectId", projectId)
    .append("logId", logId)
    .append("issueId", issueId)
    .append("phaseId", phaseId)
    .append("caption", caption)
    .append("day", day.toString())
    .append("takenAt", takenAt?.toDate())
    .append("shared", shared)
    .append("path", path)
    .append("thumbPath", thumbPath)
    .append("bytes", bytes)
    .append("uploadedBy", uploadedBy)
    .append("createdAt", createdAt.toDate())

private fun Document.toPhoto() = SitePhoto(
    id = getObjectId("_id"),
    tenantId = getObjectId("tenantId"),
    projectId = getObjectId("projectId"),
    logId = objectId("logId"),
    issueId = objectId("issueId"),
    phaseId = getString("phaseId"),
    caption = getString("caption"),
    day = LocalDate.parse(getString("day")),
    takenAt = instantOrNull("takenAt"),
    shared = getBoolean("shared") ?: false,
    path = getString("path").orEmpty(),
    thumbPath = getString("thumbPath").orEmpty(),
    bytes = getLongValue("bytes"),
    uploadedBy = getString("uploadedBy").orEmpty(),
    createdAt = getInstant("createdAt"),
)

private fun SiteIssue.toDocument(): Document = Document("_id", id)
    .append("tenantId", tenantId)
    .append("projectId", projectId)
    .append("number", number)
    .append("title", title)
    .append("description", description)
    .append("location", location)
    .append("kind", kind.name)
    .append("priority", priority.name)
    .append("status", status.name)
    .append("phaseId", phaseId)
    .append("employeeId", employeeId)
    .append("supplierId", supplierId)
    .append("dueDate", dueDate?.toString())
    .append("resolvedAt", resolvedAt?.toDate())
    .append("closedAt", closedAt?.toDate())
    .append("history", history.map { Document("at", it.at.toDate()).append("by", it.by).append("from", it.from?.name).append("to", it.to.name).append("note", it.note) })
    .append("createdBy", createdBy)
    .append("createdAt", createdAt.toDate())
    .append("updatedAt", updatedAt.toDate())

private fun Document.toIssue() = SiteIssue(
    id = getObjectId("_id"),
    tenantId = getObjectId("tenantId"),
    projectId = getObjectId("projectId"),
    number = getString("number").orEmpty(),
    title = getString("title").orEmpty(),
    description = getString("description"),
    location = getString("location"),
    kind = enumOr(getString("kind"), IssueKind.PUNCH),
    priority = enumOr(getString("priority"), IssuePriority.MEDIUM),
    status = enumOr(getString("status"), IssueStatus.OPEN),
    phaseId = getString("phaseId"),
    employeeId = objectId("employeeId"),
    supplierId = objectId("supplierId"),
    dueDate = day("dueDate"),
    resolvedAt = instantOrNull("resolvedAt"),
    closedAt = instantOrNull("closedAt"),
    history = docs("history").map {
        IssueEvent(it.getInstant("at"), it.getString("by").orEmpty(), enumOrNull<IssueStatus>(it.getString("from")), enumOr(it.getString("to"), IssueStatus.OPEN), it.getString("note"))
    },
    createdBy = getString("createdBy").orEmpty(),
    createdAt = getInstant("createdAt"),
    updatedAt = getInstant("updatedAt"),
)

private fun ChangeOrder.toDocument(): Document = Document("_id", id)
    .append("tenantId", tenantId)
    .append("projectId", projectId)
    .append("number", number)
    .append("title", title)
    .append("description", description)
    .append("kind", kind.name)
    .append("items", items.map { it.toDocument() })
    .append("totalCents", totalCents)
    .append("daysImpact", daysImpact)
    .append("status", status.name)
    .append("sentAt", sentAt?.toDate())
    .append("decidedAt", decidedAt?.toDate())
    .append("decidedBy", decidedBy)
    .append("decisionNote", decisionNote)
    .append("decidedVia", decidedVia?.name)
    .append("approval", approval?.let { Document("token", it.token).append("createdAt", it.createdAt.toDate()) })
    .append("createdBy", createdBy)
    .append("createdAt", createdAt.toDate())
    .append("updatedAt", updatedAt.toDate())

private fun Document.toChange() = ChangeOrder(
    id = getObjectId("_id"),
    tenantId = getObjectId("tenantId"),
    projectId = getObjectId("projectId"),
    number = getString("number").orEmpty(),
    title = getString("title").orEmpty(),
    description = getString("description"),
    kind = enumOr(getString("kind"), ChangeOrderKind.ADDITION),
    items = docs("items").map { it.toLineItem() },
    totalCents = getLongValue("totalCents"),
    daysImpact = int("daysImpact") ?: 0,
    status = enumOr(getString("status"), ChangeOrderStatus.DRAFT),
    sentAt = instantOrNull("sentAt"),
    decidedAt = instantOrNull("decidedAt"),
    decidedBy = getString("decidedBy"),
    decisionNote = getString("decisionNote"),
    decidedVia = enumOrNull<DecidedVia>(getString("decidedVia")),
    approval = get("approval", Document::class.java)?.let { ChangeApproval(it.getString("token").orEmpty(), it.getInstant("createdAt")) },
    createdBy = getString("createdBy").orEmpty(),
    createdAt = getInstant("createdAt"),
    updatedAt = getInstant("updatedAt"),
)

private fun Measurement.toDocument(): Document = Document("_id", id)
    .append("tenantId", tenantId)
    .append("projectId", projectId)
    .append("number", number)
    .append("periodEnd", periodEnd.toString())
    .append("status", status.name)
    .append(
        "lines",
        lines.map {
            Document("key", it.key).append("description", it.description).append("unit", it.unit).append("quantity", it.quantity)
                .append("unitPriceCents", it.unitPriceCents).append("totalCents", it.totalCents)
                .append("previousQty", it.previousQty).append("toDateQty", it.toDateQty)
        },
    )
    .append("retentionPct", retentionPct)
    .append("grossCents", grossCents)
    .append("retentionCents", retentionCents)
    .append("netCents", netCents)
    .append("toDateCents", toDateCents)
    .append("notes", notes)
    .append("invoiceId", invoiceId)
    .append("approvedAt", approvedAt?.toDate())
    .append("approvedBy", approvedBy)
    .append("invoicedAt", invoicedAt?.toDate())
    .append("createdBy", createdBy)
    .append("createdAt", createdAt.toDate())
    .append("updatedAt", updatedAt.toDate())

private fun Document.toMeasurement() = Measurement(
    id = getObjectId("_id"),
    tenantId = getObjectId("tenantId"),
    projectId = getObjectId("projectId"),
    number = int("number") ?: 0,
    periodEnd = LocalDate.parse(getString("periodEnd")),
    status = enumOr(getString("status"), MeasurementStatus.DRAFT),
    lines = docs("lines").map {
        MeasurementLine(
            key = it.getString("key").orEmpty(),
            description = it.getString("description").orEmpty(),
            unit = it.getString("unit").orEmpty(),
            quantity = it.double("quantity") ?: 0.0,
            unitPriceCents = it.getLongValue("unitPriceCents"),
            totalCents = it.getLongValue("totalCents"),
            previousQty = it.double("previousQty") ?: 0.0,
            toDateQty = it.double("toDateQty") ?: 0.0,
        )
    },
    retentionPct = double("retentionPct") ?: 0.0,
    grossCents = getLongValue("grossCents"),
    retentionCents = getLongValue("retentionCents"),
    netCents = getLongValue("netCents"),
    toDateCents = getLongValue("toDateCents"),
    notes = getString("notes"),
    invoiceId = objectId("invoiceId"),
    approvedAt = instantOrNull("approvedAt"),
    approvedBy = getString("approvedBy"),
    invoicedAt = instantOrNull("invoicedAt"),
    createdBy = getString("createdBy").orEmpty(),
    createdAt = getInstant("createdAt"),
    updatedAt = getInstant("updatedAt"),
)
