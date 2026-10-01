package com.rfm.edubot.integrations.google

import com.rfm.edubot.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.Properties

/**
 * Google's token, revoke and Gmail endpoints for tests: answers come from [onToken] and [onSend],
 * and every call is recorded.
 */
internal class FakeGoogle(val clientId: String = "client-1.apps.googleusercontent.com") {
    val config = AppConfig.GoogleConfig(clientId = clientId, clientSecret = "google-secret", redirectUri = "https://bots.example/integrations/google/callback")

    /** The form fields of each call to the token endpoint. */
    val tokenCalls = mutableListOf<Map<String, String>>()
    val revoked = mutableListOf<String>()

    /** A message Gmail was asked to send, with the token it came with. */
    class GmailSend(val accessToken: String, val raw: ByteArray, val threadId: String?) {
        fun parsed(): MimeMessage = MimeMessage(Session.getInstance(Properties()), raw.inputStream())
    }

    val sends = mutableListOf<GmailSend>()

    var onToken: (Map<String, String>) -> Pair<HttpStatusCode, String> = { form ->
        when (form["grant_type"]) {
            "authorization_code" -> HttpStatusCode.OK to grant()
            else -> HttpStatusCode.OK to """{"access_token":"access-refreshed","expires_in":3599,"scope":"${GoogleScopes.GMAIL_SEND}","token_type":"Bearer"}"""
        }
    }
    var revokeStatus = HttpStatusCode.OK

    /** Gmail's answer to a send; by default it takes the message and files it in a thread of its own. */
    var onSend: (GmailSend) -> Pair<HttpStatusCode, String> = { send ->
        val n = synchronized(sends) { sends.indexOf(send) + 1 }
        HttpStatusCode.OK to """{"id":"gm-$n","threadId":"${send.threadId ?: "th-$n"}","labelIds":["SENT"]}"""
    }

    /** How long the token endpoint takes to answer, so concurrent callers overlap. */
    var tokenDelayMs = 0L

    /** The inbox Gmail reads come from. */
    val mailbox = Mailbox()

    /**
     * A Gmail inbox: [messages] (full-format JSON) by id, the [history] records after the sync's cursor,
     * the ids a catch-up lists newest first ([listed]); [onRead] can refuse any read. Every read is in
     * [reads], and the query of each listing in [searches].
     */
    class Mailbox {
        var emailAddress = "obras@example.pt"
        var historyId = "500"
        val messages = linkedMapOf<String, String>()
        val history = mutableListOf<Pair<Long, List<String>>>()
        val listed = mutableListOf<String>()
        var pageSize = 100
        var onRead: (path: String, accessToken: String) -> Pair<HttpStatusCode, String>? = { _, _ -> null }
        val reads = mutableListOf<String>()
        val searches = mutableListOf<String>()

        /** Adds [id] to the inbox as history record [recordId], which also becomes the mailbox's history id. */
        fun receive(recordId: Long, id: String, json: String = GmailFixtures.message(id)) {
            messages[id] = json
            history += recordId to listOf(id)
            historyId = recordId.toString()
        }

