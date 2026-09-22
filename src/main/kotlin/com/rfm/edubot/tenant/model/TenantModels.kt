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
    /** Home snapshot ids the tenant turned off. Empty = show every available card. */
    val overviewHiddenCards: List<String> = emptyList(),
    val rateLimitPerHour: Int = 30,
    val rateLimitPerDay: Int = 200,
    val status: TenantStatus = TenantStatus.ACTIVE,
    /** Branding + copy used when generating quote/invoice PDFs. */
    val documentTemplate: DocumentTemplate = DocumentTemplate(),
    /** Named designs (accent/decor/layout only) the tenant saved for reuse across document templates. */
    val savedDocumentTemplates: List<SavedDocumentTemplate> = emptyList(),
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

/**
 * A reusable document *design* — accent color, decoration, and block geometry, without any of the
 * tenant's own copy (company name, terms, etc). Applying one onto a [DocumentTemplate] only touches
 * those three fields. Built-in entries come from [BuiltInDesignTemplates]; tenants can also save their
 * own from the studio.
 */
data class SavedDocumentTemplate(
    val id: String,
    /** User-entered label for a saved preset; a stable lookup key (see [BuiltInDesignTemplates]) for a built-in one. */
    val name: String,
    val accentColor: String,
    val showDecor: Boolean,
    val layout: List<DocumentLayoutBlock>,
    val createdAt: Instant,
)

/** Curated starter designs shown alongside a tenant's own saved presets in the template gallery. */
object BuiltInDesignTemplates {
    private val EPOCH = Instant.fromEpochMilliseconds(0)

