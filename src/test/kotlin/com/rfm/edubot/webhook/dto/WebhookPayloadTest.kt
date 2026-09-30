package com.rfm.edubot.webhook.dto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebhookPayloadTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a failed status carries Meta's error code and details`() {
        val payload = json.decodeFromString<WebhookPayload>(
            """{"object":"whatsapp_business_account","entry":[{"id":"waba-1","changes":[{"field":"messages","value":{
              "messaging_product":"whatsapp","metadata":{"display_phone_number":"15553526936","phone_number_id":"pn-1"},
              "statuses":[{"id":"wamid.1","status":"failed","timestamp":"1759180000","recipient_id":"351900000000",
                "errors":[{"code":131047,"title":"Re-engagement message","message":"Re-engagement message",
                  "error_data":{"details":"Message failed to send because more than 24 hours have passed since the customer last replied to this number."}}]}]}}]}]}""",
        )

        val status = payload.entry.single().changes.single().value.statuses!!.single()
        val error = status.errors!!.single()
        assertEquals("failed", status.status)
        assertEquals(131047, error.code)
        assertEquals("Message failed to send because more than 24 hours have passed since the customer last replied to this number.", error.text)
    }

    @Test
    fun `a status with partial pricing still parses, so messages in the same payload are not lost`() {
        val payload = json.decodeFromString<WebhookPayload>(
            """{"object":"whatsapp_business_account","entry":[{"id":"waba-1","changes":[{"field":"messages","value":{
              "metadata":{"display_phone_number":"15553526936","phone_number_id":"pn-1"},
              "messages":[{"from":"351900000000","id":"wamid.in","timestamp":"1759180000","type":"text","text":{"body":"Olá"}}],
              "statuses":[{"id":"wamid.2","status":"read","pricing":{"category":"service"},"conversation":{"origin":{}}}]}}]}]}""",
        )

        val value = payload.entry.single().changes.single().value
        assertEquals("Olá", value.messages!!.single().text!!.body)
        assertEquals("read", value.statuses!!.single().status)
        assertNull(value.statuses!!.single().errors)
    }
}
