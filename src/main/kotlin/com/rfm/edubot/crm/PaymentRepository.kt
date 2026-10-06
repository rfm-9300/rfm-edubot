package com.rfm.edubot.crm

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.crm.model.LineItem
import com.rfm.edubot.crm.model.Payment
import com.rfm.edubot.crm.model.PaymentStatus
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.EventPayloads
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.put
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

class PaymentRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("crm.payments")
    private val sequences = SequenceRepository(mongoModule, tenantId)
    private val events = DomainEventLog(mongoModule)

    suspend fun findById(id: ObjectId): Payment? =
        collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toPayment()

    suspend fun list(
        supplierId: ObjectId? = null,
        employeeId: ObjectId? = null,
        status: PaymentStatus? = null,
        clientId: ObjectId? = null,
    ): List<Payment> {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId))
        supplierId?.let { filters.add(Filters.eq("supplierId", it)) }
        employeeId?.let { filters.add(Filters.eq("employeeId", it)) }
        status?.let { filters.add(Filters.eq("status", it.name)) }
        clientId?.let { filters.add(Filters.eq("clientId", it)) }
        return collection.find(Filters.and(filters))
            .sort(Document("dueDate", 1).append("createdAt", -1))
            .limit(200)
            .toList()
            .map { it.toPayment() }
    }

    suspend fun create(
        supplierId: ObjectId?,
        employeeId: ObjectId?,
        items: List<LineItem>,
        dueDate: LocalDate,
        notes: String?,
        clientId: ObjectId? = null,
    ): Payment {
        val now = SystemClock.now()
        val payment = Payment(
            tenantId = tenantId,
            number = "PAG-${sequences.next("payment_number").toString().padStart(3, '0')}",
            supplierId = supplierId,
            employeeId = employeeId,
            clientId = clientId,
            items = items,
            notes = notes?.trim()?.takeIf { it.isNotBlank() },
            dueDate = dueDate,
            totalCents = items.sumOf { it.totalCents },
            createdAt = now,
            updatedAt = now,
        )
        collection.insertOne(payment.toDocument())
        events.append(tenantId, DomainEventTypes.PAYMENT_CREATED, SubjectRef.of(SubjectTypes.PAYMENT, payment.id), EventPayloads.payment(payment), payment.relatedRefs())
        return payment
    }

    /** A paid or cancelled payment comes back unchanged. */
    suspend fun markPaid(id: ObjectId): Payment? {
        val now = Instant.fromEpochMilliseconds(SystemClock.now().toEpochMilliseconds())
        val before = collection.findOneAndUpdate(
            scoped(Filters.and(Filters.eq("_id", id), Filters.`in`("status", OPEN_STATUSES))),
            Updates.combine(
                Updates.set("status", PaymentStatus.PAID.name),
                Updates.set("paidAt", now.toDate()),
                Updates.set("updatedAt", now.toDate()),
            ),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE),
        )?.toPayment() ?: return findById(id)
        val payment = before.copy(status = PaymentStatus.PAID, paidAt = now, updatedAt = now)
        events.append(
            tenantId, DomainEventTypes.PAYMENT_PAID, SubjectRef.of(SubjectTypes.PAYMENT, payment.id),
            EventPayloads.payment(payment) { put("from", before.status.name) }, payment.relatedRefs(),
        )
        return payment
    }

    /** Cancelling keeps the payment and its number, as cancelled. A paid one can only be deleted. */
    suspend fun cancel(id: ObjectId): PaymentCancel {
        val cancelled = collection.findOneAndUpdate(
            scoped(Filters.and(Filters.eq("_id", id), Filters.`in`("status", OPEN_STATUSES))),
            Updates.combine(Updates.set("status", PaymentStatus.CANCELLED.name), Updates.set("updatedAt", SystemClock.now().toDate())),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toPayment()
        if (cancelled != null) return PaymentCancel.Done(cancelled)
        return when (findById(id)?.status) {
            null -> PaymentCancel.NotFound
            PaymentStatus.PAID -> PaymentCancel.Refused("payment_paid")
            else -> PaymentCancel.Refused("payment_cancelled")
        }
    }

    /** Removes the payment for good and returns what it was. */
    suspend fun delete(id: ObjectId): Payment? = collection.findOneAndDelete(scoped(Filters.eq("_id", id)))?.toPayment()

    private fun Payment.relatedRefs(): List<SubjectRef> = listOfNotNull(
        clientId?.let { SubjectRef.of(SubjectTypes.CLIENT, it) },
        supplierId?.let { SubjectRef.of(SubjectTypes.SUPPLIER, it) },
        employeeId?.let { SubjectRef.of(SubjectTypes.EMPLOYEE, it) },
    )

    /** Links the payment to [clientId], or unlinks it when null. */
    suspend fun setClient(id: ObjectId, clientId: ObjectId?): Payment? {
        val doc = collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(
                Updates.set("clientId", clientId),
                Updates.set("updatedAt", SystemClock.now().toDate()),
            ),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        return doc?.toPayment()
    }

    private fun Document.toPayment() = Payment(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        number = getString("number"),
        supplierId = get("supplierId", ObjectId::class.java),
        employeeId = get("employeeId", ObjectId::class.java),
        clientId = get("clientId", ObjectId::class.java),
        items = getList("items", Document::class.java).orEmpty().map { it.toLineItem() },
        notes = getString("notes"),
        status = parsePaymentStatus(getString("status")),
        dueDate = LocalDate.parse(getString("dueDate")),
        paidAt = getDate("paidAt")?.toInstantValue(),
        totalCents = getLongValue("totalCents"),
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
    )

    private fun Payment.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("number", number)
        .append("supplierId", supplierId)
        .append("employeeId", employeeId)
        .append("clientId", clientId)
        .append("items", items.map { it.toDocument() })
        .append("notes", notes)
        .append("status", status.name)
        .append("dueDate", dueDate.toString())
        .append("paidAt", paidAt?.toDate())
        .append("totalCents", totalCents)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    companion object {
        private val OPEN_STATUSES = listOf(PaymentStatus.PENDING.name, PaymentStatus.OVERDUE.name)
    }
}

/** What cancelling a payment did: the payment as cancelled, a stable error code refusing it, or no such payment. */
sealed interface PaymentCancel {
    data class Done(val payment: Payment) : PaymentCancel
    data class Refused(val reason: String) : PaymentCancel
    data object NotFound : PaymentCancel
}

private fun parsePaymentStatus(raw: String?): PaymentStatus =
    runCatching { PaymentStatus.valueOf(raw?.trim()?.uppercase().orEmpty()) }.getOrDefault(PaymentStatus.PENDING)
