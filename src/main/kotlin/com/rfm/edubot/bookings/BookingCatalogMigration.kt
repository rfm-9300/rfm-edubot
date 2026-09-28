package com.rfm.edubot.bookings

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.rfm.edubot.crm.MIN_BOOKING_MINUTES
import com.rfm.edubot.crm.StandardItem
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.crm.isService
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.slf4j.LoggerFactory

/**
 * Booking services used to live in their own `bookings.services` list. They now are catalog services
 * (`crm.standard_items`), so this moves each one into its tenant's catalog — onto a same-named catalog
 * service when there is one — and repoints existing bookings and Serviços rows. Each migrated doc is
 * stamped with `catalogItemId`, so the migration runs once per service; nothing is deleted.
 */
class BookingCatalogMigration(private val mongo: MongoModule) {
    private val log = LoggerFactory.getLogger("BookingCatalogMigration")
    private val legacy = mongo.database.getCollection<Document>("bookings.services")
    private val appointments = mongo.database.getCollection<Document>("bookings.appointments")
    private val clientServices = mongo.database.getCollection<Document>("crm.client_services")
    private val tenants = mongo.database.getCollection<Document>("tenants")

    /** Returns how many legacy services were moved. Failures are logged per service and retried on the next start. */
    suspend fun run(): Int {
        val pending = legacy.find(Filters.exists("catalogItemId", false)).toList()
        if (pending.isEmpty()) return 0
        val locales = tenants.find(Filters.`in`("_id", pending.mapNotNull { it.getObjectId("tenantId") }.distinct()))
            .toList()
            .associate { it.getObjectId("_id") to it.getString("locale") }
        var moved = 0
        for (doc in pending) {
            try {
                migrate(doc, locales[doc.getObjectId("tenantId")])
                moved += 1
            } catch (e: Exception) {
                log.warn("Could not move booking service {} into the catalog: {}", doc.getObjectId("_id"), e.message)
            }
        }
        log.info("Moved {} of {} booking services into the catalog", moved, pending.size)
        return moved
    }

    private suspend fun migrate(doc: Document, locale: String?) {
        val legacyId = doc.getObjectId("_id")
        val tenantId = doc.getObjectId("tenantId") ?: return
        val name = doc.getString("name")?.trim().orEmpty().ifBlank { BookingCatalogDefaults.category(locale) }
        val duration = (doc.get("durationMinutes") as? Number)?.toInt()?.coerceAtLeast(MIN_BOOKING_MINUTES)
            ?: BookableServiceRepository.DEFAULT_DURATION_MINUTES
        val active = doc.getBoolean("active") ?: true
        val items = StandardItemRepository(mongo, tenantId)
        val match = items.search().firstOrNull { it.isService() && it.description.trim().equals(name, ignoreCase = true) }
        val catalogId = if (match != null) {
            items.update(match.id, match.copy(durationMinutes = match.durationMinutes ?: duration, bookable = match.bookable || active))
            match.id
        } else {
            val item = StandardItem(
                id = items.freeServiceId(name),
                type = "service",
                category = BookingCatalogDefaults.category(locale),
                description = name,
                unit = BookingCatalogDefaults.unit(locale),
                defaultUnitPriceEur = 0.0,
                durationMinutes = duration,
                bookable = active,
            )
            items.create(item)
            item.id
        }
        appointments.updateMany(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("serviceId", legacyId), Filters.exists("catalogItemId", false)),
            Updates.combine(Updates.set("catalogItemId", catalogId), Updates.set("serviceName", name)),
        )
        clientServices.updateMany(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("bookingServiceId", legacyId), Filters.eq("catalogItemId", null)),
            Updates.set("catalogItemId", catalogId),
        )
        legacy.updateOne(Filters.eq("_id", legacyId), Updates.set("catalogItemId", catalogId))
        log.info("Booking service {} ({}) is now catalog service {} for tenant {}", legacyId, name, catalogId, tenantId)
    }
}
