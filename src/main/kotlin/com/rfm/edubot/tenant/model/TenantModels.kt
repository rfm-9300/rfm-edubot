package com.rfm.edubot.tenant.model

import kotlinx.datetime.Instant
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

data class Tenant(
    @BsonId val id: ObjectId = ObjectId(),
    val slug: String,
    val name: String,
    val channels: List<ChannelBinding>,
    val locale: String = TenantLocales.DEFAULT,
    val timezone: String = TenantTimeZones.DEFAULT,
    val openrouterModel: String? = null,
    val enabledModules: List<String>? = null,
    val rateLimitPerHour: Int = 30,
    val rateLimitPerDay: Int = 200,
    val status: TenantStatus = TenantStatus.ACTIVE,
    /** Branding + copy used when generating quote/invoice PDFs. */
    val documentTemplate: DocumentTemplate = DocumentTemplate(),
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val phoneNumberId: String
        get() = binding(Platform.WHATSAPP)?.externalId.orEmpty()

    fun binding(platform: Platform): ChannelBinding? = channels.firstOrNull { it.platform == platform }
}

/**
 * Per-tenant quote/invoice PDF template.
 * Blank fields fall back to built-in defaults in [com.rfm.edubot.crm.PdfGenerator].
 */
data class DocumentTemplate(
    val companyName: String = "",
    val tagline: String = "",
    val taxId: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val quoteTitle: String = "",
    val invoiceTitle: String = "",
    val quotePaymentTerms: String = "",
    val invoicePaymentTerms: String = "",
    val termsText: String = "",
    val footerText: String = "",
    /** Absolute filesystem path to an uploaded logo image, if any. */
    val logoPath: String? = null,
) {
    fun withCompanyFallback(tenantName: String): DocumentTemplate =
        if (companyName.isNotBlank()) this else copy(companyName = tenantName)
}

/** Supported UI languages. Mirrors the locales shipped to the web frontends (admin/catalog.*.js). */
object TenantLocales {
    const val DEFAULT = "pt-PT"
    val SUPPORTED = setOf("en", "pt-PT", "es")
    fun normalize(value: String?): String = value?.takeIf { it in SUPPORTED } ?: DEFAULT
}

/** IANA timezones for booking scheduling. Invalid values fall back to the default. */
object TenantTimeZones {
    const val DEFAULT = "Europe/Lisbon"
    fun normalize(value: String?): String {
        val candidate = value?.trim()?.takeIf { it.isNotBlank() } ?: return DEFAULT
        return runCatching {
            kotlinx.datetime.TimeZone.of(candidate)
            candidate
        }.getOrDefault(DEFAULT)
    }
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
