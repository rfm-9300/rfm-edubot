package com.rfm.edubot.integrations.aws

import com.rfm.edubot.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * SES, S3 and SNS for tests, behind one MockEngine: SES's SendEmail (recorded, answered by [onSend]),
 * an S3 bucket ([objects], reads and deletes recorded), and SNS's signing certificate and subscription
 * confirmation. [signed] makes SNS messages signed the way SNS signs them, with a key and certificate
 * made for the run by the JDK's keytool (nothing secret lives in the repo).
 */
internal class FakeAws(
    val domain: String = "mail.example.pt",
    val bucket: String = "inbound-mail",
    val topicArn: String = "arn:aws:sns:eu-west-1:123456789012:inbound-mail",
) {
    val config = AppConfig.MailboxConfig(
        domain = domain,
        region = "eu-west-1",
        accessKeyId = "AKIDEXAMPLE",
        secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
        bucket = bucket,
        topicArns = setOf(topicArn),
    )

    /** One SendEmail call: the raw message SES was given and the recipients apart from it. */
    class SesSend(val raw: ByteArray, val to: List<String>, val cc: List<String>, val bcc: List<String>, val authorization: String?) {
        fun parsed(): MimeMessage = MimeMessage(Session.getInstance(Properties()), raw.inputStream())
        fun header(name: String): String? = parsed().getHeader(name, null)
    }

    val sends = mutableListOf<SesSend>()

    /** SES's answer; by default it takes the message and gives it an id. */
    var onSend: (SesSend) -> Triple<HttpStatusCode, String, Map<String, String>> = { send ->
        val n = synchronized(sends) { sends.indexOf(send) + 1 }
        Triple(HttpStatusCode.OK, """{"MessageId":"0102018f-ses-$n-000000"}""", emptyMap())
    }

    /** The bucket, by key. */
    val objects = linkedMapOf<String, ByteArray>()
    val reads = mutableListOf<String>()
    val deletes = mutableListOf<String>()
    var s3Status: HttpStatusCode? = null

    /** Subscription confirmation links SNS was sent to. */
    val confirmations = mutableListOf<String>()

    val certUrl = "https://sns.eu-west-1.amazonaws.com/SimpleNotificationService-test.pem"

    private val json = Json { ignoreUnknownKeys = true }

    val http = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val url = request.url.toString()
                when {
                    request.url.host == "email.eu-west-1.amazonaws.com" && request.url.encodedPath == "/v2/email/outbound-emails" -> {
                        val body = json.parseToJsonElement(String(bytes(request.body), Charsets.UTF_8)).jsonObject
                        val destination = body["Destination"] as? JsonObject
                        fun list(name: String) = (destination?.get(name) as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
                        val send = SesSend(
                            raw = Base64.getDecoder().decode(body["Content"]!!.jsonObject["Raw"]!!.jsonObject["Data"]!!.jsonPrimitive.content),
                            to = list("ToAddresses"),
                            cc = list("CcAddresses"),
                            bcc = list("BccAddresses"),
                            authorization = request.headers[HttpHeaders.Authorization],
                        )
                        synchronized(sends) { sends += send }
                        val (status, answer, headers) = onSend(send)
                        respond(answer, status, headersOf(*(headers + (HttpHeaders.ContentType to "application/json")).map { (k, v) -> k to listOf(v) }.toTypedArray()))
                    }
                    request.url.host == "$bucket.s3.eu-west-1.amazonaws.com" -> {
                        val key = request.url.encodedPath.removePrefix("/")
                        s3Status?.let { return@addHandler respond("", it) }
                        when (request.method) {
                            HttpMethod.Get -> {
                                synchronized(reads) { reads += key }
                                objects[key]?.let { respond(it, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, it.size.toString())) }
                                    ?: respond("<Error><Code>NoSuchKey</Code></Error>", HttpStatusCode.NotFound)
                            }
                            HttpMethod.Delete -> {
                                synchronized(deletes) { deletes += key }
                                objects.remove(key)
                                respond("", HttpStatusCode.NoContent)
                            }
                            else -> error("Unexpected S3 call ${request.method} $url")
                        }
                    }
                    url == certUrl -> respond(certificatePem, HttpStatusCode.OK)
                    request.url.host == "sns.eu-west-1.amazonaws.com" && request.url.parameters["Action"] == "ConfirmSubscription" -> {
                        synchronized(confirmations) { confirmations += url }
                        respond("<ConfirmSubscriptionResponse/>", HttpStatusCode.OK)
                    }
                    else -> error("Unexpected AWS call ${request.method} $url")
                }
            }
        }
    }

    private fun bytes(content: Any): ByteArray = when (content) {
        is ByteArrayContent -> content.bytes()
        is OutgoingContent.ByteArrayContent -> content.bytes()
        else -> error("Unexpected body ${content::class}")
    }

    /** An SNS message as SNS posts it, signed with this run's key (or not, to test refusals). */
    fun signed(
        type: String = SnsMessage.NOTIFICATION,
        message: String,
        topicArn: String = this.topicArn,
        timestamp: Instant = Clock.System.now(),
        version: String = "2",
        certUrl: String = this.certUrl,
        subject: String? = null,
        tamper: (SnsMessage) -> SnsMessage = { it },
    ): String {
        val unsigned = SnsMessage(
            type = type,
            messageId = "sns-${System.nanoTime()}",
            topicArn = topicArn,
            message = message,
            timestamp = timestamp.toString(),
            signatureVersion = version,
            signature = "",
            signingCertUrl = certUrl,
            subject = subject,
            subscribeUrl = if (type == SnsMessage.NOTIFICATION) null else "https://sns.eu-west-1.amazonaws.com/?Action=ConfirmSubscription&TopicArn=${topicArn}&Token=tok",
            token = if (type == SnsMessage.NOTIFICATION) null else "tok",
        )
        val signature = Signature.getInstance(if (version == "1") "SHA1withRSA" else "SHA256withRSA").run {
            initSign(key.privateKey)
            update(SnsMessage.stringToSign(unsigned)!!.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        val m = tamper(unsigned.copy(signature = signature))
        return buildJsonObject {
            put("Type", m.type)
            put("MessageId", m.messageId)
            put("TopicArn", m.topicArn)
            m.subject?.let { put("Subject", it) }
            put("Message", m.message)
            put("Timestamp", m.timestamp)
            put("SignatureVersion", m.signatureVersion)
            put("Signature", m.signature)
            put("SigningCertURL", m.signingCertUrl)
            m.subscribeUrl?.let { put("SubscribeURL", it) }
            m.token?.let { put("Token", it) }
        }.toString()
    }

    /**
     * SES's notification of a received email ([raw] stored in the bucket under [sesId]) addressed to
     * [recipients], with the given verdicts.
     */
    fun received(
        raw: String?,
        recipients: List<String>,
        sesId: String = "ses-${System.nanoTime()}",
        source: String = "sender@example.com",
        spam: String = "PASS",
        virus: String = "PASS",
        dkim: String = "PASS",
        dmarc: String = "PASS",
        dmarcPolicy: String? = null,
        bucket: String = this.bucket,
        timestamp: Instant = Clock.System.now(),
    ): JsonObject {
        if (raw != null) objects[sesId] = raw.replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
        return json.parseToJsonElement(
            """
            {"notificationType":"Received",
             "receipt":{"timestamp":"$timestamp","recipients":${recipients.joinToString(",", "[", "]") { "\"$it\"" }},
               "spamVerdict":{"status":"$spam"},"virusVerdict":{"status":"$virus"},"spfVerdict":{"status":"GRAY"},
               "dkimVerdict":{"status":"$dkim"},"dmarcVerdict":{"status":"$dmarc"}${dmarcPolicy?.let { ",\"dmarcPolicy\":\"$it\"" } ?: ""},
               "action":{"type":"S3","topicArn":"$topicArn","bucketName":"$bucket","objectKey":"$sesId"}},
             "mail":{"timestamp":"$timestamp","source":"$source","messageId":"$sesId","destination":${recipients.joinToString(",", "[", "]") { "\"$it\"" }},
               "headersTruncated":false}}
            """.trimIndent(),
        ).jsonObject
    }

    private class TestKey(val privateKey: PrivateKey, val certificate: X509Certificate)

    private val key: TestKey by lazy { makeKey() }

    private val certificatePem: String by lazy {
        "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.certificate.encoded) + "\n-----END CERTIFICATE-----\n"
    }

    private fun makeKey(): TestKey {
        val dir = Files.createTempDirectory("sns-key")
        val store = dir.resolve("sns.p12")
        val keytool = "${System.getProperty("java.home")}/bin/keytool"
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "sns", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
            "-dname", "CN=sns.eu-west-1.amazonaws.com", "-keystore", store.toString(), "-storetype", "PKCS12",
            "-storepass", "changeit", "-keypass", "changeit", "-startdate", "-1d",
        ).redirectErrorStream(true).start()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool failed: ${process.inputStream.readAllBytes().decodeToString()}" }
        val keyStore = KeyStore.getInstance("PKCS12").apply { store.toFile().inputStream().use { load(it, "changeit".toCharArray()) } }
        return TestKey(keyStore.getKey("sns", "changeit".toCharArray()) as PrivateKey, keyStore.getCertificate("sns") as X509Certificate)
    }
}
