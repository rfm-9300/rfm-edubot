package com.rfm.edubot.integrations.email

import com.rfm.edubot.integrations.email.MimeMessageBuilder.Address
import jakarta.mail.Message.RecipientType
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import kotlinx.datetime.Instant
import java.io.ByteArrayInputStream
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MimeMessageBuilderTest {
    private val date = Instant.parse("2026-09-30T15:48:00Z")
    private val pdf = ByteArray(5_000) { (it * 31 % 251).toByte() }

    private fun message(
        subject: String = "Orçamento Nº 12 — Obras Silva",
        text: String = "Olá Ana,\n\nSegue em anexo o orçamento.\n\nObras Silva",
        html: String? = "<p>Olá Ana,</p><p>Segue em anexo o orçamento.</p>",
        attachments: List<EmailAttachment> = listOf(EmailAttachment("Orçamento 12.pdf", "application/pdf", pdf)),
        fromName: String? = "Obras Silva, Lda.",
    ) = MimeMessageBuilder.Message(
        from = Address("geral@obras-silva.pt", fromName),
        to = listOf(Address("ana@example.pt", "Ana Sá"), Address("joao@example.pt")),
        cc = listOf(Address("contabilidade@obras-silva.pt")),
        bcc = listOf(Address("arquivo@obras-silva.pt")),
        replyTo = Address("orcamentos@obras-silva.pt"),
        subject = subject,
        text = text,
        html = html,
        attachments = attachments,
        messageId = "<m1@obras-silva.pt>",
        date = date,
    )

    private fun parse(raw: ByteArray) = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(raw))

    private fun String.lf() = replace("\r\n", "\n")

    @Test
    fun `text and html alternatives with a pdf attached read back as written`() {
        val parsed = parse(MimeMessageBuilder.build(message()))

        assertEquals("Orçamento Nº 12 — Obras Silva", parsed.subject)
        val from = parsed.from.single() as InternetAddress
        assertEquals("geral@obras-silva.pt", from.address)
        assertEquals("Obras Silva, Lda.", from.personal)
        val to = parsed.getRecipients(RecipientType.TO).map { it as InternetAddress }
        assertEquals(listOf("ana@example.pt", "joao@example.pt"), to.map { it.address })
        assertEquals("Ana Sá", to.first().personal)
        assertEquals("contabilidade@obras-silva.pt", (parsed.getRecipients(RecipientType.CC).single() as InternetAddress).address)
        assertEquals("arquivo@obras-silva.pt", (parsed.getRecipients(RecipientType.BCC).single() as InternetAddress).address)
        assertEquals("orcamentos@obras-silva.pt", (parsed.replyTo.single() as InternetAddress).address)
        assertEquals("<m1@obras-silva.pt>", parsed.messageID)
        assertEquals(date.toEpochMilliseconds(), parsed.sentDate.time)

        val mixed = parsed.content as MimeMultipart
        assertTrue(mixed.contentType.startsWith("multipart/mixed"))
        assertEquals(2, mixed.count)
        val alternative = mixed.getBodyPart(0).content as MimeMultipart
        assertTrue(alternative.contentType.startsWith("multipart/alternative"))
        assertTrue(alternative.getBodyPart(0).isMimeType("text/plain"))
        assertEquals(message().text, (alternative.getBodyPart(0).content as String).lf())
        assertTrue(alternative.getBodyPart(1).isMimeType("text/html"))
        assertEquals(message().html, (alternative.getBodyPart(1).content as String).lf())

        val attachment = mixed.getBodyPart(1)
        assertTrue(attachment.isMimeType("application/pdf"))
        assertEquals("attachment", attachment.disposition)
        assertEquals("Orçamento 12.pdf", MimeUtility.decodeText(attachment.fileName))
        assertContentEquals(pdf, attachment.inputStream.readBytes())
    }

    @Test
    fun `without attachments or html the message is a single text part`() {
        val parsed = parse(MimeMessageBuilder.build(message(html = null, attachments = emptyList(), fromName = null)))
        assertTrue(parsed.isMimeType("text/plain"))
        assertEquals(message().text, (parsed.content as String).lf())
        assertNull((parsed.from.single() as InternetAddress).personal)
    }

    @Test
    fun `every line is ascii and within 78 characters`() {
        val long = "Pormenores da obra em Évora — ".repeat(20) + "fim   "
        val raw = MimeMessageBuilder.build(message(subject = "Revisão ".repeat(30), text = long, html = "<p>$long</p>"))
        assertTrue(raw.all { it >= 0 }, "7-bit only")
        val lines = String(raw, Charsets.US_ASCII).split("\r\n")
        assertTrue(lines.all { it.length <= 78 }, lines.filter { it.length > 78 }.joinToString("\n"))

        val parsed = parse(raw)
        assertEquals("Revisão ".repeat(30).trim(), parsed.subject)
        val text = ((parsed.content as MimeMultipart).getBodyPart(0).content as MimeMultipart).getBodyPart(0).content as String
        assertEquals(long, text, "trailing spaces survive quoted-printable")
    }

    @Test
    fun `line breaks in names and subjects can't add headers`() {
        val raw = MimeMessageBuilder.build(
            message(subject = "Olá\r\nBcc: intruso@example.com", fromName = "Obras\nBcc: intruso@example.com").copy(bcc = emptyList()),
        )
        val parsed = parse(raw)
        assertNull(parsed.getHeader("Bcc"))
        assertEquals("Olá Bcc: intruso@example.com", parsed.subject)
        assertFailsWith<IllegalArgumentException> { MimeMessageBuilder.build(message().copy(to = listOf(Address("ana@example.pt\r\nBcc: x@y.pt")))) }
        assertFailsWith<IllegalArgumentException> { MimeMessageBuilder.build(message().copy(inReplyTo = "<a@b>\r\nBcc: x@y.pt")) }
    }

    @Test
    fun `a reply names the message it answers`() {
        val parsed = parse(
            MimeMessageBuilder.build(message(subject = "Re: Pedido de orçamento").copy(inReplyTo = "CAF=abc@mail.gmail.com", references = listOf("<first@mail.gmail.com>"))),
        )
        assertEquals("<CAF=abc@mail.gmail.com>", parsed.getHeader("In-Reply-To").single())
        assertEquals(listOf("<first@mail.gmail.com>", "<CAF=abc@mail.gmail.com>"), parsed.getHeader("References").single().split(Regex("\\s+")))
    }

    @Test
    fun `message ids use the sender's domain`() {
        val id = MimeMessageBuilder.newMessageId("geral@obras-silva.pt")
        assertTrue(Regex("^<[0-9a-f]{32}@obras-silva\\.pt>$").matches(id), id)
    }
}
