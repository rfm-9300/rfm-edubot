package com.rfm.edubot.webhook

import com.rfm.edubot.messaging.InboundMedia
import com.rfm.edubot.messaging.InboundMessage
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.webhook.dto.IncomingMessage
import com.rfm.edubot.webhook.dto.LocationContent
import com.rfm.edubot.webhook.dto.SharedContact
import org.bson.types.ObjectId

/**
 * What the pipeline receives for one WhatsApp message, or null for kinds the bot ignores (reactions,
 * deleted or unsupported messages). Button and list replies, shared locations and contacts become text
 * the AI can read; photos, voice notes, videos, documents and stickers become media, stored for the
 * inbox without an automatic reply.
 */
internal fun IncomingMessage.toInbound(tenantId: ObjectId, phoneNumberId: String, profileName: String?): InboundMessage? {
    fun inbound(text: String, media: InboundMedia? = null) = InboundMessage(
        tenantId = tenantId,
        phoneNumberId = phoneNumberId,
        platform = Platform.WHATSAPP,
        channelExternalId = phoneNumberId,
        waId = from,
        waMessageId = id,
        profileName = profileName,
        messageText = text,
        timestamp = timestamp,
        eventId = id,
        media = media,
    )

    text?.body?.takeIf { it.isNotBlank() }?.let { return inbound(it) }
    button?.let { (it.text ?: it.payload)?.takeIf { label -> label.isNotBlank() }?.let { label -> return inbound(label) } }
    interactive?.let { (it.buttonReply ?: it.listReply)?.title?.takeIf { title -> title.isNotBlank() }?.let { title -> return inbound(title) } }
    location?.describe()?.let { return inbound(it) }
    contacts?.mapNotNull { it.describe() }?.takeIf { it.isNotEmpty() }?.let { return inbound(it.joinToString("\n")) }

    val (kind, content) = listOfNotNull(
        image?.let { "image" to it },
        audio?.let { "audio" to it },
        video?.let { "video" to it },
        document?.let { "document" to it },
        sticker?.let { "sticker" to it },
    ).firstOrNull() ?: return null
    val mediaId = content.id?.takeIf { it.isNotBlank() } ?: return null
    return inbound(content.caption?.trim().orEmpty(), InboundMedia(kind, mediaId, content.mime_type, content.filename))
}

/** Name, address and a map link, so both the AI and the inbox can use a shared location. */
private fun LocationContent.describe(): String? {
    val place = listOfNotNull(name, address).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
    val link = url?.takeIf { it.isNotBlank() }
        ?: if (latitude != null && longitude != null) "https://maps.google.com/?q=$latitude,$longitude" else null
    return listOfNotNull(place.takeIf { it.isNotEmpty() }, link).joinToString(" ").takeIf { it.isNotEmpty() }
}

private fun SharedContact.describe(): String? {
    val label = (name?.formattedName ?: name?.firstName)?.trim()?.takeIf { it.isNotEmpty() }
    val phones = phones.orEmpty().mapNotNull { it.phone?.trim()?.takeIf { phone -> phone.isNotEmpty() } }
    return (listOfNotNull(label) + phones).joinToString(" ").takeIf { it.isNotEmpty() }
}
