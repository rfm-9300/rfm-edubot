package com.rfm.edubot.webhook

import com.rfm.edubot.messaging.InboundMedia
import com.rfm.edubot.webhook.dto.IncomingMessage
import kotlinx.serialization.json.Json
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WhatsAppInboundTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val tenantId = ObjectId()

    private fun inbound(message: String) = json.decodeFromString<IncomingMessage>(message).toInbound(tenantId, "pn-1", "Ana")

    @Test
    fun `a tap on a template's quick reply reaches the AI as text`() {
        val message = inbound("""{"from":"351900000000","id":"wamid.b","timestamp":"1","type":"button","button":{"text":"Confirmar","payload":"Confirmar"}}""")!!

        assertEquals("Confirmar", message.messageText)
        assertNull(message.media)
    }

    @Test
    fun `a list or button reply uses the option's title`() {
        val message = inbound("""{"from":"351900000000","id":"wamid.i","timestamp":"1","type":"interactive","interactive":{"type":"list_reply","list_reply":{"id":"slot-2","title":"Quinta, 15h","description":"Limpeza geral"}}}""")!!

        assertEquals("Quinta, 15h", message.messageText)
    }

    @Test
    fun `a shared location becomes its name, address and a map link`() {
        val message = inbound("""{"from":"351900000000","id":"wamid.l","timestamp":"1","type":"location","location":{"latitude":38.7223,"longitude":-9.1393,"name":"Casa da Ana","address":"Rua das Flores 10, Lisboa"}}""")!!

        assertEquals("Casa da Ana, Rua das Flores 10, Lisboa https://maps.google.com/?q=38.7223,-9.1393", message.messageText)
    }

    @Test
    fun `shared contacts become one line each`() {
        val message = inbound("""{"from":"351900000000","id":"wamid.c","timestamp":"1","type":"contacts","contacts":[{"name":{"formatted_name":"Rui Costa","first_name":"Rui"},"phones":[{"phone":"+351 912 345 678","wa_id":"351912345678"}]},{"name":{"first_name":"Joana"},"phones":[]}]}""")!!

        assertEquals("Rui Costa +351 912 345 678\nJoana", message.messageText)
    }

    @Test
    fun `a photo keeps its caption and media id`() {
        val message = inbound("""{"from":"351900000000","id":"wamid.p","timestamp":"1","type":"image","image":{"id":"media-1","mime_type":"image/jpeg","caption":" A mancha no sofá "}}""")!!

        assertEquals("A mancha no sofá", message.messageText)
        assertEquals(InboundMedia("image", "media-1", "image/jpeg", null), message.media)
    }

    @Test
    fun `a document keeps its file name and a voice note has no caption`() {
        val document = inbound("""{"from":"351900000000","id":"wamid.d","timestamp":"1","type":"document","document":{"id":"media-2","mime_type":"application/pdf","filename":"orcamento.pdf"}}""")!!
        val voice = inbound("""{"from":"351900000000","id":"wamid.v","timestamp":"1","type":"audio","audio":{"id":"media-3","mime_type":"audio/ogg; codecs=opus","voice":true}}""")!!

        assertEquals("orcamento.pdf", document.media?.fileName)
        assertEquals("", voice.messageText)
        assertEquals("audio", voice.media?.kind)
    }

    @Test
    fun `reactions and unknown kinds are ignored`() {
        assertNull(inbound("""{"from":"351900000000","id":"wamid.r","timestamp":"1","type":"reaction","reaction":{"message_id":"wamid.x","emoji":"👍"}}"""))
        assertNull(inbound("""{"from":"351900000000","id":"wamid.u","timestamp":"1","type":"unsupported"}"""))
    }
}
