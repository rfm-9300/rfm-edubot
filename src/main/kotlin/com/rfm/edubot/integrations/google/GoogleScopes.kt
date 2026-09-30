package com.rfm.edubot.integrations.google

/** OAuth scopes the Gmail integration asks Google for. */
object GoogleScopes {
    const val OPENID = "openid"
    const val EMAIL = "email"
    const val GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send"
    const val GMAIL_COMPOSE = "https://www.googleapis.com/auth/gmail.compose"
    const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
    const val GMAIL_MODIFY = "https://www.googleapis.com/auth/gmail.modify"
    const val GMAIL_FULL = "https://mail.google.com/"

    /** What connecting asks for: who the account is, and sending as it (a *sensitive* scope). */
    val send = listOf(OPENID, EMAIL, GMAIL_SEND)

    /** Google's `scope` answer is space-separated. */
    fun parse(scope: String?): List<String> =
        scope.orEmpty().split(' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Any of these lets `users.messages.send` work, so a grant from an earlier, wider consent counts too. */
    fun canSend(granted: Collection<String>): Boolean =
        granted.any { it == GMAIL_SEND || it == GMAIL_COMPOSE || it == GMAIL_MODIFY || it == GMAIL_FULL }
}
