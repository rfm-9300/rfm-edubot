package com.rfm.edubot.tenant.model

import kotlinx.datetime.Instant
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

data class Tenant(
    @BsonId val id: ObjectId = ObjectId(),
    val slug: String,
    val name: String,
    val phoneNumberId: String,
    val agentType: String = "CRM_V1",
    val openrouterModel: String? = null,
    val rateLimitPerHour: Int = 30,
    val rateLimitPerDay: Int = 200,
    val status: TenantStatus = TenantStatus.ACTIVE,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class TenantStatus { ACTIVE, SUSPENDED, DELETED }
