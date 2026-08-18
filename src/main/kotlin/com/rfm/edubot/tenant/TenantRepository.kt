package com.rfm.edubot.tenant

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.DocumentLayoutBlock
import com.rfm.edubot.tenant.model.DocumentLayouts
import com.rfm.edubot.tenant.model.DocumentTemplate
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.util.Date

class TenantRepository(mongoModule: MongoModule) {
    private val collection = mongoModule.database.getCollection<Document>("tenants")

    suspend fun findByPhoneNumberId(phoneNumberId: String): Tenant? =
        collection.find(
            Filters.or(
                Filters.eq("phoneNumberId", phoneNumberId),
                Filters.elemMatch("channels", Filters.and(Filters.eq("platform", Platform.WHATSAPP.name), Filters.eq("externalId", phoneNumberId))),
            )
        ).firstOrNull()?.toTenant()

    suspend fun findBySlug(slug: String): Tenant? =
        collection.find(Filters.eq("slug", slug)).firstOrNull()?.toTenant()

    suspend fun findById(id: ObjectId): Tenant? =
        collection.find(Filters.eq("_id", id)).firstOrNull()?.toTenant()

    suspend fun findAll(): List<Tenant> =
        collection.find().toList().map { it.toTenant() }

    suspend fun create(tenant: Tenant): Tenant {
        collection.insertOne(tenant.toDocument())
        return tenant
    }

