package com.rfm.edubot.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstagramOAuthScopesTest {
    @Test
    fun `login scope includes comments alongside DMs`() {
        assertTrue(InstagramOAuthScopes.COMMENTS in InstagramOAuthScopes.login)
        assertTrue(InstagramOAuthScopes.MESSAGES in InstagramOAuthScopes.login)
        assertEquals(
            "instagram_business_basic,instagram_business_manage_messages,instagram_business_manage_comments",
            InstagramOAuthScopes.authorizeParam,
        )
    }

    @Test
    fun `older bindings without stored scopes need a reconnect for comments`() {
        assertFalse(InstagramOAuthScopes.hasComments(emptyList()))
        assertTrue(InstagramOAuthScopes.hasComments(InstagramOAuthScopes.login))
    }
}
