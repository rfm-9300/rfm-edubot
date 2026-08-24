package com.rfm.edubot.crm

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.LineItem
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.crm.StandardItem
import com.rfm.edubot.crm.StandardItems
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.util.Date

class ClientRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("crm.clients")
    private val sequences = SequenceRepository(mongoModule, tenantId)

    suspend fun findById(id: ObjectId): Client? = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toClient()

    suspend fun search(query: String): List<Client> {
        val trimmed = query.trim()
        val filter = if (trimmed.isBlank()) {
            Filters.eq("tenantId", tenantId)
        } else {
            scoped(
                Filters.or(
                    Filters.regex("number", ".*${Regex.escape(trimmed)}.*", "i"),
                    Filters.regex("name", ".*${Regex.escape(trimmed)}.*", "i"),
                    Filters.regex("phone", ".*${Regex.escape(trimmed)}.*", "i"),
                    Filters.regex("address", ".*${Regex.escape(trimmed)}.*", "i"),
                )
            )
        }
        return collection.find(filter).limit(20).toList().map { it.toClient() }
    }

    suspend fun update(id: ObjectId, name: String, phone: String, address: String?): Client? {
        val now = SystemClock.now()
        val doc = collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(
                Updates.set("name", name.trim()),
                Updates.set("phone", phone.trim()),
                Updates.set("address", address?.trim()?.takeIf { it.isNotBlank() }),
                Updates.set("updatedAt", now.toDate()),
            ),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        return doc?.toClient()
    }

    suspend fun create(name: String, phone: String, address: String? = null): Client {
        val now = SystemClock.now()
        val number = "CLT-${sequences.next("client_number").toString().padStart(3, '0')}"
        val client = Client(tenantId = tenantId, number = number, name = name.trim(), phone = phone.trim(), address = address?.trim()?.takeIf { it.isNotBlank() }, createdAt = now, updatedAt = now)
        collection.insertOne(client.toDocument())
        return client
    }

    private fun Document.toClient() = Client(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        number = getString("number") ?: "CLT-???",
        name = getString("name"),
        phone = getString("phone"),
        address = getString("address"),
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
    )

    private fun Client.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("number", number)
        .append("name", name)
        .append("phone", phone)
        .append("address", address)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}

class QuoteRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("crm.quotes")
    private val sequences = SequenceRepository(mongoModule, tenantId)

    suspend fun findById(id: ObjectId): Quote? = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toQuote()

    suspend fun list(clientId: ObjectId? = null, status: QuoteStatus? = null): List<Quote> {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId))
        clientId?.let { filters.add(Filters.eq("clientId", it)) }
        status?.let { filters.add(Filters.eq("status", it.name)) }
        val filter = Filters.and(filters)
        return collection.find(filter).sort(Document("createdAt", -1)).limit(100).toList().map { it.toQuote() }
    }

    suspend fun create(clientId: ObjectId, items: List<LineItem>, notes: String?, validUntil: LocalDate?): Quote {
        val now = SystemClock.now()
        val quote = Quote(
            tenantId = tenantId,
            number = "ORC-${sequences.next("quote_number").toString().padStart(3, '0')}",
            clientId = clientId,
            items = items,
            notes = notes,
            totalCents = items.sumOf { it.totalCents },
            validUntil = validUntil,
            createdAt = now,
            updatedAt = now,
        )
        collection.insertOne(quote.toDocument())
        return quote
    }

    suspend fun findByNumber(number: String): Quote? =
        collection.find(scoped(Filters.regex("number", ".*${Regex.escape(number.trim())}.*", "i"))).firstOrNull()?.toQuote()

    suspend fun update(id: ObjectId, items: List<LineItem>?, notes: String?, validUntil: LocalDate?, status: QuoteStatus?): Quote? {
        val now = SystemClock.now()
        val ops = mutableListOf<Bson>(Updates.set("updatedAt", now.toDate()))
        items?.let {
            ops.add(Updates.set("items", it.map { item -> item.toDocument() }))
            ops.add(Updates.set("totalCents", it.sumOf { item -> item.totalCents }))
        }
        notes?.let { ops.add(Updates.set("notes", it)) }
        validUntil?.let { ops.add(Updates.set("validUntil", it.toString())) }
        status?.let { ops.add(Updates.set("status", it.name)) }
        val doc = collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(ops),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        return doc?.toQuote()
    }

    suspend fun setPdfPath(id: ObjectId, pdfPath: String) {
        collection.updateOne(scoped(Filters.eq("_id", id)), Updates.set("pdfPath", pdfPath))
    }

    data class ClientQuoteTotal(val clientId: ObjectId, val clientName: String, val clientNumber: String, val totalCents: Long, val quoteCount: Int)

    suspend fun sumByClient(): List<ClientQuoteTotal> {
        val pipeline = listOf(
            Document("\$match", Document("tenantId", tenantId)),
            Document("\$group", Document("_id", "\$clientId")
                .append("totalCents", Document("\$sum", "\$totalCents"))
                .append("quoteCount", Document("\$sum", 1))),
            Document("\$lookup", Document("from", "crm.clients")
                .append("let", Document("clientId", "\$_id"))
                .append("pipeline", listOf(Document("\$match", Document("\$expr", Document("\$and", listOf(
                    Document("\$eq", listOf("\$_id", "\$\$clientId")),
                    Document("\$eq", listOf("\$tenantId", tenantId)),
                ))))))
                .append("as", "client")),
            Document("\$unwind", "\$client"),
            Document("\$sort", Document("totalCents", -1)),
        )
        return collection.aggregate<Document>(pipeline).toList().map { doc ->
            val clientDoc = doc.get("client", Document::class.java)!!
            ClientQuoteTotal(
                clientId = doc.getObjectId("_id"),
                clientName = clientDoc.getString("name") ?: "?",
                clientNumber = clientDoc.getString("number") ?: "?",
                totalCents = doc.getLongValue("totalCents"),
                quoteCount = doc.getInteger("quoteCount") ?: 0,
            )
        }
    }

    private fun Document.toQuote() = Quote(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        number = getString("number"),
        clientId = getObjectId("clientId"),
        items = getList("items", Document::class.java).map { it.toLineItem() },
        notes = getString("notes"),
        status = parseQuoteStatus(getString("status")),
        totalCents = getLongValue("totalCents"),
        validUntil = getString("validUntil")?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
        pdfPath = getString("pdfPath"),
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
    )

    private fun Quote.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("number", number)
        .append("clientId", clientId)
        .append("items", items.map { it.toDocument() })
        .append("notes", notes)
        .append("status", status.name)
        .append("totalCents", totalCents)
        .append("validUntil", validUntil?.toString())
        .append("pdfPath", pdfPath)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}

class InvoiceRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("crm.invoices")
    private val sequences = SequenceRepository(mongoModule, tenantId)

    suspend fun findById(id: ObjectId): Invoice? = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toInvoice()

    suspend fun list(clientId: ObjectId? = null, status: InvoiceStatus? = null): List<Invoice> {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId))
        clientId?.let { filters.add(Filters.eq("clientId", it)) }
        status?.let { filters.add(Filters.eq("status", it.name)) }
        val filter = Filters.and(filters)
        return collection.find(filter).sort(Document("createdAt", -1)).limit(100).toList().map { it.toInvoice() }
    }

    suspend fun create(clientId: ObjectId, quoteId: ObjectId?, items: List<LineItem>, dueDate: LocalDate): Invoice {
        val now = SystemClock.now()
        val invoice = Invoice(
            tenantId = tenantId,
            number = "FAT-${sequences.next("invoice_number").toString().padStart(3, '0')}",
            clientId = clientId,
            quoteId = quoteId,
            items = items,
            dueDate = dueDate,
            totalCents = items.sumOf { it.totalCents },
            createdAt = now,
            updatedAt = now,
        )
        collection.insertOne(invoice.toDocument())
        return invoice
    }

    suspend fun setPdfPath(id: ObjectId, pdfPath: String) {
        collection.updateOne(scoped(Filters.eq("_id", id)), Updates.set("pdfPath", pdfPath))
    }

    data class ClientInvoiceTotal(val clientId: ObjectId, val clientName: String, val clientNumber: String, val totalCents: Long, val invoiceCount: Int, val paidCents: Long)

    suspend fun sumByClient(): List<ClientInvoiceTotal> {
        val pipeline = listOf(
            Document("\$match", Document("tenantId", tenantId)),
            Document("\$group", Document("_id", "\$clientId")
                .append("totalCents", Document("\$sum", "\$totalCents"))
                .append("paidCents", Document("\$sum", Document("\$cond", listOf(
                    Document("\$eq", listOf("\$status", "PAID")), "\$totalCents", 0L
                ))))
                .append("invoiceCount", Document("\$sum", 1))),
            Document("\$lookup", Document("from", "crm.clients")
                .append("let", Document("clientId", "\$_id"))
                .append("pipeline", listOf(Document("\$match", Document("\$expr", Document("\$and", listOf(
                    Document("\$eq", listOf("\$_id", "\$\$clientId")),
                    Document("\$eq", listOf("\$tenantId", tenantId)),
                ))))))
                .append("as", "client")),
            Document("\$unwind", "\$client"),
            Document("\$sort", Document("totalCents", -1)),
        )
        return collection.aggregate<Document>(pipeline).toList().map { doc ->
            val clientDoc = doc.get("client", Document::class.java)!!
            ClientInvoiceTotal(
                clientId = doc.getObjectId("_id"),
                clientName = clientDoc.getString("name") ?: "?",
                clientNumber = clientDoc.getString("number") ?: "?",
                totalCents = doc.getLongValue("totalCents"),
                invoiceCount = doc.getInteger("invoiceCount") ?: 0,
                paidCents = doc.getLongValue("paidCents"),
            )
        }
    }

    suspend fun markPaid(id: ObjectId): Invoice? {
        val now = SystemClock.now()
        val doc = collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(Updates.set("status", InvoiceStatus.PAID.name), Updates.set("paidAt", now.toDate()), Updates.set("updatedAt", now.toDate())),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        return doc?.toInvoice()
    }

    private fun Document.toInvoice() = Invoice(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        number = getString("number"),
        clientId = getObjectId("clientId"),
        quoteId = get("quoteId", ObjectId::class.java),
        items = getList("items", Document::class.java).map { it.toLineItem() },
        status = parseInvoiceStatus(getString("status")),
        dueDate = LocalDate.parse(getString("dueDate")),
        paidAt = getDate("paidAt")?.toInstantValue(),
        totalCents = getLongValue("totalCents"),
        pdfPath = getString("pdfPath"),
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
    )

    private fun Invoice.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("number", number)
        .append("clientId", clientId)
        .append("quoteId", quoteId)
        .append("items", items.map { it.toDocument() })
        .append("status", status.name)
        .append("dueDate", dueDate.toString())
        .append("paidAt", paidAt?.toDate())
        .append("totalCents", totalCents)
        .append("pdfPath", pdfPath)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}

class StandardItemRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("crm.standard_items")

    suspend fun seedDefaults() {
        if (collection.countDocuments(Filters.eq("tenantId", tenantId)) > 0) return
        collection.insertMany(StandardItems.defaults.map { it.toDocument() })
    }

    suspend fun search(query: String? = null, type: String? = null): List<StandardItem> {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId))
        type?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { filters.add(Filters.eq("type", it)) }
        query?.trim()?.takeIf { it.isNotBlank() }?.let { term ->
            filters.add(
                Filters.or(
                    Filters.regex("description", ".*${Regex.escape(term)}.*", "i"),
                    Filters.regex("category", ".*${Regex.escape(term)}.*", "i"),
                    Filters.regex("id", ".*${Regex.escape(term)}.*", "i"),
                )
            )
        }
        val filter = Filters.and(filters)
        return collection.find(filter).sort(Document("type", 1).append("category", 1).append("description", 1)).toList().map { it.toStandardItem() }
    }

    suspend fun create(item: StandardItem): StandardItem {
        collection.insertOne(item.toDocument())
        return item
    }

    suspend fun update(id: String, item: StandardItem): StandardItem? {
        val result = collection.findOneAndUpdate(
            scoped(Filters.eq("id", id)),
            Updates.combine(
                Updates.set("type", item.type),
                Updates.set("category", item.category),
                Updates.set("description", item.description),
                Updates.set("unit", item.unit),
                Updates.set("defaultUnitPriceEur", item.defaultUnitPriceEur),
            ),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        return result?.toStandardItem()
    }

    suspend fun delete(id: String): Boolean = collection.deleteOne(scoped(Filters.eq("id", id))).deletedCount > 0

    private fun Document.toStandardItem() = StandardItem(
        id = getString("id"),
        type = getString("type"),
        category = getString("category"),
        description = getString("description"),
        unit = getString("unit"),
        defaultUnitPriceEur = getDoubleValue("defaultUnitPriceEur"),
    )

    private fun StandardItem.toDocument() = Document("id", id)
        .append("tenantId", tenantId)
        .append("type", type)
        .append("category", category)
        .append("description", description)
        .append("unit", unit)
        .append("defaultUnitPriceEur", defaultUnitPriceEur)

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}

private class SequenceRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("crm.sequences")

    suspend fun next(name: String): Long {
        val doc = collection.findOneAndUpdate(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("name", name)),
            Updates.combine(
                Updates.inc("value", 1L),
                Updates.setOnInsert("tenantId", tenantId),
                Updates.setOnInsert("name", name),
            ),
            FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER),
        ) ?: error("Could not increment sequence $name")
        return doc.getLongValue("value")
    }
}

fun lineItem(description: String, quantity: Double = 1.0, unitPriceEur: Double): LineItem {
    val unitPriceCents = (unitPriceEur * 100).toLong()
    return LineItem(
        description = description,
        quantity = quantity,
        unit = "",
        unitPriceCents = unitPriceCents,
        totalCents = (quantity * unitPriceCents).toLong(),
    )
}

private fun parseQuoteStatus(raw: String?): QuoteStatus =
    runCatching { QuoteStatus.valueOf(raw?.trim()?.uppercase().orEmpty()) }.getOrDefault(QuoteStatus.PENDENTE)

private fun parseInvoiceStatus(raw: String?): InvoiceStatus =
    runCatching { InvoiceStatus.valueOf(raw?.trim()?.uppercase().orEmpty()) }.getOrDefault(InvoiceStatus.PENDING)

private fun Document.toLineItem() = LineItem(
    description = getString("description"),
    quantity = getDoubleValue("quantity"),
    unit = getString("unit") ?: "",
    unitPriceCents = getLongValue("unitPriceCents"),
    totalCents = getLongValue("totalCents"),
)

private fun LineItem.toDocument() = Document("description", description)
    .append("quantity", quantity)
    .append("unit", unit)
    .append("unitPriceCents", unitPriceCents)
    .append("totalCents", totalCents)

private fun Document.getLongValue(field: String): Long = when (val value = get(field)) {
    is Long -> value
    is Int -> value.toLong()
    is Double -> value.toLong()
    else -> 0L
}

private fun Document.getDoubleValue(field: String): Double = when (val value = get(field)) {
    is Double -> value
    is Int -> value.toDouble()
    is Long -> value.toDouble()
    else -> 0.0
}

private fun Document.getInstant(field: String): Instant = getDate(field).toInstantValue()

private fun Date.toInstantValue(): Instant = Instant.fromEpochMilliseconds(time)

private fun Instant.toDate(): Date = Date(toEpochMilliseconds())