        fun fetched(): List<String> = reads.filter { it.startsWith("messages/") }.map { it.removePrefix("messages/") }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun readGmail(path: String, query: Map<String, String>, accessToken: String): Pair<HttpStatusCode, String> {
        synchronized(mailbox.reads) { mailbox.reads += path }
        mailbox.onRead(path, accessToken)?.let { return it }
        val page = query["pageToken"]?.removePrefix("p")?.toIntOrNull() ?: 0
        return when {
            path == "profile" -> HttpStatusCode.OK to """{"emailAddress":"${mailbox.emailAddress}","historyId":"${mailbox.historyId}"}"""
            path == "history" -> {
                val start = query.getValue("startHistoryId").toLong()
                val after = mailbox.history.filter { it.first > start }.sortedBy { it.first }
                val chunk = after.drop(page * mailbox.pageSize).take(mailbox.pageSize)
                val records = chunk.joinToString(",") { (id, ids) ->
                    val added = ids.joinToString(",") { """{"message":{"id":"$it","threadId":"th-$it","labelIds":["INBOX","UNREAD"]}}""" }
                    """{"id":"$id","messages":[${ids.joinToString(",") { """{"id":"$it"}""" }}],"messagesAdded":[$added]}"""
                }
                val next = if (after.size > (page + 1) * mailbox.pageSize) ""","nextPageToken":"p${page + 1}"""" else ""
                HttpStatusCode.OK to """{"history":[$records],"historyId":"${mailbox.historyId}"$next}"""
            }
            path == "messages" -> {
                synchronized(mailbox.searches) { mailbox.searches += query["q"].orEmpty() }
                val chunk = mailbox.listed.drop(page * mailbox.pageSize).take(mailbox.pageSize)
                val next = if (mailbox.listed.size > (page + 1) * mailbox.pageSize) ""","nextPageToken":"p${page + 1}"""" else ""
                HttpStatusCode.OK to """{"messages":[${chunk.joinToString(",") { """{"id":"$it","threadId":"th-$it"}""" }}],"resultSizeEstimate":${chunk.size}$next}"""
            }
            path.startsWith("messages/") -> mailbox.messages[path.removePrefix("messages/")]?.let { HttpStatusCode.OK to it }
                ?: (HttpStatusCode.NotFound to googleError(404, "notFound", "Requested entity was not found."))
            else -> error("Unexpected Gmail read $path")
        }
    }

    val http = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val url = request.url.toString().substringBefore('?')
                if (url.startsWith("${GmailClient.BASE_URL}/users/me/") && url != "${GmailClient.BASE_URL}/users/me/messages/send") {
                    val query = request.url.parameters.entries().associate { (name, values) -> name to values.first() }
                    val token = request.headers[HttpHeaders.Authorization].orEmpty().removePrefix("Bearer ")
                    val (status, body) = readGmail(url.removePrefix("${GmailClient.BASE_URL}/users/me/"), query, token)
                    return@addHandler respond(body, status, jsonHeaders)
                }
                when (url) {
                    "${GmailClient.BASE_URL}/users/me/messages/send" -> {
                        val body = json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val send = GmailSend(
                            accessToken = request.headers[HttpHeaders.Authorization].orEmpty().removePrefix("Bearer "),
                            raw = Base64.getUrlDecoder().decode(body.getValue("raw").jsonPrimitive.content),
                            threadId = body["threadId"]?.jsonPrimitive?.content,
                        )
                        synchronized(sends) { sends += send }
                        val (status, answer) = onSend(send)
                        respond(answer, status, jsonHeaders)
                    }
                    GoogleOAuthClient.TOKEN_URL -> {
                        val form = form(request.body as FormDataContent)
                        synchronized(tokenCalls) { tokenCalls += form }
                        if (tokenDelayMs > 0) delay(tokenDelayMs)
                        val (status, body) = onToken(form)
                        respond(body, status, jsonHeaders)
                    }
                    GoogleOAuthClient.REVOKE_URL -> {
                        revoked += form(request.body as FormDataContent).getValue("token")
                        respond("", revokeStatus)
                    }
                    else -> error("Unexpected call ${request.url}")
                }
            }
        }
    }

    private fun form(content: FormDataContent) = content.formData.entries().associate { (name, values) -> name to values.first() }

    fun client() = GoogleOAuthClient({ config }, http)

    fun grant(
        email: String = "Obras@Example.pt",
        scope: String = "openid https://www.googleapis.com/auth/userinfo.email ${GoogleScopes.GMAIL_SEND}",
        refreshToken: String? = "refresh-1",
        verified: Boolean = true,
        audience: String = clientId,
    ): String {
        val refresh = refreshToken?.let { ""","refresh_token":"$it"""" }.orEmpty()
        return """{"access_token":"access-1","expires_in":3599$refresh,"scope":"$scope","token_type":"Bearer","id_token":"${idToken(email, verified, audience)}"}"""
    }

    fun idToken(email: String, verified: Boolean = true, audience: String = clientId): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val claims = """{"iss":"https://accounts.google.com","aud":"$audience","sub":"1001","email":"$email","email_verified":$verified,"exp":4102444800}"""
        return listOf("""{"alg":"RS256"}""", claims).joinToString(".") { encoder.encodeToString(it.toByteArray()) } + ".signature"
    }

    /** Google's error body for a refused call. */
    fun googleError(code: Int, reason: String, message: String = "refused") =
        """{"error":{"code":$code,"message":"$message","errors":[{"message":"$message","domain":"global","reason":"$reason"}],"status":"X"}}"""
}

