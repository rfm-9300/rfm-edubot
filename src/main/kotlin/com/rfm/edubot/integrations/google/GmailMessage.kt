package com.rfm.edubot.integrations.google

import com.rfm.edubot.integrations.email.EmailAddresses
import com.rfm.edubot.integrations.email.EmailAttachmentInfo
import com.rfm.edubot.integrations.email.EmailMessage
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64

data class MailAddress(val email: String, val name: String? = null)

/** A Gmail message as inbox sync keeps it: decoded headers, its text (the HTML part as text when there's no plain one) and attachment names. */
data class GmailMessage(
    val id: String,
    val threadId: String?,
    val labelIds: Set<String>,
    val date: Instant?,
    val from: MailAddress?,
    val replyTo: MailAddress?,
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    val messageIdHeader: String?,
    val text: String,
    val snippet: String,
    val attachments: List<EmailAttachmentInfo>,
    /** An out-of-office or other automatic answer, or a bounce: kept, but it never wakes an automation. */
    val autoReply: Boolean,
    /** Sent by a machine (newsletters, notifications, no-reply senders): automations may read it, never answer it. */
    val automated: Boolean,
) {
    val hasPdf: Boolean get() = attachments.any { GmailMessages.isPdf(it) }
}

object GmailMessages {
    /** Past this, an HTML body is cut before it is turned into text; the kept text is capped far below anyway. */
    private const val MAX_HTML = 400_000
    private const val MAX_DEPTH = 12

    fun parse(json: JsonObject): GmailMessage? {
        val id = json.string("id") ?: return null
        val payload = json["payload"] as? JsonObject ?: return null
        val headers = headers(payload)
        val walk = Walk()
        walk(payload, walk, 0)
        val text = (walk.plain?.let(::normalize)?.takeIf { it.isNotBlank() } ?: walk.html?.let(::htmlToText).orEmpty()).take(EmailMessage.MAX_BODY)
        val from = addresses(headers["from"]).firstOrNull()
        val subject = decodeHeader(headers["subject"].orEmpty())
        val autoSubmitted = headers["auto-submitted"]?.trim()?.lowercase()
        val precedence = headers["precedence"]?.trim()?.lowercase()
        val local = from?.email?.substringBefore('@').orEmpty()
        val bounce = payload.string("mimeType").equals("multipart/report", ignoreCase = true) || local in BOUNCE_SENDERS
        val automated = bounce ||
            (autoSubmitted != null && autoSubmitted != "no") ||
            precedence in setOf("bulk", "list", "junk", "auto_reply") ||
            "list-id" in headers || "list-unsubscribe" in headers ||
            "x-autoreply" in headers || "x-autorespond" in headers ||
            NO_REPLY.matches(local)
        val autoReply = bounce ||
            autoSubmitted?.startsWith("auto-replied") == true ||
            "x-autoreply" in headers || "x-autorespond" in headers || precedence == "auto_reply" ||
            (automated && OUT_OF_OFFICE.containsMatchIn(subject))
        return GmailMessage(
            id = id,
            threadId = json.string("threadId"),
            labelIds = (json["labelIds"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(),
            date = json.string("internalDate")?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it) },
            from = from,
            replyTo = addresses(headers["reply-to"]).firstOrNull(),
            to = addresses(headers["to"]).map { it.email },
            cc = addresses(headers["cc"]).map { it.email },
            subject = subject,
            messageIdHeader = headers["message-id"]?.trim()?.takeIf { it.isNotEmpty() },
            text = text,
            snippet = EmailMessage.snippetOf(text.ifBlank { decodeEntities(json.string("snippet").orEmpty()) }),
            attachments = walk.attachments,
            autoReply = autoReply,
            automated = automated,
        )
    }

    fun isPdf(attachment: EmailAttachmentInfo): Boolean =
        attachment.mimeType.equals("application/pdf", ignoreCase = true) || attachment.filename.endsWith(".pdf", ignoreCase = true)

    /** `"Maria" <maria@example.com>, joao@example.com` → the valid addresses, lower-cased, with their names. */
    fun addresses(raw: String?): List<MailAddress> {
        if (raw.isNullOrBlank()) return emptyList()
        return split(unfold(raw)).mapNotNull { part ->
            val open = part.lastIndexOf('<')
            val close = part.lastIndexOf('>')
            val (address, name) = if (open >= 0 && close > open) {
                part.substring(open + 1, close) to part.substring(0, open)
            } else {
                part.replace(COMMENT, "").trim() to COMMENT.find(part)?.groupValues?.get(1).orEmpty()
            }
            val email = EmailAddresses.normalize(address) ?: return@mapNotNull null
            MailAddress(email, decodeHeader(name.trim().trim('"').replace("\\\"", "\"")).trim().takeIf { it.isNotEmpty() && it != email })
        }.distinctBy { it.email }
    }

