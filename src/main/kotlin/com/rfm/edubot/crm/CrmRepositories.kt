package com.rfm.edubot.crm

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceInstallment
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
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

    /** [customKeys] are the tenant's own fields whose values the query also matches. */
    suspend fun search(query: String, limit: Int = 20, archived: Boolean = false, customKeys: Collection<String> = emptyList()): List<Client> {
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
                ) + customKeys.filter(CustomFields::isKey).map { Filters.regex("customFields.$it", contains, "i") }
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
     * [customFieldChanges] sets custom values by field key, or clears the ones mapped to null; others stay.
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
        customFieldChanges: Map<String, JsonPrimitive?> = emptyMap(),
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
        customFieldChanges.filterKeys(CustomFields::isKey).forEach { (key, value) ->
            updates += if (value == null) Updates.unset("customFields.$key") else Updates.set("customFields.$key", value.toBsonValue())
        }
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
        customFields: Map<String, JsonPrimitive> = emptyMap(),
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
            customFields = customFields.filterKeys(CustomFields::isKey),
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
        customFields = get("customFields", Document::class.java)?.customFieldValues().orEmpty(),
    )

    private fun Document.customFieldValues(): Map<String, JsonPrimitive> = entries.mapNotNull { (key, value) ->
        when (value) {
            is String -> key to JsonPrimitive(value)
            is Boolean -> key to JsonPrimitive(value)
            is Number -> key to JsonPrimitive(value.toDouble())
            else -> null
        }
    }.toMap()

    private fun JsonPrimitive.toBsonValue(): Any = when {
        isString -> content
        booleanOrNull != null -> boolean
        else -> double
    }

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
        .apply { if (customFields.isNotEmpty()) append("customFields", Document(customFields.mapValues { it.value.toBsonValue() })) }

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

    /** Quotes matching every filter given, newest first: created in [createdFrom, createdUntil), up to [limit]. */
    suspend fun search(
        clientId: ObjectId? = null,
        statuses: Collection<QuoteStatus> = emptyList(),
        createdFrom: Instant? = null,
        createdUntil: Instant? = null,
        limit: Int = 1_000,
    ): List<Quote> {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId))
        clientId?.let { filters.add(Filters.eq("clientId", it)) }
        if (statuses.isNotEmpty()) filters.add(Filters.`in`("status", statuses.map { it.name }))
        createdFrom?.let { filters.add(Filters.gte("createdAt", it.toDate())) }
        createdUntil?.let { filters.add(Filters.lt("createdAt", it.toDate())) }
        return collection.find(Filters.and(filters)).sort(Document("createdAt", -1)).limit(limit).toList().map { it.toQuote() }
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

    suspend fun findByNumber(number: String): Invoice? =
        collection.find(scoped(Filters.regex("number", "^${Regex.escape(number.trim())}$", "i"))).firstOrNull()?.toInvoice()

    suspend fun list(clientId: ObjectId? = null, status: InvoiceStatus? = null): List<Invoice> {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId))
        clientId?.let { filters.add(Filters.eq("clientId", it)) }
        status?.let { filters.add(Filters.eq("status", it.name)) }
        val filter = Filters.and(filters)
        return collection.find(filter).sort(Document("createdAt", -1)).limit(100).toList().map { it.toInvoice() }
    }

    /**
     * Invoices matching every filter given, newest first: issued (created) in [issuedFrom, issuedUntil),
     * due between [dueFrom] and [dueTo] inclusive, up to [limit].
     */
    suspend fun search(
        clientId: ObjectId? = null,
        statuses: Collection<InvoiceStatus> = emptyList(),
        issuedFrom: Instant? = null,
        issuedUntil: Instant? = null,
        dueFrom: LocalDate? = null,
        dueTo: LocalDate? = null,
        limit: Int = 1_000,
    ): List<Invoice> {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId))
        clientId?.let { filters.add(Filters.eq("clientId", it)) }
        if (statuses.isNotEmpty()) filters.add(Filters.`in`("status", statuses.map { it.name }))
        issuedFrom?.let { filters.add(Filters.gte("createdAt", it.toDate())) }
        issuedUntil?.let { filters.add(Filters.lt("createdAt", it.toDate())) }
        // Due dates are stored as ISO strings, which sort like the dates they are.
        dueFrom?.let { filters.add(Filters.gte("dueDate", it.toString())) }
        dueTo?.let { filters.add(Filters.lte("dueDate", it.toString())) }
        return collection.find(Filters.and(filters)).sort(Document("createdAt", -1)).limit(limit).toList().map { it.toInvoice() }
    }

    suspend fun create(clientId: ObjectId, quoteId: ObjectId?, items: List<LineItem>, dueDate: LocalDate, taxOfficeCode: String? = null): Invoice {
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
            taxOfficeCode = taxOfficeCode?.trim()?.takeIf { it.isNotBlank() },
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
            Document("\$match", Document("tenantId", tenantId).append("status", Document("\$ne", InvoiceStatus.CANCELLED.name))),
            Document("\$addFields", Document("paidPart", paidPartExpression())),
            Document("\$group", Document("_id", "\$clientId")
                .append("totalCents", Document("\$sum", "\$totalCents"))
                .append("paidCents", Document("\$sum", "\$paidPart"))
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

    /** Pays the whole invoice, open installments included. A paid or cancelled invoice comes back unchanged. */
    suspend fun markPaid(id: ObjectId): Invoice? {
        val before = findById(id) ?: return null
        if (before.status == InvoiceStatus.PAID || before.status == InvoiceStatus.CANCELLED) return before
        val now = nowMillis()
        val ops = mutableListOf(
            Updates.set("status", InvoiceStatus.PAID.name),
            Updates.set("paidAt", now.toDate()),
            Updates.set("updatedAt", now.toDate()),
        )
        if (before.installments.isNotEmpty()) {
            ops += Updates.set("installments", InvoiceInstallments.payAll(before, now).map { it.toDocument() })
        }
        val invoice = collection.findOneAndUpdate(
            scoped(Filters.and(Filters.eq("_id", id), Filters.`in`("status", OPEN_STATUSES))),
            Updates.combine(ops),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toInvoice() ?: return findById(id)
        appendPaid(invoice, before.status)
        return invoice
    }

    /** A blank [code] clears it. */
    suspend fun setTaxOfficeCode(id: ObjectId, code: String?): Invoice? =
        collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(
                Updates.set("taxOfficeCode", code?.trim()?.takeIf { it.isNotBlank() }),
                Updates.set("updatedAt", SystemClock.now().toDate()),
            ),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toInvoice()

    /** Splits what is still to receive into [parts] ([InvoiceInstallments.plan]). */
    suspend fun setInstallments(id: ObjectId, parts: List<InvoiceInstallments.Part>): InvoiceChange =
        change(id) { InvoiceInstallments.plan(it, parts) }

    /** Records installment [index] as received now; receiving the last one pays the invoice. */
    suspend fun receiveInstallment(id: ObjectId, index: Int): InvoiceChange {
        val now = nowMillis()
        return change(id) { InvoiceInstallments.receive(it, index, now) }
    }

    /**
     * Cancelling keeps the invoice and its number, as cancelled. Only one with no money received can be
     * cancelled; a paid one, or one with installments received, can only be deleted.
     */
    suspend fun cancel(id: ObjectId): InvoiceChange {
        val before = findById(id) ?: return InvoiceChange.NotFound
        when {
            before.status == InvoiceStatus.CANCELLED -> return InvoiceChange.Refused("invoice_cancelled")
            before.status == InvoiceStatus.PAID -> return InvoiceChange.Refused("invoice_paid")
            before.paidCents > 0 -> return InvoiceChange.Refused("installments_paid")
        }
        val invoice = collection.findOneAndUpdate(
            scoped(
                Filters.and(
                    Filters.eq("_id", id),
                    Filters.`in`("status", OPEN_STATUSES),
                    Filters.nor(Filters.elemMatch("installments", Filters.exists("paidAt"))),
                ),
            ),
            Updates.combine(Updates.set("status", InvoiceStatus.CANCELLED.name), Updates.set("updatedAt", SystemClock.now().toDate())),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toInvoice() ?: return InvoiceChange.Refused("conflict")
        return InvoiceChange.Done(invoice)
    }

    /** Removes the invoice for good and returns what it was. */
    suspend fun delete(id: ObjectId): Invoice? = collection.findOneAndDelete(scoped(Filters.eq("_id", id)))?.toInvoice()

    /**
     * Applies [decide] to the stored invoice. The write only lands if its status and installments are still the
     * ones [decide] saw, so two people receiving installments at once can't undo each other; it then tries again.
     */
    private suspend fun change(id: ObjectId, decide: (Invoice) -> InvoiceInstallments.Outcome): InvoiceChange {
        repeat(3) {
            val before = findById(id) ?: return InvoiceChange.NotFound
            val outcome = when (val decided = decide(before)) {
                is InvoiceInstallments.Outcome.Refused -> return InvoiceChange.Refused(decided.reason)
                is InvoiceInstallments.Outcome.Ready -> decided
            }
            val unchanged = Filters.and(
                Filters.eq("_id", id),
                Filters.eq("status", before.status.name),
                if (before.installments.isEmpty()) {
                    Filters.or(Filters.exists("installments", false), Filters.eq("installments", emptyList<Document>()))
                } else {
                    Filters.eq("installments", before.installments.map { it.toDocument() })
                },
            )
            val invoice = collection.findOneAndUpdate(
                scoped(unchanged),
                Updates.combine(
                    Updates.set("installments", outcome.installments.map { it.toDocument() }),
                    Updates.set("status", outcome.status.name),
                    Updates.set("dueDate", outcome.dueDate.toString()),
                    Updates.set("paidAt", outcome.paidAt?.toDate()),
                    Updates.set("updatedAt", SystemClock.now().toDate()),
                ),
                FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
            )?.toInvoice() ?: return@repeat
            appendPaid(invoice, before.status)
            return InvoiceChange.Done(invoice)
        }
        return InvoiceChange.Refused("conflict")
    }

    private suspend fun appendPaid(invoice: Invoice, from: InvoiceStatus) {
        if (from == InvoiceStatus.PAID || invoice.status != InvoiceStatus.PAID) return
        events.append(
            tenantId, DomainEventTypes.INVOICE_PAID, SubjectRef.of(SubjectTypes.INVOICE, invoice.id),
            EventPayloads.invoice(invoice) { put("from", from.name) }, invoice.relatedRefs(),
        )
    }

    private fun nowMillis(): Instant = Instant.fromEpochMilliseconds(SystemClock.now().toEpochMilliseconds())

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
        taxOfficeCode = getString("taxOfficeCode"),
        installments = getList("installments", Document::class.java).orEmpty().map { it.toInstallment() },
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
        .append("taxOfficeCode", taxOfficeCode)
        .append("installments", installments.map { it.toDocument() })

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    companion object {
        private val OPEN_STATUSES = listOf(InvoiceStatus.PENDING.name, InvoiceStatus.OVERDUE.name)
    }
}

/** Result of a guarded invoice change: the invoice after it, a stable error code, or no such invoice. */
sealed interface InvoiceChange {
    data class Done(val invoice: Invoice) : InvoiceChange
    data class Refused(val reason: String) : InvoiceChange
    data object NotFound : InvoiceChange
}

/**
 * Per invoice, what was received: the total once paid, otherwise its received installments. For
 * aggregations over `crm.invoices` (`$addFields`, before a `$group`).
 */
internal fun paidPartExpression(): Document {
    val received = Document(
        "\$filter",
        Document("input", Document("\$ifNull", listOf("\$installments", emptyList<Any>())))
            .append("as", "part")
            .append("cond", Document("\$eq", listOf(Document("\$type", "\$\$part.paidAt"), "date"))),
    )
    return Document(
        "\$cond",
        listOf(
            Document("\$eq", listOf("\$status", InvoiceStatus.PAID.name)),
            "\$totalCents",
            Document("\$sum", Document("\$map", Document("input", received).append("as", "part").append("in", "\$\$part.amountCents"))),
        ),
    )
}

internal fun Document.toInstallment() = InvoiceInstallment(
    amountCents = getLongValue("amountCents"),
    dueDate = LocalDate.parse(getString("dueDate")),
    paidAt = getDate("paidAt")?.toInstantValue(),
)

internal fun InvoiceInstallment.toDocument(): Document = Document("amountCents", amountCents)
    .append("dueDate", dueDate.toString())
    .apply { paidAt?.let { append("paidAt", it.toDate()) } }

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
