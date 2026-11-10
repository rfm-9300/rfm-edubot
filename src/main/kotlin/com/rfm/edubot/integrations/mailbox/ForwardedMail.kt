package com.rfm.edubot.integrations.mailbox

import com.rfm.edubot.integrations.ForwardingConfirmation
import com.rfm.edubot.integrations.email.EmailAddresses
import com.rfm.edubot.integrations.email.MailAddress
import com.rfm.edubot.integrations.google.GmailMessages
import kotlinx.datetime.Instant

/**
 * What mail forwarded to a company's address says about how it got there: who wrote an email someone
 * at the company forwarded by hand, which mailbox forwards automatically, and Gmail's request to
 * confirm forwarding.
 */
object ForwardedMail {
    /** Who wrote an email forwarded by hand, read from the header block the mail program quotes. */
    data class Original(val from: MailAddress, val subject: String?)

    /**
     * The sender (and subject) of the email [text] forwards, from the block Gmail, Outlook, Apple Mail and
     * others put above it ("---------- Forwarded message ---------", "De: …", in several languages). Only
     * the first forwarded email counts; null when the text forwards nothing.
     */
    fun original(text: String, subject: String): Original? {
        val lines = text.lines()
        val marker = lines.indexOfFirst { line -> MARKERS.any { it.containsMatchIn(line.trim()) } }
        val start = when {
            marker >= 0 -> marker + 1
            // A forward whose separator didn't survive the HTML: trust the subject, look near the top.
            FORWARD_SUBJECT.containsMatchIn(subject.trim()) -> 0
            else -> return null
        }
        val window = lines.drop(start).take(if (marker >= 0) BLOCK_LINES else TOP_LINES)
        val fromIndex = window.indexOfFirst { FROM_LINE.matches(it.trim()) }
        if (fromIndex < 0) return null
        val from = address(FROM_LINE.find(window[fromIndex].trim())!!.groupValues[2]) ?: return null
        val forwardedSubject = window.drop(fromIndex).take(BLOCK_LINES).firstNotNullOfOrNull { SUBJECT_LINE.find(it.trim())?.groupValues?.get(2)?.trim() }
            ?.takeIf { it.isNotEmpty() }
        return Original(from, forwardedSubject)
    }

    /** The subject without the "Fwd:"/"Enc:"/"RV:" its forward added. */
    fun withoutForwardPrefix(subject: String): String {
        var out = subject.trim()
        while (true) {
            val stripped = out.replace(FORWARD_SUBJECT, "").trim()
            if (stripped == out) return out
            out = stripped
        }
    }

    /**
     * The mailbox forwarding to [ours]: Gmail and Google Workspace name it in `X-Forwarded-For` and in the
     * envelope sender (`geral+caf_=…@empresa.pt`). Null when the mail doesn't say (other providers).
     */
    fun forwarder(headers: Map<String, String>, envelopeSource: String?, ours: String): String? {
        headers["x-forwarded-for"]?.split(' ', ',')?.firstNotNullOfOrNull { EmailAddresses.normalize(it.trim('<', '>')) }
            ?.takeIf { it != ours }?.let { return it }
        val source = envelopeSource?.trim()?.lowercase() ?: return null
        val local = source.substringBefore('@')
        if ("+caf_=" !in local) return null
        return EmailAddresses.normalize("${local.substringBefore("+caf_=")}@${source.substringAfter('@')}")?.takeIf { it != ours }
    }

    /**
     * Gmail's "confirm forwarding" email sent to the company's address: the confirmation code, the link
     * that confirms it and the Gmail account asking. Only from Gmail's own sender, when [verified] (SES
     * found Google's DKIM signature valid), and only links on Google's mail settings hosts.
     */
    fun gmailConfirmation(from: String?, subject: String, text: String, verified: Boolean, ours: String, at: Instant): ForwardingConfirmation? {
        if (from != GMAIL_FORWARDING || !verified) return null
        val code = SUBJECT_CODE.find(subject)?.groupValues?.get(1) ?: BODY_CODE.find(text)?.groupValues?.get(1)
        val link = LINK.findAll(text).map { it.value.trimEnd('.', ',', ';', ')') }.firstOrNull { link ->
            runCatching { java.net.URI(link).host?.lowercase() in CONFIRM_HOSTS }.getOrDefault(false)
        }
        if (code == null && link == null) return null
        val account = (EMAIL.findAll(subject) + EMAIL.findAll(text)).mapNotNull { EmailAddresses.normalize(it.value) }
            .firstOrNull { it != ours && it != GMAIL_FORWARDING }
        return ForwardingConfirmation(code = code, link = link, account = account, at = at)
    }

    /** "Maria Silva <maria@x.pt>", "maria@x.pt", "Maria Silva [mailto:maria@x.pt]": the address and the name. */
    private fun address(raw: String): MailAddress? {
        val parsed = GmailMessages.addresses(raw.replace("*", "")).firstOrNull()
            ?: EMAIL.find(raw)?.value?.let(EmailAddresses::normalize)?.let { email ->
                MailAddress(email, raw.replace("*", "").substringBefore('[').substringBefore('<').replace(EMAIL, "").trim().trim('"', '\'').takeIf { it.isNotEmpty() })
            }
            ?: return null
        return parsed.copy(name = parsed.name?.trim()?.takeIf { it.isNotEmpty() && it != parsed.email })
    }

    const val GMAIL_FORWARDING = "forwarding-noreply@google.com"

    private const val BLOCK_LINES = 12
    private const val TOP_LINES = 60
    private val CONFIRM_HOSTS = setOf("mail-settings.google.com", "mail.google.com", "isolated.mail.google.com")

    private val MARKERS = listOf(
        // Gmail and others: "---------- Forwarded message ---------"
        Regex(
            "^-{2,}\\s*(forwarded message|original message|mensagem encaminhada|mensagem reencaminhada|mensagem original|mensaje reenviado|mensaje original|message transf[ée]r[ée]|message d'origine|weitergeleitete nachricht|urspr[üu]ngliche nachricht|messaggio inoltrato|messaggio originale)\\s*-{2,}",
            RegexOption.IGNORE_CASE,
        ),
        // Apple Mail: "Begin forwarded message:"
        Regex(
            "^(begin forwarded message|in[íi]cio da mensagem (re)?encaminhada|inicio del mensaje reenviado|d[ée]but du message (r[ée]exp[ée]di[ée]|transf[ée]r[ée])|anfang der weitergeleiteten nachricht)\\s*:",
            RegexOption.IGNORE_CASE,
        ),
        // Outlook: a rule of underscores above the quoted header.
        Regex("^_{10,}$"),
    )
    // Plain text versions bold the labels with asterisks: "*From:* Maria", "From: *Maria* <maria@x.pt>".
    private val FROM_LINE = Regex("^\\*?(from|de|von|da|van)\\*?\\s*:\\*?\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val SUBJECT_LINE = Regex("^\\*?(subject|assunto|asunto|objet|betreff|oggetto|onderwerp)\\*?\\s*:\\*?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val FORWARD_SUBJECT = Regex("^((fwd?|enc|rv|reenv|tr|wg)\\s*:\\s*)+", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+")
    private val SUBJECT_CODE = Regex("\\(#(\\d{5,12})\\)")
    private val BODY_CODE = Regex("(?i)(?:confirmation code|c[oó]digo de confirma[cç][aã]o|c[oó]digo de confirmaci[oó]n|code de confirmation|best[äa]tigungscode)\\s*:?\\s*(\\d{5,12})")
    private val LINK = Regex("https://[A-Za-z0-9.-]+\\.google\\.com/mail/[^\\s<>\"'\\]]+")
}
