package com.rfm.edubot.integrations.mailbox

import com.rfm.edubot.integrations.email.MailAddress
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboundMimeTest {
    /** Headers may carry raw UTF-8 (RFC 6532), as mail programs send it today. */
    private fun mime(text: String): ByteArray = text.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.UTF_8)

    @Test
    fun `reads a forwarded email's headers, text and attachments`() {
        val mail = InboundMime.parse(
            mime(
                """
                Return-Path: <geral+caf_=obras.k7p2x9=mail.example.pt@obras.pt>
                X-Forwarded-For: geral@obras.pt obras.k7p2x9@mail.example.pt
                X-Forwarded-To: obras.k7p2x9@mail.example.pt
                From: =?UTF-8?Q?Jo=C3=A3o_Gon=C3=A7alves?= <Joao@Cliente.PT>
                Reply-To: "Gonçalves, João" <joao.pessoal@mail.pt>
                To: Obras <geral@obras.pt>
                Cc: ana@cliente.pt, "Rui" <rui@cliente.pt>
                Subject: =?ISO-8859-1?Q?Or=E7amento_para_cozinha?=
                Message-ID: <CAF-123@mail.gmail.com>
                In-Reply-To: <0102018f-ses-1-000000@eu-west-1.amazonses.com>
                References: <first@obras.pt>
                 <0102018f-ses-1-000000@eu-west-1.amazonses.com>
                Date: Tue, 3 Nov 2026 10:15:00 +0000
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary="mix"

                --mix
                Content-Type: multipart/alternative; boundary="alt"

                --alt
                Content-Type: text/plain; charset=ISO-8859-1
                Content-Transfer-Encoding: quoted-printable

                Bom dia,

                Queria um or=E7amento.
                --alt
                Content-Type: text/html; charset=UTF-8

                <p>Bom dia</p>
                --alt--
                --mix
                Content-Type: image/png
                Content-Disposition: inline; filename="logo.png"
                Content-ID: <logo>
                Content-Transfer-Encoding: base64

                iVBORw0KGgo=
                --mix
                Content-Type: application/pdf; name="planta.pdf"
                Content-Disposition: attachment; filename="planta.pdf"
                Content-Transfer-Encoding: base64

                JVBERi0xLjQKJcOkw7zDtsOfCg==
                --mix--
                """,
            ),
        )!!
        assertEquals(MailAddress("joao@cliente.pt", "João Gonçalves"), mail.from)
        assertEquals(MailAddress("joao.pessoal@mail.pt", "Gonçalves, João"), mail.replyTo)
        assertEquals(listOf("geral@obras.pt"), mail.to)
        assertEquals(listOf("ana@cliente.pt", "rui@cliente.pt"), mail.cc)
        assertEquals("Orçamento para cozinha", mail.subject)
        assertEquals("<CAF-123@mail.gmail.com>", mail.messageIdHeader)
        assertEquals(listOf("<0102018f-ses-1-000000@eu-west-1.amazonses.com>"), mail.inReplyTo)
        assertEquals(listOf("<first@obras.pt>", "<0102018f-ses-1-000000@eu-west-1.amazonses.com>"), mail.references)
        assertEquals("Bom dia,\n\nQueria um orçamento.", mail.text)
        assertEquals(listOf("planta.pdf"), mail.attachments.map { it.filename }, "the signature's inline logo isn't an attachment")
        assertTrue(mail.attachments.single().isPdf)
        assertFalse(mail.automated)
        assertEquals(Instant.parse("2026-11-03T10:15:00Z"), mail.sentAt)
        assertEquals("geral@obras.pt", ForwardedMail.forwarder(mail.headers, "geral+caf_=obras.k7p2x9=mail.example.pt@obras.pt", "obras.k7p2x9@mail.example.pt"))
    }

    @Test
    fun `an HTML-only newsletter is read as text and flagged as a machine's`() {
        val mail = InboundMime.parse(
            mime(
                """
                From: Loja <no-reply@loja.example>
                To: geral@obras.pt
                Subject: Promoções
                List-Unsubscribe: <https://loja.example/u>
                Content-Type: text/html; charset=UTF-8

                <html><head><style>p{}</style></head><body><p>Descontos&nbsp;de&nbsp;30%</p><p>S&oacute; hoje</p></body></html>
                """,
            ),
        )!!
        assertEquals("Descontos de 30%\n\nSó hoje", mail.text)
        assertTrue(mail.automated)
        assertFalse(mail.autoReply)
    }

    @Test
    fun `a bounce is an automatic reply`() {
        val mail = InboundMime.parse(
            mime(
                """
                From: MAILER-DAEMON@amazonses.com
                To: obras.k7p2x9@mail.example.pt
                Subject: Delivery Status Notification (Failure)
                Content-Type: multipart/report; report-type=delivery-status; boundary="b"

                --b
                Content-Type: text/plain

                An error occurred while trying to deliver the mail to the following recipients: x@y.pt
                --b--
                """,
            ),
        )!!
        assertTrue(mail.automated && mail.autoReply)
    }

    @Test
    fun `an email forwarded as an attachment carries the original whole`() {
        val mail = InboundMime.parse(
            mime(
                """
                From: Rui Silva <geral@obras.pt>
                To: obras.k7p2x9@mail.example.pt
                Subject: Fwd: Fatura
                Content-Type: multipart/mixed; boundary="m"

                --m
                Content-Type: text/plain

                Para tratar.
                --m
                Content-Type: message/rfc822

                From: Fornecedor <faturas@fornecedor.pt>
                To: geral@obras.pt
                Subject: Fatura FT 2026/77
                Message-ID: <ft77@fornecedor.pt>
                Content-Type: text/plain

                Segue a fatura de novembro.
                --m--
                """,
            ),
        )!!
        val attached = assertNotNull(mail.attached)
        assertEquals("faturas@fornecedor.pt", attached.from!!.email)
        assertEquals("Fatura FT 2026/77", attached.subject)
        assertEquals("<ft77@fornecedor.pt>", attached.messageIdHeader)
        assertEquals("Segue a fatura de novembro.", attached.text)
        assertEquals("Para tratar.", mail.text)
    }

    @Test
    fun `finds who wrote an email forwarded by hand, in Gmail, Outlook and Apple Mail`() {
        val gmailPt = ForwardedMail.original(
            "Vê isto\n\n---------- Mensagem encaminhada ---------\nDe: *Maria Silva* <maria@cliente.pt>\nDate: segunda, 2/11/2026 às 10:15\nSubject: Pedido de orçamento\nTo: <geral@obras.pt>\n\nOlá, queria um orçamento.",
            "Fwd: Pedido de orçamento",
        )
        assertEquals(ForwardedMail.Original(MailAddress("maria@cliente.pt", "Maria Silva"), "Pedido de orçamento"), gmailPt)

        val outlook = ForwardedMail.original(
            "\n________________________________\nDe: Ana Costa [mailto:ana@cliente.pt]\nEnviado: 2 de novembro de 2026 10:00\nPara: geral@obras.pt\nAssunto: Marcação\n\nPodem vir amanhã?",
            "RE: algo",
        )
        assertEquals(ForwardedMail.Original(MailAddress("ana@cliente.pt", "Ana Costa"), "Marcação"), outlook)

        val apple = ForwardedMail.original(
            "Begin forwarded message:\n\nFrom: Joe <joe@client.example>\nSubject: Quote\nDate: 2 November 2026\nTo: info@obras.pt\n\nHi",
            "Fwd: Quote",
        )
        assertEquals("joe@client.example", apple!!.from.email)

        assertNull(ForwardedMail.original("Just a normal email.\nDe: nada", "Olá"))
        assertEquals("Pedido", ForwardedMail.withoutForwardPrefix("Fwd: Enc: RV: Pedido"))
    }

    @Test
    fun `reads Gmail's forwarding confirmation, only from Gmail and only with Google's links`() {
        val at = Instant.parse("2026-11-04T10:00:00Z")
        val text = """
            maria.obras@gmail.com has requested to automatically forward mail to your email address obras.k7p2x9@mail.example.pt.
            Confirmation code: 123456789
            To allow maria.obras@gmail.com to automatically forward mail to your address, please click the link below to confirm the request:
            https://mail-settings.google.com/mail/vf-%5BANGjdJ8x%5D-abcdef
            Phishing: https://evil.example/mail/vf-x
        """.trimIndent()
        val subject = "(#123456789) Gmail Forwarding Confirmation - Receive Mail from maria.obras@gmail.com"
        val confirmation = ForwardedMail.gmailConfirmation(ForwardedMail.GMAIL_FORWARDING, subject, text, verified = true, ours = "obras.k7p2x9@mail.example.pt", at = at)!!
        assertEquals("123456789", confirmation.code)
        assertEquals("https://mail-settings.google.com/mail/vf-%5BANGjdJ8x%5D-abcdef", confirmation.link)
        assertEquals("maria.obras@gmail.com", confirmation.account)

        assertNull(ForwardedMail.gmailConfirmation("forwarding-noreply@google.com", subject, text, verified = false, ours = "x@mail.example.pt", at = at), "a forged sender isn't believed")
        assertNull(ForwardedMail.gmailConfirmation("attacker@evil.example", subject, text, verified = true, ours = "x@mail.example.pt", at = at))
        val onlyEvil = ForwardedMail.gmailConfirmation(ForwardedMail.GMAIL_FORWARDING, "Forwarding", "https://mail.evil.example/mail/vf-1", verified = true, ours = "x@mail.example.pt", at = at)
        assertNull(onlyEvil)
    }

    @Test
    fun `the address is the slug, unaccented, then six random characters`() {
        val address = MailboxAddresses.generate("Família Clean & Co", "mail.example.pt", kotlin.random.Random(1))
        assertTrue(Regex("^familia-clean-co\\.[a-z2-9]{6}@mail\\.example\\.pt$").matches(address), address)
        assertEquals("company", MailboxAddresses.base("---"))
    }
}
