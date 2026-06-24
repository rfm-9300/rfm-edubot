package com.rfm.edubot.tenant.model

import kotlinx.datetime.Instant
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

data class Tenant(
    @BsonId val id: ObjectId = ObjectId(),
    val slug: String,
    val name: String,
    val channels: List<ChannelBinding>,
    val agentType: String = "CRM_V1",
    val locale: String = TenantLocales.DEFAULT,
    val openrouterModel: String? = null,
    val enabledModules: List<String>? = null,
    val rateLimitPerHour: Int = 30,
    val rateLimitPerDay: Int = 200,
    val status: TenantStatus = TenantStatus.ACTIVE,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val phoneNumberId: String
        get() = binding(Platform.WHATSAPP)?.externalId.orEmpty()

    fun binding(platform: Platform): ChannelBinding? = channels.firstOrNull { it.platform == platform }
}

/** Supported UI languages. Mirrors the locales shipped to the web frontends (admin/catalog.*.js). */
object TenantLocales {
    const val DEFAULT = "pt-PT"
    val SUPPORTED = setOf("en", "pt-PT", "es")
    fun normalize(value: String?): String = value?.takeIf { it in SUPPORTED } ?: DEFAULT
}

enum class Platform { WHATSAPP, INSTAGRAM, WEB }

data class ChannelBinding(
    val platform: Platform,
    val externalId: String,
    val accessToken: String = "",
    val displayName: String? = null,
    val wabaId: String? = null,
    val tokenObtainedAt: Instant? = null,
    val source: String? = null,
    // WEB only: browser origins allowed to open the widget WebSocket. Empty = allow any.
    val allowedOrigins: List<String> = emptyList(),
)

enum class TenantStatus { ACTIVE, SUSPENDED, DELETED }
