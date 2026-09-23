package com.rfm.edubot.crm

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.crm.model.LineItem
import com.rfm.edubot.crm.model.Payment
import com.rfm.edubot.crm.model.PaymentStatus
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDate
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

class PaymentRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("crm.payments")
    private val sequences = SequenceRepository(mongoModule, tenantId)

    suspend fun findById(id: ObjectId): Payment? =
        collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toPayment()

    suspend fun list(supplierId: ObjectId? = null, employeeId: ObjectId? = null, status: PaymentStatus? = null): List<Payment> {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId))
        supplierId?.let { filters.add(Filters.eq("supplierId", it)) }
        employeeId?.let { filters.add(Filters.eq("employeeId", it)) }
        status?.let { filters.add(Filters.eq("status", it.name)) }
        return collection.find(Filters.and(filters))
            .sort(Document("dueDate", 1).append("createdAt", -1))
            .limit(200)
            .toList()
            .map { it.toPayment() }
    }

    suspend fun create(supplierId: ObjectId?, employeeId: ObjectId?, items: List<LineItem>, dueDate: LocalDate, notes: String?): Payment {
        val now = SystemClock.now()
        val payment = Payment(
            tenantId = tenantId,
            number = "PAG-${sequences.next("payment_number").toString().padStart(3, '0')}",
            supplierId = supplierId,
            employeeId = employeeId,
            items = items,
            notes = notes?.trim()?.takeIf { it.isNotBlank() },
            dueDate = dueDate,
            totalCents = items.sumOf { it.totalCents },
            createdAt = now,
            updatedAt = now,
        )
        collection.insertOne(payment.toDocument())
        return payment
    }

    suspend fun markPaid(id: ObjectId): Payment? {
        val now = SystemClock.now()
        val doc = collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(
                Updates.set("status", PaymentStatus.PAID.name),
                Updates.set("paidAt", now.toDate()),
                Updates.set("updatedAt", now.toDate()),
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
        .append("items", items.map { it.toDocument() })
        .append("notes", notes)
        .append("status", status.name)
        .append("dueDate", dueDate.toString())
        .append("paidAt", paidAt?.toDate())
        .append("totalCents", totalCents)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}

private fun parsePaymentStatus(raw: String?): PaymentStatus =
    runCatching { PaymentStatus.valueOf(raw?.trim()?.uppercase().orEmpty()) }.getOrDefault(PaymentStatus.PENDING)
