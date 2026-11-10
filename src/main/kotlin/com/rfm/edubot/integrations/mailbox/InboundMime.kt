package com.rfm.edubot.integrations.mailbox

import com.rfm.edubot.integrations.email.EmailAttachmentInfo
import com.rfm.edubot.integrations.email.EmailMessage
import com.rfm.edubot.integrations.email.MailAddress
import com.rfm.edubot.integrations.email.MailHeuristics
import com.rfm.edubot.integrations.google.GmailMessages
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinInstant
import java.io.InputStream
import java.util.Properties

/** A received email read from its raw MIME, as SES stored it. */
data class InboundMail(
    val messageIdHeader: String?,
    /** The message ids it answers: In-Reply-To first, then References. */
    val inReplyTo: List<String>,
    val references: List<String>,
    val from: MailAddress?,
    val replyTo: MailAddress?,
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    /** Its text: the plain part, else the HTML part as text; capped. */
    val text: String,
    val attachments: List<EmailAttachmentInfo>,
    val automated: Boolean,
    val autoReply: Boolean,
    /** The first value of each header, by lower-cased name, unfolded. */
    val headers: Map<String, String>,
    /** An email attached whole (`message/rfc822`), as "Forward as attachment" sends one. */
    val attached: InboundMail?,
    /** Its Date header; when it reached the company is SES's to say. */
    val sentAt: Instant?,
)

/**
 * Reads raw MIME into an [InboundMail] the way Gmail's inbox sync reads Gmail's payload
 * ([GmailMessages]): the same address and header decoding, charset fallback, HTML to text, attachment
 * names (pictures a signature shows inline aren't attachments) and machine-mail heuristics.
 */
object InboundMime {
    private const val MAX_DEPTH = 12
    private const val MAX_PARTS = 300
    /** Text read per part before it's cut; the kept text is capped far below anyway. */
    private const val MAX_TEXT_BYTES = 1_000_000

    private val session: Session = Session.getInstance(
        Properties().apply {
            // Mail from the wild: take what mail programs actually send.
            put("mail.mime.address.strict", "false")
            put("mail.mime.decodetext.strict", "false")
            put("mail.mime.parameters.strict", "false")
            put("mail.mime.decodefilename", "true")
            put("mail.mime.allowutf8", "true")
        },
    )

    /** Null when [raw] can't be read as an email at all. */
    fun parse(raw: ByteArray): InboundMail? = runCatching { read(MimeMessage(session, raw.inputStream()), 0) }.getOrNull()

    private fun read(message: MimeMessage, depth: Int): InboundMail {
        val headers = headers(message)
        val walk = Walk()
        walk(message, walk, depth)
        val text = (walk.plain?.let(GmailMessages::normalizeText)?.takeIf { it.isNotBlank() } ?: walk.html?.let(GmailMessages::htmlToText).orEmpty())
            .take(EmailMessage.MAX_BODY)
        val from = GmailMessages.addresses(headers["from"]).firstOrNull()
        val subject = GmailMessages.decodeHeader(headers["subject"].orEmpty())
        val kind = MailHeuristics.classify(headers, from?.email, subject, runCatching { message.contentType }.getOrNull())
        return InboundMail(
            messageIdHeader = ids(headers["message-id"]).firstOrNull() ?: headers["message-id"]?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) },
            inReplyTo = ids(headers["in-reply-to"]),
            references = ids(headers["references"]),
            from = from,
            replyTo = GmailMessages.addresses(headers["reply-to"]).firstOrNull(),
            to = GmailMessages.addresses(headers["to"]).map { it.email },
            cc = GmailMessages.addresses(headers["cc"]).map { it.email },
            subject = subject,
            text = text,
            attachments = walk.attachments,
            automated = kind.automated,
            autoReply = kind.autoReply,
            headers = headers,
            attached = walk.attached,
            sentAt = runCatching { message.sentDate?.toInstant()?.toKotlinInstant() }.getOrNull(),
        )
    }

    private class Walk {
        var plain: String? = null
        var html: String? = null
        var attached: InboundMail? = null
        var parts = 0
        val attachments = mutableListOf<EmailAttachmentInfo>()
    }

    private fun walk(part: Part, walk: Walk, depth: Int) {
        if (depth > MAX_DEPTH || ++walk.parts > MAX_PARTS) return
        val contentType = runCatching { part.contentType }.getOrNull().orEmpty()
        val mime = contentType.substringBefore(';').trim().lowercase()
        val disposition = runCatching { part.disposition }.getOrNull()?.trim()?.lowercase().orEmpty()
        if (mime.startsWith("multipart/")) {
            val multipart = runCatching { part.content as? Multipart }.getOrNull() ?: return
            for (i in 0 until multipart.count) runCatching { multipart.getBodyPart(i) }.getOrNull()?.let { walk(it, walk, depth + 1) }
            return
        }
        if (mime == "message/rfc822") {
            val inner = runCatching { part.content as? MimeMessage }.getOrNull()
            if (inner != null && walk.attached == null) walk.attached = runCatching { read(inner, depth + 1) }.getOrNull()
            return
        }
        val filename = runCatching { part.fileName }.getOrNull()?.let(::decodeFilename)?.trim().orEmpty()
        if (filename.isNotEmpty()) {
            // Pictures a signature or a newsletter shows in its text aren't what someone attached.
            val embedded = disposition.startsWith("inline") && part.getHeader("Content-ID") != null && mime.startsWith("image/")
            if (!embedded) walk.attachments += EmailAttachmentInfo(filename.take(MAX_FILENAME), mime.ifEmpty { "application/octet-stream" }, size(part))
            return
        }
        if (disposition.startsWith("attachment")) return
        when (mime) {
            "text/plain" -> if (walk.plain == null) walk.plain = text(part, contentType)
            "text/html" -> if (walk.html == null) walk.html = text(part, contentType)
        }
    }

    /** The part's decoded bytes as text, in its declared charset (or the same fallback Gmail's reading uses). */
    private fun text(part: Part, contentType: String): String? = runCatching {
        val stream: InputStream = part.inputStream
        stream.use { GmailMessages.decodeText(it.readNBytes(MAX_TEXT_BYTES), contentType) }
    }.getOrNull()

    /** The attachment's size as sent, base64's overhead taken off. */
    private fun size(part: Part): Int {
        val encoded = runCatching { part.size }.getOrDefault(-1)
        if (encoded < 0) return 0
        val encoding = runCatching { (part as? jakarta.mail.internet.MimePart)?.encoding }.getOrNull()?.lowercase()
        return if (encoding == "base64") (encoded.toLong() * 3 / 4).toInt() else encoded
    }

    private fun decodeFilename(name: String): String = runCatching { MimeUtility.decodeText(name) }.getOrDefault(name)

    /** Every header's first value, unfolded, by lower-cased name. */
    private fun headers(message: Message): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val all = runCatching { message.allHeaders }.getOrNull() ?: return out
        while (all.hasMoreElements()) {
            val header = all.nextElement()
            val name = header.name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: continue
            if (name !in out) out[name] = header.value.orEmpty().replace(FOLD, " ").trim()
        }
        return out
    }

    /** The `<…>` message ids in a header, in order, each once. */
    fun ids(raw: String?): List<String> = MESSAGE_ID.findAll(raw.orEmpty()).map { it.value }.distinct().take(MAX_IDS).toList()

    private const val MAX_FILENAME = 200
    private const val MAX_IDS = 50
    private val FOLD = Regex("\\r?\\n[ \\t]+")
    private val MESSAGE_ID = Regex("<[^<>\\s]{3,250}>")
}
