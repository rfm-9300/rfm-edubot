package com.rfm.edubot.tenant

import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.Platform
import org.bson.types.ObjectId
import java.util.concurrent.ConcurrentHashMap

class TenantRegistry(private val repo: TenantRepository) {
    private val byBinding = ConcurrentHashMap<String, Tenant>()

    suspend fun initialize() {
        byBinding.clear()
        repo.findAll().forEach(::put)
    }

    fun byPhoneNumberId(id: String): Tenant? = byExternalId(Platform.WHATSAPP, id)

    fun byExternalId(platform: Platform, externalId: String): Tenant? = byBinding[key(platform, externalId)]

    fun put(tenant: Tenant) {
        tenant.channels.forEach { byBinding[key(it.platform, it.externalId)] = tenant }
    }

    fun remove(tenant: Tenant) {
        tenant.channels.forEach { byBinding.remove(key(it.platform, it.externalId)) }
    }

    suspend fun reload(id: ObjectId) {
        repo.findById(id)?.let(::put)
    }

    private fun key(platform: Platform, externalId: String) = "${platform.name}:$externalId"
}
