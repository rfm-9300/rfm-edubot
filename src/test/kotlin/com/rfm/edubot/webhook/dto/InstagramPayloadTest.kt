package com.rfm.edubot.webhook.dto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstagramPayloadTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `self messaging echo exposes recipient IGSID`() {
        val payload = json.decodeFromString<InstagramPayload>(
            """{"object":"instagram","entry":[{"id":"account-id","messaging":[{"sender":{"id":"account-id"},"recipient":{"id":"self-igsid"},"message":{"mid":"message-id","text":"Start preview","is_echo":true,"is_self":true}}]}]}"""
        )

        val event = payload.entry.single().events.single()
        assertTrue(event.message!!.isEcho)
        assertTrue(event.message!!.isSelf)
        assertEquals("self-igsid", event.recipient!!.id)
    }
}