/** Gmail API messages in `format=full`, as `users.messages.get` returns them. */
internal object GmailFixtures {
    data class Attachment(val filename: String, val mimeType: String, val size: Int = 48_213, val headers: Map<String, String> = emptyMap())

    fun data(text: String, charset: java.nio.charset.Charset = Charsets.UTF_8): String =
        Base64.getUrlEncoder().encodeToString(text.toByteArray(charset))

    fun message(
        id: String,
        from: String = "Maria Silva <maria@cliente.pt>",
        subject: String = "Pedido de orçamento",
        text: String? = "Olá, preciso de um orçamento para pintar a sala.\nObrigada, Maria",
        html: String? = null,
        labels: List<String> = listOf("INBOX", "UNREAD", "CATEGORY_PERSONAL"),
        headers: Map<String, String> = emptyMap(),
        attachments: List<Attachment> = emptyList(),
        to: String = "obras@example.pt",
        threadId: String = "th-$id",
        internalDate: Long = 1_790_000_000_000,
        mimeType: String? = null,
    ): String {
        val top = buildMap {
            put("From", from)
            put("To", to)
            put("Subject", subject)
            put("Message-ID", "<$id@mail.cliente.pt>")
            putAll(headers)
        }
        val bodies = buildList {
            text?.let { add(part("text/plain", mapOf("Content-Type" to "text/plain; charset=\"UTF-8\""), """{"size":${it.length},"data":"${data(it)}"}""")) }
            html?.let { add(part("text/html", mapOf("Content-Type" to "text/html; charset=\"UTF-8\""), """{"size":${it.length},"data":"${data(it)}"}""")) }
        }
        val files = attachments.map { file ->
            part(
                file.mimeType,
                mapOf("Content-Type" to "${file.mimeType}; name=\"${file.filename}\"", "Content-Disposition" to "attachment; filename=\"${file.filename}\"") + file.headers,
                """{"attachmentId":"att-${file.filename.hashCode()}","size":${file.size}}""",
                filename = file.filename,
            )
        }
        val payload = when {
            files.isEmpty() && bodies.size == 1 -> bodies.single().replaceFirst("\"headers\":[", "\"headers\":[${headers(top)},")
            else -> {
                val alternative = if (bodies.size > 1) listOf(part("multipart/alternative", emptyMap(), """{"size":0}""", parts = bodies)) else bodies
                part(mimeType ?: "multipart/mixed", top, """{"size":0}""", parts = alternative + files)
            }
        }
        val labelIds = labels.joinToString(",") { "\"$it\"" }
        return """{"id":"$id","threadId":"$threadId","labelIds":[$labelIds],"snippet":"Ol&aacute;, preciso","historyId":"1","internalDate":"$internalDate","payload":$payload,"sizeEstimate":2048}"""
    }

    private fun headers(values: Map<String, String>) =
        values.entries.joinToString(",") { (name, value) -> """{"name":"$name","value":${kotlinx.serialization.json.JsonPrimitive(value)}}""" }

    private fun part(mimeType: String, headers: Map<String, String>, body: String, filename: String = "", parts: List<String> = emptyList()): String {
        val children = if (parts.isEmpty()) "" else ""","parts":[${parts.joinToString(",")}]"""
        return """{"partId":"0","mimeType":"$mimeType","filename":"$filename","headers":[${headers(headers)}],"body":$body$children}"""
    }
}
