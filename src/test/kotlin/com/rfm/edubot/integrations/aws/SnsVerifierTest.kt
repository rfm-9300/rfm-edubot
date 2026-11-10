package com.rfm.edubot.integrations.aws

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class SnsVerifierTest {
    private val aws = FakeAws()
    private val verifier = SnsVerifier(aws.http)

    private fun verify(body: String): Boolean = runBlocking { verifier.verify(SnsMessage.parse(body)!!) }

    @Test
    fun `accepts what SNS signed, in both signature versions`() {
        assertTrue(verify(aws.signed(message = "{\"notificationType\":\"Received\"}")))
        assertTrue(verify(aws.signed(message = "hello", version = "1", subject = "With a subject")))
        assertTrue(verify(aws.signed(type = SnsMessage.SUBSCRIPTION_CONFIRMATION, message = "You have chosen to subscribe")))
    }

    @Test
    fun `refuses a changed message, a foreign certificate host, plain http and old messages`() {
        assertFalse(verify(aws.signed(message = "original") { it.copy(message = "changed") }))
        assertFalse(verify(aws.signed(message = "x") { it.copy(topicArn = "arn:aws:sns:eu-west-1:999:other") }))
        assertFalse(verify(aws.signed(message = "x", certUrl = "https://sns.eu-west-1.amazonaws.com.evil.example/cert.pem")))
        assertFalse(verify(aws.signed(message = "x", certUrl = "http://sns.eu-west-1.amazonaws.com/cert.pem")))
        assertFalse(verify(aws.signed(message = "x", certUrl = "https://sns.eu-west-1.amazonaws.com/cert.txt")))
        assertFalse(verify(aws.signed(message = "x", timestamp = Clock.System.now() - 7.hours)))
        assertFalse(verify(aws.signed(message = "x", version = "3")))
    }

    @Test
    fun `a local stand-in's certificate is trusted only when named`() {
        val local = "http://127.0.0.1:8099/sns/cert.pem"
        assertFalse(verify(aws.signed(message = "x", certUrl = local)))
        val trusting = SnsVerifier(aws.http, trustedCertUrl = { local })
        assertFalse(runBlocking { trusting.verify(SnsMessage.parse(aws.signed(message = "x", certUrl = "http://127.0.0.1:8099/other.pem"))!!) })
    }

    @Test
    fun `reads SNS's messages and builds what it signs`() {
        assertNull(SnsMessage.parse("not json"))
        assertNull(SnsMessage.parse("{\"Type\":\"Notification\"}"))
        val parsed = SnsMessage.parse(aws.signed(message = "m", subject = "s"))!!
        assertEquals("Message\nm\nMessageId\n${parsed.messageId}\nSubject\ns\nTimestamp\n${parsed.timestamp}\nTopicArn\n${parsed.topicArn}\nType\nNotification\n", SnsMessage.stringToSign(parsed))
        assertTrue(SnsMessage.isSnsUrl("https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription"))
        assertFalse(SnsMessage.isSnsUrl("https://sns.us-east-1.amazonaws.com:8443/x"))
        assertFalse(SnsMessage.isSnsUrl("https://evil.example/?sns.us-east-1.amazonaws.com"))
    }
}
