package com.rfm.edubot.integrations.email

import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import java.security.SecureRandom
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * An RFC 5322 message as Gmail's `messages.send` takes it: plain text with an HTML alternative, PDF
 * attachments, UTF-8 headers as RFC 2047 encoded words, and `In-Reply-To` / `References` for replies.
 * Addresses must come through [EmailAddresses]; names, the subject and file names lose their line
 * breaks, so no value can add a header of its own.
 */
object MimeMessageBuilder {
    data class Address(val email: String, val name: String? = null)

    data class Message(
        val from: Address,
        val to: List<Address>,
        val subject: String,
        val text: String,
        val html: String? = null,
        val cc: List<Address> = emptyList(),
        val bcc: List<Address> = emptyList(),
        val replyTo: Address? = null,
        val attachments: List<EmailAttachment> = emptyList(),
        /** With angle brackets, as [newMessageId] makes it. */
        val messageId: String,
        val inReplyTo: String? = null,
        val references: List<String> = emptyList(),
        val date: Instant,
    )

    private const val CRLF = "\r\n"
    private val random = SecureRandom()
    private val dateFormat = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)

    fun newMessageId(fromEmail: String): String {
        val domain = fromEmail.substringAfter('@', "").ifBlank { "localhost" }
        return "<${hex(16)}@$domain>"
    }

    fun build(message: Message): ByteArray {
        require(message.to.isNotEmpty()) { "an email needs a recipient" }
        val out = StringBuilder()
        header(out, "From", address(message.from))
        header(out, "To", message.to.joinToString(",$CRLF ") { address(it) })
        if (message.cc.isNotEmpty()) header(out, "Cc", message.cc.joinToString(",$CRLF ") { address(it) })
        if (message.bcc.isNotEmpty()) header(out, "Bcc", message.bcc.joinToString(",$CRLF ") { address(it) })
        message.replyTo?.let { header(out, "Reply-To", address(it)) }
        header(out, "Subject", encodeText(message.subject))
        header(out, "Date", dateFormat.format(message.date.toJavaInstant().atOffset(ZoneOffset.UTC)))
        header(out, "Message-ID", messageIdHeader(message.messageId))
        message.inReplyTo?.let { header(out, "In-Reply-To", messageIdHeader(it)) }
        val references = (message.references + listOfNotNull(message.inReplyTo)).map { messageIdHeader(it) }.distinct()
        if (references.isNotEmpty()) header(out, "References", references.joinToString("$CRLF "))
        header(out, "MIME-Version", "1.0")

        val body = bodyPart(message)
        if (message.attachments.isEmpty()) {
            out.append(body)
        } else {
            val boundary = boundary()
            header(out, "Content-Type", "multipart/mixed;$CRLF boundary=\"$boundary\"")
            out.append(CRLF)
            out.append("--").append(boundary).append(CRLF).append(body)
            message.attachments.forEach { attachment ->
                out.append(CRLF).append("--").append(boundary).append(CRLF).append(attachmentPart(attachment))
            }
            out.append(CRLF).append("--").append(boundary).append("--").append(CRLF)
        }
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    /** The readable part: text alone, or text and HTML as alternatives. Starts with its own headers. */
    private fun bodyPart(message: Message): String {
        val text = textPart("text/plain", message.text)
        val html = message.html ?: return text
        val boundary = boundary()
        return buildString {
            append("Content-Type: multipart/alternative;$CRLF boundary=\"$boundary\"").append(CRLF).append(CRLF)
            append("--").append(boundary).append(CRLF).append(text).append(CRLF)
            append("--").append(boundary).append(CRLF).append(textPart("text/html", html)).append(CRLF)
            append("--").append(boundary).append("--")
        }
    }

    private fun textPart(type: String, content: String): String = buildString {
        append("Content-Type: $type; charset=UTF-8").append(CRLF)
        append("Content-Transfer-Encoding: quoted-printable").append(CRLF).append(CRLF)
        append(quotedPrintable(content))
    }

    private fun attachmentPart(attachment: EmailAttachment): String = buildString {
        val filename = cleanFilename(attachment.filename)
        val mimeType = attachment.mimeType.takeIf { MIME_TYPE.matches(it) } ?: "application/octet-stream"
        if (filename.all { it.code in 0x20..0x7e }) {
            append("Content-Type: $mimeType; name=\"$filename\"").append(CRLF)
            append("Content-Disposition: attachment; filename=\"$filename\"").append(CRLF)
        } else {
            append("Content-Type: $mimeType; name=\"${encodedWords(filename).joinToString(" ")}\"").append(CRLF)
            append("Content-Disposition: attachment; filename*=UTF-8''${percentEncode(filename)}").append(CRLF)
        }
        append("Content-Transfer-Encoding: base64").append(CRLF).append(CRLF)
        append(Base64.getMimeEncoder(76, CRLF.toByteArray()).encodeToString(attachment.bytes))
    }

    private fun header(out: StringBuilder, name: String, value: String) {
        out.append(name).append(": ").append(value).append(CRLF)
    }

    private fun address(address: Address): String {
        val email = requireNotNull(EmailAddresses.normalize(address.email)) { "not an email address" }
        val name = address.name?.let(::oneLine)?.takeIf { it.isNotEmpty() } ?: return email
        return "${encodePhrase(name)} <$email>"
    }

    /** A display name: quoted when it is plain ASCII, encoded words otherwise. */
    private fun encodePhrase(name: String): String =
        if (isPlain(name)) "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else encodedWords(name).joinToString("$CRLF ")

    private fun encodeText(value: String): String {
        val text = oneLine(value)
        return if (isPlain(text)) text else encodedWords(text).joinToString("$CRLF ")
    }

    private fun isPlain(text: String) = text.all { it.code in 0x20..0x7e } && "=?" !in text

    /**
     * RFC 2047 "B" words holding whole characters: 39 UTF-8 bytes make 52 base64 characters, 64 with
     * the `=?UTF-8?B?` and `?=` around them, so even `Reply-To: ` and a word stay within 76 per line.
     */
    private fun encodedWords(text: String): List<String> {
        val words = mutableListOf<String>()
        val chunk = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val char = String(Character.toChars(codePoint))
            val size = char.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 39 && chunk.isNotEmpty()) {
                words += word(chunk.toString())
                chunk.clear()
                bytes = 0
            }
            chunk.append(char)
            bytes += size
            i += Character.charCount(codePoint)
        }
        if (chunk.isNotEmpty() || words.isEmpty()) words += word(chunk.toString())
        return words
    }

    private fun word(text: String) = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)) + "?="

    /** RFC 2045 quoted-printable of UTF-8 text, lines at most 76 characters, line breaks as CRLF. */
    internal fun quotedPrintable(content: String): String {
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return lines.joinToString(CRLF) { line -> encodeLine(line.toByteArray(Charsets.UTF_8)) }
    }

    private fun encodeLine(bytes: ByteArray): String {
        val out = StringBuilder()
        var width = 0
        bytes.forEachIndexed { index, byte ->
            val b = byte.toInt() and 0xff
            val last = index == bytes.lastIndex
            val literal = (b in 33..126 && b != '='.code) || ((b == ' '.code || b == '\t'.code) && !last)
            val token = if (literal) b.toChar().toString() else "=" + HEX[b shr 4] + HEX[b and 0x0f]
            // 75 characters plus the soft break's "=" keep every line within 76.
            if (width + token.length > 75) {
                out.append("=").append(CRLF)
                width = 0
            }
            out.append(token)
            width += token.length
        }
        return out.toString()
    }

    private fun messageIdHeader(id: String): String {
        val bare = oneLine(id).trim().removePrefix("<").removeSuffix(">")
        require(bare.isNotEmpty() && bare.none { it.isWhitespace() || it == '<' || it == '>' }) { "not a message id" }
        return "<$bare>"
    }

    private fun cleanFilename(name: String): String =
        oneLine(name).replace(Regex("[\"\\\\/]"), "_").trim().take(120).ifEmpty { "attachment" }

    private fun percentEncode(text: String): String = buildString {
        text.toByteArray(Charsets.UTF_8).forEach { byte ->
            val b = byte.toInt() and 0xff
            if (b.toChar().isLetterOrDigit() && b < 0x80 || b.toChar() in "-._~") append(b.toChar()) else append('%').append(HEX[b shr 4]).append(HEX[b and 0x0f])
        }
    }

    private fun oneLine(value: String): String = value.map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(Regex(" {2,}"), " ").trim()

    private fun boundary() = "=_edubot_" + hex(12)

    private fun hex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private const val HEX = "0123456789ABCDEF"
    private val MIME_TYPE = Regex("^[a-z0-9.+-]+/[a-z0-9.+-]+$")
}
