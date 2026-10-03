package com.rfm.edubot.crm

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.crm.model.LineItem
import com.rfm.edubot.crm.model.ServiceSubmission
import com.rfm.edubot.crm.model.ServiceSubmissionStatus
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDate
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

/** What an employee registers, and what an approver may change before approving it. */
data class SubmissionContent(
    val clientId: ObjectId,
    val name: String,
    val notes: String?,
    val items: List<LineItem>,
    val performedAt: LocalDate,
    val catalogItemId: String? = null,
)

fun ServiceSubmission.content() = SubmissionContent(clientId, name, notes, items, performedAt, catalogItemId)

/** An employee changing or withdrawing their own submission. */
sealed interface SubmissionChange {
    data class Done(val submission: ServiceSubmission) : SubmissionChange
    data object NotFound : SubmissionChange
    data object NotPending : SubmissionChange
}

class ServiceSubmissionRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>(COLLECTION)

    suspend fun findById(id: ObjectId): ServiceSubmission? =
        collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toSubmission()

    /** Newest first. */
    suspend fun list(employeeId: ObjectId? = null, status: ServiceSubmissionStatus? = null, limit: Int = 200): List<ServiceSubmission> {
        val filters = listOfNotNull(
            Filters.eq("tenantId", tenantId),
            employeeId?.let { Filters.eq("employeeId", it) },
            status?.let { Filters.eq("status", it.name) },
        )
        return collection.find(Filters.and(filters)).sort(Document("createdAt", -1)).limit(limit).toList().map { it.toSubmission() }
    }

    suspend fun create(employeeId: ObjectId, content: SubmissionContent): ServiceSubmission {
        // Mongo keeps milliseconds, so the record returned here is the one read back later.
        val now = SystemClock.now().toDate().toInstantValue()
        val clean = content.cleaned()
        val submission = ServiceSubmission(
            tenantId = tenantId,
            employeeId = employeeId,
            clientId = clean.clientId,
            name = clean.name,
            notes = clean.notes,
            items = clean.items,
            totalCents = clean.items.sumOf { it.totalCents },
            catalogItemId = clean.catalogItemId,
            performedAt = clean.performedAt,
            createdAt = now,
            updatedAt = now,
        )
        collection.insertOne(submission.toDocument())
        return submission
    }

    /** Rewrites the employee's own submission while it is still pending. */
    suspend fun update(id: ObjectId, employeeId: ObjectId, content: SubmissionContent): SubmissionChange {
        own(id, employeeId)?.let { return it }
        val updated = collection.findOneAndUpdate(
            ownPending(id, employeeId),
            Updates.combine(contentUpdates(content.cleaned()) + Updates.set("updatedAt", SystemClock.now().toDate())),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        return updated?.let { SubmissionChange.Done(it.toSubmission()) } ?: SubmissionChange.NotPending
    }

    /** The employee takes back a submission nobody decided on yet. */
    suspend fun withdraw(id: ObjectId, employeeId: ObjectId): SubmissionChange {
        val existing = findById(id)?.takeIf { it.employeeId == employeeId } ?: return SubmissionChange.NotFound
        if (existing.status != ServiceSubmissionStatus.PENDING) return SubmissionChange.NotPending
        val removed = collection.deleteOne(ownPending(id, employeeId)).deletedCount > 0
        return if (removed) SubmissionChange.Done(existing) else SubmissionChange.NotPending
    }

    /**
     * Claims [submission] as approved into [serviceId], storing [content] as what was approved. Null when it
     * is no longer pending, so two people approving at once create one service between them.
     */
    suspend fun approve(submission: ServiceSubmission, content: SubmissionContent, serviceId: ObjectId, reviewer: String): ServiceSubmission? {
        val clean = content.cleaned()
        val now = SystemClock.now().toDate()
        val doc = collection.findOneAndUpdate(
            pending(submission.id),
            Updates.combine(
                contentUpdates(clean) + listOf(
                    Updates.set("status", ServiceSubmissionStatus.APPROVED.name),
                    Updates.set("serviceId", serviceId),
                    Updates.set("reviewedBy", reviewer),
                    Updates.set("reviewedAt", now),
                    Updates.set("adjusted", clean != submission.content().cleaned()),
                    Updates.set("updatedAt", now),
                ),
            ),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        return doc?.toSubmission()
    }

    /** Undoes an [approve] whose service couldn't be saved, back to what the employee sent. */
    suspend fun reopen(original: ServiceSubmission, serviceId: ObjectId) {
        collection.updateOne(
            scoped(Filters.and(Filters.eq("_id", original.id), Filters.eq("serviceId", serviceId))),
            Updates.combine(
                contentUpdates(original.content()) + listOf(
                    Updates.set("status", ServiceSubmissionStatus.PENDING.name),
                    Updates.unset("serviceId"),
                    Updates.unset("reviewedBy"),
                    Updates.unset("reviewedAt"),
                    Updates.set("adjusted", false),
                    Updates.set("updatedAt", original.updatedAt.toDate()),
                ),
            ),
        )
    }

    /** Null when it is no longer pending. */
    suspend fun reject(id: ObjectId, reason: String?, reviewer: String): ServiceSubmission? {
        val now = SystemClock.now().toDate()
        return collection.findOneAndUpdate(
            pending(id),
            Updates.combine(
                Updates.set("status", ServiceSubmissionStatus.REJECTED.name),
                Updates.set("rejectionReason", reason?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_REASON)),
                Updates.set("reviewedBy", reviewer),
                Updates.set("reviewedAt", now),
                Updates.set("updatedAt", now),
            ),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toSubmission()
    }

    /** Null when [id] is the employee's own pending submission; otherwise why it can't change. */
    private suspend fun own(id: ObjectId, employeeId: ObjectId): SubmissionChange? {
        val existing = findById(id)?.takeIf { it.employeeId == employeeId } ?: return SubmissionChange.NotFound
        return if (existing.status == ServiceSubmissionStatus.PENDING) null else SubmissionChange.NotPending
    }

    private fun pending(id: ObjectId): Bson = scoped(Filters.and(Filters.eq("_id", id), Filters.eq("status", ServiceSubmissionStatus.PENDING.name)))

    private fun ownPending(id: ObjectId, employeeId: ObjectId): Bson = Filters.and(pending(id), Filters.eq("employeeId", employeeId))

    private fun contentUpdates(content: SubmissionContent): List<Bson> = listOf(
        Updates.set("clientId", content.clientId),
        Updates.set("name", content.name),
        Updates.set("notes", content.notes),
        Updates.set("items", content.items.map { it.toDocument() }),
        Updates.set("totalCents", content.items.sumOf { it.totalCents }),
        Updates.set("catalogItemId", content.catalogItemId),
        Updates.set("performedAt", content.performedAt.toString()),
    )

    private fun SubmissionContent.cleaned() = copy(
        name = name.trim().take(MAX_NAME),
        notes = notes?.trim()?.takeIf { it.isNotEmpty() },
        catalogItemId = catalogItemId?.trim()?.takeIf { it.isNotEmpty() },
    )

    private fun Document.toSubmission() = ServiceSubmission(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        employeeId = getObjectId("employeeId"),
        clientId = getObjectId("clientId"),
        name = getString("name").orEmpty(),
        notes = getString("notes"),
        items = getList("items", Document::class.java).orEmpty().map { it.toLineItem() },
        totalCents = getLongValue("totalCents"),
        catalogItemId = getString("catalogItemId"),
        performedAt = LocalDate.parse(getString("performedAt")),
        status = runCatching { ServiceSubmissionStatus.valueOf(getString("status")) }.getOrDefault(ServiceSubmissionStatus.PENDING),
        serviceId = get("serviceId", ObjectId::class.java),
        reviewedBy = getString("reviewedBy"),
        reviewedAt = getDate("reviewedAt")?.toInstantValue(),
        rejectionReason = getString("rejectionReason"),
        adjusted = getBoolean("adjusted") ?: false,
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
    )

    private fun ServiceSubmission.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("employeeId", employeeId)
        .append("clientId", clientId)
        .append("name", name)
        .append("notes", notes)
        .append("items", items.map { it.toDocument() })
        .append("totalCents", totalCents)
        .append("catalogItemId", catalogItemId)
        .append("performedAt", performedAt.toString())
        .append("status", status.name)
        .append("adjusted", adjusted)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    companion object {
        const val COLLECTION = "crm.service_submissions"
        const val MAX_NAME = 160
        const val MAX_REASON = 500
    }
}
