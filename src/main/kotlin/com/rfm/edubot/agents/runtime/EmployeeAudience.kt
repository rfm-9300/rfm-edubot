package com.rfm.edubot.agents.runtime

/**
 * Who a schedule that runs once per employee writes to: active employees with a phone, minus anyone tagged
 * [OPT_OUT_TAG]. The tag is how a person (or the company on their behalf) stops the messages, with no field on
 * the employee. [SubjectQueries] picks the people by these rules and the Briefings page says why someone is left out.
 */
object EmployeeAudience {
    /** The employee tag that keeps a person out of the messages, in any case. */
    const val OPT_OUT_TAG = "no-briefing"

    fun optedOut(tags: Collection<String>): Boolean = tags.any { it.trim().equals(OPT_OUT_TAG, ignoreCase = true) }

    /** A phone with at least one digit; a blank one would fall back to a task every day. */
    fun hasPhone(phone: String?): Boolean = phone.orEmpty().any(Char::isDigit)

    /** [hasPhone] as a Mongo regex, so the query leaves those people out before its limit. */
    const val PHONE_PATTERN = "[0-9]"
}
