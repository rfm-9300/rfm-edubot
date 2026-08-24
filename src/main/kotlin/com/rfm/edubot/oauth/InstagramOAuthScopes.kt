package com.rfm.edubot.oauth

object InstagramOAuthScopes {
    const val BASIC = "instagram_business_basic"
    const val MESSAGES = "instagram_business_manage_messages"
    const val COMMENTS = "instagram_business_manage_comments"

    val login = listOf(BASIC, MESSAGES, COMMENTS)
    val authorizeParam: String = login.joinToString(",")

    fun hasComments(scopes: Collection<String>): Boolean = COMMENTS in scopes
}
