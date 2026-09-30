package com.rfm.edubot.agents.actions

import com.rfm.edubot.agents.registry.ActionResult
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.agents.registry.strings
import com.rfm.edubot.agents.runtime.RunContext
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.whatsapp.WhatsAppClient
import com.rfm.edubot.whatsapp.WhatsAppTemplate
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

/**
 * Business-initiated WhatsApp messages: outside the 24-hour window only an approved template may go.
 * A step maps its template by name and language and fills the body variables in order.
 */
internal object WhatsAppTemplateSending {
    data class Mapping(val name: String, val language: String, val params: List<String>)

    private val cache = ConcurrentHashMap<ObjectId, Pair<Instant, List<WhatsAppTemplate>>>()
    private val cacheFor = 5.minutes

    fun mapping(input: JsonObject): Mapping? {
        val name = input.string("template") ?: return null
        return Mapping(name, input.string("templateLanguage") ?: "pt_PT", input.strings("templateParams"))
    }

    private suspend fun approved(ctx: RunContext, client: WhatsAppClient, mapping: Mapping): WhatsAppTemplate? {
        val wabaId = ctx.tenant.binding(Platform.WHATSAPP)?.wabaId?.takeIf { it.isNotBlank() } ?: return null
        val cached = cache[ctx.tenant.id]
        val templates = if (cached != null && ctx.now - cached.first < cacheFor) {
            cached.second
        } else {
            runCatching { client.templates(wabaId) }.getOrNull()?.also { cache[ctx.tenant.id] = ctx.now to it } ?: return null
        }
        return templates.firstOrNull { it.name == mapping.name && it.language == mapping.language && it.approved && it.sendable }
    }

    private fun values(template: WhatsAppTemplate, mapping: Mapping): Map<String, String> =
        template.params.mapIndexed { index, key -> key to mapping.params.getOrNull(index).orEmpty().trim() }.toMap()

    suspend fun previewText(ctx: RunContext, mapping: Mapping): String? {
        val client = ctx.services.whatsApp(ctx.tenant) ?: return null
        val template = approved(ctx, client, mapping) ?: return null
        return template.render(values(template, mapping))
    }

    suspend fun send(ctx: RunContext, client: WhatsAppClient, phone: String, mapping: Mapping): ActionResult {
        val template = approved(ctx, client, mapping) ?: return ActionResult.Failed("template_not_found")
        val values = values(template, mapping)
        if (values.values.any { it.isEmpty() || it.length > 1024 }) return ActionResult.Failed("template_params")
        val body = template.render(values)
        return when (val sent = AgentMessaging.deliver(ctx, "whatsapp", phone) { client.sendTemplate(phone, template.name, template.language, template.bodyParameters(values)).id }) {
            is Delivery.Sent -> {
                AgentMessaging.storeOutbound(ctx, Platform.WHATSAPP, phone, MessageContent.Template(template.name, template.language, body), sent.providerMessageId)
                ActionResult.Done(
                    buildJsonObject {
                        put("channel", "whatsapp")
                        put("to", phone)
                        put("template", template.name)
                        sent.providerMessageId?.let { put("messageId", it) }
                    },
                    note = "template",
                )
            }
            Delivery.AlreadySent -> ActionResult.Done(buildJsonObject { put("channel", "whatsapp") }, note = "already_sent")
            Delivery.InDoubt -> ActionResult.Failed("send_in_doubt")
            is Delivery.Failed -> ActionResult.Failed(sent.key, sent.retryable)
        }
    }
}
