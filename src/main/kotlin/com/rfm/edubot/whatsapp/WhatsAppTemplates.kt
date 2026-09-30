package com.rfm.edubot.whatsapp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A message template on a WhatsApp Business Account, reduced to what the dashboard shows and sends. */
data class WhatsAppTemplate(
    val name: String,
    val language: String,
    val category: String?,
    val header: String?,
    val body: String,
    val footer: String?,
    val buttons: List<String>,
    /** Body variables in order of appearance: "1", "2"… or names such as "first_name". */
    val params: List<String>,
    val namedParams: Boolean,
    /** False when sending needs input the dashboard doesn't collect (media header, dynamic button, one-time code). */
    val sendable: Boolean,
    val id: String? = null,
    /** Meta's review state: APPROVED, PENDING, REJECTED, PAUSED, DISABLED… Only APPROVED can be sent. */
    val status: String = "APPROVED",
    val rejectedReason: String? = null,
) {
    val approved: Boolean get() = status.equals("APPROVED", ignoreCase = true)

    /** The text the customer receives, with [values] filled in; blank values keep their placeholder. */
    fun render(values: Map<String, String>): String {
        val filledBody = PLACEHOLDER.replace(body) { match ->
            values[match.groupValues[1]]?.takeIf { it.isNotBlank() } ?: match.value
        }
        return listOfNotNull(header, filledBody, footer).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    fun bodyParameters(values: Map<String, String>): List<TemplateParameter> = params.map { key ->
        TemplateParameter(text = values[key].orEmpty(), parameterName = if (namedParams) key else null)
    }

    companion object {
        private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z0-9_]+)\s*\}\}""")
        private val SENDABLE_BUTTONS = setOf("QUICK_REPLY", "PHONE_NUMBER", "URL")

        internal fun fromGraph(graph: GraphTemplate): WhatsAppTemplate? {
            val body = graph.components.firstOrNull { it.type.equals("BODY", ignoreCase = true) }?.text ?: return null
            val header = graph.components.firstOrNull { it.type.equals("HEADER", ignoreCase = true) }
            val footer = graph.components.firstOrNull { it.type.equals("FOOTER", ignoreCase = true) }?.text
            val buttons = graph.components.firstOrNull { it.type.equals("BUTTONS", ignoreCase = true) }?.buttons.orEmpty()
            val params = PLACEHOLDER.findAll(body).map { it.groupValues[1] }.distinct().toList()
            val headerOk = header == null ||
                (header.format.equals("TEXT", ignoreCase = true) && !PLACEHOLDER.containsMatchIn(header.text.orEmpty()))
            val buttonsOk = buttons.all { button ->
                button.type.uppercase() in SENDABLE_BUTTONS && !PLACEHOLDER.containsMatchIn(button.url.orEmpty())
            }
            return WhatsAppTemplate(
                name = graph.name,
                language = graph.language,
                category = graph.category,
                header = header?.text?.takeIf { header.format.equals("TEXT", ignoreCase = true) },
                body = body,
                footer = footer,
                buttons = buttons.mapNotNull { it.text },
                params = params,
                namedParams = graph.parameterFormat.equals("NAMED", ignoreCase = true) || params.any { it.toIntOrNull() == null },
                sendable = headerOk && buttonsOk && !graph.category.equals("AUTHENTICATION", ignoreCase = true),
                id = graph.id,
                status = graph.status?.uppercase() ?: "APPROVED",
                rejectedReason = graph.rejectedReason?.takeIf { it.isNotBlank() && !it.equals("NONE", ignoreCase = true) },
            )
        }
    }
}

/**
 * A new template as the dashboard submits it for Meta's review. The dashboard only creates what it can
 * later send: an optional text header, a body with positional variables, an optional footer and up to
 * three quick-reply buttons.
 */
data class TemplateDraft(
    val name: String,
    val language: String,
    val category: String,
    val header: String?,
    val body: String,
    /** One example per body variable, in order; Meta rejects templates with variables but no examples. */
    val bodyExamples: List<String>,
    val footer: String?,
    val quickReplies: List<String>,
) {
    /** The first rule this draft breaks, as a dashboard error key, or null when Meta may review it. */
    fun problem(): String? {
        if (!NAME.matches(name)) return "template_name_invalid"
        if (!LANGUAGE.matches(language)) return "template_language_invalid"
        if (category !in CATEGORIES) return "template_category_invalid"
        if (header != null && (header.isBlank() || header.length > 60 || '\n' in header || PLACEHOLDER.containsMatchIn(header))) return "template_header_invalid"
        if (footer != null && (footer.isBlank() || footer.length > 60 || '\n' in footer || PLACEHOLDER.containsMatchIn(footer))) return "template_footer_invalid"
        val text = body.trim()
        if (text.isEmpty() || body.length > 1024) return "template_body_invalid"
        val numbers = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toList()
        val distinct = numbers.distinct()
        if (distinct.any { it.toIntOrNull() == null } || distinct.map { it.toInt() }.sorted() != (1..distinct.size).toList()) return "template_variables_invalid"
        if (distinct.isNotEmpty() && (text.startsWith("{{") || text.endsWith("}}"))) return "template_variables_invalid"
        if (bodyExamples.size != distinct.size || bodyExamples.any { it.isBlank() }) return "template_examples_missing"
        if (quickReplies.size > 3 || quickReplies.any { it.isBlank() || it.length > 25 } || quickReplies.distinct().size != quickReplies.size) return "template_buttons_invalid"
        return null
    }

    internal fun toRequest() = CreateTemplateRequest(
        name = name,
        language = language,
        category = category,
        components = buildList {
            header?.let { add(CreateTemplateComponent(type = "HEADER", format = "TEXT", text = it)) }
            add(CreateTemplateComponent(type = "BODY", text = body.trim(), example = bodyExamples.takeIf { it.isNotEmpty() }?.let { TemplateExample(listOf(it)) }))
            footer?.let { add(CreateTemplateComponent(type = "FOOTER", text = it)) }
            if (quickReplies.isNotEmpty()) add(CreateTemplateComponent(type = "BUTTONS", buttons = quickReplies.map { CreateTemplateButton("QUICK_REPLY", it) }))
        },
    )

    companion object {
        val CATEGORIES = setOf("UTILITY", "MARKETING")
        private val NAME = Regex("^[a-z0-9_]{1,512}$")
        private val LANGUAGE = Regex("^[a-z]{2,3}(_[A-Z]{2})?$")
        private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z0-9_]+)\s*\}\}""")
    }
}

