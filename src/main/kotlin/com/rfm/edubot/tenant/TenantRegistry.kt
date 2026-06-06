package com.rfm.edubot.tenant

import com.rfm.edubot.tenant.model.Tenant
import org.bson.types.ObjectId
import java.util.concurrent.ConcurrentHashMap

class TenantRegistry(private val repo: TenantRepository) {
    private val byPhoneId = ConcurrentHashMap<String, Tenant>()

    suspend fun initialize() {
        byPhoneId.clear()
        repo.findAll().forEach(::put)
    }

    fun byPhoneNumberId(id: String): Tenant? = byPhoneId[id]

    fun put(tenant: Tenant) {
        byPhoneId[tenant.phoneNumberId] = tenant
    }

    fun remove(tenant: Tenant) {
        byPhoneId.remove(tenant.phoneNumberId)
    }

    suspend fun reload(id: ObjectId) {
        repo.findById(id)?.let(::put)
    }
}
