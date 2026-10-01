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
import java.util.Date
import kotlin.math.roundToLong

class ClientRepository(private val mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("crm.clients")
    private val sequences = SequenceRepository(mongoModule, tenantId)
    private val events = DomainEventLog(mongoModule)

    suspend fun findById(id: ObjectId): Client? = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toClient()

    suspend fun search(query: String, limit: Int = 20, archived: Boolean = false): List<Client> {
        val trimmed = query.trim()
        val text = if (trimmed.isBlank()) {
            null
        } else {
            val contains = ".*${Regex.escape(trimmed)}.*"
            val digits = trimmed.filter(Char::isDigit)
            // "912345678" should find "+351 912 345 678": match the digits with any separators between them.
            val phoneDigits = if (digits.length >= 3 && trimmed.all { it.isDigit() || it in " +-()." }) {
                Filters.regex("phone", digits.toList().joinToString("\\D*"))
            } else {
                null
            }
            Filters.or(
                listOfNotNull(
                    Filters.regex("number", contains, "i"),
                    Filters.regex("name", contains, "i"),
                    Filters.regex("phone", contains, "i"),
                    Filters.regex("address", contains, "i"),
                    Filters.regex("postalCode", contains, "i"),
                    Filters.regex("city", contains, "i"),
                    Filters.regex("contactPerson", contains, "i"),
                    Filters.regex("email", contains, "i"),
                    Filters.regex("taxId", contains, "i"),
                    phoneDigits,
                )
            )
        }
        val filter = Filters.and(listOfNotNull(Filters.eq("tenantId", tenantId), archivedFilter(archived), text))
        return collection.find(filter).limit(limit).toList().map { it.toClient() }
    }

    /** Only a client no quote, invoice, Serviços row, booking or payment refers to can be deleted; the others get archived. */
    suspend fun delete(id: ObjectId): DirectoryDelete =
        collection.deleteUnreferenced(
            mongoModule, tenantId, id, "clientId",
            listOf("crm.quotes", "crm.invoices", "crm.client_services", "bookings.appointments", "crm.payments"),
        )

    suspend fun setArchived(id: ObjectId, archived: Boolean): Client? {
        val client = collection.setArchived(tenantId, id, archived)?.toClient() ?: return null
        val type = if (archived) DomainEventTypes.CLIENT_ARCHIVED else DomainEventTypes.CLIENT_RESTORED
        events.append(tenantId, type, SubjectRef.of(SubjectTypes.CLIENT, client.id), EventPayloads.client(client))
        return client
    }

    /** Agents skip a paused client entirely. */
    suspend fun setAutomationPaused(id: ObjectId, paused: Boolean): Client? =
        collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(Updates.set("automationPaused", paused), Updates.set("updatedAt", SystemClock.now().toDate())),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toClient()

    suspend fun findByEmail(email: String): Client? {
        val normalized = email.trim().lowercase().takeIf { it.contains('@') } ?: return null
        return collection.find(scoped(Filters.regex("email", "^${Regex.escape(normalized)}$", "i"))).firstOrNull()?.toClient()
    }

    /**
     * The client with this phone however it was typed ("+351 912 345 678" = "912345678"): numbers of
     * 9+ digits match on their last 9 digits, shorter ones must match exactly.
     */
    suspend fun findByPhone(phone: String): Client? {
        val digits = phone.filter(Char::isDigit)
        if (digits.length < 6) return null
        val key = if (digits.length >= 9) digits.takeLast(9) else digits
        val pattern = key.toList().joinToString("\\D*") + "\\D*$"
        return collection.find(scoped(Filters.regex("phone", pattern))).limit(20).toList()
            .map { it.toClient() }
            .firstOrNull { client ->
                val stored = client.phone.filter(Char::isDigit)
                if (key.length == 9) stored.length >= 9 && stored.takeLast(9) == key else stored == key
            }
    }

    /**
     * [address] is replaced as given (null clears it). The other details are only written when not null,
     * so callers that don't send them keep the stored values; a blank string clears them.
     */
    suspend fun update(
        id: ObjectId,
        name: String,
        phone: String,
        address: String?,
        email: String? = null,
        taxId: String? = null,
        notes: String? = null,
        postalCode: String? = null,
        city: String? = null,
        contactPerson: String? = null,
    ): Client? {
        val now = SystemClock.now()
        val updates = mutableListOf(
            Updates.set("name", name.trim()),
            Updates.set("phone", phone.trim()),
            Updates.set("address", address.cleaned()),
            Updates.set("updatedAt", now.toDate()),
        )
        email?.let { updates += Updates.set("email", it.cleaned()) }
        taxId?.let { updates += Updates.set("taxId", it.cleaned()) }
        notes?.let { updates += Updates.set("notes", it.cleaned()) }
        postalCode?.let { updates += Updates.set("postalCode", it.cleaned()) }
        city?.let { updates += Updates.set("city", it.cleaned()) }
        contactPerson?.let { updates += Updates.set("contactPerson", it.cleaned()) }
        val doc = collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(updates),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        val client = doc?.toClient() ?: return null
        events.append(tenantId, DomainEventTypes.CLIENT_UPDATED, SubjectRef.of(SubjectTypes.CLIENT, client.id), EventPayloads.client(client))
        return client
    }

    suspend fun create(
        name: String,
        phone: String,
        address: String? = null,
        email: String? = null,
        taxId: String? = null,
        notes: String? = null,
        postalCode: String? = null,
        city: String? = null,
        contactPerson: String? = null,
    ): Client {
        val now = SystemClock.now()
        val number = "CLT-${sequences.next("client_number").toString().padStart(3, '0')}"
        val client = Client(
            tenantId = tenantId,
            number = number,
            name = name.trim(),
            phone = phone.trim(),
            address = address.cleaned(),
            postalCode = postalCode.cleaned(),
            city = city.cleaned(),
            contactPerson = contactPerson.cleaned(),
            email = email.cleaned(),
            taxId = taxId.cleaned(),
            notes = notes.cleaned(),
            createdAt = now,
            updatedAt = now,
        )
        collection.insertOne(client.toDocument())
        events.append(tenantId, DomainEventTypes.CLIENT_CREATED, SubjectRef.of(SubjectTypes.CLIENT, client.id), EventPayloads.client(client))
        return client
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private fun Document.toClient() = Client(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        number = getString("number") ?: "CLT-???",
        name = getString("name"),
        phone = getString("phone"),
        address = getString("address"),
        postalCode = getString("postalCode"),
        city = getString("city"),
        contactPerson = getString("contactPerson"),
        email = getString("email"),
        taxId = getString("taxId"),
        notes = getString("notes"),
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
        archivedAt = getDate("archivedAt")?.toInstantValue(),
        automationPaused = getBoolean("automationPaused") ?: false,
    )

    private fun Client.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("number", number)
        .append("name", name)
        .append("phone", phone)
        .append("address", address)
        .append("postalCode", postalCode)
        .append("city", city)
        .append("contactPerson", contactPerson)
        .append("email", email)
        .append("taxId", taxId)
        .append("notes", notes)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())
        .append("automationPaused", automationPaused)

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}

class QuoteRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("crm.quotes")
    private val sequences = SequenceRepository(mongoModule, tenantId)
    private val events = DomainEventLog(mongoModule)

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
        events.append(
            tenantId, DomainEventTypes.QUOTE_CREATED, SubjectRef.of(SubjectTypes.QUOTE, quote.id),
            EventPayloads.quote(quote), EventPayloads.clientRefs(quote.clientId),
        )
        return quote
    }

    suspend fun findByNumber(number: String): Quote? =
        collection.find(scoped(Filters.regex("number", ".*${Regex.escape(number.trim())}.*", "i"))).firstOrNull()?.toQuote()

    suspend fun update(id: ObjectId, items: List<LineItem>?, notes: String?, validUntil: LocalDate?, status: QuoteStatus?): Quote? {
        val now = SystemClock.now()
        val before = findById(id) ?: return null
        val ops = mutableListOf<Bson>(Updates.set("updatedAt", now.toDate()))
        items?.let {
            ops.add(Updates.set("items", it.map { item -> item.toDocument() }))
            ops.add(Updates.set("totalCents", it.sumOf { item -> item.totalCents }))
        }
        notes?.let { ops.add(Updates.set("notes", it)) }
        validUntil?.let { ops.add(Updates.set("validUntil", it.toString())) }
        status?.let { ops.add(Updates.set("status", it.name)) }
        if (status == QuoteStatus.SENT && before.sentAt == null) ops.add(Updates.set("sentAt", now.toDate()))
        if (status == QuoteStatus.ACEITO && before.acceptedAt == null) ops.add(Updates.set("acceptedAt", now.toDate()))
        val doc = collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(ops),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        val quote = doc?.toQuote() ?: return null
        val subject = SubjectRef.of(SubjectTypes.QUOTE, quote.id)
        val related = EventPayloads.clientRefs(quote.clientId)
        if (status != null && status != before.status) {
            events.append(
                tenantId, DomainEventTypes.QUOTE_STATUS_CHANGED, subject,
                EventPayloads.quote(quote) { put("from", before.status.name); put("to", status.name) }, related,
            )
        }
        if (items != null || notes != null || validUntil != null) {
            events.append(tenantId, DomainEventTypes.QUOTE_UPDATED, subject, EventPayloads.quote(quote), related)
        }
        return quote
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
        sentAt = getDate("sentAt")?.toInstantValue(),
        acceptedAt = getDate("acceptedAt")?.toInstantValue(),
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
        .append("sentAt", sentAt?.toDate())
        .append("acceptedAt", acceptedAt?.toDate())

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}

class InvoiceRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("crm.invoices")
    private val sequences = SequenceRepository(mongoModule, tenantId)
    private val events = DomainEventLog(mongoModule)

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
        events.append(
            tenantId, DomainEventTypes.INVOICE_CREATED, SubjectRef.of(SubjectTypes.INVOICE, invoice.id),
            EventPayloads.invoice(invoice), invoice.relatedRefs(),
        )
        return invoice
    }

    private fun Invoice.relatedRefs(): List<SubjectRef> =
        EventPayloads.clientRefs(clientId) + listOfNotNull(quoteId?.let { SubjectRef.of(SubjectTypes.QUOTE, it) })

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
        val now = Instant.fromEpochMilliseconds(SystemClock.now().toEpochMilliseconds())
        val before = collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(Updates.set("status", InvoiceStatus.PAID.name), Updates.set("paidAt", now.toDate()), Updates.set("updatedAt", now.toDate())),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE),
        )?.toInvoice() ?: return null
        val invoice = before.copy(status = InvoiceStatus.PAID, paidAt = now, updatedAt = now)
        if (before.status != InvoiceStatus.PAID) {
            events.append(
                tenantId, DomainEventTypes.INVOICE_PAID, SubjectRef.of(SubjectTypes.INVOICE, invoice.id),
                EventPayloads.invoice(invoice) { put("from", before.status.name) }, invoice.relatedRefs(),
            )
        }
        return invoice
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
    private val sequences = SequenceRepository(mongoModule, tenantId)

    suspend fun search(query: String? = null, type: String? = null): List<StandardItem> {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId))
        type?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { filters.add(Filters.eq("type", it)) }
        query?.trim()?.takeIf { it.isNotBlank() }?.let { term ->
            val contains = ".*${Regex.escape(term)}.*"
            filters.add(
                Filters.or(
                    Filters.regex("code", contains, "i"),
                    Filters.regex("title", contains, "i"),
                    Filters.regex("description", contains, "i"),
                    Filters.regex("category", contains, "i"),
                    Filters.regex("id", contains, "i"),
                )
            )
        }
        val filter = Filters.and(filters)
        return collection.find(filter).sort(Document("type", 1).append("category", 1).append("title", 1)).toList().map { it.toStandardItem() }
    }

    suspend fun findById(id: String): StandardItem? =
        collection.find(scoped(Filters.eq("id", id))).firstOrNull()?.toStandardItem()

    suspend fun findByCode(code: String): StandardItem? =
        collection.find(scoped(Filters.eq("code", code))).firstOrNull()?.toStandardItem()

    suspend fun findByIds(ids: Collection<String>): List<StandardItem> {
        if (ids.isEmpty()) return emptyList()
        return collection.find(scoped(Filters.`in`("id", ids.toList()))).toList().map { it.toStandardItem() }
    }

    /** Saves a new item. One without a code gets the next free code for its type; a taken code fails on the unique index. */
    suspend fun create(item: StandardItem): StandardItem {
        val saved = item.copy(
            description = item.description.ifBlank { item.title },
            code = item.code?.takeIf { it.isNotBlank() } ?: freeCode(catalogCodePrefix(item.type)),
        )
        collection.insertOne(saved.toDocument())
        return saved
    }

    /** A null code keeps the stored one. */
    suspend fun update(id: String, item: StandardItem): StandardItem? {
        val result = collection.findOneAndUpdate(
            scoped(Filters.eq("id", id)),
            Updates.combine(
                listOfNotNull(
                    Updates.set("type", item.type),
                    Updates.set("category", item.category),
                    Updates.set("title", item.title),
                    Updates.set("description", item.description.ifBlank { item.title }),
                    Updates.set("unit", item.unit),
                    Updates.set("defaultUnitPriceEur", item.defaultUnitPriceEur),
                    Updates.set("durationMinutes", item.durationMinutes),
                    Updates.set("bookable", item.bookable),
                    item.code?.takeIf { it.isNotBlank() }?.let { Updates.set("code", it) },
                )
            ),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        return result?.toStandardItem()
    }

    suspend fun delete(id: String): Boolean = collection.deleteOne(scoped(Filters.eq("id", id))).deletedCount > 0

    /** A `srv-<slug>` id (`mat-<slug>` for materials) not yet used by this tenant. */
    suspend fun freeId(name: String, type: String = "service"): String {
        val prefix = if (isServiceType(type)) "srv" else "mat"
        val base = "$prefix-" + catalogSlug(name).take(40).trimEnd('-').ifBlank { "item" }
        if (findById(base) == null) return base
        for (n in 2..99) {
            val candidate = "$base-$n"
            if (findById(candidate) == null) return candidate
        }
        return "$prefix-${ObjectId().toHexString()}"
    }

    /** The next `PREFIX-nnn` code this tenant doesn't use yet; numbers someone typed by hand are skipped. */
    suspend fun freeCode(prefix: String): String {
        var code: String
        do {
            code = "$prefix-${sequences.next("catalog_${prefix.lowercase()}_code").toString().padStart(3, '0')}"
        } while (findByCode(code) != null)
        return code
    }

    private fun Document.toStandardItem(): StandardItem {
        val description = getString("description").orEmpty()
        return StandardItem(
            id = getString("id"),
            type = getString("type"),
            category = getString("category"),
            description = description,
            unit = getString("unit"),
            defaultUnitPriceEur = getDoubleValue("defaultUnitPriceEur"),
            durationMinutes = (get("durationMinutes") as? Number)?.toInt(),
            bookable = getBoolean("bookable") ?: false,
            title = getString("title")?.takeIf { it.isNotBlank() } ?: description,
            code = getString("code")?.takeIf { it.isNotBlank() },
        )
    }

    private fun StandardItem.toDocument() = Document("id", id)
        .append("tenantId", tenantId)
        .append("code", code)
        .append("type", type)
        .append("category", category)
        .append("title", title)
        .append("description", description.ifBlank { title })
        .append("unit", unit)
        .append("defaultUnitPriceEur", defaultUnitPriceEur)
        .append("durationMinutes", durationMinutes)
        .append("bookable", bookable)

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}

