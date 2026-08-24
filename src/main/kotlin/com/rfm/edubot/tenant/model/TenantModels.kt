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
 * An empty [layout] keeps the historical hardcoded page; a saved layout is used as-is.
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
    /** Hex accent used for pills, headings, and logo fallback. Blank = built-in slate. */
    val accentColor: String = "",
    val showDecor: Boolean = true,
    /** A4 blocks in top-left points. Empty = PdfGenerator's original geometry. */
    val layout: List<DocumentLayoutBlock> = emptyList(),
) {
    fun withCompanyFallback(tenantName: String): DocumentTemplate =
        if (companyName.isNotBlank()) this else copy(companyName = tenantName)
}

/**
 * One region on an A4 page (595×842 pt). Origin is the top-left of the page,
 * matching the dashboard editor. PdfGenerator converts to PDF bottom-left.
 */
data class DocumentLayoutBlock(
    val id: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val visible: Boolean = true,
) {
    fun pdfY(pageH: Float = DocumentLayouts.PAGE_H): Float = pageH - y - h
    fun pdfTop(pageH: Float = DocumentLayouts.PAGE_H): Float = pageH - y
}

object DocumentLayouts {
    const val PAGE_W = 595f
    const val PAGE_H = 842f
    const val DEFAULT_ACCENT = "#96AAB6"

    val IDS = listOf(
        "logo", "contact", "company", "title", "client",
        "items", "totals", "payment", "terms", "footer",
    )

    /** Geometry that matches [com.rfm.edubot.crm.PdfGenerator] when [DocumentTemplate.layout] is empty. */
    val DEFAULT: List<DocumentLayoutBlock> = listOf(
        DocumentLayoutBlock("logo", 403f, 60f, 150f, 52f),
        DocumentLayoutBlock("contact", 42f, 48f, 340f, 44f),
        DocumentLayoutBlock("company", 42f, 96f, 280f, 44f, visible = false),
        DocumentLayoutBlock("title", 42f, 151f, 400f, 48f),
        DocumentLayoutBlock("client", 42f, 214f, 280f, 90f),
        DocumentLayoutBlock("items", 42f, 290f, 511f, 280f),
        DocumentLayoutBlock("totals", 333f, 590f, 220f, 32f),
        DocumentLayoutBlock("payment", 50f, 708f, 320f, 56f),
        DocumentLayoutBlock("terms", 50f, 766f, 320f, 40f),
        DocumentLayoutBlock("footer", 200f, 812f, 353f, 18f),
    )

    fun resolve(template: DocumentTemplate): Map<String, DocumentLayoutBlock> {
        val custom = template.layout.associateBy { it.id }
        return DEFAULT.associateBy { it.id }.mapValues { (id, fallback) -> custom[id] ?: fallback }
    }

    fun sanitize(blocks: List<DocumentLayoutBlock>): List<DocumentLayoutBlock> {
        val seen = linkedSetOf<String>()
        return blocks.mapNotNull { block ->
            if (block.id !in IDS || !seen.add(block.id)) return@mapNotNull null
            val w = block.w.coerceIn(36f, PAGE_W)
            val h = block.h.coerceIn(16f, PAGE_H)
            block.copy(
                x = block.x.coerceIn(0f, PAGE_W - 24f),
                y = block.y.coerceIn(0f, PAGE_H - 16f),
                w = w,
                h = h,
            )
        }
    }

    fun sanitizeAccent(value: String): String {
        val match = Regex("^#?([0-9a-fA-F]{6})$").matchEntire(value.trim()) ?: return ""
        return "#" + match.groupValues[1].uppercase()
    }
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
    /** OAuth scopes granted at connect time. Empty on older Instagram bindings. */
    val grantedScopes: List<String> = emptyList(),
    // WEB only: browser origins allowed to open the widget WebSocket. Empty = allow any.
    val allowedOrigins: List<String> = emptyList(),
)

enum class TenantStatus { ACTIVE, SUSPENDED, DELETED }