/** Meta's answer to a template submission. */
@Serializable
data class CreatedTemplate(val id: String? = null, val status: String? = null, val category: String? = null)

@Serializable
internal data class CreateTemplateRequest(val name: String, val language: String, val category: String, val components: List<CreateTemplateComponent>)

@Serializable
internal data class CreateTemplateComponent(
    val type: String,
    val format: String? = null,
    val text: String? = null,
    val example: TemplateExample? = null,
    val buttons: List<CreateTemplateButton>? = null,
)

@Serializable
internal data class TemplateExample(@SerialName("body_text") val bodyText: List<List<String>>)

@Serializable
internal data class CreateTemplateButton(val type: String, val text: String)

@Serializable
data class TemplateParameter(
    val type: String = "text",
    val text: String,
    @SerialName("parameter_name") val parameterName: String? = null,
)

@Serializable
data class TemplateMessage(
    val name: String,
    val language: TemplateLanguage,
    val components: List<TemplateComponent>? = null,
)

@Serializable
data class TemplateLanguage(val code: String)

@Serializable
data class TemplateComponent(val type: String, val parameters: List<TemplateParameter>)

@Serializable
internal data class GraphTemplatesPage(val data: List<GraphTemplate> = emptyList(), val paging: GraphPaging? = null)

@Serializable
internal data class GraphPaging(val next: String? = null)

@Serializable
internal data class GraphTemplate(
    val name: String,
    val language: String,
    val status: String? = null,
    val category: String? = null,
    val components: List<GraphTemplateComponent> = emptyList(),
    @SerialName("parameter_format") val parameterFormat: String? = null,
    val id: String? = null,
    @SerialName("rejected_reason") val rejectedReason: String? = null,
)

@Serializable
internal data class GraphTemplateComponent(
    val type: String,
    val format: String? = null,
    val text: String? = null,
    val buttons: List<GraphTemplateButton> = emptyList(),
)

@Serializable
internal data class GraphTemplateButton(val type: String, val text: String? = null, val url: String? = null)
