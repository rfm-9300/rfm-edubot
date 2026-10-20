package com.rfm.edubot.dashboard.overview

import com.mongodb.client.model.Filters
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.OverviewHomeLayout
import com.rfm.edubot.dashboard.OverviewMath
import com.rfm.edubot.dashboard.OverviewRecentItemDto
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

/** The minimal layout's feed: the latest events across the cards that are visible. */
internal class RecentOverview(mongo: MongoModule) : OverviewQueries(mongo) {
    /** Latest events across the modules whose Home card is visible, newest first. */
    suspend fun recent(tenantId: ObjectId, modules: Set<String>, hidden: Set<String>): List<OverviewRecentItemDto> = coroutineScope {
        val none = CompletableDeferred(emptyList<Document>())
        fun latest(collection: String, filter: Bson?, sortField: String) = async {
            val scoped = listOfNotNull(Filters.eq("tenantId", tenantId), filter)
            coll(collection).find(Filters.and(scoped)).sort(Document(sortField, -1)).limit(OverviewMath.RECENT_LIMIT).toList()
        }
        val money = OverviewHomeLayout.FINANCEIRO !in hidden
        val invoicesOn = DashboardModules.INVOICES in modules && money
        val paymentsOn = DashboardModules.PAYMENTS in modules && money
        val quotesOn = DashboardModules.QUOTES in modules && OverviewHomeLayout.PIPELINE !in hidden
        val clientsOn = DashboardModules.CLIENTS in modules && OverviewHomeLayout.CUSTOMERS !in hidden
        val bookingsOn = DashboardModules.BOOKINGS in modules && OverviewHomeLayout.CALENDAR !in hidden
        val paidFilter = Filters.and(Filters.eq("status", "PAID"), Filters.ne("paidAt", null))

        val paidInvoices = if (invoicesOn) latest("crm.invoices", paidFilter, "paidAt") else none
        val issuedInvoices = if (invoicesOn) latest("crm.invoices", Filters.ne("status", "CANCELLED"), "createdAt") else none
        val paidPayments = if (paymentsOn) latest("crm.payments", paidFilter, "paidAt") else none
        val acceptedQuotes = if (quotesOn) latest("crm.quotes", Filters.eq("status", "ACEITO"), "updatedAt") else none
        val createdQuotes = if (quotesOn) latest("crm.quotes", null, "createdAt") else none
        val newClients = if (clientsOn) latest("crm.clients", null, "createdAt") else none
        val newBookings = if (bookingsOn) latest("bookings.appointments", Filters.ne("status", "CANCELLED"), "createdAt") else none

        val billed = paidInvoices.await() + issuedInvoices.await() + acceptedQuotes.await() + createdQuotes.await()
        val payments = paidPayments.await()
        val clients = clientNames(tenantId, billed.mapNotNull { it.getObjectIdOrNull("clientId") }.toSet())
        val suppliers = supplierNames(tenantId, payments.mapNotNull { it.getObjectIdOrNull("supplierId") }.toSet())
        val staff = employeeNames(tenantId, payments.mapNotNull { it.getObjectIdOrNull("employeeId") }.toSet())

        fun clientOf(doc: Document) = doc.getObjectIdOrNull("clientId")?.let { clients[it] }
        fun event(kind: String, tab: String, doc: Document, atField: String, name: String?): Pair<Long, OverviewRecentItemDto>? {
            val at = doc.getDate(atField) ?: return null
            return at.time to OverviewRecentItemDto(
                kind = kind,
                tab = tab,
                id = doc.getObjectId("_id").toHexString(),
                number = doc.getString("number"),
                name = name?.takeIf { it.isNotBlank() },
                amountCents = doc.get("totalCents")?.asLong(),
                at = Instant.fromEpochMilliseconds(at.time).toString(),
            )
        }
        val events = buildList {
            paidInvoices.await().forEach { add(event(OverviewMath.RECENT_INVOICE_PAID, DashboardModules.INVOICES, it, "paidAt", clientOf(it))) }
            issuedInvoices.await().forEach { add(event(OverviewMath.RECENT_INVOICE_ISSUED, DashboardModules.INVOICES, it, "createdAt", clientOf(it))) }
            payments.forEach { doc ->
                val payee = doc.getObjectIdOrNull("employeeId")?.let { staff[it] }
                    ?: doc.getObjectIdOrNull("supplierId")?.let { suppliers[it] }
                add(event(OverviewMath.RECENT_PAYMENT_PAID, DashboardModules.PAYMENTS, doc, "paidAt", payee))
            }
            acceptedQuotes.await().forEach { add(event(OverviewMath.RECENT_QUOTE_ACCEPTED, DashboardModules.QUOTES, it, "updatedAt", clientOf(it))) }
            createdQuotes.await().forEach { add(event(OverviewMath.RECENT_QUOTE_CREATED, DashboardModules.QUOTES, it, "createdAt", clientOf(it))) }
            newClients.await().forEach { add(event(OverviewMath.RECENT_CLIENT_CREATED, DashboardModules.CLIENTS, it, "createdAt", it.getString("name"))) }
            newBookings.await().forEach { add(event(OverviewMath.RECENT_BOOKING_CREATED, DashboardModules.BOOKINGS, it, "createdAt", it.getString("contactName"))) }
        }.filterNotNull()
        events.sortedByDescending { it.first }.take(OverviewMath.RECENT_LIMIT).map { it.second }
    }
}