internal class SequenceRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
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

/** Rounded, not truncated: 4.35 * 100 is 434.99999999999994 in floating point. */
fun eurToCents(eur: Double): Long = (eur * 100).roundToLong()

fun lineItem(description: String, quantity: Double = 1.0, unitPriceEur: Double, unit: String = ""): LineItem {
    val unitPriceCents = eurToCents(unitPriceEur)
    return LineItem(
        description = description,
        quantity = quantity,
        unit = unit,
        unitPriceCents = unitPriceCents,
        totalCents = (quantity * unitPriceCents).toLong(),
    )
}

private fun parseQuoteStatus(raw: String?): QuoteStatus =
    runCatching { QuoteStatus.valueOf(raw?.trim()?.uppercase().orEmpty()) }.getOrDefault(QuoteStatus.PENDENTE)

private fun parseInvoiceStatus(raw: String?): InvoiceStatus =
    runCatching { InvoiceStatus.valueOf(raw?.trim()?.uppercase().orEmpty()) }.getOrDefault(InvoiceStatus.PENDING)

internal fun Document.toLineItem() = LineItem(
    description = getString("description"),
    quantity = getDoubleValue("quantity"),
    unit = getString("unit") ?: "",
    unitPriceCents = getLongValue("unitPriceCents"),
    totalCents = getLongValue("totalCents"),
)

internal fun LineItem.toDocument() = Document("description", description)
    .append("quantity", quantity)
    .append("unit", unit)
    .append("unitPriceCents", unitPriceCents)
    .append("totalCents", totalCents)

internal fun Document.getLongValue(field: String): Long = when (val value = get(field)) {
    is Long -> value
    is Int -> value.toLong()
    is Double -> value.toLong()
    else -> 0L
}

internal fun Document.getDoubleValue(field: String): Double = when (val value = get(field)) {
    is Double -> value
    is Int -> value.toDouble()
    is Long -> value.toDouble()
    else -> 0.0
}

internal fun Document.getInstant(field: String): Instant = getDate(field).toInstantValue()

internal fun Date.toInstantValue(): Instant = Instant.fromEpochMilliseconds(time)

internal fun Instant.toDate(): Date = Date(toEpochMilliseconds())
