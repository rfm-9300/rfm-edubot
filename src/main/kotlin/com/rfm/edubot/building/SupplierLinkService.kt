package com.rfm.edubot.building

import com.rfm.edubot.building.model.SupplierLink
import org.bson.types.ObjectId

/**
 * A supplier's link to the site issues assigned to them (`/app/share/issues/{token}`), across the company's projects:
 * made once and kept, renewed (the old address stops working), or turned off. Archiving the supplier closes it too.
 */
class SupplierLinkService(private val records: BuildingRecords) {
    suspend fun find(supplierId: ObjectId): SupplierLink? = records.supplierLinks.forSupplier(supplierId)

    /** The supplier's link, made now when it has none. */
    suspend fun open(supplierId: ObjectId, stamp: Stamp): Outcome<SupplierLink> {
        val supplier = records.suppliers.findById(supplierId)?.takeIf { it.archivedAt == null } ?: return Outcome.NotFound
        records.supplierLinks.forSupplier(supplier.id)?.let { return Outcome.Done(it) }
        val link = records.supplierLinks.put(supplier.id, secretToken(), stamp.who, stamp.now)
        records.events.supplierLinkChanged(supplier.id, supplier.name, ShareActions.ON)
        return Outcome.Done(link)
    }

    /** A new address for the same link; whoever had the old one can't open it any more. */
    suspend fun rotate(supplierId: ObjectId, stamp: Stamp): Outcome<SupplierLink> {
        val supplier = records.suppliers.findById(supplierId)?.takeIf { it.archivedAt == null } ?: return Outcome.NotFound
        val link = records.supplierLinks.put(supplier.id, secretToken(), stamp.who, stamp.now)
        records.events.supplierLinkChanged(supplier.id, supplier.name, ShareActions.ROTATED)
        return Outcome.Done(link)
    }

    suspend fun stop(supplierId: ObjectId): Outcome<Unit> {
        val supplier = records.suppliers.findById(supplierId) ?: return Outcome.NotFound
        if (records.supplierLinks.delete(supplier.id)) records.events.supplierLinkChanged(supplier.id, supplier.name, ShareActions.OFF)
        return Outcome.Done(Unit)
    }
}
