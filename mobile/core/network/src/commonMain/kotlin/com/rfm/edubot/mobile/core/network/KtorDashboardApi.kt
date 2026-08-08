package com.rfm.edubot.mobile.core.network

import com.rfm.edubot.mobile.core.model.AssistantThread
import com.rfm.edubot.mobile.core.model.AssistantThreadDetail
import com.rfm.edubot.mobile.core.model.CatalogItem
import com.rfm.edubot.mobile.core.model.Contact
import com.rfm.edubot.mobile.core.model.Conversation
import com.rfm.edubot.mobile.core.model.CreateInvoice
import com.rfm.edubot.mobile.core.model.CreateQuote
import com.rfm.edubot.mobile.core.model.CrmClient
import com.rfm.edubot.mobile.core.model.DashboardIdentity
import com.rfm.edubot.mobile.core.model.Invoice
import com.rfm.edubot.mobile.core.model.Overview
import com.rfm.edubot.mobile.core.model.Persona
import com.rfm.edubot.mobile.core.model.Quote
import com.rfm.edubot.mobile.core.model.Session
import com.rfm.edubot.mobile.core.model.ThreadMessage
import com.rfm.edubot.mobile.core.model.WebWidget
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class KtorDashboardApi(
    private val baseUrl: String,
) : DashboardApi {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
    }
    override suspend fun login(email: String, password: String): Session = request {
        client.post("${baseUrl.trimEnd('/')}/app/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email.trim(), password))
        }
    }

    override suspend fun me(token: String): DashboardIdentity = request {
        client.get("${baseUrl.trimEnd('/')}/app/api/me") {
            bearerAuth(token)
        }
    }

    override suspend fun overview(token: String): Overview = request {
        client.get("${baseUrl.trimEnd('/')}/app/api/overview") {
            bearerAuth(token)
        }
    }

    override suspend fun contacts(token: String): List<Contact> = request {
        client.get("${baseUrl.trimEnd('/')}/app/api/contacts") { bearerAuth(token) }
    }

    override suspend fun updateContactStatus(token: String, contactId: String, status: String): Contact = request {
        client.patch("${baseUrl.trimEnd('/')}/app/api/contacts/$contactId/status") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ContactStatusRequest(status))
        }
    }

    override suspend fun conversations(token: String): List<Conversation> = request {
        client.get("${baseUrl.trimEnd('/')}/app/api/conversations") { bearerAuth(token) }
    }

    override suspend fun messages(token: String, conversationId: String): List<ThreadMessage> = request {
        client.get("${baseUrl.trimEnd('/')}/app/api/conversations/$conversationId/messages") { bearerAuth(token) }
    }

    override suspend fun sendMessage(token: String, conversationId: String, text: String, assetExternalId: String): ThreadMessage = request {
        client.post("${baseUrl.trimEnd('/')}/app/api/conversations/$conversationId/messages") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(OutboundMessageRequest(text, assetExternalId))
        }
    }

    override suspend fun assistantThreads(token: String): List<AssistantThread> = request {
        client.get("${baseUrl.trimEnd('/')}/app/api/assistant/threads") { bearerAuth(token) }
    }

    override suspend fun createAssistantThread(token: String, title: String): AssistantThread = request {
        client.post("${baseUrl.trimEnd('/')}/app/api/assistant/threads") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(CreateAssistantThreadRequest(title))
        }
    }

    override suspend fun assistantThread(token: String, threadId: String): AssistantThreadDetail = request {
        client.get("${baseUrl.trimEnd('/')}/app/api/assistant/threads/$threadId") { bearerAuth(token) }
    }

    override suspend fun sendAssistantMessage(token: String, threadId: String, content: String): AssistantThreadDetail = request {
        client.post("${baseUrl.trimEnd('/')}/app/api/assistant/threads/$threadId/messages") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(AssistantMessageRequest(content))
        }
    }

    override suspend fun decideAssistantAction(token: String, threadId: String, actionId: String, decision: String): AssistantThreadDetail = request {
        client.post("${baseUrl.trimEnd('/')}/app/api/assistant/threads/$threadId/actions/$actionId/$decision") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(emptyMap<String, String>())
        }
    }

    override suspend fun clients(token: String): List<CrmClient> = request {
        client.get("${baseUrl.trimEnd('/')}/app/api/crm/clients") { bearerAuth(token) }
    }

    override suspend fun createClient(token: String, name: String, phone: String, address: String?): CrmClient = request {
        client.post("${baseUrl.trimEnd('/')}/app/api/crm/clients") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(CreateClientRequest(name, phone, address))
        }
    }

    override suspend fun quotes(token: String): List<Quote> = request {
        client.get("${baseUrl.trimEnd('/')}/app/api/crm/quotes") { bearerAuth(token) }
    }

    override suspend fun invoices(token: String): List<Invoice> = request {
        client.get("${baseUrl.trimEnd('/')}/app/api/crm/invoices") { bearerAuth(token) }
    }

    override suspend fun catalog(token: String): List<CatalogItem> = request {
        client.get("${baseUrl.trimEnd('/')}/app/api/crm/standard-items") { bearerAuth(token) }
    }

    override suspend fun createCatalogItem(token: String, item: CatalogItem): CatalogItem = request {
        client.post("${baseUrl.trimEnd('/')}/app/api/crm/standard-items") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(item)
        }
    }

    override suspend fun createQuote(token: String, request: CreateQuote): Quote = request {
        client.post("${baseUrl.trimEnd('/')}/app/api/crm/quotes") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun createInvoice(token: String, request: CreateInvoice): Invoice = request {
        client.post("${baseUrl.trimEnd('/')}/app/api/crm/invoices") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun persona(token: String): Persona = request {
        client.get("${baseUrl.trimEnd('/')}/app/api/persona") { bearerAuth(token) }
    }

    override suspend fun updatePersona(token: String, compiledInstructions: String): Persona = request {
        client.put("${baseUrl.trimEnd('/')}/app/api/persona") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PersonaUpdateRequest(compiledInstructions))
        }
    }

    override suspend fun webWidget(token: String): WebWidget = request {
        client.get("${baseUrl.trimEnd('/')}/app/api/web-widget") { bearerAuth(token) }
    }

    override suspend fun updateLocale(token: String, locale: String): String = request<LocaleResponse> {
        client.post("${baseUrl.trimEnd('/')}/app/api/settings/locale") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(LocaleRequest(locale))
        }
    }.locale

    private suspend inline fun <reified T> request(block: suspend () -> HttpResponse): T {
        val response = block()
        if (!response.status.isSuccess()) throw DashboardApiException(response.status.value, response.bodyAsText())
        return response.body()
    }
}

@Serializable
private data class LoginRequest(val email: String, val password: String)

@Serializable
private data class ContactStatusRequest(val status: String)

@Serializable
private data class OutboundMessageRequest(val text: String, val assetExternalId: String)

@Serializable
private data class CreateAssistantThreadRequest(val title: String)

@Serializable
private data class AssistantMessageRequest(val content: String)

@Serializable
private data class PersonaUpdateRequest(val compiledInstructions: String)

@Serializable
private data class LocaleRequest(val locale: String)

@Serializable
private data class LocaleResponse(val locale: String)

@Serializable
private data class CreateClientRequest(val name: String, val phone: String, val address: String? = null)