    val ALL: List<SavedDocumentTemplate> = listOf(
        SavedDocumentTemplate(
            id = "builtin-classic",
            name = "classic",
            accentColor = DocumentLayouts.DEFAULT_ACCENT,
            showDecor = true,
            layout = DocumentLayouts.DEFAULT,
            createdAt = EPOCH,
        ),
        SavedDocumentTemplate(
            id = "builtin-modern",
            name = "modern",
            accentColor = "#1F6FEB",
            showDecor = true,
            layout = listOf(
                DocumentLayoutBlock("logo", 42f, 48f, 150f, 52f),
                DocumentLayoutBlock("contact", 403f, 54f, 150f, 40f),
                DocumentLayoutBlock("company", 42f, 96f, 280f, 44f, visible = false),
                DocumentLayoutBlock("title", 42f, 120f, 511f, 48f),
                DocumentLayoutBlock("client", 42f, 190f, 280f, 90f),
                DocumentLayoutBlock("items", 42f, 290f, 511f, 280f),
                DocumentLayoutBlock("totals", 333f, 590f, 220f, 32f),
                DocumentLayoutBlock("payment", 50f, 708f, 320f, 56f),
                DocumentLayoutBlock("terms", 50f, 766f, 320f, 40f),
                DocumentLayoutBlock("footer", 200f, 812f, 353f, 18f),
            ),
            createdAt = EPOCH,
        ),
        SavedDocumentTemplate(
            id = "builtin-minimal",
            name = "minimal",
            accentColor = "#111827",
            showDecor = false,
            layout = listOf(
                DocumentLayoutBlock("logo", 42f, 40f, 120f, 40f),
                DocumentLayoutBlock("contact", 42f, 84f, 340f, 32f),
                DocumentLayoutBlock("company", 42f, 84f, 280f, 32f, visible = false),
                DocumentLayoutBlock("title", 42f, 136f, 400f, 40f),
                DocumentLayoutBlock("client", 42f, 192f, 280f, 80f),
                DocumentLayoutBlock("items", 42f, 284f, 511f, 290f),
                DocumentLayoutBlock("totals", 333f, 584f, 220f, 32f),
                DocumentLayoutBlock("payment", 42f, 700f, 320f, 50f),
                DocumentLayoutBlock("terms", 42f, 754f, 320f, 36f),
                DocumentLayoutBlock("footer", 42f, 812f, 353f, 18f),
            ),
            createdAt = EPOCH,
        ),
        SavedDocumentTemplate(
            id = "builtin-editorial",
            name = "editorial",
            accentColor = "#C45C26",
            showDecor = true,
            layout = listOf(
                DocumentLayoutBlock("logo", 42f, 40f, 168f, 52f),
                DocumentLayoutBlock("contact", 340f, 40f, 213f, 52f),
                DocumentLayoutBlock("company", 42f, 100f, 260f, 44f),
                DocumentLayoutBlock("title", 42f, 168f, 330f, 52f),
                DocumentLayoutBlock("client", 400f, 168f, 153f, 108f),
                DocumentLayoutBlock("items", 42f, 292f, 511f, 268f),
                DocumentLayoutBlock("totals", 333f, 576f, 220f, 32f),
                DocumentLayoutBlock("payment", 42f, 700f, 320f, 52f),
                DocumentLayoutBlock("terms", 42f, 758f, 320f, 36f),
                DocumentLayoutBlock("footer", 42f, 812f, 511f, 18f),
            ),
            createdAt = EPOCH,
        ),
        SavedDocumentTemplate(
            id = "builtin-ledger",
            name = "ledger",
            accentColor = "#1B4D3E",
            showDecor = false,
            layout = listOf(
                DocumentLayoutBlock("company", 42f, 36f, 300f, 48f),
                DocumentLayoutBlock("logo", 403f, 36f, 150f, 52f),
                DocumentLayoutBlock("contact", 42f, 92f, 340f, 36f),
                DocumentLayoutBlock("title", 42f, 148f, 511f, 44f),
                DocumentLayoutBlock("client", 42f, 204f, 280f, 80f),
                DocumentLayoutBlock("items", 42f, 296f, 511f, 276f),
                DocumentLayoutBlock("totals", 333f, 588f, 220f, 32f),
                DocumentLayoutBlock("payment", 42f, 700f, 320f, 50f),
                DocumentLayoutBlock("terms", 42f, 756f, 320f, 36f),
                DocumentLayoutBlock("footer", 42f, 812f, 511f, 18f),
            ),
            createdAt = EPOCH,
        ),
        SavedDocumentTemplate(
            id = "builtin-harbor",
            name = "harbor",
            accentColor = "#0E7490",
            showDecor = true,
            layout = listOf(
                DocumentLayoutBlock("contact", 42f, 28f, 511f, 36f),
                DocumentLayoutBlock("logo", 42f, 80f, 150f, 52f),
                DocumentLayoutBlock("company", 220f, 80f, 280f, 52f),
                DocumentLayoutBlock("title", 42f, 156f, 400f, 48f),
                DocumentLayoutBlock("client", 42f, 220f, 280f, 84f),
                DocumentLayoutBlock("items", 42f, 316f, 511f, 256f),
                DocumentLayoutBlock("totals", 333f, 588f, 220f, 32f),
                DocumentLayoutBlock("payment", 50f, 704f, 320f, 52f),
                DocumentLayoutBlock("terms", 50f, 762f, 320f, 36f),
                DocumentLayoutBlock("footer", 200f, 812f, 353f, 18f),
            ),
            createdAt = EPOCH,
        ),
        SavedDocumentTemplate(
            id = "builtin-atelier",
            name = "atelier",
            accentColor = "#B45309",
            showDecor = true,
            layout = listOf(
                DocumentLayoutBlock("logo", 42f, 48f, 188f, 56f),
                DocumentLayoutBlock("company", 42f, 112f, 280f, 32f, visible = false),
                DocumentLayoutBlock("contact", 42f, 116f, 360f, 36f),
                DocumentLayoutBlock("title", 42f, 172f, 511f, 56f),
                DocumentLayoutBlock("client", 42f, 244f, 300f, 72f),
                DocumentLayoutBlock("items", 42f, 332f, 511f, 236f),
                DocumentLayoutBlock("totals", 333f, 588f, 220f, 32f),
                DocumentLayoutBlock("payment", 42f, 700f, 340f, 50f),
                DocumentLayoutBlock("terms", 42f, 756f, 340f, 36f),
                DocumentLayoutBlock("footer", 42f, 812f, 511f, 18f),
            ),
            createdAt = EPOCH,
        ),
    )

    fun find(id: String): SavedDocumentTemplate? = ALL.find { it.id == id }
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

    /** Visible blocks whose rectangles intersect. The PDF paints at these coordinates. */
    fun overlappingIds(blocks: List<DocumentLayoutBlock>): Set<String> {
        val visible = blocks.filter { it.visible }
        val hit = mutableSetOf<String>()
        for (i in visible.indices) {
            for (j in i + 1 until visible.size) {
                val a = visible[i]
                val b = visible[j]
                if (a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h) {
                    hit += a.id
                    hit += b.id
                }
            }
        }
        return hit
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