    /** Decodes RFC 2047 encoded words (`=?UTF-8?B?…?=`); adjacent words of one charset are joined before decoding. */
    fun decodeHeader(raw: String): String {
        if (!raw.contains("=?")) return unfold(raw)
        val out = StringBuilder()
        val bytes = ByteArrayOutputStream()
        var charset: Charset? = null
        var last = 0
        var previousEncoded = false
        fun flush() {
            if (bytes.size() > 0) out.append(text(bytes.toByteArray(), charset))
            bytes.reset()
        }
        for (match in ENCODED_WORD.findAll(raw)) {
            val between = raw.substring(last, match.range.first)
            last = match.range.last + 1
            val decoded = wordBytes(match.groupValues[2], match.groupValues[3])
            if (decoded == null) {
                flush()
                out.append(between).append(match.value)
                previousEncoded = false
                continue
            }
            val wordCharset = charsetOf(match.groupValues[1])
            // Whitespace between two encoded words isn't part of the text (RFC 2047 §6.2).
            val joins = previousEncoded && between.isBlank()
            if (!joins || wordCharset != charset) flush()
            if (!joins) out.append(between)
            charset = wordCharset
            bytes.write(decoded)
            previousEncoded = true
        }
        flush()
        out.append(raw.substring(last))
        return unfold(out.toString())
    }

