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

    @Test
    fun `comment changes are not treated as messages`() {
        val payload = json.decodeFromString<InstagramPayload>(
            """{"object":"instagram","entry":[{"id":"account-id","time":1700000000,"changes":[{"field":"comments","value":{"from":{"id":"user-1","username":"ada"},"media":{"id":"media-1","media_product_type":"FEED"},"id":"comment-1","text":"Nice work"}}]}]}"""
        )

        val entry = payload.entry.single()
        assertTrue(entry.events.isEmpty())
        val comment = entry.commentEvents.single()
        assertEquals("comment-1", comment.id)
        assertEquals("Nice work", comment.text)
        assertEquals("ada", comment.from?.username)
        assertEquals("media-1", comment.media?.id)
    }

    @Test
    fun `message changes still decode after comments share the value object`() {
        val payload = json.decodeFromString<InstagramPayload>(
            """{"object":"instagram","entry":[{"id":"account-id","changes":[{"field":"messages","value":{"sender":{"id":"user-1"},"recipient":{"id":"account-id"},"message":{"mid":"mid-1","text":"Hello"}}}]}]}"""
        )

        val event = payload.entry.single().events.single()
        assertEquals("user-1", event.sender!!.id)
        assertEquals("Hello", event.message!!.text)
        assertTrue(payload.entry.single().commentEvents.isEmpty())
    }
}
