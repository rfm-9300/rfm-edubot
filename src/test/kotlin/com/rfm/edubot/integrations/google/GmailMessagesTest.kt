package com.rfm.edubot.integrations.google

import com.rfm.edubot.integrations.email.EmailAttachmentInfo
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GmailMessagesTest {
    private fun parse(json: String): GmailMessage = GmailMessages.parse(Json.parseToJsonElement(json).jsonObject)!!

    @Test
    fun `a plain email is read with its headers decoded`() {
        val message = parse(
            GmailFixtures.message(
                "m1",
                from = "=?UTF-8?Q?Jo=C3=A3o_Gon=C3=A7alves?= <Joao@Cliente.PT>",
                subject = "=?UTF-8?B?UGVkaWRvIGRl?= =?UTF-8?B?IG9yw6dhbWVudG8=?= urgente",
                headers = mapOf("Reply-To" to "\"Gonçalves, João\" <joao.pessoal@mail.pt>", "Cc" to "ana@cliente.pt, not-an-address, Ana <ana@cliente.pt>"),
                text = "Olá,\r\n\r\n\r\n\r\nPreciso   de um orçamento.\r\n",
            ),
        )

        assertEquals("m1", message.id)
        assertEquals("th-m1", message.threadId)
        assertEquals(setOf("INBOX", "UNREAD", "CATEGORY_PERSONAL"), message.labelIds)
        assertEquals(Instant.fromEpochMilliseconds(1_790_000_000_000), message.date)
        assertEquals(MailAddress("joao@cliente.pt", "João Gonçalves"), message.from)
        assertEquals(MailAddress("joao.pessoal@mail.pt", "Gonçalves, João"), message.replyTo)
        assertEquals(listOf("obras@example.pt"), message.to)
        assertEquals(listOf("ana@cliente.pt"), message.cc)
        assertEquals("Pedido de orçamento urgente", message.subject)
        assertEquals("<m1@mail.cliente.pt>", message.messageIdHeader)
        assertEquals("Olá,\n\nPreciso de um orçamento.", message.text)
        assertEquals("Olá, Preciso de um orçamento.", message.snippet)
        assertFalse(message.automated)
        assertFalse(message.autoReply)
        assertTrue(message.attachments.isEmpty())
    }

    @Test
    fun `an html-only email becomes readable text`() {
        val html = """
            <html><head><title>Fatura</title><style>p { color: red }</style></head>
            <body><script>alert(1)</script><!-- tracking -->
            <p>Caro cliente,</p><div>Valor:&nbsp;<b>45,20&euro;</b> &amp; IVA &#8364; &#x20AC;</div>
            <ul><li>Luz</li><li>G&aacute;s &ccedil; &ntilde; &yuml;</li></ul>Obrigado<br>EDP</body></html>
        """.trimIndent()

        val message = parse(GmailFixtures.message("m2", text = null, html = html))

        assertEquals("Caro cliente,\n\nValor: 45,20€ & IVA € €\n\n- Luz\n- Gás ç ñ ÿ\nObrigado\nEDP", message.text)
        assertEquals("Caro cliente, Valor: 45,20€ & IVA € € - Luz - Gás ç ñ ÿ Obrigado EDP", message.snippet)
    }

    @Test
    fun `the text part wins over the html one and gmail's snippet fills in for an empty body`() {
        val both = parse(GmailFixtures.message("m3", text = "Plain words", html = "<p>Rich words</p>"))
        assertEquals("Plain words", both.text)

        val empty = parse(GmailFixtures.message("m4", text = "", html = null))
        assertEquals("", empty.text)
        assertEquals("Olá, preciso", empty.snippet)
    }

    @Test
    fun `declared charsets are honoured and broken utf-8 is read as windows-1252`() {
        val latin = "Olá, orçamento".toByteArray(Charsets.ISO_8859_1)
        fun payload(contentType: String, bytes: ByteArray) = """{"id":"m5","payload":{"mimeType":"text/plain","filename":"","headers":[
            {"name":"From","value":"a@b.pt"},{"name":"Content-Type","value":"$contentType"}],
            "body":{"size":${bytes.size},"data":"${Base64.getUrlEncoder().encodeToString(bytes)}"}}}"""

        assertEquals("Olá, orçamento", parse(payload("text/plain; charset=ISO-8859-1", latin)).text)
        assertEquals("Olá, orçamento", parse(payload("text/plain; charset=\\\"utf-8\\\"", latin)).text)
        assertEquals("Olá, orçamento", parse(payload("text/plain", "Olá, orçamento".toByteArray())).text)
    }

    @Test
    fun `attachments are listed without reading them and inline pictures don't count`() {
        val message = parse(
            GmailFixtures.message(
                "m6",
                attachments = listOf(
                    GmailFixtures.Attachment("FT 2026-118.pdf", "application/pdf", size = 81_532),
                    GmailFixtures.Attachment("logo.png", "image/png", headers = mapOf("Content-Disposition" to "inline; filename=\"logo.png\"", "Content-ID" to "<logo>")),
                    GmailFixtures.Attachment("=?UTF-8?Q?or=C3=A7amento.PDF?=", "application/octet-stream", size = 10),
                ),
            ),
        )

        assertEquals(
            listOf(EmailAttachmentInfo("FT 2026-118.pdf", "application/pdf", 81_532), EmailAttachmentInfo("orçamento.PDF", "application/octet-stream", 10)),
            message.attachments,
        )
        assertTrue(message.hasPdf)
        assertTrue(message.text.startsWith("Olá, preciso"), "the body is still the text part")
    }

    @Test
    fun `automatic mail is told apart from people writing`() {
        fun message(from: String = "Maria <maria@cliente.pt>", subject: String = "Olá", headers: Map<String, String> = emptyMap(), mimeType: String? = null) =
            parse(GmailFixtures.message("m", from = from, subject = subject, headers = headers, mimeType = mimeType, attachments = if (mimeType != null) listOf(GmailFixtures.Attachment("x.txt", "text/plain")) else emptyList()))

        val vacation = message(headers = mapOf("Auto-Submitted" to "auto-replied (vacation)"))
        assertTrue(vacation.autoReply && vacation.automated)
        val newsletter = message(headers = mapOf("List-Unsubscribe" to "<https://news.example/u>", "Precedence" to "bulk"))
        assertTrue(newsletter.automated && !newsletter.autoReply)
        val bill = message(from = "EDP <no-reply@edp.pt>", subject = "A sua fatura")
        assertTrue(bill.automated && !bill.autoReply)
        val generated = message(headers = mapOf("Auto-Submitted" to "auto-generated"), subject = "Automatic reply: Pedido")
        assertTrue(generated.autoReply, "an out-of-office subject on generated mail")
        val bounce = message(from = "Mail Delivery Subsystem <mailer-daemon@googlemail.com>", mimeType = "multipart/report")
        assertTrue(bounce.autoReply && bounce.automated)
        val person = message(headers = mapOf("Auto-Submitted" to "no"), subject = "Automatic reply: why?")
        assertFalse(person.automated || person.autoReply, "a person's subject alone proves nothing")
    }

    @Test
    fun `address lists keep valid addresses once, with their names`() {
        assertEquals(
            listOf(
                MailAddress("maria@cliente.pt", "Silva, Maria"),
                MailAddress("joao@cliente.pt", "João"),
                MailAddress("ana@cliente.pt"),
            ),
            GmailMessages.addresses("\"Silva, Maria\" <Maria@Cliente.pt>, joao@cliente.pt (João), undisclosed-recipients:;, <ana@cliente.pt>, MARIA@cliente.pt"),
        )
        assertTrue(GmailMessages.addresses(null).isEmpty())
        assertNull(GmailMessages.addresses("maria@cliente.pt <maria@cliente.pt>").single().name, "a name that only repeats the address")
    }

    @Test
    fun `encoded words decode across charsets and leave broken ones alone`() {
        assertEquals("Olá mundo", GmailMessages.decodeHeader("=?ISO-8859-1?Q?Ol=E1?= mundo"))
        assertEquals("€uro", GmailMessages.decodeHeader("=?UTF-8?B?4oKs?=\r\n =?UTF-8?Q?uro?="))
        assertEquals("=?X-UNKNOWN?Z?abc?= ok", GmailMessages.decodeHeader("=?X-UNKNOWN?Z?abc?= ok"))
        assertEquals("a b", GmailMessages.decodeHeader("a\r\n\tb"))
    }
}
