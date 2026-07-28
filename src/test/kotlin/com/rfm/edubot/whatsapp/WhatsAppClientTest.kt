package com.rfm.edubot.whatsapp

import com.rfm.edubot.channel.OutboundDeliveryException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WhatsAppClientTest {
    @Test
    fun `permanent Graph failure is reported without retrying`() = runBlocking {
        var attempts = 0
        val http = HttpClient(MockEngine) {
            engine {
                addHandler {
                    attempts += 1
                    respond(
                        """{"error":{"message":"Invalid token","code":190}}""",
                        status = HttpStatusCode.Unauthorized,
                    )
                }
            }
        }

        assertFailsWith<OutboundDeliveryException> {
            WhatsAppClient("bad-token", "phone-1", maxRetries = 3, httpClient = http).sendText("351900000000", "Hello")
        }
        assertEquals(1, attempts)
    }
}
