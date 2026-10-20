package com.rfm.edubot.dashboard.settings

import com.rfm.edubot.crm.PdfGenerator
import com.rfm.edubot.tenant.model.BuiltInDesignTemplates
import com.rfm.edubot.tenant.model.DocumentDesignStyle
import com.rfm.edubot.tenant.model.DocumentLayoutBlock
import com.rfm.edubot.tenant.model.DocumentLayouts
import com.rfm.edubot.tenant.model.DocumentTemplate
import com.rfm.edubot.tenant.model.SavedDocumentTemplate
import com.rfm.edubot.tenant.model.Tenant
import kotlinx.serialization.Serializable

@Serializable
internal data class DocumentTemplateRequest(
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
    val accentColor: String? = null,
    val showDecor: Boolean? = null,
    val layout: List<DocumentLayoutBlockDto>? = null,
    val style: String? = null,
)

@Serializable
internal data class DocumentTemplateDto(
    val companyName: String,
    val tagline: String,
    val taxId: String,
    val email: String,
    val phone: String,
    val address: String,
    val quoteTitle: String,
    val invoiceTitle: String,
    val quotePaymentTerms: String,
    val invoicePaymentTerms: String,
    val termsText: String,
    val footerText: String,
    val accentColor: String,
    val showDecor: Boolean,
    val layout: List<DocumentLayoutBlockDto>,
    val style: String,
    val hasLogo: Boolean,
    val defaults: DocumentTemplateDefaultsDto,
)

@Serializable
internal data class DocumentLayoutBlockDto(
    val id: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val visible: Boolean = true,
)

// No default values here: the JSON config drops properties equal to their default, which used to send
// the studio an empty object, so its preview showed none of what the PDFs print.
@Serializable
internal data class DocumentTemplateDefaultsDto(
    val quoteTitle: String,
    val invoiceTitle: String,
    val quotePaymentTerms: String,
    val invoicePaymentTerms: String,
    val termsText: String,
    val footerText: String,
    val accentColor: String,
    val layout: List<DocumentLayoutBlockDto>,
    val style: String,
)

@Serializable
internal data class SaveDesignPresetRequest(val name: String)

@Serializable
internal data class DesignPresetDto(
    val id: String,
    /** For a saved preset, the tenant's own label. For a built-in one, a stable key the UI maps via i18n. */
    val name: String,
    val builtIn: Boolean,
    val accentColor: String,
    val showDecor: Boolean,
    val layout: List<DocumentLayoutBlockDto>,
    val style: String,
)

private fun documentTemplateDefaults() = DocumentTemplateDefaultsDto(
    quoteTitle = "ORÇAMENTO",
    invoiceTitle = "FATURA",
    quotePaymentTerms = "",
    invoicePaymentTerms = PdfGenerator.DEFAULT_INVOICE_PAYMENT_TERMS,
    termsText = PdfGenerator.DEFAULT_QUOTE_TERMS,
    footerText = PdfGenerator.DEFAULT_FOOTER,
    accentColor = DocumentLayouts.DEFAULT_ACCENT,
    layout = DocumentLayouts.DEFAULT.map { it.dto() },
    style = DocumentDesignStyle.CLASSIC.id,
)

internal fun DocumentTemplateRequest.toTemplate(existing: DocumentTemplate): DocumentTemplate = DocumentTemplate(
    companyName = companyName.trim(),
    tagline = tagline.trim(),
    taxId = taxId.trim(),
    email = email.trim(),
    phone = phone.trim(),
    address = address.trim(),
    quoteTitle = quoteTitle.trim(),
    invoiceTitle = invoiceTitle.trim(),
    quotePaymentTerms = quotePaymentTerms.trim(),
    invoicePaymentTerms = invoicePaymentTerms.trim(),
    termsText = termsText.trim(),
    footerText = footerText.trim(),
    logoPath = existing.logoPath,
    accentColor = accentColor?.let(DocumentLayouts::sanitizeAccent) ?: existing.accentColor,
    showDecor = showDecor ?: existing.showDecor,
    layout = layout?.let { DocumentLayouts.sanitize(it.map { block -> block.toModel() }) } ?: existing.layout,
    style = style?.let(DocumentDesignStyle::sanitize) ?: existing.style,
)

internal fun DocumentTemplate.dto(tenantName: String) = DocumentTemplateDto(
    companyName = companyName.ifBlank { tenantName },
    tagline = tagline,
    taxId = taxId,
    email = email,
    phone = phone,
    address = address,
    quoteTitle = quoteTitle,
    invoiceTitle = invoiceTitle,
    quotePaymentTerms = quotePaymentTerms,
    invoicePaymentTerms = invoicePaymentTerms,
    termsText = termsText,
    footerText = footerText,
    accentColor = accentColor,
    showDecor = showDecor,
    layout = layout.map { it.dto() },
    style = DocumentDesignStyle.sanitize(style),
    hasLogo = logoFile() != null,
    defaults = documentTemplateDefaults(),
)

private fun DocumentLayoutBlock.dto() = DocumentLayoutBlockDto(id, x, y, w, h, visible)

private fun DocumentLayoutBlockDto.toModel() = DocumentLayoutBlock(id, x, y, w, h, visible)

private fun SavedDocumentTemplate.dto(builtIn: Boolean) = DesignPresetDto(
    id = id,
    name = name,
    builtIn = builtIn,
    accentColor = accentColor,
    showDecor = showDecor,
    layout = layout.map { it.dto() },
    style = DocumentDesignStyle.sanitize(style),
)

internal fun Tenant.designPresets(): List<DesignPresetDto> =
    BuiltInDesignTemplates.ALL.map { it.dto(builtIn = true) } + savedDocumentTemplates.map { it.dto(builtIn = false) }
