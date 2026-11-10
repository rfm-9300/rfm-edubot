package com.rfm.edubot.integrations.email

/**
 * Whether a received email came from a machine, read from its headers the same way for Gmail's inbox
 * sync and for mail forwarded to a company's address.
 */
object MailHeuristics {
    /**
     * [automated]: a newsletter, a notification, a no-reply sender, a bounce or an automatic reply, which
     * automations may read but never answer. [autoReply]: an out-of-office or other automatic answer, or a
     * bounce, kept but never announced, so two mailboxes can't answer each other forever.
     */
    data class Kind(val automated: Boolean, val autoReply: Boolean)

    /** [headers] by lower-cased name; [mimeType] is the message's own content type. */
    fun classify(headers: Map<String, String>, fromEmail: String?, subject: String, mimeType: String?): Kind {
        val autoSubmitted = headers["auto-submitted"]?.trim()?.lowercase()
        val precedence = headers["precedence"]?.trim()?.lowercase()
        val local = fromEmail?.substringBefore('@').orEmpty()
        val bounce = mimeType.orEmpty().trim().lowercase().startsWith("multipart/report") || local.lowercase() in BOUNCE_SENDERS
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
        return Kind(automated, autoReply)
    }

    private val NO_REPLY = Regex("^(no[-_.]?reply|do[-_.]?not[-_.]?reply|notifications?|mailer[-_.]?daemon|postmaster|bounces?)([-+._].*)?$", RegexOption.IGNORE_CASE)
    private val BOUNCE_SENDERS = setOf("mailer-daemon", "postmaster")
    private val OUT_OF_OFFICE = Regex(
        "^(auto(matic)?[ -]?(reply|response)|out of (the )?office|resposta autom[aá]tica|respuesta autom[aá]tica|fora do escrit[oó]rio|fuera de la oficina|ausente|r[ée]ponse automatique|abwesenheit)",
        RegexOption.IGNORE_CASE,
    )
}