    suspend fun update(slug: String, update: Bson): Tenant? =
        collection.findOneAndUpdate(
            Filters.eq("slug", slug),
            update,
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toTenant()

    suspend fun setStatus(slug: String, status: TenantStatus, updatedAt: Instant): Tenant? =
        update(slug, Updates.combine(Updates.set("status", status.name), Updates.set("updatedAt", updatedAt.toDate())))

    suspend fun setLocale(slug: String, locale: String, updatedAt: Instant): Tenant? =
        update(slug, Updates.combine(Updates.set("locale", locale), Updates.set("updatedAt", updatedAt.toDate())))

    suspend fun setDocumentTemplate(slug: String, template: DocumentTemplate, updatedAt: Instant): Tenant? =
        update(
            slug,
            Updates.combine(
                Updates.set("documentTemplate", template.toDocument()),
                Updates.set("updatedAt", updatedAt.toDate()),
            ),
        )

    private fun Document.toTenant() = Tenant(
        id = getObjectId("_id"),
        slug = getString("slug"),
        name = getString("name"),
        channels = getChannels(),
        locale = com.rfm.edubot.tenant.model.TenantLocales.normalize(getString("locale")),
        timezone = com.rfm.edubot.tenant.model.TenantTimeZones.normalize(getString("timezone")),
        openrouterModel = getString("openrouterModel"),
        enabledModules = getList("enabledModules", String::class.java),
        rateLimitPerHour = getInteger("rateLimitPerHour") ?: 30,
        rateLimitPerDay = getInteger("rateLimitPerDay") ?: 200,
        status = TenantStatus.valueOf(getString("status") ?: TenantStatus.ACTIVE.name),
        documentTemplate = get("documentTemplate", Document::class.java)?.toDocumentTemplate() ?: DocumentTemplate(),
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
    )

    private fun Tenant.toDocument(): Document {
        val doc = Document("_id", id)
            .append("slug", slug)
            .append("name", name)
            .append("channels", channels.map { it.toDocument() })
            .append("locale", locale)
            .append("timezone", timezone)
            .append("openrouterModel", openrouterModel)
            .append("enabledModules", enabledModules)
            .append("rateLimitPerHour", rateLimitPerHour)
            .append("rateLimitPerDay", rateLimitPerDay)
            .append("status", status.name)
            .append("documentTemplate", documentTemplate.toDocument())
            .append("createdAt", createdAt.toDate())
            .append("updatedAt", updatedAt.toDate())
        if (phoneNumberId.isNotBlank()) doc.append("phoneNumberId", phoneNumberId)
        return doc
    }
}

private fun DocumentTemplate.toDocument(): Document = Document()
    .append("companyName", companyName)
    .append("tagline", tagline)
    .append("taxId", taxId)
    .append("email", email)
    .append("phone", phone)
    .append("address", address)
    .append("quoteTitle", quoteTitle)
    .append("invoiceTitle", invoiceTitle)
    .append("quotePaymentTerms", quotePaymentTerms)
    .append("invoicePaymentTerms", invoicePaymentTerms)
    .append("termsText", termsText)
    .append("footerText", footerText)
    .appendIfNotNull("logoPath", logoPath)
    .append("accentColor", accentColor)
    .append("showDecor", showDecor)
    .append("layout", layout.map { it.toDocument() })

private fun Document.toDocumentTemplate() = DocumentTemplate(
    companyName = getString("companyName").orEmpty(),
    tagline = getString("tagline").orEmpty(),
    taxId = getString("taxId").orEmpty(),
    email = getString("email").orEmpty(),
    phone = getString("phone").orEmpty(),
    address = getString("address").orEmpty(),
    quoteTitle = getString("quoteTitle").orEmpty(),
    invoiceTitle = getString("invoiceTitle").orEmpty(),
    quotePaymentTerms = getString("quotePaymentTerms").orEmpty(),
    invoicePaymentTerms = getString("invoicePaymentTerms").orEmpty(),
    termsText = getString("termsText").orEmpty(),
    footerText = getString("footerText").orEmpty(),
    logoPath = getString("logoPath"),
    accentColor = DocumentLayouts.sanitizeAccent(getString("accentColor").orEmpty()),
    showDecor = bool("showDecor", true),
    layout = DocumentLayouts.sanitize(
        getList("layout", Document::class.java).orEmpty().mapNotNull { it.toLayoutBlock() },
    ),
)

private fun DocumentLayoutBlock.toDocument(): Document = Document()
    .append("id", id)
    .append("x", x.toDouble())
    .append("y", y.toDouble())
    .append("w", w.toDouble())
    .append("h", h.toDouble())
    .append("visible", visible)

private fun Document.toLayoutBlock(): DocumentLayoutBlock? {
    val id = getString("id")?.takeIf { it.isNotBlank() } ?: return null
    return DocumentLayoutBlock(
        id = id,
        x = number("x") ?: return null,
        y = number("y") ?: return null,
        w = number("w") ?: return null,
        h = number("h") ?: return null,
        visible = bool("visible", true),
    )
}

private fun Document.number(key: String): Float? = (get(key) as? Number)?.toFloat()

private fun Document.bool(key: String, default: Boolean): Boolean = when (val value = get(key)) {
    is Boolean -> value
    else -> default
}

private fun Document.getChannels(): List<ChannelBinding> {
    val raw = getList("channels", Document::class.java).orEmpty()
    val channels = raw.mapNotNull { doc ->
        val platform = doc.getString("platform")?.let { runCatching { Platform.valueOf(it) }.getOrNull() } ?: return@mapNotNull null
        val externalId = doc.getString("externalId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ChannelBinding(
            platform = platform,
            externalId = externalId,
            accessToken = doc.getString("accessToken").orEmpty(),
            displayName = doc.getString("displayName"),
            wabaId = doc.getString("wabaId"),
            tokenObtainedAt = doc.getDate("tokenObtainedAt")?.let { Instant.fromEpochMilliseconds(it.time) },
            source = doc.getString("source"),
            allowedOrigins = doc.getList("allowedOrigins", String::class.java).orEmpty(),
        )
    }
    if (channels.isNotEmpty()) return channels
    val legacyPhoneNumberId = getString("phoneNumberId")
    return if (legacyPhoneNumberId.isNullOrBlank()) emptyList() else listOf(ChannelBinding(Platform.WHATSAPP, legacyPhoneNumberId))
}

private fun ChannelBinding.toDocument(): Document = Document("platform", platform.name)
    .append("externalId", externalId)
    .append("accessToken", accessToken)
    .appendIfNotNull("displayName", displayName)
    .appendIfNotNull("wabaId", wabaId)
    .appendIfNotNull("tokenObtainedAt", tokenObtainedAt?.toDate())
    .appendIfNotNull("source", source)
    .append("allowedOrigins", allowedOrigins)

private fun Document.getInstant(field: String): Instant = Instant.fromEpochMilliseconds(getDate(field).time)

private fun Instant.toDate(): Date = Date(toEpochMilliseconds())

private fun Document.appendIfNotNull(key: String, value: Any?): Document = apply {
    if (value != null) append(key, value)
}