    fun htmlToText(html: String): String {
        var text = html.take(MAX_HTML)
        text = HIDDEN_BLOCKS.replace(text, " ")
        text = COMMENTS.replace(text, " ")
        text = LINE_BREAK.replace(text, "\n")
        text = PARAGRAPH_END.replace(text, "\n\n")
        text = BLOCK_END.replace(text, "\n")
        text = LIST_ITEM.replace(text, "\n- ")
        text = TAG.replace(text, "")
        return normalize(decodeEntities(text))
    }

    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        return ENTITY.replace(text) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
                name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
                else -> NAMED_ENTITIES[name]
            } ?: match.value
        }
    }

    private class Walk {
        var plain: String? = null
        var html: String? = null
        val attachments = mutableListOf<EmailAttachmentInfo>()
    }

    private fun walk(part: JsonObject, walk: Walk, depth: Int) {
        if (depth > MAX_DEPTH) return
        val mime = part.string("mimeType")?.lowercase().orEmpty()
        val headers = headers(part)
        val filename = decodeHeader(part.string("filename").orEmpty())
        val disposition = headers["content-disposition"]?.trim()?.lowercase().orEmpty()
        val body = part["body"] as? JsonObject
        if (filename.isNotBlank()) {
            // Pictures a signature or a newsletter shows in its text aren't what someone attached.
            val embedded = disposition.startsWith("inline") && "content-id" in headers && mime.startsWith("image/")
            if (!embedded) walk.attachments += EmailAttachmentInfo(filename, mime, (body?.get("size") as? JsonPrimitive)?.intOrNull ?: 0)
            return
        }
        if (disposition.startsWith("attachment")) return
        when (mime) {
            "text/plain" -> if (walk.plain == null) walk.plain = body?.let { bodyText(it, headers["content-type"]) }
            "text/html" -> if (walk.html == null) walk.html = body?.let { bodyText(it, headers["content-type"]) }
        }
        (part["parts"] as? JsonArray).orEmpty().forEach { child -> (child as? JsonObject)?.let { walk(it, walk, depth + 1) } }
    }

    private fun bodyText(body: JsonObject, contentType: String?): String? {
        val data = body.string("data")?.takeIf { it.isNotEmpty() } ?: return null
        val bytes = runCatching {
            Base64.getUrlDecoder().decode(data.filterNot { it.isWhitespace() }.replace('+', '-').replace('/', '_'))
        }.getOrNull() ?: return null
        return text(bytes, CHARSET_PARAM.find(contentType.orEmpty())?.groupValues?.get(1)?.let(::charsetOf))
    }

    /** Honours the declared charset; undeclared or UTF-8 text that isn't valid UTF-8 is read as Windows-1252. */
    private fun text(bytes: ByteArray, charset: Charset?): String {
        if (charset != null && charset != Charsets.UTF_8 && charset != Charsets.US_ASCII) return String(bytes, charset)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, WINDOWS_1252)
        }
    }

    private fun charsetOf(name: String): Charset? =
        runCatching { Charset.forName(name.trim().trim('"', '\'').substringBefore('*')) }.getOrNull()

    private fun wordBytes(encoding: String, data: String): ByteArray? = when (encoding.uppercase()) {
        "B" -> runCatching { Base64.getMimeDecoder().decode(data) }.getOrNull()
        "Q" -> {
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < data.length) {
                val c = data[i]
                when {
                    c == '_' -> out.write(' '.code)
                    c == '=' && i + 2 < data.length -> {
                        val byte = data.substring(i + 1, i + 3).toIntOrNull(16)
                        if (byte == null) out.write(c.code) else {
                            out.write(byte)
                            i += 2
                        }
                    }
                    else -> out.write(c.code and 0xFF)
                }
                i++
            }
            out.toByteArray()
        }
        else -> null
    }

    private fun headers(part: JsonObject): Map<String, String> =
        (part["headers"] as? JsonArray).orEmpty().mapNotNull { element ->
            val header = element as? JsonObject ?: return@mapNotNull null
            val name = header.string("name")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            name to header.string("value").orEmpty()
        }.distinctBy { it.first }.toMap()

    /** Splits an address list on commas outside quotes and angle brackets. */
    private fun split(raw: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var angle = false
        var escaped = false
        for (c in raw) {
            when {
                escaped -> escaped = false
                c == '\\' && quoted -> escaped = true
                c == '"' -> quoted = !quoted
                c == '<' && !quoted -> angle = true
                c == '>' && !quoted -> angle = false
                (c == ',' || c == ';') && !quoted && !angle -> {
                    parts += current.toString()
                    current.clear()
                    continue
                }
            }
            current.append(c)
        }
        parts += current.toString()
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun unfold(value: String): String = value.replace(FOLD, " ").trim()

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ')
            .lines().joinToString("\n") { it.replace(SPACES, " ").trim() }
            .replace(BLANK_LINES, "\n\n")
            .trim()

    private fun codePoint(value: Int): String? =
        if (value > 0 && Character.isValidCodePoint(value)) String(Character.toChars(value)) else null

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")
    private val ENCODED_WORD = Regex("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?\\s]*)\\?=")
    private val CHARSET_PARAM = Regex("charset\\s*=\\s*\"?([^\";\\s]+)\"?", RegexOption.IGNORE_CASE)
    private val COMMENT = Regex("\\(([^)]*)\\)")
    private val FOLD = Regex("\\r?\\n[ \\t]+|\\s+")
    private val SPACES = Regex("[ \\t\\f\\u000B]+")
    private val BLANK_LINES = Regex("\\n{3,}")
    private val HIDDEN_BLOCKS = Regex("(?is)<(script|style|head|title)\\b.*?</\\1\\s*>")
    private val COMMENTS = Regex("(?s)<!--.*?-->")
    private val LINE_BREAK = Regex("(?i)<br\\s*/?>")
    private val PARAGRAPH_END = Regex("(?i)</(p|h[1-6]|table|blockquote)\\s*>")
    private val BLOCK_END = Regex("(?i)</(div|tr|section|article|header|footer|ul|ol)\\s*>")
    private val LIST_ITEM = Regex("(?i)<li\\b[^>]*>")
    private val TAG = Regex("(?s)<[^>]*>")
    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")
    private val NO_REPLY = Regex("^(no[-_.]?reply|do[-_.]?not[-_.]?reply|notifications?|mailer[-_.]?daemon|postmaster|bounces?)([-+._].*)?$", RegexOption.IGNORE_CASE)
    private val BOUNCE_SENDERS = setOf("mailer-daemon", "postmaster")
    private val OUT_OF_OFFICE = Regex(
        "^(auto(matic)?[ -]?(reply|response)|out of (the )?office|resposta autom[aá]tica|respuesta autom[aá]tica|fora do escrit[oó]rio|fuera de la oficina|ausente|r[ée]ponse automatique|abwesenheit)",
        RegexOption.IGNORE_CASE,
    )

    private val LATIN1_ENTITIES = (
        "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 acute micro para " +
            "middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute " +
            "Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute " +
            "THORN szlig agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde " +
            "ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
        ).split(' ').withIndex().associate { (index, name) -> name to String(Character.toChars(160 + index)) }

    private val NAMED_ENTITIES: Map<String, String> = LATIN1_ENTITIES + mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "euro" to "€", "hellip" to "…", "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "sbquo" to "‚", "bdquo" to "„", "bull" to "•", "trade" to "™",
        "zwnj" to "", "zwj" to "", "lrm" to "", "rlm" to "",
    )
}
